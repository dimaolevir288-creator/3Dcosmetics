package ru.hat1.cosmetics.resourcepack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.logging.Level

/**
 * Встроенный HTTP-сервер для раздачи файла ресурспака.
 *
 * Клиент Minecraft не умеет скачивать пак по `file://` и не имеет доступа к
 * файлам на диске сервера — ему нужен настоящий HTTP URL. Своего веб-сервера
 * у Bukkit/Paper нет, поэтому плагин поднимает минимальный сервер на JDK
 * (`com.sun.net.httpserver`, входит в стандартную библиотеку).
 *
 * Сервер отдаёт **только** файл текущего ресурспака — никакого доступа к
 * остальным файлам сервера нет. Путь фиксированный (`/<zipName>.zip`),
 * поэтому подобрать чужие файлы через URL невозможно.
 *
 * Если администратор уже указал свой `resource-pack.url`, сервер не нужен и
 * не запускается ([start] вернёт `false`).
 */
class ResourcePackHttpServer(private val plugin: JavaPlugin) {

    private var server: HttpServer? = null

    /** Порт, на котором реально слушает сервер; -1, если не запущен. */
    var port: Int = -1
        private set

    /** Базовый URL без имени файла, например `http://1.2.3.4:8123`. */
    var baseUrl: String = ""
        private set

    val isRunning: Boolean get() = server != null

    /**
     * Запускает сервер на [portNumber] (`0` — свободный порт на выбор ОС).
     *
     * @param host адрес, к которому будут обращаться клиенты. Попадает в URL
     *             внутри `setResourcePack`, поэтому должен быть доступен
     *             снаружи (публичный IP или домен).
     * @param portNumber порт; `0` — любой свободный.
     * @param archive файл архива, который отдаётся клиентам.
     * @param route путь запроса без ведущего слэша, например `3dcosmetics.zip`.
     * @param https ставить ли в URL схему https (нужен внешний reverse-proxy).
     * @return true, если сервер поднялся.
     */
    fun start(host: String, portNumber: Int, archive: File, route: String, https: Boolean = false): Boolean {
        stop()

        return try {
            val socket = InetSocketAddress(portNumber)
            val created = HttpServer.create(socket, 0)
            created.executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "3DCosmetics-ResourcePack").also { it.isDaemon = true }
            }

            created.createContext("/$route") { exchange -> serveArchive(exchange, archive) }
            // Любой другой путь получает 404, чтобы сервер нельзя было
            // использовать как прокси к файловой системе.
            created.createContext("/") { exchange -> respond(exchange, 404, "Not found") }

            created.start()
            server = created
            this.port = created.address.port
            // Плагин сам слушает plain HTTP; https возможен только через
            // reverse-proxy (nginx/Cloudflare), поэтому схема влияет лишь на URL.
            val scheme = if (https) "https" else "http"
            this.baseUrl = "$scheme://$host:${created.address.port}"

            plugin.logger.info(
                "Ресурспак раздаётся по адресу $baseUrl/$route " +
                    "(${archive.length()} байт)"
            )
            true
        } catch (e: Exception) {
            server = null
            this.port = -1
            baseUrl = ""
            plugin.logger.log(
                Level.SEVERE,
                "Не удалось запустить встроенный HTTP-сервер ресурспака. " +
                    "Проверьте resource-pack.http-server в config.yml или укажите свой url.",
                e,
            )
            false
        }
    }

    /** Отдаёт архив клиенту. При ошибке чтения отвечает 500. */
    private fun serveArchive(exchange: HttpExchange, archive: File) {
        try {
            if (!archive.exists() || archive.length() == 0L) {
                respond(exchange, 404, "Resource pack is not ready")
                return
            }

            val bytes = archive.readBytes()
            exchange.responseHeaders.add("Content-Type", "application/zip")
            exchange.responseHeaders.add("Content-Length", bytes.size.toString())
            // Кеш: клиент сверяет SHA-1 сам, поэтому запрещаем кеширование
            // устаревшей версии после пересборки пака.
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Ошибка при отдаче ресурспака", e)
            runCatching { respond(exchange, 500, "Internal error") }
        } finally {
            exchange.close()
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Останавливает сервер. Безопасно вызывать несколько раз. */
    fun stop() {
        val current = server ?: return
        try {
            current.stop(0)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Ошибка при остановке HTTP-сервера", e)
        } finally {
            server = null
            port = -1
            baseUrl = ""
        }
    }
}
