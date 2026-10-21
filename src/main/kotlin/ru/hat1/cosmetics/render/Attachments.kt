package ru.hat1.cosmetics.render

import org.bukkit.entity.Player
import ru.hat1.cosmetics.config.CosmeticsConfig
import ru.hat1.cosmetics.integration.LuckPermsService
import ru.hat1.cosmetics.model.Cosmetic
import ru.hat1.cosmetics.storage.YamlCosmeticsStorage

/**
 * Resolves which cosmetics a player should currently be wearing.
 *
 * This is the single source of truth used by the renderer, the GUI and the
 * commands, which keeps the three layers from drifting apart.
 */
class Attachments(
    private val config: CosmeticsConfig,
    private val storage: YamlCosmeticsStorage,
    private val luckPerms: LuckPermsService,
) {

    /**
     * Returns the cosmetics that must be rendered for [player].
     *
     * An equipped cosmetic is dropped when the player no longer has the
     * required permission (e.g. an admin revoked the LuckPerms node), so a
     * revoked cosmetic stops rendering on the next refresh.
     *
     * Trials are previews of unowned cosmetics; they are rendered through the
     * same path, which is why no special-casing is needed here.
     */
    fun attached(player: Player): Map<String, Cosmetic> {
        val state = storage.cached(player.uniqueId) ?: return emptyMap()
        val result = LinkedHashMap<String, Cosmetic>()

        for ((_, cosmeticId) in state.equipped) {
            val cosmetic = config.cosmetic(cosmeticId) ?: continue
            // During a trial the player does not own the cosmetic yet.
            if (!state.owns(cosmetic.id)) {
                val node = cosmetic.permissionFor(player.name, player.uniqueId.toString())
                if (!luckPerms.hasPermission(player, node)) continue
            }
            result[cosmetic.id] = cosmetic
        }

        return result
    }

    /** Looks up a cosmetic definition by id. */
    fun cosmetic(cosmeticId: String): Cosmetic? = config.cosmetic(cosmeticId)

    /** True when [player] owns [cosmeticId] locally or via permission. */
    fun owns(player: Player, cosmeticId: String): Boolean {
        val cosmetic = config.cosmetic(cosmeticId) ?: return false
        val state = storage.cached(player.uniqueId)
        if (state?.owns(cosmetic.id) == true) return true

        val node = cosmetic.permissionFor(player.name, player.uniqueId.toString())
        return luckPerms.hasPermission(player, node)
    }
}
