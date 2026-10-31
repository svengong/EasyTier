package com.easytier.jni

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * [PATCH] 从 protobuf JSON 的 virtual_ipv4 对象解析出 "ip/prefix"。
 *
 * 原始实现依赖 Square Wire 生成的 Kotlin 数据类（可属性访问）。
 * 这里改为直接读 Map，不依赖代码生成产物，便于在 Tauri 工程里编译。
 */
fun parseIpv4InetToString(inet: Map<String, Any?>?): String? {
    val addrObj = inet?.get("address") as? Map<*, *> ?: return null
    val addr = (addrObj["addr"] as? Number)?.toInt() ?: return null
    val networkLength = (inet["network_length"] as? Number)?.toInt() ?: 24

    // 将 int32 转换为 IPv4 字符串
    val ip =
            String.format(
                    "%d.%d.%d.%d",
                    (addr shr 24) and 0xFF,
                    (addr shr 16) and 0xFF,
                    (addr shr 8) and 0xFF,
                    addr and 0xFF
            )

    return "$ip/$networkLength"
}

/** EasyTier 管理类 负责管理 EasyTier 实例的生命周期、监控网络状态变化、控制 VpnService */
class EasyTierManager(
        private val activity: Activity,
        private val instanceName: String,
        private val networkConfig: String
) {
    companion object {
        private const val TAG = "EasyTierManager"
        private const val MONITOR_INTERVAL = 3000L // 3秒监控间隔
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var currentIpv4: String? = null
    private var currentProxyCidrs: List<String> = emptyList()
    private var vpnServiceIntent: Intent? = null

    // JSON 解析器
    private val moshi = Moshi.Builder().build()
    private val adapter =
            moshi.adapter<Map<String, Any?>>(
                    Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            )

    // 监控任务
    private val monitorRunnable =
            object : Runnable {
                override fun run() {
                    if (isRunning) {
                        monitorNetworkStatus()
                        handler.postDelayed(this, MONITOR_INTERVAL)
                    }
                }
            }

    /** 启动 EasyTier 实例和监控 */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "EasyTier 实例已经在运行中")
            return
        }

        try {
            // 启动 EasyTier 实例
            val result = EasyTierJNI.runNetworkInstance(networkConfig)
            if (result == 0) {
                isRunning = true
                Log.i(TAG, "EasyTier 实例启动成功: $instanceName")

                // 开始监控网络状态
                handler.post(monitorRunnable)
            } else {
                Log.e(TAG, "EasyTier 实例启动失败: $result")
                val error = EasyTierJNI.getLastError()
                Log.e(TAG, "错误信息: $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动 EasyTier 实例时发生异常", e)
        }
    }

    /** 停止 EasyTier 实例和监控 */
    fun stop() {
        if (!isRunning) {
            Log.w(TAG, "EasyTier 实例未在运行")
            return
        }

        isRunning = false

        // 停止监控任务
        handler.removeCallbacks(monitorRunnable)

        try {
            // 停止 VpnService
            stopVpnService()

            // 停止 EasyTier 实例
            EasyTierJNI.stopAllInstances()
            Log.i(TAG, "EasyTier 实例已停止: $instanceName")

            // 重置状态
            currentIpv4 = null
            currentProxyCidrs = emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "停止 EasyTier 实例时发生异常", e)
        }
    }

    /** 监控网络状态 */
    private fun monitorNetworkStatus() {
        try {
            val infosJson = EasyTierJNI.collectNetworkInfos(10)
            if (infosJson.isNullOrEmpty()) {
                Log.d(TAG, "未获取到网络信息")
                return
            }

            val networkInfoMap = parseNetworkInfo(infosJson)
            @Suppress("UNCHECKED_CAST")
            val instanceMap =
                    (networkInfoMap?.get("map") as? Map<String, Any?>) ?: emptyMap<String, Any?>()
            @Suppress("UNCHECKED_CAST")
            val networkInfo = instanceMap[instanceName] as? Map<String, Any?>

            if (networkInfo == null) {
                Log.d(TAG, "未找到实例 $instanceName 的网络信息")
                return
            }

            Log.d(TAG, "网络信息: $networkInfo")

            // 检查实例是否正在运行
            if (networkInfo["running"] != true) {
                Log.w(TAG, "EasyTier 实例未运行: ${networkInfo["error_msg"]}")
                return
            }

            @Suppress("UNCHECKED_CAST")
            val myNodeInfo = networkInfo["my_node_info"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val newIpv4Inet = myNodeInfo?.get("virtual_ipv4") as? Map<String, Any?>

            if (newIpv4Inet == null) {
                Log.w(TAG, "EasyTier No Ipv4: $networkInfo")
                return
            }

            // 获取当前节点的 IPv4 地址
            val newIpv4 = parseIpv4InetToString(newIpv4Inet)

            // 获取所有节点的 proxy_cidrs
            val newProxyCidrs = mutableListOf<String>()
            @Suppress("UNCHECKED_CAST")
            (networkInfo["routes"] as? List<Map<String, Any?>>)?.forEach { route ->
                @Suppress("UNCHECKED_CAST")
                (route["proxy_cidrs"] as? List<String>)?.let { cidrs -> newProxyCidrs.addAll(cidrs) }
            }

            // [PATCH] 全流量接管：若本实例配置了 exit_nodes，则把默认路由也声明给 VPN。
            //
            // 原实现只读 proxy_cidrs，导致 exit_nodes 配置形同虚设（issue #1701）。
            // 只有让 VpnService 调用 addRoute("0.0.0.0", 0)，系统才会把全部流量
            // 交给 tun；在此之前，exit_nodes 仅影响 EasyTier 内部路由决策。
            if (exitNodesConfigured() && !newProxyCidrs.contains("0.0.0.0/0")) {
                newProxyCidrs.add("0.0.0.0/0")
                Log.i(TAG, "检测到 exit_nodes，启用全流量接管")
            }

            // 检查是否有变化
            val ipv4Changed = newIpv4 != currentIpv4
            val proxyCidrsChanged = newProxyCidrs != currentProxyCidrs

            if (ipv4Changed || proxyCidrsChanged) {
                Log.i(TAG, "网络状态发生变化:")
                Log.i(TAG, "  IPv4: $currentIpv4 -> $newIpv4")
                Log.i(TAG, "  Proxy CIDRs: $currentProxyCidrs -> $newProxyCidrs")

                // 更新状态
                currentIpv4 = newIpv4
                currentProxyCidrs = newProxyCidrs.toList()

                // 重启 VpnService
                if (newIpv4 != null) {
                    restartVpnService(newIpv4, newProxyCidrs)
                }
            } else {
                Log.d(TAG, "网络状态无变化 - IPv4: $currentIpv4, Proxy CIDRs: ${currentProxyCidrs.size} 个")
            }
        } catch (e: Exception) {
            Log.e(TAG, "监控网络状态时发生异常", e)
        }
    }

    /** 解析网络信息 JSON */
    private fun parseNetworkInfo(jsonString: String): Map<String, Any?>? {
        return try {
            adapter.fromJson(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "解析网络信息失败", e)
            null
        }
    }

    /**
     * [PATCH] 判断当前实例配置里是否声明了 exit_nodes。
     *
     * 配置形如：
     * ```toml
     * exit_nodes = ["10.144.144.1"]
     * ```
     *
     * 这里只做轻量字符串匹配，不引入 TOML 解析依赖。匹配规则：
     * 存在顶层 `exit_nodes` 且等号右侧的数组非空。
     */
    private fun exitNodesConfigured(): Boolean {
        return try {
            val line = networkConfig
                .lineSequence()
                .firstOrNull { it.trimStart().startsWith("exit_nodes") }
                ?: return false
            // 取等号右侧，判断是否有引号包裹的非空元素
            val rhs = line.substringAfter("=", "")
            Regex("\"([^\"]+)\"").findAll(rhs).any { it.groupValues[1].isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "解析 exit_nodes 失败", e)
            false
        }
    }

    /** 重启 VpnService */
    private fun restartVpnService(ipv4: String, proxyCidrs: List<String>) {
        try {
            // 先停止现有的 VpnService
            stopVpnService()

            // 启动新的 VpnService
            startVpnService(ipv4, proxyCidrs)
        } catch (e: Exception) {
            Log.e(TAG, "重启 VpnService 时发生异常", e)
        }
    }

    /** 启动 VpnService */
    private fun startVpnService(ipv4: String, proxyCidrs: List<String>) {
        try {
            val intent = Intent(activity, EasyTierVpnService::class.java)
            intent.putExtra("ipv4_address", ipv4)
            intent.putStringArrayListExtra("proxy_cidrs", ArrayList(proxyCidrs))
            intent.putExtra("instance_name", instanceName)

            activity.startService(intent)
            vpnServiceIntent = intent

            Log.i(TAG, "VpnService 已启动 - IPv4: $ipv4, Proxy CIDRs: $proxyCidrs")
        } catch (e: Exception) {
            Log.e(TAG, "启动 VpnService 时发生异常", e)
        }
    }

    /** 停止 VpnService */
    private fun stopVpnService() {
        try {
            vpnServiceIntent?.let { intent ->
                activity.stopService(intent)
                Log.i(TAG, "VpnService 已停止")
            }
            vpnServiceIntent = null
        } catch (e: Exception) {
            Log.e(TAG, "停止 VpnService 时发生异常", e)
        }
    }

    /** 获取当前状态信息 */
    fun getStatus(): EasyTierStatus {
        return EasyTierStatus(
                isRunning = isRunning,
                instanceName = instanceName,
                currentIpv4 = currentIpv4,
                currentProxyCidrs = currentProxyCidrs.toList()
        )
    }

    /** 状态数据类 */
    data class EasyTierStatus(
            val isRunning: Boolean,
            val instanceName: String,
            val currentIpv4: String?,
            val currentProxyCidrs: List<String>
    )
}
