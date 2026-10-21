package ru.hat1.cosmetics.config

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Display
import org.bukkit.plugin.java.JavaPlugin
import ru.hat1.cosmetics.model.Anchor
import ru.hat1.cosmetics.model.Cosmetic
import ru.hat1.cosmetics.model.CosmeticCategory
import ru.hat1.cosmetics.model.CosmeticSlot
import ru.hat1.cosmetics.model.DisplayModel
import java.io.File
import java.util.logging.Level

/**
 * Parses `cosmetics.yml` into strongly typed definitions.
 *
 * Parsing is intentionally tolerant: a broken entry is logged and skipped
 * instead of taking the whole plugin down.
 */
class CosmeticsConfig(private val plugin: JavaPlugin) {

    private val categories = LinkedHashMap<String, CosmeticCategory>()
    private val cosmetics = LinkedHashMap<String, Cosmetic>()
    private val byCategory = LinkedHashMap<String, MutableList<Cosmetic>>()

    fun load() {
        categories.clear()
        cosmetics.clear()
        byCategory.clear()
        priceDefaults.clear()
        priceMultipliers.clear()

        val file = File(plugin.dataFolder, FILE_NAME)
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false)
        }

        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Не удалось прочитать $FILE_NAME", e)
            return
        }

        loadCategories(yaml)
        loadPriceRules(yaml)
        loadCosmetics(yaml)

        plugin.logger.info("Загружено категорий: ${categories.size}, косметики: ${cosmetics.size}")
    }

    private fun loadCategories(yaml: YamlConfiguration) {
        val root = yaml.getConfigurationSection("categories") ?: run {
            plugin.logger.warning("В $FILE_NAME отсутствует секция categories")
            return
        }

        var fallbackSlot = 10
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            val icon = parseMaterial(section.getString("icon"), Material.CHEST) ?: continue
            val category = CosmeticCategory(
                id = id.lowercase(),
                displayName = MessageService.color(section.getString("display-name") ?: id),
                icon = icon,
                slot = section.getInt("slot", fallbackSlot),
            )
            categories[category.id] = category
            byCategory[category.id] = mutableListOf()
            fallbackSlot += 2
        }
    }

    private fun loadCosmetics(yaml: YamlConfiguration) {
        val root = yaml.getConfigurationSection("cosmetics") ?: run {
            plugin.logger.warning("В $FILE_NAME отсутствует секция cosmetics")
            return
        }

        for (rawId in root.getKeys(false)) {
            val id = rawId.lowercase()
            val section = root.getConfigurationSection(rawId) ?: continue

            val categoryId = section.getString("category", "")?.lowercase().orEmpty()
            if (categoryId.isEmpty()) {
                plugin.logger.warning("Косметика '$id' пропущена: не указана category")
                continue
            }
            if (!categories.containsKey(categoryId)) {
                plugin.logger.warning(
                    "Косметика '$id' пропущена: неизвестная категория '$categoryId'"
                )
                continue
            }

            val model = parseModel(section.getConfigurationSection("model"), id) ?: continue
            val iconMaterial = parseMaterial(section.getString("icon"), model.material) ?: continue

            val cosmetic = Cosmetic(
                id = id,
                categoryId = categoryId,
                displayName = MessageService.color(section.getString("display-name") ?: id),
                description = section.getStringList("description").map(MessageService::color),
                icon = iconMaterial,
                price = section.getDouble("price", -1.0),
                permission = section.getString("permission").orEmpty(),
                model = model,
                slot = model.slot,
                trialAllowed = section.getBoolean("trial", true),
            )

            cosmetics[id] = cosmetic
            byCategory.getOrPut(categoryId) { mutableListOf() }.add(cosmetic)
        }
    }

    private fun parseModel(
        section: org.bukkit.configuration.ConfigurationSection?,
        cosmeticId: String,
    ): DisplayModel? {
        if (section == null) {
            plugin.logger.warning("Косметика '$cosmeticId' пропущена: не указана model")
            return null
        }

        val material = parseMaterial(section.getString("material"), Material.PAPER) ?: return null
        val customModelData = if (section.contains("custom-model-data")) {
            section.getInt("custom-model-data")
        } else {
            null
        }

        val anchor = runCatching {
            Anchor.valueOf(section.getString("anchor", "head").orEmpty().uppercase())
        }.getOrElse {
            plugin.logger.warning(
                "Косметика '$cosmeticId': неизвестный anchor, используется head"
            )
            Anchor.HEAD
        }

        // Явный слот побеждает; иначе слот выводится из anchor-а точки крепления.
        val slot = CosmeticSlot.parse(section.getString("slot"))
            ?: slotFromAnchor(anchor)

        val billboard = runCatching {
            Display.Billboard.valueOf(
                section.getString("billboard", "fixed").orEmpty().uppercase()
            )
        }.getOrElse { Display.Billboard.FIXED }

        return DisplayModel(
            material = material,
            itemModel = section.getString("item-model").orEmpty(),
            customModelData = customModelData,
            scale = section.getDouble("scale", 1.0).toFloat(),
            translation = parseTriple(section, "translation"),
            rotation = parseTriple(section, "rotation"),
            anchor = anchor,
            billboard = billboard,
            slot = slot,
        )
    }

    /** Выводит слот из точки крепления, когда `slot` не указан явно. */
    private fun slotFromAnchor(anchor: Anchor): CosmeticSlot = when (anchor) {
        Anchor.HEAD -> CosmeticSlot.HEAD
        Anchor.CHEST, Anchor.BACK -> CosmeticSlot.BODY
        Anchor.LEFT_ARM, Anchor.RIGHT_ARM -> CosmeticSlot.ARMS
        Anchor.FEET, Anchor.LEFT_LEG, Anchor.RIGHT_LEG -> CosmeticSlot.LEGS
    }

    private fun parseTriple(
        section: org.bukkit.configuration.ConfigurationSection,
        path: String,
    ): Triple<Float, Float, Float> {
        val list = section.getDoubleList(path)
        if (list.size < 3) return Triple(0f, 0f, 0f)
        return Triple(list[0].toFloat(), list[1].toFloat(), list[2].toFloat())
    }

    private fun parseMaterial(raw: String?, fallback: Material): Material? {
        if (raw.isNullOrBlank()) return fallback
        val material = Material.matchMaterial(raw.trim().uppercase())
        if (material == null) {
            plugin.logger.warning("Неизвестный материал '$raw', используется $fallback")
            return fallback
        }
        return material
    }

    fun categories(): List<CosmeticCategory> = categories.values.toList()

    fun category(id: String): CosmeticCategory? = categories[id.lowercase()]

    fun cosmetic(id: String): Cosmetic? = cosmetics[id.lowercase()]

    fun cosmeticsIn(categoryId: String): List<Cosmetic> =
        byCategory[categoryId.lowercase()].orEmpty().toList()

    fun all(): Collection<Cosmetic> = cosmetics.values

    /** Все косметики, которые крепятся к [slot]. */
    fun cosmeticsInSlot(slot: CosmeticSlot): List<Cosmetic> =
        cosmetics.values.filter { it.slot == slot }

    /**
     * Итоговая цена косметики с учётом правил по слотам.
     *
     * В `cosmetics.yml` можно задать:
     *
     *   prices:
     *     head:
     *       multiplier: 1.0   # общий множитель
     *       default: 2500     # цена для косметики без своего price
     *
     * Приоритет: конкретное поле price у косметики → default слота → 0.
     * Множитель применяется последним.
     */
    fun priceFor(cosmetic: Cosmetic): Double {
        val base = if (cosmetic.price >= 0.0) {
            cosmetic.price
        } else {
            priceDefaults[cosmetic.slot] ?: 0.0
        }
        val multiplier = priceMultipliers[cosmetic.slot] ?: 1.0
        return (base * multiplier).coerceAtLeast(0.0)
    }

    private val priceDefaults = HashMap<CosmeticSlot, Double>()
    private val priceMultipliers = HashMap<CosmeticSlot, Double>()

    private fun loadPriceRules(yaml: YamlConfiguration) {
        priceDefaults.clear()
        priceMultipliers.clear()

        val root = yaml.getConfigurationSection("prices") ?: return
        for (key in root.getKeys(false)) {
            val slot = CosmeticSlot.parse(key)
            if (slot == null) {
                plugin.logger.warning("prices: неизвестный слот '$key' (ожидались head/body/arms/legs)")
                continue
            }
            val section = root.getConfigurationSection(key) ?: continue
            if (section.contains("default")) {
                priceDefaults[slot] = section.getDouble("default")
            }
            if (section.contains("multiplier")) {
                priceMultipliers[slot] = section.getDouble("multiplier", 1.0)
            }
        }
    }

    companion object {
        const val FILE_NAME = "cosmetics.yml"
    }
}
