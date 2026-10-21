package ru.hat1.cosmetics.integration

import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin

/**
 * Vault economy bridge.
 *
 * Vault's API artifact is not published to Maven Central and is unavailable on
 * some build machines, so the bridge talks to Vault through reflection. That
 * keeps the plugin compilable with a plain Spigot API dependency while still
 * working with every Vault-compatible economy plugin at runtime.
 *
 * Reflection is confined to this class: the rest of the plugin only sees the
 * strongly typed methods below.
 */
class EconomyService(private val plugin: JavaPlugin) {

    private var provider: Any? = null
    private var withdraw: java.lang.reflect.Method? = null
    private var deposit: java.lang.reflect.Method? = null
    private var balance: java.lang.reflect.Method? = null
    private var has: java.lang.reflect.Method? = null
    private var format: java.lang.reflect.Method? = null
    private var isEnabled: java.lang.reflect.Method? = null
    private var name: java.lang.reflect.Method? = null

    private var providerName: String = ""

    /** True when a usable Vault economy provider is present. */
    val available: Boolean
        get() {
            val provider = provider ?: return false
            val enabled = isEnabled ?: return false
            return runCatching { enabled.invoke(provider) as? Boolean ?: false }.getOrDefault(false)
        }

    /**
     * Attempts to hook into Vault. Returns true on success.
     * Must be called after all plugins have enabled.
     */
    fun setup(): Boolean {
        reset()

        if (plugin.server.pluginManager.getPlugin("Vault") == null) {
            plugin.logger.warning("Vault не найден — покупки косметики отключены.")
            return false
        }

        val economyClass = runCatching {
            Class.forName("net.milkbowl.vault.economy.Economy")
        }.getOrElse {
            plugin.logger.warning("Класс Vault Economy недоступен: ${it.message}")
            return false
        }

        val registration = plugin.server.servicesManager.getRegistration(economyClass)
        val instance = registration?.provider ?: run {
            plugin.logger.warning("Vault найден, но провайдер экономики не зарегистрирован.")
            return false
        }

        provider = instance

        withdraw = method(economyClass, "withdrawPlayer", OfflinePlayer::class.java, Double::class.javaPrimitiveType)
        deposit = method(economyClass, "depositPlayer", OfflinePlayer::class.java, Double::class.javaPrimitiveType)
        balance = method(economyClass, "getBalance", OfflinePlayer::class.java)
        has = method(economyClass, "has", OfflinePlayer::class.java, Double::class.javaPrimitiveType)
        format = method(economyClass, "format", Double::class.javaPrimitiveType)
        isEnabled = method(economyClass, "isEnabled")
        name = method(economyClass, "getName")

        providerName = runCatching { name?.invoke(instance) as? String ?: "unknown" }
            .getOrDefault("unknown")

        if (!available) {
            plugin.logger.warning("Провайдер экономики Vault отключён.")
            return false
        }

        plugin.logger.info("Экономика Vault подключена: $providerName")
        return true
    }

    fun balance(player: OfflinePlayer): Double {
        val method = balance ?: return 0.0
        val provider = provider ?: return 0.0
        return runCatching { method.invoke(provider, player) as? Double ?: 0.0 }.getOrDefault(0.0)
    }

    fun has(player: OfflinePlayer, amount: Double): Boolean {
        val method = has ?: return false
        val provider = provider ?: return false
        return runCatching { method.invoke(provider, player, amount) as? Boolean ?: false }
            .getOrDefault(false)
    }

    /**
     * Withdraws [amount] from [player].
     * Returns true only when the transaction actually succeeded.
     */
    fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        val method = withdraw ?: return false
        val provider = provider ?: return false
        if (amount <= 0.0) return true

        val response = runCatching { method.invoke(provider, player, amount) }.getOrNull()
            ?: return false

        return isSuccess(response, "списание", amount)
    }

    /** Deposits [amount] back (used for refunds). */
    fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        val method = deposit ?: return false
        val provider = provider ?: return false
        if (amount <= 0.0) return true

        val response = runCatching { method.invoke(provider, player, amount) }.getOrNull()
            ?: return false

        return isSuccess(response, "зачисление", amount)
    }

    /** Formats [amount] using the economy provider, falling back to a plain string. */
    fun format(amount: Double, fallbackSymbol: String): String {
        val method = format ?: return fallbackSymbol + "%.2f".format(amount)
        val provider = provider ?: return fallbackSymbol + "%.2f".format(amount)
        return runCatching { method.invoke(provider, amount) as? String }.getOrNull()
            ?: (fallbackSymbol + "%.2f".format(amount))
    }

    /**
     * Reads the boolean result of a Vault `EconomyResponse`.
     * Vault reports success through `transactionSuccess()`; the raw balance is
     * used as a secondary signal when the method is missing.
     */
    private fun isSuccess(response: Any, action: String, amount: Double): Boolean {
        val success = runCatching {
            response.javaClass.getMethod("transactionSuccess").invoke(response) as? Boolean
        }.getOrNull()

        if (success == null) {
            plugin.logger.warning("Неизвестный ответ Vault при $action $amount")
            return false
        }

        if (!success) {
            val error = runCatching {
                response.javaClass.getField("errorMessage").get(response) as? String
            }.getOrNull()
            plugin.logger.warning("Vault отказал в $action $amount: $error")
        }
        return success
    }

    private fun method(owner: Class<*>, name: String, vararg params: Class<*>?): java.lang.reflect.Method? =
        runCatching { owner.getMethod(name, *params) }.getOrNull()

    private fun reset() {
        provider = null
        withdraw = null
        deposit = null
        balance = null
        has = null
        format = null
        isEnabled = null
        name = null
        providerName = ""
    }
}
