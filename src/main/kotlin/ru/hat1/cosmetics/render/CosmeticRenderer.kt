package ru.hat1.cosmetics.render

import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.hat1.cosmetics.model.Anchor
import ru.hat1.cosmetics.model.Cosmetic
import ru.hat1.cosmetics.model.DisplayModel
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders equipped cosmetics as invisible [ItemDisplay] entities riding the
 * owning player.
 *
 * Vanilla clients see the displays because they are ordinary entities; there is
 * no packet trickery involved, which keeps the plugin compatible with every
 * Paper/Spigot 1.21.x build and with plugins that inspect entities.
 *
 * The renderer must be driven from the main thread: call [tick] from a
 * repeating task and [refresh]/[clear] whenever the state changes.
 */
class CosmeticRenderer(
    private val plugin: JavaPlugin,
    private val attachments: Attachments,
) {

    /** player uuid -> (cosmetic id -> display entity). Main-thread only. */
    private val rendered = HashMap<UUID, MutableMap<String, ItemDisplay>>()

    /**
     * Recomputes the display set for [player], spawning/removing entities as
     * needed. Safe to call every tick; entity creation only happens on change.
     */
    fun refresh(player: Player) {
        val wanted = attachments.attached(player)
        val current = rendered.getOrPut(player.uniqueId) { mutableMapOf() }

        // Remove cosmetics that are no longer equipped.
        val unwanted = current.keys.filter { it !in wanted }
        for (cosmeticId in unwanted) {
            current.remove(cosmeticId)?.let { display ->
                if (display.isValid) display.remove()
            }
        }

        // Spawn missing cosmetics.
        for ((cosmeticId, cosmetic) in wanted) {
            val existing = current[cosmeticId]
            if (existing != null && existing.isValid) continue
            if (existing != null) current.remove(cosmeticId)

            val display = spawn(player, cosmetic) ?: continue
            current[cosmeticId] = display
        }
    }

    /**
     * Repositions already-spawned displays so they follow the player smoothly.
     * Cheap enough to run every tick.
     */
    fun tick(player: Player) {
        val current = rendered[player.uniqueId] ?: return
        if (current.isEmpty()) return

        // Collect stale entries first: mutating the map while iterating would
        // throw ConcurrentModificationException.
        val stale = mutableListOf<String>()

        for ((cosmeticId, display) in current) {
            if (!display.isValid) {
                stale += cosmeticId
                continue
            }
            val cosmetic = attachments.cosmetic(cosmeticId) ?: continue
            display.teleport(anchorLocation(player, cosmetic.model))
        }

        for (id in stale) {
            current.remove(id)
        }
    }

    /** Removes every display belonging to [player]. */
    fun clear(player: Player) {
        val current = rendered.remove(player.uniqueId) ?: return
        for (display in current.values) {
            if (display.isValid) display.remove()
        }
    }

    /** Removes a single cosmetic display from [player] without a full refresh. */
    fun clearOne(player: Player, cosmeticId: String) {
        val current = rendered[player.uniqueId] ?: return
        val display = current.remove(cosmeticId.lowercase()) ?: return
        if (display.isValid) display.remove()
    }

    /** Removes every display tracked by the renderer. */
    fun clearAll() {
        for (displays in rendered.values) {
            for (display in displays.values.toList()) {
                if (display.isValid) display.remove()
            }
        }
        rendered.clear()
    }

    /** Number of live displays for [player]; used by tests and debug output. */
    fun renderedCount(player: Player): Int = rendered[player.uniqueId]?.size ?: 0

    private fun spawn(player: Player, cosmetic: Cosmetic): ItemDisplay? {
        val location = anchorLocation(player, cosmetic.model)
        val model = cosmetic.model

        val display = runCatching {
            player.world.spawn(location, ItemDisplay::class.java) { entity ->
                entity.setItemStack(buildItem(model))
                entity.billboard = model.billboard
                entity.isPersistent = false
                entity.setGravity(false)
                entity.isInvulnerable = true
                entity.transformation = transformation(model)
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.setMetadata(METADATA_KEY, FixedMetadataValue(plugin, cosmetic.id))
            }
        }.getOrElse {
            plugin.logger.warning("Не удалось создать ItemDisplay для ${cosmetic.id}: ${it.message}")
            return null
        }

        // The display is positioned by tick() every rendering interval. Making it
        // a passenger as well would fight with those teleports, so it stays a
        // free-standing entity in front of the client.
        return display
    }

    /**
     * Builds the display item.
     *
     * `ItemMeta#setItemModel` exists only on Paper 1.21.4+, so it is applied
     * reflectively: on Spigot the call is simply skipped and CustomModelData
     * (or the plain material model) is used instead.
     */
    private fun buildItem(model: DisplayModel): ItemStack {
        val item = ItemStack(model.material)
        val meta = item.itemMeta ?: return item

        model.customModelData?.let { data -> applyCustomModelData(meta, data) }

        if (model.itemModel.isNotBlank()) {
            applyItemModel(meta, model.itemModel)
        }

        item.itemMeta = meta
        return item
    }

    /**
     * Applies CustomModelData in a version-tolerant way.
     *
     * 1.21.5+ replaced the integer setter with a component while keeping the
     * legacy method; invoking it reflectively keeps the plugin compilable against
     * the oldest supported API while working on newer servers.
     */
    private fun applyCustomModelData(meta: org.bukkit.inventory.meta.ItemMeta, data: Int) {
        runCatching {
            @Suppress("DEPRECATION")
            meta.setCustomModelData(data)
        }.onFailure {
            plugin.logger.fine("CustomModelData не применён: ${it.message}")
        }
    }

    /**
     * Applies a 1.21.4+ `item-model` component when the server exposes it.
     * Silently does nothing on Spigot builds without the method.
     */
    private fun applyItemModel(meta: org.bukkit.inventory.meta.ItemMeta, key: String) {
        val parsed = org.bukkit.NamespacedKey.fromString(key) ?: run {
            plugin.logger.fine("Некорректный item-model '$key'")
            return
        }

        runCatching {
            val method = meta.javaClass.getMethod("setItemModel", org.bukkit.NamespacedKey::class.java)
            method.invoke(meta, parsed)
        }.onFailure {
            plugin.logger.fine("item-model не поддержан этим сервером: ${it.message}")
        }
    }

    /**
     * Builds the display transformation from scale/translation/rotation.
     */
    private fun transformation(model: DisplayModel): Transformation {
        val scale = Vector3f(model.scale, model.scale, model.scale)
        val translation = Vector3f(
            model.translation.first,
            model.translation.second,
            model.translation.third,
        )
        val leftRotation = euler(model.rotation.first, model.rotation.second, model.rotation.third)
        return Transformation(translation, leftRotation, scale, Quaternionf())
    }

    private fun euler(yaw: Float, pitch: Float, roll: Float): Quaternionf {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val rollRad = Math.toRadians(roll.toDouble())

        val qy = Quaternionf().rotateY(yawRad.toFloat())
        val qx = Quaternionf().rotateX(pitchRad.toFloat())
        val qz = Quaternionf().rotateZ(rollRad.toFloat())
        return qy.mul(qx).mul(qz)
    }

    /**
     * Computes the world location for [model]'s anchor around [player].
     * Offsets are rotated by the player's yaw so capes stay on the back.
     */
    private fun anchorLocation(player: Player, model: DisplayModel): Location {
        val base = player.location
        val yawRad = Math.toRadians(base.yaw.toDouble())

        // Forward direction of the player in the X/Z plane.
        val forwardX = -sin(yawRad)
        val forwardZ = cos(yawRad)
        val rightX = cos(yawRad)
        val rightZ = sin(yawRad)

        val (offsetY, offsetForward) = anchorOffset(model.anchor)

        val worldOffset = model.translation
        // The Z component of the translation is treated as "backwards" offset,
        // the X component as sideways offset, Y as height above the anchor.
        val back = worldOffset.third + offsetForward
        val side = worldOffset.first + anchorSide(model.anchor)
        val height = worldOffset.second

        val x = base.x - forwardX * back + rightX * side
        val y = base.y + offsetY + height
        val z = base.z - forwardZ * back + rightZ * side

        return Location(base.world, x, y, z, base.yaw, 0f)
    }

    /**
     * Returns (heightAboveFeet, backwardOffset) for an anchor point.
     *
     * Blockbench/vanilla player model is 32 pixels tall (2 blocks).
     * Arms hang from y=22 to y=12, legs from y=12 to y=0.
     */
    private fun anchorOffset(anchor: Anchor): Pair<Double, Double> = when (anchor) {
        Anchor.HEAD -> 1.62 to 0.0
        Anchor.BACK -> 1.20 to 0.22
        Anchor.CHEST -> 1.20 to 0.0
        Anchor.FEET -> 0.10 to 0.0
        Anchor.LEFT_ARM -> 1.10 to 0.0
        Anchor.RIGHT_ARM -> 1.10 to 0.0
        Anchor.LEFT_LEG -> 0.55 to 0.0
        Anchor.RIGHT_LEG -> 0.55 to 0.0
    }

    /**
     * Sideways offset in blocks: negative is the player's left, positive the right.
     *
     * The player model is 8 pixels wide per arm/leg, so limbs sit at ±6 px
     * from the centre (6/16 = 0.375 blocks).
     */
    private fun anchorSide(anchor: Anchor): Double = when (anchor) {
        Anchor.LEFT_ARM -> -0.375
        Anchor.RIGHT_ARM -> 0.375
        Anchor.LEFT_LEG -> -0.125
        Anchor.RIGHT_LEG -> 0.125
        else -> 0.0
    }

    companion object {
        /** Metadata key marking entities owned by this plugin. */
        const val METADATA_KEY = "3dcosmetics_display"

        /** Marker check used to clean up leaked displays on startup/shutdown. */
        fun isCosmeticDisplay(entity: org.bukkit.entity.Entity): Boolean =
            entity is ItemDisplay && entity.hasMetadata(METADATA_KEY)
    }
}
