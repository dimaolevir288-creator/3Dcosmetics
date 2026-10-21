package ru.hat1.cosmetics.integration

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import net.luckperms.api.node.Node
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.logging.Level

/**
 * Soft integration with LuckPerms.
 *
 * Ownership of a cosmetic is driven purely by permission nodes, so admins can
 * hand out cosmetics with the standard LuckPerms tooling:
 *
 *     /lp user <name> permission set cosmetics.hat.crown
 *
 * When LuckPerms is present the plugin additionally writes the node itself when
 * an admin runs `/cosmetics give`, and checks the node before a purchase or an
 * equip attempt.
 */
class LuckPermsService(private val plugin: JavaPlugin) {

    private var api: LuckPerms? = null

    val available: Boolean get() = api != null

    fun setup(): Boolean {
        api = null

        if (plugin.server.pluginManager.getPlugin("LuckPerms") == null) {
            plugin.logger.warning(
                "LuckPerms не найден — права будут проверяться через обычные permission-ноды."
            )
            return false
        }

        api = runCatching { LuckPermsProvider.get() }.getOrElse {
            plugin.logger.warning("Не удалось получить LuckPerms API: ${it.message}")
            null
        }

        if (api != null) {
            plugin.logger.info("LuckPerms подключён.")
        }
        return api != null
    }

    /**
     * Checks whether [player] has [node].
     *
     * Falls back to Bukkit's permission check (which itself is backed by
     * LuckPerms on a LuckPerms server) so behaviour stays correct when the API
     * is unavailable.
     */
    fun hasPermission(player: Player, node: String): Boolean {
        if (node.isBlank()) return true
        return player.hasPermission(node)
    }

    /**
     * Checks a node for a player who may be offline.
     * Returns false when nothing can be resolved.
     */
    fun hasPermission(uuid: UUID, node: String): Boolean {
        if (node.isBlank()) return true

        Bukkit.getPlayer(uuid)?.let { return it.hasPermission(node) }

        val api = api ?: return false
        val user: User = api.userManager.getUser(uuid) ?: return false

        return user.cachedData.getPermissionData().checkPermission(node).asBoolean()
    }

    /**
     * Grants [node] to [uuid] via LuckPerms.
     *
     * The write is dispatched asynchronously and the returned future is
     * completed on LuckPerms' own thread pool, so callers on the main thread
     * never block. Returns false only when LuckPerms is unavailable.
     */
    fun grant(uuid: UUID, node: String): Boolean = mutate(uuid, node, add = true)

    /**
     * Removes [node] from [uuid] via LuckPerms.
     *
     * Behaves like [grant]: the write happens off-thread.
     */
    fun revoke(uuid: UUID, node: String): Boolean = mutate(uuid, node, add = false)

    private fun mutate(uuid: UUID, node: String, add: Boolean): Boolean {
        if (node.isBlank()) return true
        val api = api ?: return false

        runCatching {
            api.userManager.modifyUser(uuid) { user ->
                val built = Node.builder(node).build()
                if (add) {
                    user.data().add(built)
                } else {
                    user.data().remove(built)
                }
            }
        }.onFailure {
            plugin.logger.log(Level.WARNING, "Не удалось изменить ноду $node у $uuid", it)
        }

        // LuckPerms futures complete asynchronously; success is reported through
        // onFailure above, so report acceptance here.
        return true
    }

    /** Resolves the loaded LP user, or null when unavailable. */
    fun user(uuid: UUID): User? {
        val api = api ?: return null
        return runCatching { api.userManager.getUser(uuid) }.getOrNull()
    }
}
