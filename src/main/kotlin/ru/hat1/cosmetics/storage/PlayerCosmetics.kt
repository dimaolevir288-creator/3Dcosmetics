package ru.hat1.cosmetics.storage

import java.util.UUID

/**
 * In-memory snapshot of a single player's cosmetics state.
 *
 * Must only be mutated on the main server thread; persistence is debounced
 * by [YamlCosmeticsStorage] and always writes asynchronously.
 *
 * Keyed by **slot group** (`head`, `body`, `arms`, `legs`), not by menu
 * category: two cosmetics of different categories still collide when they
 * attach to the same body part.
 */
class PlayerCosmetics(
    val uuid: UUID,
    val owned: MutableSet<String> = mutableSetOf(),
    /** slot id -> cosmetic id */
    val equipped: MutableMap<String, String> = mutableMapOf(),
    /**
     * Cosmetics already tried on for free. A trial is granted once per
     * cosmetic and never repeats, so it stays a demo rather than a free
     * permanent unlock.
     */
    val trials: MutableSet<String> = mutableSetOf(),
) {

    /** True when the player owns [cosmeticId]. */
    fun owns(cosmeticId: String): Boolean = owned.contains(cosmeticId.lowercase())

    /** Adds the cosmetic to the owned set. Returns true when it was not owned before. */
    fun addOwned(cosmeticId: String): Boolean = owned.add(cosmeticId.lowercase())

    /** Removes the cosmetic from the owned set and unequips it. */
    fun removeOwned(cosmeticId: String): Boolean {
        val id = cosmeticId.lowercase()
        equipped.values.removeIf { it == id }
        return owned.remove(id)
    }

    /** Cosmetic id currently equipped in [slotId], if any. */
    fun equippedIn(slotId: String): String? = equipped[slotId.lowercase()]

    /**
     * Equips [cosmeticId] in [slotId], replacing whatever was equipped there.
     * Returns the previously equipped id in that slot (or null).
     */
    fun equip(slotId: String, cosmeticId: String): String? =
        equipped.put(slotId.lowercase(), cosmeticId.lowercase())

    /** Unequips [cosmeticId] if it is currently equipped. */
    fun unequip(cosmeticId: String): Boolean {
        val id = cosmeticId.lowercase()
        val entry = equipped.entries.firstOrNull { it.value == id } ?: return false
        equipped.remove(entry.key)
        return true
    }

    /** True when [cosmeticId] is equipped in any slot. */
    fun isEquipped(cosmeticId: String): Boolean =
        equipped.values.contains(cosmeticId.lowercase())

    /** True when [cosmeticId] was already tried on for free. */
    fun hasTrialed(cosmeticId: String): Boolean = trials.contains(cosmeticId.lowercase())

    /** Marks [cosmeticId] as trialed. Returns false when it was already tried. */
    fun markTrialed(cosmeticId: String): Boolean = trials.add(cosmeticId.lowercase())
}
