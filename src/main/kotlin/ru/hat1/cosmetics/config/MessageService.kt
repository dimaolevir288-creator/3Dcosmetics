package ru.hat1.cosmetics.config

import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin

/**
 * Загружает сообщения из config.yml и превращает их в цветной текст.
 *
 * Поддерживается необязательный файл локализации `messages_<язык>.yml`:
 * он загружается первым, а значения из config.yml перекрывают его.
 */
class MessageService(private val plugin: JavaPlugin) {

    private val messages = mutableMapOf<String, String>()
    private var prefix: String = ""

    fun load() {
        messages.clear()

        // 1. Базовая локаль (сообщения из ресурсов плагина).
        val language = plugin.config.getString("language")?.trim().orEmpty()
        if (language.isNotEmpty()) {
            loadBundled(language)
        }

        // 2. Значения из config.yml — приоритетнее.
        val config: FileConfiguration = plugin.config
        prefix = color(config.getString("messages.prefix").orEmpty())
        val section = config.getConfigurationSection("messages")
        if (section != null) {
            for (key in section.getKeys(false)) {
                if (key == "prefix") continue
                messages[key] = section.getString(key).orEmpty()
            }
        }

        // 3. Если файл локализации лежит рядом с конфигом — тоже читаем.
        if (language.isNotEmpty()) {
            loadExternal(language)
        }
    }

    /** Читает `messages_<language>.yml` из ресурсов jar. */
    private fun loadBundled(language: String) {
        val stream = plugin.getResource("lang/messages_$language.yml") ?: return
        stream.use { input ->
            val yaml = org.bukkit.configuration.file.YamlConfiguration()
            yaml.load(java.io.InputStreamReader(input, Charsets.UTF_8))
            valuesInto(yaml)
        }
    }

    /** Читает `messages_<language>.yml` из папки плагина, если он есть. */
    private fun loadExternal(language: String) {
        val file = java.io.File(plugin.dataFolder, "messages_$language.yml")
        if (!file.exists()) return
        runCatching {
            val yaml = org.bukkit.configuration.file.YamlConfiguration()
            yaml.load(file)
            valuesInto(yaml)
        }.onFailure {
            plugin.logger.warning("Не удалось прочитать ${file.name}: ${it.message}")
        }
    }

    /** Переносит пары ключ-значение из загруженного файла в карту сообщений. */
    private fun valuesInto(yaml: org.bukkit.configuration.file.YamlConfiguration) {
        val section = yaml.getConfigurationSection("messages") ?: return
        for (key in section.getKeys(false)) {
            if (key == "prefix") {
                if (prefix.isEmpty()) prefix = color(section.getString(key).orEmpty())
                continue
            }
            // Значения из config.yml перекрывают локаль.
            messages.putIfAbsent(key, section.getString(key).orEmpty())
        }
    }

    /**
     * Returns the raw (coloured) message for [key] without the prefix.
     */
    fun raw(key: String, placeholders: Map<String, String> = emptyMap()): String =
        apply(messages[key] ?: key, placeholders)

    /**
     * Returns the message prefixed with the configured chat prefix.
     */
    fun prefixed(key: String, placeholders: Map<String, String> = emptyMap()): String =
        prefix + raw(key, placeholders)

    fun send(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        sender.sendMessage(prefixed(key, placeholders))
    }

    fun has(key: String): Boolean = messages.containsKey(key)

    private fun apply(text: String, placeholders: Map<String, String>): String {
        var result = text
        for ((name, value) in placeholders) {
            result = result.replace("%$name%", value)
        }
        return color(result)
    }

    companion object {
        private val HEX_PATTERN = Regex("&#([0-9a-fA-F]{6})")

        /**
         * Translates `&` colour codes and `&#RRGGBB` hex codes.
         */
        fun color(input: String): String {
            val withHex = HEX_PATTERN.replace(input) { match ->
                val hex = match.groupValues[1]
                "§x" + hex.map { "§$it" }.joinToString("")
            }
            return withHex.replace('&', '§')
        }
    }
}
