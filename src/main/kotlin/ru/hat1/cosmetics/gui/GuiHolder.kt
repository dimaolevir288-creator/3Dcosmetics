package ru.hat1.cosmetics.gui

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * Inventory holder used to identify our menus in [org.bukkit.event.inventory.InventoryClickEvent].
 *
 * Using a dedicated holder (instead of comparing titles) is the only reliable
 * way to detect custom inventories: it survives colour codes, translations and
 * inventory spoofing.
 */
sealed class GuiHolder : InventoryHolder {

    private lateinit var inventory: Inventory

    override fun getInventory(): Inventory = inventory

    /** Called by the menu factory right after the inventory is created. */
    fun bind(inventory: Inventory) {
        this.inventory = inventory
    }

    /** Root menu listing every category. */
    class Root : GuiHolder()

    /** Menu listing every cosmetic inside [categoryId]. */
    class Category(val categoryId: String) : GuiHolder()
}
