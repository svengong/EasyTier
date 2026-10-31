package com.easytier.jni

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlin.concurrent.thread

class EasyTierVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var instanceName: String? = null

    companion object {
        private const val TAG = "EasyTierVpnService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 获取传入的参数
        val ipv4Address = intent?.getStringExtra("ipv4_address")
        val proxyCidrs = intent?.getStringArrayListExtra("proxy_cidrs") ?: arrayListOf()
        instanceName = intent?.getStringExtra("instance_name")

        if (ipv4Address == null || instanceName == null) {
            Log.e(TAG, "缺少必要参数: ipv4Address=$ipv4Address, instanceName=$instanceName")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(
                TAG,
                "启动 VPN Service - IPv4: $ipv4Address, Proxy CIDRs: $proxyCidrs, Instance: $instanceName"
        )

        thread {
            try {
                setupVpnInterface(ipv4Address, proxyCidrs)
            } catch (t: Throwable) {
                Log.e(TAG, "VPN 设置失败", t)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun setupVpnInterface(ipv4Address: String, proxyCidrs: List<String>) {
        try {
            // 解析 IPv4 地址和网络长度
            val (ip, networkLength) = parseIpv4Address(ipv4Address)

            // 1. 准备 VpnService.Builder
            val builder = Builder()
            builder.setSession("EasyTier VPN")
                    .addAddress(ip, networkLength)
                    .addDnsServer("223.5.5.5")
                    .addDnsServer("114.114.114.114")

            // [PATCH] 接管 IPv6 并黑洞化。
            // Android 没有"禁用 IPv6"的 API。若不接管，手机自身的 IPv6 会从物理网卡
            // 直出而绕过隧道（实测 4G 下 curl -6 直接拿到运营商 IPv6 地址）。
            // 接管后 EasyTier 侧 enable_ipv6=false 会丢弃这些包，应用自动回退 IPv4。
            try {
                builder.addAddress("fd00::1", 128)
                builder.addRoute("::", 0)
                Log.i(TAG, "已接管 IPv6（黑洞化），防止绕过隧道")
            } catch (e: Exception) {
                Log.w(TAG, "接管 IPv6 失败，IPv6 流量可能绕过隧道", e)
            }

            // [PATCH] 豁免本 App 自身流量，避免全网关模式下建连流量被 tun 吞掉造成回环。
            // 原代码写死 "com.easytier.easytiervpn"，与真实包名 com.kkrainbow.easytier
            // 不一致，导致豁免静默失效。
            try {
                builder.addDisallowedApplication(packageName)
                Log.i(TAG, "已豁免自身包名: $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "豁免自身包名失败: $packageName", e)
            }

            // 2. 添加路由表 - 为每个 proxy CIDR 添加路由
            proxyCidrs.forEach { cidr ->
                try {
                    val (routeIp, routeLength) = parseCidr(cidr)
                    builder.addRoute(routeIp, routeLength)
                    Log.d(TAG, "添加路由: $routeIp/$routeLength")
                } catch (e: Exception) {
                    Log.w(TAG, "解析 CIDR 失败: $cidr", e)
                }
            }

            // 3. 构建虚拟网络接口
            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "创建 VPN 接口失败")
                return
            }

            Log.i(TAG, "VPN 接口创建成功")

            // 4. 将 TUN 文件描述符传递给 EasyTier
            instanceName?.let { name ->
                val fd = vpnInterface!!.fd
                val result = EasyTierJNI.setTunFd(name, fd)
                if (result == 0) {
                    Log.i(TAG, "TUN 文件描述符设置成功: $fd")
                } else {
                    Log.e(TAG, "TUN 文件描述符设置失败: $result")
                }
            }

            isRunning = true

            // [PATCH] 注册 socket 保护回调，供 Rust 侧在创建连接前申请豁免。
            // 这是全网关的核心：让 EasyTier 自己到中心节点的连接绕开 tun，
            // 否则会形成"建连流量进 tun -> 隧道未建立 -> 死循环"。
            registerSocketProtector()

            // 5. 保持服务运行
            while (isRunning && vpnInterface != null) {
                Thread.sleep(1000)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VPN 接口设置过程中发生错误", t)
        } finally {
            cleanup()
        }
    }

    /**
     * [PATCH] 供 Rust 侧通过 JNI 调用的 socket 保护入口。
     *
     * 对应 Android 的 VpnService.protect(int)。AOSP 文档明确：
     * "a VPN tunnel should protect itself if its destination is covered by VPN
     *  routes. Otherwise its outgoing packets will be sent back to the VPN
     *  interface and cause an infinite loop."
     *
     * @param fd 需要豁免的 socket 文件描述符
     * @return true 表示已成功豁免
     */
    fun protectSocketFd(fd: Int): Boolean {
        if (!isRunning) {
            Log.w(TAG, "VPN 未运行，无法保护 socket fd=$fd")
            return false
        }
        return try {
            protect(fd)
        } catch (t: Throwable) {
            Log.w(TAG, "保护 socket fd=$fd 失败", t)
            false
        }
    }

    /** [PATCH] 把当前 Service 实例注册为 Rust 侧的 socket protector */
    private fun registerSocketProtector() {
        try {
            val ret = EasyTierJNI.setSocketProtector(this)
            if (ret == 0) {
                Log.i(TAG, "已注册 socket protector")
            } else {
                Log.w(TAG, "注册 socket protector 失败: $ret")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "注册 socket protector 异常", t)
        }
    }

    /** 解析 IPv4 地址，返回 IP 和网络长度 */
    private fun parseIpv4Address(ipv4Address: String): Pair<String, Int> {
        return if (ipv4Address.contains("/")) {
            val parts = ipv4Address.split("/")
            Pair(parts[0], parts[1].toInt())
        } else {
            // 默认使用 /24 网络
            Pair(ipv4Address, 24)
        }
    }

    /** 解析 CIDR，返回 IP 和网络长度 */
    private fun parseCidr(cidr: String): Pair<String, Int> {
        val parts = cidr.split("/")
        if (parts.size != 2) {
            throw IllegalArgumentException("无效的 CIDR 格式: $cidr")
        }
        return Pair(parts[0], parts[1].toInt())
    }

    private fun cleanup() {
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        Log.i(TAG, "VPN 接口已清理")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "VPN Service destroyed")
        cleanup()
    }
}
