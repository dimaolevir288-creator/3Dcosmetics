package ru.hat1.cosmetics.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.java.JavaPlugin
import ru.hat1.cosmetics.config.CosmeticsConfig
import ru.hat1.cosmetics.config.MessageService
import ru.hat1.cosmetics.model.Cosmetic
import ru.hat1.cosmetics.model.CosmeticCategory
import ru.hat1.cosmetics.model.CosmeticSlot
import ru.hat1.cosmetics.service.CosmeticsService

/**
 * Builds the cosmetics inventories.
 *
 * Layout rules:
 *  - the root menu shows one button per category;
 *  - a category menu shows one button per cosmetic, plus a "take everything off"
 *    button and a "back" button on the bottom row.
 *
 * Menu items encode their action and target id in the item's persistent data
 * container, so [MenuListener] never has to parse lore text.
 */
class MenuFactory(
    private val plugin: JavaPlugin,
    private val config: CosmeticsConfig,
    private val service: CosmeticsService,
    private val messages: MessageService,
) {

    private val rows: Int
        get() = plugin.config.getInt("menu.rows", 6).coerceIn(1, 6)

    private val fillerMaterial: Material
        get() = Material.matchMaterial(
            plugin.config.getString("menu.filler-material") ?: "GRAY_STAINED_GLASS_PANE"
        ) ?: Material.GRAY_STAINED_GLASS_PANE

    private val fillerName: String
        get() = plugin.config.getString("menu.filler-name") ?: " "

    private val currencySymbol: String
        get() = plugin.config.getString("economy.currency-symbol") ?: "$"

    // ------------------------------------------------------------------ root

    fun openRoot(player: Player) {
        val holder = GuiHolder.Root()
        val title = MessageService.color(plugin.config.getString("menu.title") ?: "Косметика")
        val inventory = Bukkit.createInventory(holder, rows * 9, title)
        holder.bind(inventory)

        fill(inventory)

        val used = mutableSetOf<Int>()
        for (category in config.categories()) {
            val preferred = category.slot
            val slot = when {
                preferred in 0 until inventory.size && preferred !in used -> preferred
                else -> nextFree(inventory, used)
            }
            if (slot < 0) continue
            used += slot
            inventory.setItem(slot, categoryButton(player, category))
        }

        player.openInventory(inventory)
    }

    private fun categoryButton(player: Player, category: CosmeticCategory): ItemStack {
        val cosmetics = config.cosmeticsIn(category.id)
        val owned = cosmetics.count { service.owns(player, it) }
        val equipped = cosmetics.count { service.isEquipped(player, it.id) }

        val lore = mutableListOf<String>()
        lore += "&7Всего: &f${cosmetics.size}"
        lore += "&7Открыто: &a$owned"
        if (equipped > 0) {
            lore += "&7Надето: &e$equipped"
        }
        lore += ""
        lore += "&eНажмите, чтобы открыть"

        return buildItem(
            material = category.icon,
            name = category.displayName,
            lore = lore.map(MessageService::color),
            action = MenuKeys.OPEN_CATEGORY,
            target = category.id,
        )
    }

    // -------------------------------------------------------------- category

    fun openCategory(player: Player, categoryId: String) {
        val category = config.category(categoryId) ?: run {
            openRoot(player)
            return
        }

        val holder = GuiHolder.Category(category.id)
        val titleTemplate = plugin.config.getString("menu.category-title")
            ?: "Косметика » %category%"
        val title = MessageService.color(titleTemplate.replace("%category%", category.displayName))
        val inventory = Bukkit.createInventory(holder, rows * 9, title)
        holder.bind(inventory)

        fill(inventory)

        val cosmetics = config.cosmeticsIn(category.id)
        val lastRowStart = inventory.size - 9
        var slot = 0
        for (cosmetic in cosmetics) {
            if (slot >= lastRowStart) break
            inventory.setItem(slot, cosmeticButton(player, cosmetic))
            slot++
        }

        // Bottom row controls.
        inventory.setItem(
            inventory.size - 9,
            buildItem(
                material = Material.BARRIER,
                name = MessageService.color("&cСнять всё"),
                lore = listOf(MessageService.color("&7Снимает всю надетую косметику этой категории")),
                action = MenuKeys.UNEQUIP_CATEGORY,
                target = category.id,
            ),
        )

        inventory.setItem(
            inventory.size - 1,
            buildItem(
                material = Material.ARROW,
                name = MessageService.color("&fНазад"),
                lore = listOf(MessageService.color("&7К списку категорий")),
                action = MenuKeys.BACK,
                target = "",
            ),
        )

        player.openInventory(inventory)
    }

    private fun cosmeticButton(player: Player, cosmetic: Cosmetic): ItemStack {
        val owned = service.owns(player, cosmetic)
        val equipped = service.isEquipped(player, cosmetic.id)
        val trialing = service.activeTrial(player) == cosmetic.id
        val trialAvailable = service.canTrial(player, cosmetic)

        val lore = mutableListOf<String>()
        lore += cosmetic.description
        if (cosmetic.description.isNotEmpty()) lore += ""

        lore += "&7Слот: &f${slotName(cosmetic.slot)}"

        when {
            trialing -> lore += "&b⏳ Примерка..."
            equipped -> lore += "&a✔ Надето"
            owned -> lore += "&e✔ Куплено"
            cosmetic.isPurchasable -> lore += "&7Цена: &6${service.formatPrice(service.priceOf(cosmetic))}"
            else -> lore += "&7Не продаётся"
        }

        if (!owned && !equipped) {
            lore += when {
                trialing -> ""
                trialAvailable -> "&7Примерка: &b${service.trialSeconds} сек. бесплатно"
                service.trialsEnabled -> "&8Примерка уже использована"
                else -> ""
            }
        }
        lore += ""

        val action = when {
            equipped -> MenuKeys.UNEQUIP
            owned -> MenuKeys.EQUIP
            cosmetic.isPurchasable -> MenuKeys.BUY
            trialAvailable -> MenuKeys.TRIAL
            else -> MenuKeys.NONE
        }

        when (action) {
            MenuKeys.UNEQUIP -> {
                lore += "&cЛКМ — снять"
                if (trialAvailable) lore += "&bПКМ — примерить на ${service.trialSeconds} сек."
            }

            MenuKeys.EQUIP -> lore += "&aЛКМ — надеть"
            MenuKeys.BUY -> {
                lore += "&aЛКМ — купить"
                if (trialAvailable) lore += "&bПКМ — примерить на ${service.trialSeconds} сек."
            }

            MenuKeys.TRIAL -> lore += "&bЛКМ — примерить на ${service.trialSeconds} сек."
            else -> lore += "&8Недоступно"
        }

        val glow = equipped || owned
        return buildItem(
            material = cosmetic.icon,
            name = cosmetic.displayName,
            lore = lore.filter { it.isNotEmpty() }.map(MessageService::color),
            action = action,
            target = cosmetic.id,
            glow = glow,
        )
    }

    /** Человекочитаемое имя слота. */
    private fun slotName(slot: CosmeticSlot): String = when (slot) {
        CosmeticSlot.HEAD -> MessageService.color("&fГолова")
        CosmeticSlot.BODY -> MessageService.color("&fТело")
        CosmeticSlot.ARMS -> MessageService.color("&fРуки")
        CosmeticSlot.LEGS -> MessageService.color("&fНоги")
    }

    // ----------------------------------------------------------------- utils

    private fun fill(inventory: Inventory) {
        val filler = ItemStack(fillerMaterial)
        val meta = filler.itemMeta
        if (meta != null) {
            meta.setDisplayName(MessageService.color(fillerName))
            filler.itemMeta = meta
        }
        for (slot in 0 until inventory.size) {
            inventory.setItem(slot, filler)
        }
    }

    /**
     * Returns the first slot that is not yet occupied by a category button.
     * The bottom row is skipped so future navigation buttons keep their place.
     */
    private fun nextFree(inventory: Inventory, used: Set<Int>): Int {
        val lastRowStart = inventory.size - 9
        for (slot in 0 until lastRowStart) {
            if (slot !in used) return slot
        }
        return -1
    }

    /**
     * Builds a menu item tagged with the action that should run on click.
     */
    private fun buildItem(
        material: Material,
        name: String,
        lore: List<String>,
        action: String,
        target: String,
        glow: Boolean = false,
    ): ItemStack {
        val item = ItemStack(material)
        val meta: ItemMeta = item.itemMeta ?: return item

        meta.setDisplayName(name)
        if (lore.isNotEmpty()) meta.lore = lore

        if (glow) {
            runCatching {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true)
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS)
            }
        }

        // Tag with persistent data so clicks are unambiguous.
        val container = meta.persistentDataContainer
        container.set(MenuKeys.ACTION_KEY, org.bukkit.persistence.PersistentDataType.STRING, action)
        container.set(MenuKeys.TARGET_KEY, org.bukkit.persistence.PersistentDataType.STRING, target)

        item.itemMeta = meta
        return item
    }
}
