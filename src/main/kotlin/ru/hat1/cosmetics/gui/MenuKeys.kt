package ru.hat1.cosmetics.gui

import org.bukkit.NamespacedKey

/**
 * NamespacedKeys and action constants shared by [MenuFactory] and [MenuListener].
 */
object MenuKeys {

    val ACTION_KEY = NamespacedKey("3dcosmetics", "menu_action")
    val TARGET_KEY = NamespacedKey("3dcosmetics", "menu_target")

    const val OPEN_CATEGORY = "open_category"
    const val BACK = "back"
    const val BUY = "buy"
    const val EQUIP = "equip"
    const val UNEQUIP = "unequip"
    const val UNEQUIP_CATEGORY = "unequip_category"
    const val TRIAL = "trial"
    const val NONE = "none"
}
