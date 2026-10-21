package ru.hat1.cosmetics.service

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import ru.hat1.cosmetics.config.CosmeticsConfig
import ru.hat1.cosmetics.config.MessageService
import ru.hat1.cosmetics.integration.EconomyService
import ru.hat1.cosmetics.integration.LuckPermsService
import ru.hat1.cosmetics.model.Cosmetic
import ru.hat1.cosmetics.render.CosmeticRenderer
import ru.hat1.cosmetics.storage.YamlCosmeticsStorage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of a player action, carrying the message key to show and its
 * placeholders so the caller stays free of business logic.
 */
data class ActionResult(
    val success: Boolean,
    val messageKey: String,
    val placeholders: Map<String, String> = emptyMap(),
)

/**
 * Encapsulates every cosmetics operation (purchase/equip/unequip/grant/trial).
 *
 * All mutations happen on the main thread; persistence is delegated to
 * [YamlCosmeticsStorage] which debounces disk writes.
 *
 * Cosmetics occupy **slot groups** (`head`, `body`, `arms`, `legs`), never
 * armour or inventory slots — they are display entities, so a hat can be worn
 * together with a real helmet.
 */
class CosmeticsService(
    private val plugin: JavaPlugin,
    private val config: CosmeticsConfig,
    private val storage: YamlCosmeticsStorage,
    private val economy: EconomyService,
    private val luckPerms: LuckPermsService,
    private val renderer: CosmeticRenderer,
    private val messages: MessageService,
) {

    /**
     * Active trials: player uuid -> preview session.
     *
     * A trial shows an unowned cosmetic for a few seconds and then removes it
     * again, so the player can judge the look before paying.
     */
    private val activeTrials = ConcurrentHashMap<UUID, TrialSession>()

    private data class TrialSession(
        val cosmeticId: String,
        /** Slot that was worn before the trial started, restored afterwards. */
        val previousInSlot: String?,
        val task: BukkitTask,
    )

    private val currencySymbol: String
        get() = plugin.config.getString("economy.currency-symbol") ?: "$"

    /** How long a free trial lasts. */
    val trialSeconds: Int
        get() = plugin.config.getInt("trial.duration-seconds", 5).coerceIn(1, 60)

    /** True when free trials are enabled server-wide. */
    val trialsEnabled: Boolean
        get() = plugin.config.getBoolean("trial.enabled", true)

    /**
     * Attempts to purchase [cosmeticId] for [player].
     */
    fun purchase(player: Player, cosmeticId: String): ActionResult {
        val cosmetic = config.cosmetic(cosmeticId)
            ?: return ActionResult(false, "unknown-cosmetic", mapOf("id" to cosmeticId))

        val state = storage.load(player.uniqueId)

        if (owns(player, cosmetic)) {
            return ActionResult(false, "already-owned", mapOf("cosmetic" to cosmetic.displayName))
        }

        if (!cosmetic.isPurchasable) {
            return ActionResult(false, "not-for-sale")
        }

        if (!economy.available) {
            return ActionResult(false, "no-economy")
        }

        val price = priceOf(cosmetic)
        if (!economy.has(player, price)) {
            return ActionResult(
                false,
                "not-enough-money",
                mapOf(
                    "price" to formatPrice(price),
                    "balance" to formatPrice(economy.balance(player)),
                ),
            )
        }

        if (!economy.withdraw(player, price)) {
            return ActionResult(false, "no-economy")
        }

        state.addOwned(cosmetic.id)
        storage.markDirty(player.uniqueId)

        // Mirror ownership into LuckPerms so the node stays the source of truth
        // for other plugins and survives a local data reset.
        val node = cosmetic.permissionFor(player.name, player.uniqueId.toString())
        luckPerms.grant(player.uniqueId, node)

        return ActionResult(
            true,
            "bought",
            mapOf(
                "cosmetic" to cosmetic.displayName,
                "price" to formatPrice(price),
            ),
        )
    }

    /**
     * Equips [cosmeticId] for [player], replacing the current cosmetic in the
     * same slot group. Different slots stack.
     */
    fun equip(player: Player, cosmeticId: String): ActionResult {
        val cosmetic = config.cosmetic(cosmeticId)
            ?: return ActionResult(false, "unknown-cosmetic", mapOf("id" to cosmeticId))

        if (!owns(player, cosmetic)) {
            return ActionResult(false, "not-owned", mapOf("cosmetic" to cosmetic.displayName))
        }

        // Wearing a cosmetic permanently cancels a running preview of it.
        cancelTrial(player, restore = false, silent = true)

        val state = storage.load(player.uniqueId)
        if (state.isEquipped(cosmetic.id)) {
            return ActionResult(
                false,
                "already-equipped",
                mapOf("cosmetic" to cosmetic.displayName),
            )
        }

        val slotId = cosmetic.slot.id
        val previous = state.equip(slotId, cosmetic.id)
        storage.markDirty(player.uniqueId)

        if (previous != null && previous != cosmetic.id) {
            renderer.clearOne(player, previous)
        }
        renderer.refresh(player)

        return ActionResult(
            true,
            "equipped",
            mapOf(
                "cosmetic" to cosmetic.displayName,
                "slot" to cosmetic.slot.id,
            ),
        )
    }

    /**
     * Unequips [cosmeticId] for [player].
     */
    fun unequip(player: Player, cosmeticId: String): ActionResult {
        val cosmetic = config.cosmetic(cosmeticId)
            ?: return ActionResult(false, "unknown-cosmetic", mapOf("id" to cosmeticId))

        val state = storage.load(player.uniqueId)
        if (!state.unequip(cosmetic.id)) {
            return ActionResult(
                false,
                "not-owned",
                mapOf("cosmetic" to cosmetic.displayName),
            )
        }

        storage.markDirty(player.uniqueId)
        renderer.clearOne(player, cosmetic.id)

        return ActionResult(true, "unequipped", mapOf("cosmetic" to cosmetic.displayName))
    }

    /**
     * Unequips whatever is worn in [slotId] for [player].
     */
    fun unequipSlot(player: Player, slotId: String): ActionResult {
        val state = storage.load(player.uniqueId)
        val equippedId = state.equippedIn(slotId)
            ?: return ActionResult(false, "nothing-equipped")

        val cosmetic = config.cosmetic(equippedId)
        state.equipped.remove(slotId.lowercase())
        storage.markDirty(player.uniqueId)
        renderer.clearOne(player, equippedId)

        return ActionResult(
            true,
            "unequipped",
            mapOf("cosmetic" to (cosmetic?.displayName ?: equippedId)),
        )
    }

    /**
     * Unequips every cosmetic of [player] that belongs to menu [categoryId].
     *
     * A category may mix slots (e.g. "wings" is BODY, "boots" is LEGS), so the
     * removal is done per cosmetic rather than per slot.
     */
    fun unequipCategory(player: Player, categoryId: String): ActionResult {
        val state = storage.load(player.uniqueId)
        val equippedInCategory = state.equipped.values.filter { cosmeticId ->
            config.cosmetic(cosmeticId)?.categoryId == categoryId.lowercase()
        }
        if (equippedInCategory.isEmpty()) {
            return ActionResult(false, "nothing-equipped")
        }

        for (cosmeticId in equippedInCategory) {
            state.unequip(cosmeticId)
            renderer.clearOne(player, cosmeticId)
        }
        storage.markDirty(player.uniqueId)

        val names = equippedInCategory.mapNotNull { config.cosmetic(it)?.displayName }
        return ActionResult(
            true,
            if (names.size == 1) "unequipped" else "unequipped-many",
            mapOf(
                "cosmetic" to names.joinToString(", "),
                "count" to names.size.toString(),
            ),
        )
    }

    /** Unequips every cosmetic of [player]. */
    fun unequipAll(player: Player): Int {
        cancelTrial(player, restore = false, silent = true)

        val state = storage.load(player.uniqueId)
        val count = state.equipped.size
        if (count == 0) return 0

        state.equipped.clear()
        storage.markDirty(player.uniqueId)
        renderer.clear(player)
        return count
    }

    // ------------------------------------------------------------------ trial

    /**
     * Shows [cosmeticId] on [player] for a few seconds without owning it.
     *
     * Rules:
     *  - only one trial runs per player at a time;
     *  - each cosmetic can be trialed **once** — `trials` is persisted, so
     *    relogging does not reset it;
     *  - after the timer the previous cosmetic in that slot is restored.
     */
    fun startTrial(player: Player, cosmeticId: String): ActionResult {
        val cosmetic = config.cosmetic(cosmeticId)
            ?: return ActionResult(false, "unknown-cosmetic", mapOf("id" to cosmeticId))

        if (!trialsEnabled) {
            return ActionResult(false, "trial-disabled")
        }
        if (!cosmetic.trialAllowed) {
            return ActionResult(false, "trial-not-allowed", mapOf("cosmetic" to cosmetic.displayName))
        }
        if (owns(player, cosmetic)) {
            return ActionResult(false, "already-owned", mapOf("cosmetic" to cosmetic.displayName))
        }

        val state = storage.load(player.uniqueId)
        if (state.hasTrialed(cosmetic.id)) {
            return ActionResult(false, "trial-already-used", mapOf("cosmetic" to cosmetic.displayName))
        }

        // A second trial replaces the running one, restoring the original state first.
        cancelTrial(player, restore = true, silent = true)

        val slotId = cosmetic.slot.id
        val previous = state.equippedIn(slotId)

        // Apply the trial through the same path the renderer reads from, so no
        // separate "preview" code path can drift from the real one.
        state.equip(slotId, cosmetic.id)
        state.markTrialed(cosmetic.id)
        storage.markDirty(player.uniqueId)

        // Remove a now-shadowed cosmetic so two models never overlap.
        if (previous != null && previous != cosmetic.id) {
            renderer.clearOne(player, previous)
        }
        renderer.refresh(player)

        val task = plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable { finishTrial(player.uniqueId, cosmetic.id) },
            trialSeconds * 20L,
        )
        activeTrials[player.uniqueId] = TrialSession(cosmetic.id, previous, task)

        return ActionResult(
            true,
            "trial-started",
            mapOf(
                "cosmetic" to cosmetic.displayName,
                "seconds" to trialSeconds.toString(),
            ),
        )
    }

    /** True when [player] can still trial [cosmeticId]. */
    fun canTrial(player: Player, cosmetic: Cosmetic): Boolean {
        if (!trialsEnabled || !cosmetic.trialAllowed) return false
        if (owns(player, cosmetic)) return false
        return storage.cached(player.uniqueId)?.hasTrialed(cosmetic.id) != true
    }

    /** Cosmetic currently being previewed by [player], if any. */
    fun activeTrial(player: Player): String? = activeTrials[player.uniqueId]?.cosmeticId

    /** Ends a running trial, optionally restoring the previous cosmetic. */
    fun cancelTrial(player: Player, restore: Boolean, silent: Boolean) {
        val session = activeTrials.remove(player.uniqueId) ?: return
        session.task.cancel()
        revertTrial(player.uniqueId, session, restore)
        if (!silent && player.isOnline) {
            messages.send(player, "trial-cancelled")
        }
    }

    /** Cancels a trial on quit without touching the renderer of a leaving player. */
    fun forgetPlayer(uuid: UUID) {
        activeTrials.remove(uuid)?.task?.cancel()
    }

    /** Called by the scheduled task when the timer runs out. */
    private fun finishTrial(uuid: UUID, cosmeticId: String) {
        val session = activeTrials.remove(uuid) ?: return
        // The trial may have been replaced by a newer one in the meantime.
        if (session.cosmeticId != cosmeticId) return

        revertTrial(uuid, session, restore = true)

        val player = plugin.server.getPlayer(uuid) ?: return
        if (!player.isOnline) return

        val cosmetic = config.cosmetic(cosmeticId)
        messages.send(
            player,
            "trial-finished",
            mapOf(
                "cosmetic" to (cosmetic?.displayName ?: cosmeticId),
                "price" to (cosmetic?.let { formatPrice(priceOf(it)) } ?: "?"),
            ),
        )
    }

    /** Restores the pre-trial state of the slot. */
    private fun revertTrial(uuid: UUID, session: TrialSession, restore: Boolean) {
        val state = storage.cached(uuid) ?: return
        val cosmetic = config.cosmetic(session.cosmeticId)
        val slotId = cosmetic?.slot?.id ?: return

        // Only undo the equip when the trial cosmetic is still the one worn:
        // a purchase in the meantime must not be rolled back.
        if (state.equippedIn(slotId) != session.cosmeticId) return

        val previous = session.previousInSlot
        if (restore && previous != null) {
            state.equipped[slotId] = previous
        } else {
            state.equipped.remove(slotId)
        }
        storage.markDirty(uuid)

        val player = plugin.server.getPlayer(uuid) ?: return
        if (!player.isOnline) return
        renderer.refresh(player)
    }

    // ------------------------------------------------------------------ admin

    /**
     * Admin grant: adds the cosmetic to the target's local data and, when
     * LuckPerms is present, to their permission nodes.
     */
    fun grant(player: Player, cosmeticId: String): ActionResult {
        val cosmetic = config.cosmetic(cosmeticId)
            ?: return ActionResult(false, "unknown-cosmetic", mapOf("id" to cosmeticId))

        val state = storage.load(player.uniqueId)
        state.addOwned(cosmetic.id)
        storage.markDirty(player.uniqueId)

        val node = cosmetic.permissionFor(player.name, player.uniqueId.toString())
        luckPerms.grant(player.uniqueId, node)

        return ActionResult(
            true,
            "granted",
            mapOf(
                "cosmetic" to cosmetic.displayName,
                "player" to player.name,
            ),
        )
    }

    /**
     * Admin revoke: removes the cosmetic from local data, unequips it and
     * strips the LuckPerms node.
     */
    fun revoke(player: Player, cosmeticId: String): ActionResult {
        val cosmetic = config.cosmetic(cosmeticId)
            ?: return ActionResult(false, "unknown-cosmetic", mapOf("id" to cosmeticId))

        val state = storage.load(player.uniqueId)
        state.removeOwned(cosmetic.id)
        storage.markDirty(player.uniqueId)

        val node = cosmetic.permissionFor(player.name, player.uniqueId.toString())
        luckPerms.revoke(player.uniqueId, node)

        renderer.refresh(player)

        return ActionResult(
            true,
            "revoked",
            mapOf(
                "cosmetic" to cosmetic.displayName,
                "player" to player.name,
            ),
        )
    }

    /** True when [player] owns [cosmeticId]. */
    fun owns(player: Player, cosmetic: Cosmetic): Boolean {
        val state = storage.cached(player.uniqueId)
        if (state?.owns(cosmetic.id) == true) return true

        val node = cosmetic.permissionFor(player.name, player.uniqueId.toString())
        return luckPerms.hasPermission(player, node)
    }

    /** True when the cosmetic is currently equipped by [player]. */
    fun isEquipped(player: Player, cosmeticId: String): Boolean =
        storage.cached(player.uniqueId)?.isEquipped(cosmeticId) == true

    /** True when [player] already spent their free trial on [cosmeticId]. */
    fun hasTrialed(player: Player, cosmeticId: String): Boolean =
        storage.cached(player.uniqueId)?.hasTrialed(cosmeticId) == true

    /** Re-renders every cosmetic of [player]. */
    fun refresh(player: Player) {
        renderer.refresh(player)
    }

    /**
     * Final price of [cosmetic], after applying the optional per-slot price
     * rules configured in `cosmetics.yml` (`prices.head`, …).
     */
    fun priceOf(cosmetic: Cosmetic): Double = config.priceFor(cosmetic)

    fun formatPrice(amount: Double): String =
        economy.format(amount, currencySymbol)

    fun message(key: String, placeholders: Map<String, String> = emptyMap()): String =
        messages.raw(key, placeholders)
}
