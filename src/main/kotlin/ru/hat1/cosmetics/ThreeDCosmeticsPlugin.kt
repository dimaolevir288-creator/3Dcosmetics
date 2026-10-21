package ru.hat1.cosmetics

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.hat1.cosmetics.command.CosmeticsCommand
import ru.hat1.cosmetics.config.CosmeticsConfig
import ru.hat1.cosmetics.config.InfoFileWriter
import ru.hat1.cosmetics.config.MessageService
import ru.hat1.cosmetics.gui.MenuFactory
import ru.hat1.cosmetics.gui.MenuListener
import ru.hat1.cosmetics.integration.EconomyService
import ru.hat1.cosmetics.integration.LuckPermsService
import ru.hat1.cosmetics.listener.PlayerListener
import ru.hat1.cosmetics.render.Attachments
import ru.hat1.cosmetics.render.CosmeticRenderer
import ru.hat1.cosmetics.resourcepack.ResourcePackService
import ru.hat1.cosmetics.service.CosmeticsService
import ru.hat1.cosmetics.storage.YamlCosmeticsStorage

/**
 * Entry point of 3DCosmetics.
 *
 * Wiring order matters:
 *  1. configs, 2. integrations, 3. storage, 4. rendering, 5. GUI, 6. commands.
 */
class ThreeDCosmeticsPlugin : JavaPlugin() {

    lateinit var messages: MessageService

    lateinit var cosmeticsConfig: CosmeticsConfig

    lateinit var storage: YamlCosmeticsStorage

    lateinit var economy: EconomyService

    lateinit var luckPerms: LuckPermsService

    lateinit var renderer: CosmeticRenderer

    lateinit var cosmeticsService: CosmeticsService

    lateinit var menuFactory: MenuFactory

    lateinit var resourcePacks: ResourcePackService

    private var tickTaskId: Int = -1
    private var autosaveTaskId: Int = -1

    override fun onEnable() {
        saveDefaultConfig()

        messages = MessageService(this).also { it.load() }
        cosmeticsConfig = CosmeticsConfig(this).also { it.load() }

        // Ресурспак распаковывается в папку плагина, туда же импортируются
        // модели из blockbench/, после чего всё собирается в архив.
        resourcePacks = ResourcePackService(this).also { it.prepare() }

        // info.txt создаётся рядом с config.yml при каждом включении.
        val infoFile = InfoFileWriter(this).write()
        logger.info("Информация о плагине записана: ${infoFile.name}")

        economy = EconomyService(this).also { it.setup() }
        luckPerms = LuckPermsService(this).also { it.setup() }

        storage = YamlCosmeticsStorage(this)

        val attachments = Attachments(cosmeticsConfig, storage, luckPerms)
        renderer = CosmeticRenderer(this, attachments)

        cosmeticsService = CosmeticsService(
            plugin = this,
            config = cosmeticsConfig,
            storage = storage,
            economy = economy,
            luckPerms = luckPerms,
            renderer = renderer,
            messages = messages,
        )

        menuFactory = MenuFactory(this, cosmeticsConfig, cosmeticsService, messages)

        server.pluginManager.registerEvents(MenuListener(this, menuFactory, cosmeticsService, messages), this)
        server.pluginManager.registerEvents(PlayerListener(this), this)

        val command = getCommand("cosmetics")
        if (command != null) {
            val executor = CosmeticsCommand(
                plugin = this,
                factory = menuFactory,
                service = cosmeticsService,
                config = cosmeticsConfig,
                messages = messages,
                reloadAction = ::reloadAll,
                resourcePacks = resourcePacks,
            )
            command.setExecutor(executor)
            command.tabCompleter = executor
        }

        startTasks()

        // Restore cosmetics for players who were already online (plugin reload).
        for (player in server.onlinePlayers) {
            storage.load(player.uniqueId)
            renderer.refresh(player)
        }

        logger.info("3DCosmetics включён.")
    }

    override fun onDisable() {
        stopTasks()

        // Встроенный HTTP-сервер держит собственный поток — его нужно
        // остановить, иначе он переживёт выгрузку плагина.
        if (::resourcePacks.isInitialized) {
            resourcePacks.shutdown()
        }

        if (::renderer.isInitialized) {
            renderer.clearAll()
        }
        if (::storage.isInitialized) {
            storage.saveAllBlocking()
        }

        logger.info("3DCosmetics выключен.")
    }

    /**
     * Reloads every configuration file and re-renders all online players.
     */
    fun reloadAll() {
        reloadConfig()
        messages.load()
        cosmeticsConfig.load()
        resourcePacks.prepare()
        InfoFileWriter(this).write()

        for (player in server.onlinePlayers) {
            renderer.clear(player)
            renderer.refresh(player)
        }
    }

    private fun startTasks() {
        stopTasks()

        val interval = config.getInt("rendering.refresh-interval-ticks", 2).coerceAtLeast(1)

        tickTaskId = server.scheduler.runTaskTimer(this, Runnable {
            for (player in server.onlinePlayers) {
                renderer.tick(player)
            }
        }, interval.toLong(), interval.toLong()).taskId

        // Periodic safety refresh: catches permission changes made via LuckPerms
        // without us noticing (e.g. an admin editing nodes by hand).
        val syncTicks = SYNC_INTERVAL_TICKS
        server.scheduler.runTaskTimer(this, Runnable {
            for (player in server.onlinePlayers) {
                renderer.refresh(player)
            }
        }, syncTicks, syncTicks)

        // Autosave dirty player data every 5 minutes.
        autosaveTaskId = server.scheduler.runTaskTimer(this, Runnable {
            storage.flush()
        }, AUTOSAVE_TICKS, AUTOSAVE_TICKS).taskId
    }

    private fun stopTasks() {
        if (tickTaskId != -1) {
            server.scheduler.cancelTask(tickTaskId)
            tickTaskId = -1
        }
        if (autosaveTaskId != -1) {
            server.scheduler.cancelTask(autosaveTaskId)
            autosaveTaskId = -1
        }
    }

    /** Convenience accessor used by listeners. */
    fun loadPlayer(player: Player) {
        storage.load(player.uniqueId)
        renderer.refresh(player)
    }

    companion object {
        /** How often the permission-aware refresh runs (in ticks). */
        private const val SYNC_INTERVAL_TICKS = 60L

        /** Autosave period (5 minutes). */
        private const val AUTOSAVE_TICKS = 6000L
    }
}
