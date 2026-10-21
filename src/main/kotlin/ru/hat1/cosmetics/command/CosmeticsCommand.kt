package ru.hat1.cosmetics.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.hat1.cosmetics.config.CosmeticsConfig
import ru.hat1.cosmetics.config.MessageService
import ru.hat1.cosmetics.gui.MenuFactory
import ru.hat1.cosmetics.resourcepack.ResourcePackService
import ru.hat1.cosmetics.service.CosmeticsService

/**
 * `/cosmetics` command tree.
 *
 *   /cosmetics                       — open the menu
 *   /cosmetics give <player> <id>    — grant a cosmetic (admin)
 *   /cosmetics take <player> <id>    — revoke a cosmetic (admin)
 *   /cosmetics trial <id>            — try a cosmetic on for free
 *   /cosmetics reload                — reload configs and resource pack (admin)
 *   /cosmetics import                — import .bbmodel files from blockbench/ (admin)
 */
class CosmeticsCommand(
    private val plugin: JavaPlugin,
    private val factory: MenuFactory,
    private val service: CosmeticsService,
    private val config: CosmeticsConfig,
    private val messages: MessageService,
    private val reloadAction: () -> Unit,
    private val resourcePacks: ResourcePackService,
) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.isEmpty()) {
            val player = sender as? Player ?: run {
                messages.send(sender, "no-permission")
                return true
            }
            if (!player.hasPermission("cosmetics.use")) {
                messages.send(player, "no-permission")
                return true
            }
            factory.openRoot(player)
            return true
        }

        when (args[0].lowercase()) {
            "give" -> return handleAdminTransfer(sender, args, grant = true)
            "take", "revoke" -> return handleAdminTransfer(sender, args, grant = false)
            "reload" -> return handleReload(sender)
            "import" -> return handleImport(sender, args)
            "trial", "preview", "try" -> return handleTrial(sender, args)
            "off", "clear" -> return handleUnequipAll(sender)
            else -> {
                messages.send(sender, "usage-give")
                return true
            }
        }
    }

    private fun handleAdminTransfer(
        sender: CommandSender,
        args: Array<out String>,
        grant: Boolean,
    ): Boolean {
        if (!sender.hasPermission("cosmetics.admin")) {
            messages.send(sender, "no-permission")
            return true
        }

        if (args.size < 3) {
            messages.send(sender, if (grant) "usage-give" else "usage-take")
            return true
        }

        val targetName = args[1]
        val cosmeticId = args[2]

        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            messages.send(sender, "player-not-found", mapOf("player" to targetName))
            return true
        }

        if (config.cosmetic(cosmeticId) == null) {
            messages.send(sender, "unknown-cosmetic", mapOf("id" to cosmeticId))
            return true
        }

        val result = if (grant) {
            service.grant(target, cosmeticId)
        } else {
            service.revoke(target, cosmeticId)
        }

        // Сообщаем игроку отдельным текстом, чтобы он знал об изменении.
        if (result.success) {
            val targetKey = if (grant) "granted-target" else "revoked-target"
            target.sendMessage(messages.prefixed(targetKey, result.placeholders))
        }
        messages.send(sender, result.messageKey, result.placeholders)
        return true
    }

    private fun handleReload(sender: CommandSender): Boolean {
        if (!sender.hasPermission("cosmetics.admin")) {
            messages.send(sender, "no-permission")
            return true
        }
        reloadAction()
        messages.send(sender, "reloaded")
        return true
    }

    /** `/cosmetics trial <id>` — бесплатная примерка на несколько секунд. */
    private fun handleTrial(sender: CommandSender, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            messages.send(sender, "no-permission")
            return true
        }

        if (!player.hasPermission("cosmetics.trial")) {
            messages.send(player, "no-permission")
            return true
        }

        if (args.size < 2) {
            messages.send(player, "usage-trial")
            return true
        }

        val result = service.startTrial(player, args[1])
        player.sendMessage(messages.prefixed(result.messageKey, result.placeholders))
        return true
    }

    /**
     * Импорт моделей Blockbench из папки `blockbench/` и пересборка пакета.
     *
     * Аргумент `force` (или `-f`, `all`) пересобирает всё, включая уже
     * импортированные модели.
     */
    private fun handleImport(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("cosmetics.admin")) {
            messages.send(sender, "no-permission")
            return true
        }

        val force = args.any { it.equals("force", true) || it == "-f" || it.equals("all", true) }

        // Импорт читает и пишет файлы — выносим с главного потока.
        val folderPath = resourcePacks.blockbenchFolder.absolutePath
        messages.send(sender, "import-started", mapOf("folder" to folderPath))

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val summary = try {
                resourcePacks.importBlockbenchModels(force)
            } catch (e: Exception) {
                plugin.logger.log(java.util.logging.Level.WARNING, "Blockbench: импорт не удался", e)
                null
            }

            // Сборку архива и отправку пакета делаем на главном потоке.
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (summary == null) {
                    sender.sendMessage(messages.prefixed("import-failed", emptyMap()))
                    return@Runnable
                }

                val summaryText = summary.imported.joinToString("\n") { model ->
                    val warnings = if (model.warnings.isEmpty()) ""
                    else "\n  &8• " + model.warnings.joinToString("\n  &8• ")
                    "&a✔ &f${model.sourceFile} &7→ &f${model.modelKey} &8(${model.cubeCount} кубов)$warnings"
                }
                val failedText = summary.failed.joinToString("\n") { (file, reason) ->
                    "&c✖ &f$file &7— &c$reason"
                }

                if (summary.total == 0) {
                    messages.send(
                        sender,
                        "import-empty",
                        mapOf("folder" to folderPath),
                    )
                } else {
                    messages.send(
                        sender,
                        "import-done",
                        mapOf(
                            "imported" to summary.imported.size.toString(),
                            "failed" to summary.failed.size.toString(),
                            "details" to listOf(summaryText, failedText)
                                .filter { it.isNotBlank() }
                                .joinToString("\n"),
                        ),
                    )
                }

                if (summary.imported.isNotEmpty()) {
                    resourcePacks.rebuild()
                    resendPackToAll()
                }
            })
        })

        return true
    }

    private fun resendPackToAll() {
        for (player in Bukkit.getOnlinePlayers()) {
            resourcePacks.send(player)
        }
    }

    private fun handleUnequipAll(sender: CommandSender): Boolean {
        val player = sender as? Player ?: run {
            messages.send(sender, "no-permission")
            return true
        }
        val removed = service.unequipAll(player)
        if (removed == 0) {
            messages.send(player, "nothing-equipped")
        } else {
            messages.send(player, "all-unequipped", mapOf("count" to removed.toString()))
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (!sender.hasPermission("cosmetics.admin")) return emptyList()

        return when (args.size) {
            1 -> listOf("give", "take", "reload", "import", "trial", "off")
                .filter { it.startsWith(args[0].lowercase()) }

            2 -> {
                val sub = args[0].lowercase()
                when (sub) {
                    "give", "take", "revoke" -> Bukkit.getOnlinePlayers().map { it.name }
                        .filter { it.lowercase().startsWith(args[1].lowercase()) }

                    "import" -> listOf("force").filter { it.startsWith(args[1].lowercase()) }

                    "trial", "preview", "try" -> config.all().map { it.id }
                        .filter { it.startsWith(args[1].lowercase()) }

                    else -> emptyList()
                }
            }

            3 -> {
                val sub = args[0].lowercase()
                if (sub == "give" || sub == "take" || sub == "revoke") {
                    config.all().map { it.id }
                        .filter { it.startsWith(args[2].lowercase()) }
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }
}
