package ru.hat1.cosmetics.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * YAML-backed cosmetics storage.
 *
 * All player data lives in `players/<uuid>.yml`. Reads happen synchronously on
 * join (the file is tiny); writes are queued and flushed asynchronously so the
 * main thread never blocks on disk I/O.
 */
class YamlCosmeticsStorage(private val plugin: JavaPlugin) {

    private val cache = ConcurrentHashMap<UUID, PlayerCosmetics>()
    private val dirty = ConcurrentHashMap.newKeySet<UUID>()

    private val playersDir: File
        get() = File(plugin.dataFolder, "players").apply { if (!exists()) mkdirs() }

    /**
     * Loads (or creates) the cosmetics state for [uuid].
     * Safe to call from the main thread only.
     */
    fun load(uuid: UUID): PlayerCosmetics {
        cache[uuid]?.let { return it }

        val file = fileFor(uuid)
        val state = PlayerCosmetics(uuid)

        if (file.exists()) {
            val yaml = YamlConfiguration()
            try {
                yaml.load(file)
                state.owned.addAll(yaml.getStringList("owned").map { it.lowercase() })
                val equippedSection = yaml.getConfigurationSection("equipped")
                if (equippedSection != null) {
                    for (slot in equippedSection.getKeys(false)) {
                        val cosmetic = equippedSection.getString(slot) ?: continue
                        state.equipped[slot.lowercase()] = cosmetic.lowercase()
                    }
                }
                state.trials.addAll(yaml.getStringList("trials").map { it.lowercase() })
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Не удалось прочитать данные $uuid", e)
            }
        }

        cache[uuid] = state
        return state
    }

    /**
     * Returns the cached state without touching the disk, or null when the
     * player is not loaded.
     */
    fun cached(uuid: UUID): PlayerCosmetics? = cache[uuid]

    /** Marks the player's data as needing a write. */
    fun markDirty(uuid: UUID) {
        if (cache.containsKey(uuid)) dirty.add(uuid)
    }

    /** Drops the player from the cache after flushing pending changes. */
    fun unload(uuid: UUID) {
        val state = cache.remove(uuid) ?: return
        dirty.remove(uuid)
        writeSync(state)
    }

    /** Flushes every dirty player asynchronously. */
    fun flush() {
        val snapshot = dirty.toList()
        if (snapshot.isEmpty()) return
        dirty.removeAll(snapshot.toSet())

        val states = snapshot.mapNotNull { cache[it]?.let { state -> state to deepCopy(state) } }
        if (states.isEmpty()) return

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            for ((_, copy) in states) {
                writeSync(copy)
            }
        })
    }

    /** Flushes all dirty players synchronously; used on disable. */
    fun flushBlocking() {
        val snapshot = dirty.toList()
        dirty.removeAll(snapshot.toSet())
        for (uuid in snapshot) {
            cache[uuid]?.let { writeSync(it) }
        }
    }

    /** Persists every cached player immediately. Used on plugin disable. */
    fun saveAllBlocking() {
        for (state in cache.values) {
            writeSync(state)
        }
        dirty.clear()
    }

    private fun writeSync(state: PlayerCosmetics) {
        val yaml = YamlConfiguration()
        yaml.set("owned", state.owned.toList().sorted())
        val equipped = state.equipped.toSortedMap()
        for ((slot, cosmetic) in equipped) {
            yaml.set("equipped.$slot", cosmetic)
        }
        yaml.set("trials", state.trials.toList().sorted())

        val file = fileFor(state.uuid)
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            yaml.save(temp)
            if (file.exists() && !file.delete()) {
                plugin.logger.warning("Не удалось перезаписать ${file.name}")
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Не удалось сохранить ${state.uuid}", e)
        }
    }

    private fun fileFor(uuid: UUID): File = File(playersDir, "$uuid.yml")

    private fun deepCopy(state: PlayerCosmetics): PlayerCosmetics = PlayerCosmetics(
        uuid = state.uuid,
        owned = state.owned.toMutableSet(),
        equipped = state.equipped.toMutableMap(),
        trials = state.trials.toMutableSet(),
    )
}
