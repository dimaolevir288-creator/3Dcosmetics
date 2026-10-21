package ru.hat1.cosmetics.gui

import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.hat1.cosmetics.config.MessageService
import ru.hat1.cosmetics.service.ActionResult
import ru.hat1.cosmetics.service.CosmeticsService

/**
 * Handles clicks inside the cosmetics menus.
 *
 * Every click inside one of our inventories is cancelled, including shift-clicks
 * and drags, so items can never be extracted.
 */
class MenuListener(
    private val plugin: JavaPlugin,
    private val factory: MenuFactory,
    private val service: CosmeticsService,
    private val messages: MessageService,
) : Listener {

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is GuiHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder
        if (holder !is GuiHolder) return

        // Always cancel: the menu is a read-only view.
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val clicked = event.currentItem ?: return

        val (action, target) = readTag(clicked)
        if (action == null) return

        // ПКМ по кнопке покупки/надевания запускает бесплатную примерку.
        val rightClick = event.click == org.bukkit.event.inventory.ClickType.RIGHT ||
            event.click == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT

        when (action) {
            MenuKeys.OPEN_CATEGORY -> {
                play(player, "open")
                factory.openCategory(player, target.orEmpty())
            }

            MenuKeys.BACK -> {
                play(player, "open")
                factory.openRoot(player)
            }

            MenuKeys.BUY -> {
                if (rightClick) {
                    val result = service.startTrial(player, target.orEmpty())
                    report(player, result, successSound = "equip")
                } else {
                    val result = service.purchase(player, target.orEmpty())
                    report(player, result)
                }
                reopen(player, holder)
            }

            MenuKeys.EQUIP -> {
                if (rightClick) {
                    val result = service.startTrial(player, target.orEmpty())
                    report(player, result, successSound = "equip")
                } else {
                    val result = service.equip(player, target.orEmpty())
                    report(player, result, successSound = "equip")
                }
                reopen(player, holder)
            }

            MenuKeys.TRIAL -> {
                val result = service.startTrial(player, target.orEmpty())
                report(player, result, successSound = "equip")
                reopen(player, holder)
            }

            MenuKeys.UNEQUIP -> {
                val result = service.unequip(player, target.orEmpty())
                report(player, result, successSound = "unequip")
                reopen(player, holder)
            }

            MenuKeys.UNEQUIP_CATEGORY -> {
                val categoryId = target.orEmpty()
                // Категория может содержать косметику разных слотов, поэтому
                // снимаем всё, что надето из этой категории.
                val result = service.unequipCategory(player, categoryId)
                report(player, result, successSound = "unequip")
                factory.openCategory(player, categoryId)
            }

            MenuKeys.NONE -> {
                // Informative slot, nothing to do.
            }
        }
    }

    /**
     * Re-opens the menu the player is currently looking at so buttons reflect
     * the new state (bought / equipped / unequipped).
     */
    private fun reopen(player: Player, holder: GuiHolder) {
        when (holder) {
            is GuiHolder.Root -> factory.openRoot(player)
            is GuiHolder.Category -> factory.openCategory(player, holder.categoryId)
        }
    }

    private fun report(
        player: Player,
        result: ActionResult,
        successSound: String = "purchase",
    ) {
        val message = messages.prefixed(result.messageKey, result.placeholders)
        player.sendMessage(message)

        if (result.success) {
            play(player, successSound)
            return
        }

        if (result.messageKey == "not-owned" || result.messageKey == "nothing-equipped") {
            // Informational, not an actual failure: stay quiet.
            return
        }

        play(player, "error")
    }

    private fun play(player: Player, key: String) {
        if (!plugin.config.getBoolean("sounds.enabled", true)) return
        val name = plugin.config.getString("sounds.$key") ?: return
        val sound = runCatching { Sound.valueOf(name.uppercase()) }.getOrNull() ?: return
        player.playSound(player.location, sound, 0.7f, 1.0f)
    }

    private fun readTag(item: ItemStack): Pair<String?, String?> {
        val meta = item.itemMeta ?: return null to null
        val container = meta.persistentDataContainer
        val action = container.get(MenuKeys.ACTION_KEY, PersistentDataType.STRING)
        val target = container.get(MenuKeys.TARGET_KEY, PersistentDataType.STRING)
        return action to target
    }
}
