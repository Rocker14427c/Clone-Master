package com.clonemaster.networking

import android.content.Context
import java.io.File

/**
 * Independent implementation for HTTP proxy list + speed test
 * Public feature reference: App Cloner 3.6.0 mentions "HTTP proxy list" and 3.6.6 mentions speed test for SOCKS/HTTP proxy
 * Equivalent functionality implemented independently
 */
class HttpProxyListManager(private val context: Context) {

    data class ProxyEntry(
        val id: String = java.util.UUID.randomUUID().toString(),
        val host: String,
        val port: Int,
        val type: String = "HTTP", // HTTP, SOCKS5
        val username: String? = null,
        val password: String? = null,
        var latencyMs: Long = -1,
        var isWorking: Boolean = false,
        var lastTested: Long = 0,
        var country: String = "",
        var anonymity: String = ""
    )

    data class ProxyListConfig(
        var proxies: MutableList<ProxyEntry> = mutableListOf(),
        var autoRotate: Boolean = false,
        var rotateIntervalMinutes: Int = 30,
        var testOnAdd: Boolean = true,
        var useBestLatency: Boolean = true
    )

    private val configFile: File by lazy { File(context.filesDir, "proxy_list.json") }

    fun addProxy(entry: ProxyEntry) {
        val config = loadConfig()
        config.proxies.add(entry)
        saveConfig(config)
        if (config.testOnAdd) {
            testProxy(entry) { result ->
                entry.latencyMs = result.latencyMs
                entry.isWorking = result.success
                entry.lastTested = System.currentTimeMillis()
                saveConfig(config)
            }
        }
    }

    fun removeProxy(id: String) {
        val config = loadConfig()
        config.proxies.removeIf { it.id == id }
        saveConfig(config)
    }

    fun testProxy(entry: ProxyEntry, callback: (ProxyManager.ProxyTestResult) -> Unit) {
        val proxyManager = ProxyManager(context)
        val result = proxyManager.testProxy("${entry.host}:${entry.port}")
        callback(result)
    }

    fun testAllProxies(callback: (List<ProxyEntry>) -> Unit) {
        val config = loadConfig()
        var tested = 0
        config.proxies.forEach { proxy ->
            testProxy(proxy) { result ->
                proxy.latencyMs = result.latencyMs
                proxy.isWorking = result.success
                proxy.lastTested = System.currentTimeMillis()
                tested++
                if (tested == config.proxies.size) {
                    saveConfig(config)
                    callback(config.proxies)
                }
            }
        }
    }

    fun getBestProxy(): ProxyEntry? {
        val config = loadConfig()
        return config.proxies.filter { it.isWorking }.minByOrNull { it.latencyMs }
    }

    fun loadConfig(): ProxyListConfig {
        return if (configFile.exists()) {
            try { com.google.gson.Gson().fromJson(configFile.readText(), ProxyListConfig::class.java) } catch (ignored: Exception) { ProxyListConfig() }
        } else ProxyListConfig()
    }

    fun saveConfig(config: ProxyListConfig) {
        configFile.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config))
    }

    fun importFromUrl(url: String, callback: (Int) -> Unit) {
        // Fetch proxy list from URL (e.g., https://api.proxyscrape.com/?request=displayproxies&proxytype=http)
        Thread {
            try {
                val content = java.net.URL(url).readText()
                val lines = content.lines().filter { it.contains(":") }
                var added = 0
                lines.forEach { line ->
                    val parts = line.split(":")
                    if (parts.size >= 2) {
                        val host = parts[0].trim()
                        val port = parts[1].trim().toIntOrNull() ?: return@forEach
                        addProxy(ProxyEntry(host = host, port = port))
                        added++
                    }
                }
                callback(added)
            } catch (e: Exception) {
                callback(0)
            }
        }.start()
    }
}
