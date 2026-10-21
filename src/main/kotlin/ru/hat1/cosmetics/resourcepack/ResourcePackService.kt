package ru.hat1.cosmetics.resourcepack

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.hat1.cosmetics.blockbench.BlockbenchImporter
import ru.hat1.cosmetics.blockbench.ImportSummary
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.logging.Level
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Отвечает за ресурспак плагина.
 *
 * При включении:
 *  1. распаковывает встроенный шаблон `resourcepack/` в папку плагина;
 *  2. импортирует модели Blockbench из папки `blockbench/` в тот же ресурспак;
 *  3. собирает из неё готовый `.zip`, который раздаётся клиентам;
 *  4. считает SHA-1, чтобы клиент мог проверить целостность.
 *
 * При входе игрока пакет отправляется принудительно (required = true),
 * поэтому клиент не может отказаться от его загрузки.
 */
class ResourcePackService(private val plugin: JavaPlugin) {

    /** Папка с распакованным содержимым ресурспака. */
    val folder: File get() = File(plugin.dataFolder, FOLDER_NAME)

    /** Папка, откуда берутся исходники моделей Blockbench (`.bbmodel`). */
    val blockbenchFolder: File get() = File(plugin.dataFolder, BlockbenchImporter.SOURCE_FOLDER)

    /** Импортёр моделей Blockbench, привязанный к папке этого ресурспака. */
    private val importer: BlockbenchImporter by lazy {
        BlockbenchImporter(plugin, blockbenchFolder, folder)
    }

    /** Готовый архив, который раздаётся клиентам. */
    val archive: File get() = File(plugin.dataFolder, "$ZIP_NAME.zip")

    /** SHA-1 архива в hex-виде; пусто, если архив ещё не собран. */
    var sha1: String = ""
        private set

    /** true, если ресурспак успешно подготовлен и может быть отправлен. */
    var ready: Boolean = false
        private set

    /** Встроенный HTTP-сервер, раздающий архив клиентам. */
    private val httpServer = ResourcePackHttpServer(plugin)

    /** URL, который фактически отправляется клиентам (для логов и `/cosmetics pack`). */
    var activeUrl: String = ""
        private set

    /**
     * Распаковывает шаблон, импортирует модели Blockbench, собирает архив
     * и считает хеш. Вызывается из `onEnable` и `/cosmetics reload`.
     */
    fun prepare() {
        ready = false
        try {
            extractTemplate()
            importBlockbenchModels()
            buildArchive()
            sha1 = computeSha1(archive)
            ready = archive.length() > 0 && sha1.isNotEmpty()
            if (ready) {
                plugin.logger.info("Ресурспак собран: ${archive.name} (${archive.length()} байт)")
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Не удалось подготовить ресурспак", e)
        }

        // Ссылка для клиентов вычисляется только после сборки архива.
        activeUrl = resolveUrl()
        validateUrlAsync()
    }

    /**
     * Останавливает встроенный HTTP-сервер. Вызывается из `onDisable`,
     * иначе поток сервера переживёт выгрузку плагина.
     */
    fun shutdown() {
        httpServer.stop()
    }

    /**
     * Пересобирает архив из текущего содержимого [folder] без импорта.
     * Используется после ручного импорта моделей Blockbench.
     */
    fun rebuild() {
        try {
            buildArchive()
            sha1 = computeSha1(archive)
            ready = archive.length() > 0 && sha1.isNotEmpty()
            plugin.logger.info("Ресурспак пересобран: ${archive.name} (${archive.length()} байт)")
        } catch (e: Exception) {
            ready = false
            plugin.logger.log(Level.SEVERE, "Не удалось пересобрать ресурспак", e)
        }
        activeUrl = resolveUrl()
    }

    /**
     * Импортирует все `.bbmodel` из папки `blockbench/` в ресурспак.
     *
     * Импорт идёт до сборки архива, поэтому модели сразу попадают в zip.
     * Уже импортированные и не изменившиеся файлы пропускаются по хешу.
     */
    fun importBlockbenchModels(force: Boolean = false): ImportSummary {
        val summary = importer.importAll(force)

        if (summary.isEmpty) {
            plugin.logger.fine("Blockbench: новых моделей нет (папка ${blockbenchFolder.name}/)")
            return summary
        }

        if (summary.imported.isNotEmpty()) {
            plugin.logger.info("Blockbench: импортировано моделей — ${summary.imported.size}")
        }
        for ((file, reason) in summary.failed) {
            plugin.logger.warning("Blockbench: ${file} пропущен — $reason")
        }
        return summary
    }

    /**
     * Распаковывает содержимое папки `resourcepack` из jar в папку плагина.
     *
     * Для перезаписи используется точный список ресурсов из jar, поэтому
     * пользовательские правки в папке не затираются без нужды: файл
     * перезаписывается только когда он отсутствует или отличается.
     */
    private fun extractTemplate() {
        folder.mkdirs()
        copyResource("pack.mcmeta", File(folder, "pack.mcmeta"))

        // Ресурсы внутри jar читаются через zip-файл плагина: перебираем записи.
        val jarFile = File(javaClass.protectionDomain.codeSource.location.toURI())

        if (jarFile.isFile && jarFile.extension.equals("jar", ignoreCase = true)) {
            java.util.zip.ZipFile(jarFile).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    if (!entry.name.startsWith("$TEMPLATE_ROOT/")) continue
                    val relative = entry.name.removePrefix("$TEMPLATE_ROOT/")
                    val target = File(folder, relative)
                    if (target.exists()) continue
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    private fun copyResource(resourcePath: String, target: File) {
        if (target.exists()) return
        val stream = plugin.getResource(resourcePath) ?: return
        target.parentFile?.mkdirs()
        stream.use { input ->
            Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Пакует содержимое [folder] в [archive]. */
    private fun buildArchive() {
        if (!folder.exists()) return

        archive.parentFile?.mkdirs()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            folder.walkTopDown()
                .filter { it.isFile && it.name != archive.name }
                .forEach { file ->
                    val relative = file.relativeTo(folder).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /**
     * Отправляет ресурспак игроку.
     * Возвращает true, если пакет был отправлен.
     */
    fun send(player: Player): Boolean {
        if (!ready) {
            plugin.logger.warning("Ресурспак не готов — пропуск отправки игроку ${player.name}")
            return false
        }

        val url = activeUrl.ifEmpty { resolveUrl() }
        if (url.isEmpty()) {
            // Причина уже записана в лог при resolveUrl(); здесь — только факт пропуска.
            plugin.logger.warning(
                "Ресурспак не отправлен ${player.name}: не задан рабочий URL (см. ошибки выше)."
            )
            return false
        }

        return try {
            plugin.logger.fine("Отправка ресурспака ${player.name}: $url")
            val promptText = plugin.config.getString("resource-pack.prompt").orEmpty()
            val prompt = if (promptText.isBlank()) {
                net.kyori.adventure.text.Component.empty()
            } else {
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand()
                    .deserialize(promptText)
            }

            player.setResourcePack(
                url,
                sha1Bytes(),
                prompt,
                plugin.config.getBoolean("resource-pack.force", true),
            )
            true
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Не удалось отправить ресурспак ${player.name}", e)
            false
        }
    }

    /** SHA-1 архива в виде байтов — этого требует API 1.21.x. */
    private fun sha1Bytes(): ByteArray {
        if (sha1.isEmpty()) return ByteArray(0)
        return ByteArray(sha1.length / 2) { i ->
            sha1.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Определяет URL для скачивания и при необходимости поднимает сервер.
     *
     * Два режима:
     *  1. `resource-pack.url` задан — используется он как есть (внешний хостинг).
     *  2. `resource-pack.url` пуст — поднимается встроенный HTTP-сервер,
     *     который отдаёт `3dcosmetics.zip` напрямую. Адрес берётся из
     *     `resource-pack.http-server.host` (или авто-определяется).
     *
     * Ссылка `file://` и `localhost` бесполезны для клиента — вместо них
     * пишется понятная ошибка в консоль.
     */
    private fun resolveUrl(): String {
        val configured = plugin.config.getString("resource-pack.url").orEmpty().trim()

        if (configured.isNotEmpty()) {
            // Своя ссылка: встроенный сервер не нужен.
            httpServer.stop()

            if (configured.startsWith("file:", ignoreCase = true)) {
                plugin.logger.severe(
                    "resource-pack.url не может быть file:// — клиент Minecraft " +
                        "не имеет доступа к диску сервера. Укажите http(s)-ссылку."
                )
                return ""
            }
            if (!configured.startsWith("http://", true) && !configured.startsWith("https://", true)) {
                plugin.logger.severe(
                    "resource-pack.url должен начинаться с http:// или https:// (сейчас: '$configured')"
                )
                return ""
            }
            return configured
        }

        if (!plugin.config.getBoolean("resource-pack.http-server.enabled", true)) {
            plugin.logger.severe(
                "Ресурспак не будет загружаться: resource-pack.url пуст, " +
                    "а встроенный сервер отключён (resource-pack.http-server.enabled = false)."
            )
            return ""
        }

        if (!archive.exists() || archive.length() == 0L) {
            plugin.logger.severe("Ресурспак не будет загружаться: архив ${archive.name} пуст или не собран.")
            return ""
        }

        val host = resolvePublicHost()
        if (host.isBlank()) {
            plugin.logger.severe(
                "Ресурспак не будет загружаться: не удалось определить адрес сервера. " +
                    "Задайте resource-pack.http-server.host в config.yml (например, ваш IP или домен)."
            )
            return ""
        }

        val port = plugin.config.getInt("resource-pack.http-server.port", 8123)
        // Домен для https должен быть публичным: клиент Minecraft отказывается
        // скачивать пак по https с IP-адреса или самоподписанного сертификата.
        val useHttps = plugin.config.getBoolean("resource-pack.http-server.https", false)
        val ok = httpServer.start(host, port, archive, "$ZIP_NAME.zip", useHttps)
        if (!ok) return ""

        return "${httpServer.baseUrl}/$ZIP_NAME.zip"
    }

    /**
     * Адрес, по которому клиенты скачивают пак.
     *
     * Приоритет: явный `host` из конфига → внешний IP из `server-ip`
     * → авто-определение через системный сетевой интерфейс.
     */
    private fun resolvePublicHost(): String {
        val configured = plugin.config.getString("resource-pack.http-server.host").orEmpty().trim()
        if (configured.isNotEmpty()) return configured

        val serverIp = plugin.server.ip.trim()
        if (serverIp.isNotEmpty() && serverIp != "0.0.0.0" && serverIp != "127.0.0.1") {
            return serverIp
        }

        return detectLocalAddress()
    }

    /**
     * Пытается определить локальный IP машины.
     *
     * Подходит для игры в локальной сети. На хостингах с NAT этого мало —
     * в таком случае нужно вручную задать `http-server.host` (публичный IP или домен).
     */
    private fun detectLocalAddress(): String {
        return try {
            val candidates = java.net.NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { it.isSiteLocalAddress }

            val address = candidates.firstOrNull()?.hostAddress ?: return ""
            plugin.logger.warning(
                "resource-pack.http-server.host не задан — используется локальный адрес $address. " +
                    "Если сервер за NAT, игроки из интернета не скачают пак: укажите публичный IP или домен."
            )
            address
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Не удалось определить адрес для ресурспака", e)
            ""
        }
    }

    /**
     * Асинхронно проверяет, что URL ресурспака реально отдаёт файл.
     *
     * Без этой проверки единственным симптомом ошибки была бы запись в логе
     * клиента — игрок просто не видел бы косметику. Теперь в консоль сервера
     * попадает понятная причина: заглушка в USER/REPO, 404 от GitHub
     * (значит Action ещё не отработал) или недоступный хост.
     */
    private fun validateUrlAsync() {
        val url = activeUrl
        if (url.isEmpty()) {
            plugin.logger.severe(
                "Ресурспак не будет загружаться: URL не задан. " +
                    "Укажите resource-pack.url в config.yml."
            )
            return
        }

        // Незаполненная заглушка — самая частая ошибка после установки.
        if (url.contains("/USER/REPO/") || url.contains("/USERNAME/REPO/")) {
            plugin.logger.severe(
                "resource-pack.url содержит заглушку USER/REPO: '$url'. " +
                    "Замените на свой GitHub-аккаунт и репозиторий — иначе пак не скачается."
            )
            return
        }

        plugin.server.scheduler.runTaskAsynchronously(
            plugin,
            Runnable {
                try {
                    val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "3DCosmetics")

                    val code = connection.responseCode
                    when {
                        code == 200 -> {
                            val length = connection.getHeaderField("Content-Length")
                            plugin.logger.info(
                                "Ресурспак доступен: $url" +
                                    (if (length != null) " ($length байт)" else "")
                            )
                        }

                        code == 404 -> plugin.logger.severe(
                            "Ресурспак не найден (404): $url. " +
                                "Если это GitHub Releases — проверьте, что Action 'Resource Pack' " +
                                "завершился успешно и релиз создан."
                        )

                        else -> plugin.logger.warning(
                            "Ресурспак вернул HTTP $code: $url"
                        )
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    plugin.logger.severe(
                        "Не удалось проверить URL ресурспака: ${e.message}. " +
                            "Проверьте ссылку вручную в браузере: $url"
                    )
                }
            },
        )
    }

    private fun computeSha1(file: File): String {
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FOLDER_NAME = "resourcepack"
        const val ZIP_NAME = "3dcosmetics"
        private const val TEMPLATE_ROOT = "resourcepack"
    }
}
