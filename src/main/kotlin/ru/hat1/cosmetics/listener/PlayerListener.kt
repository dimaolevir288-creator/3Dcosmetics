package ru.hat1.cosmetics.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.hat1.cosmetics.ThreeDCosmeticsPlugin

/**
 * Keeps cosmetics in sync with the player lifecycle.
 */
class PlayerListener(private val plugin: ThreeDCosmeticsPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player

        plugin.loadPlayer(player)

        // Ресурспак отправляем на следующем тике: клиент к этому моменту уже
        // завершил handshake и готов принять пакет.
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) plugin.resourcePacks.send(player)
        }, RESOURCE_PACK_DELAY_TICKS)

        // The client may not be fully ready on the join tick; re-render shortly
        // after so displays are visible immediately.
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) plugin.renderer.refresh(player)
        }, JOINS_DELAY_TICKS)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        // Примерка не должна пережить выход: снимаем задачу и откатываем слот.
        plugin.cosmeticsService.cancelTrial(player, restore = true, silent = true)
        plugin.cosmeticsService.forgetPlayer(player.uniqueId)
        plugin.renderer.clear(player)
        plugin.storage.unload(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        // Vehicles (including display passengers) are unreliable across world
        // changes, so rebuild them on the next tick.
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            plugin.renderer.clear(player)
            plugin.renderer.refresh(player)
        })
    }

    companion object {

        /** Задержка перед отправкой ресурспака (1 секунда). */
        private const val RESOURCE_PACK_DELAY_TICKS = 20L

        /** Задержка повторного рендера после входа. */
        private const val JOINS_DELAY_TICKS = 10L
    }
}
