package ru.hat1.cosmetics.model

import org.bukkit.Material
import org.bukkit.entity.Display

/**
 * Describes how an [org.bukkit.entity.ItemDisplay] should be configured
 * to render a single cosmetic.
 */
data class DisplayModel(
    val material: Material,
    /** 1.21.4+ item-model key; empty means "use the vanilla material model". */
    val itemModel: String = "",
    /** CustomModelData for resource-pack driven models, or null when unused. */
    val customModelData: Int? = null,
    val scale: Float = 1.0f,
    val translation: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    val rotation: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    val anchor: Anchor = Anchor.HEAD,
    val billboard: Display.Billboard = Display.Billboard.FIXED,
    /**
     * Slot group the cosmetic occupies. Only one cosmetic per [CosmeticSlot]
     * can be worn at a time, and different slots stack on top of each other.
     */
    val slot: CosmeticSlot = CosmeticSlot.HEAD,
)

/**
 * Attachment point relative to the owning player.
 * Offsets are applied in the player's local space (yaw-relative).
 */
enum class Anchor {
    HEAD,
    BACK,
    CHEST,
    FEET,
    LEFT_ARM,
    RIGHT_ARM,
    LEFT_LEG,
    RIGHT_LEG,
    ;

    companion object {

        /**
         * Parses an anchor name tolerant of the spellings used in Blockbench
         * bones and in `cosmetics.yml` (`left_arm`, `leftArm`, `Left-Arm`, …).
         * Returns null when the name is not an anchor.
         */
        fun parse(raw: String?): Anchor? {
            if (raw.isNullOrBlank()) return null
            val normalized = normalize(raw)
            return entries.firstOrNull { normalize(it.name) == normalized }
        }

        private fun normalize(raw: String): String =
            raw.lowercase().filter { it.isLetterOrDigit() }
    }
}

/**
 * Body part a cosmetic is attached to.
 *
 * This is the *slot group*: a hat lives in [HEAD], a cape in [BODY], and so on.
 * One cosmetic per slot can be worn at a time; different slots stack.
 *
 * No armour or inventory slots are used — cosmetics are rendered as display
 * entities, so a [HEAD] cosmetic coexists with a real helmet.
 */
enum class CosmeticSlot(val id: String, val anchor: Anchor) {
    HEAD("head", Anchor.HEAD),
    BODY("body", Anchor.CHEST),
    ARMS("arms", Anchor.RIGHT_ARM),
    LEGS("legs", Anchor.FEET),
    ;

    /** Permission path segment: `cosmetics.head.<id>`. */
    val permissionPart: String get() = id

    companion object {

        /** Parses `head` / `body` / `arms` / `legs`, case- and dash-insensitive. */
        fun parse(raw: String?): CosmeticSlot? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.lowercase().filter { it.isLetterOrDigit() }
            return entries.firstOrNull { it.id == normalized }
        }

        /**
         * Maps a Blockbench bone name to a slot.
         *
         * Blockbench Minecraft skins and Custom Player Models both name their
         * bones after body parts, so `right_arm` or `Right Arm` lands in [ARMS].
         */
        fun fromBoneName(raw: String?): CosmeticSlot? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.lowercase().filter { it.isLetterOrDigit() }
            return when {
                normalized.contains("head") || normalized.contains("hat") -> HEAD
                normalized.contains("arm") -> ARMS
                normalized.contains("leg") || normalized.contains("foot") ||
                    normalized.contains("feet") || normalized.contains("boot") -> LEGS
                normalized.contains("body") || normalized.contains("torso") ||
                    normalized.contains("chest") || normalized.contains("cape") ||
                    normalized.contains("wing") || normalized.contains("back") -> BODY
                else -> null
            }
        }
    }
}

/**
 * A single cosmetics entry loaded from `cosmetics.yml`.
 */
data class Cosmetic(
    val id: String,
    val categoryId: String,
    val displayName: String,
    val description: List<String>,
    val icon: Material,
    /** Price in Vault currency. 0 = free, negative = not purchasable. */
    val price: Double,
    /** Permission node required to own/use the cosmetic; empty = none. */
    val permission: String,
    val model: DisplayModel,
    /** Body part this cosmetic occupies. */
    val slot: CosmeticSlot = CosmeticSlot.HEAD,
    /** True when the cosmetic can be worn temporarily without buying it. */
    val trialAllowed: Boolean = true,
) {

    val isPurchasable: Boolean get() = price >= 0.0

    val isFree: Boolean get() = price == 0.0

    /**
     * Resolves the runtime permission node for a specific player.
     * Supports `%player%` and `%uuid%` placeholders so servers can hand out
     * per-player nodes if they prefer.
     */
    fun permissionFor(playerName: String, uuid: String): String = permission
        .replace("%player%", playerName.lowercase())
        .replace("%uuid%", uuid.lowercase())
}

/**
 * A cosmetics category shown as a menu button.
 */
data class CosmeticCategory(
    val id: String,
    val displayName: String,
    val icon: Material,
    val slot: Int,
)
