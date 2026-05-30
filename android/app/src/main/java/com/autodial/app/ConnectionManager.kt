package com.autodial.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * AutoDial ConnectionManager v6
 * 单机对单机架构：PIN 标识，单状态机，ReconnectScheduler 阶梯降频
 * 支持 LAN + Cloud 双通道，云端唤醒 (reconnect_request)
 */
class ConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "ConnMgr"
        private const val LAN_PORT = 35432
        private const val DISCOVERY_PORT = 35433
        private const val DISCOVERY_TIMEOUT_MS = 8000L      // 发现超时 8s
        private const val DISCOVERY_RETRY_COUNT = 3
        private const val DISCOVERY_INTERVAL_MS = 200L
        private const val HEARTBEAT_INTERVAL_MS = 30000L     // 30s
        private const val PONG_TIMEOUT_MS = 15000L           // 15s pong 超时
        private const val MAX_PEER_CONNECTIONS = 1           // 手机最多1个对端
        private const val CLOUD_CONNECTION_LIMIT = 1         // +1 云端中继
        private const val NETWORK_DEBOUNCE_MS = 2000L        // 网络变化防抖 2s
    }

    // ==================== 状态机 ====================

    enum class ConnectionState { DISCONNECTED, DISCOVERING, CONNECTING, CONNECTED }

    sealed class ConnectionError {
        data class LanDiscoveryFailed(val reason: String) : ConnectionError()
        data class LanConnectFailed(val reason: String) : ConnectionError()
        data class CloudConnectFailed(val server: String, val reason: String) : ConnectionError()
        data class AuthFailed(val reason: String) : ConnectionError()
        data class Disconnected(val reason: String) : ConnectionError()
        data class TooManyConnections(val limit: Int) : ConnectionError()
    }

    interface ConnectionStateListener {
        fun onStateChanged(newState: ConnectionState, oldState: ConnectionState)
        fun onMessageReceived(msg: JSONObject)
        fun onError(error: ConnectionError)
    }

    // ==================== 内部状态 ====================

    @Volatile private var state: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile private var transportMode: String = "" // "lan" | "cloud" | "lan+cloud" | ""

    private var lanWebSocket: WebSocket? = null
    private var cloudWebSocket: WebSocket? = null

    // OkHttp 客户端
    private val lanClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val cloudClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    // ==================== v6: ReconnectScheduler ====================

    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null
    private var cloudReconnectAttempts = 0
    private var cloudReconnectRunnable: Runnable? = null
    private val MAX_RETRY_ATTEMPTS = 30  // 30 次后停止

    // ==================== v6: 心跳 ====================

    private var heartbeatRunnable: Runnable? = null
    private var lastLanPongTime = 0L
    private var lastCloudPongTime = 0L
    private var lanPingInFlight = false
    private var cloudPingInFlight = false

    // ==================== v6: NetworkMonitor ====================

    private var networkDebounceRunnable: Runnable? = null
    private var lanRetryRunnable: Runnable? = null  // v6: 云端在线时定期尝试LAN
    // Bug2修复: 发现线程取消标志，防止旧线程在新连接发起后继续运行并调用 connectLan
    @Volatile private var discoveryGeneration = 0
    private val LAN_RETRY_INTERVAL_MS = 60000L       // 60s 尝试一次LAN
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ==================== 配置 ====================

    private var lastPin = ""
    private var lastLanIp = ""
    private var currentCloudServer = ""
    private var cloudServerList: List<String> = emptyList()
    private var autoReconnect = true
    private var manualConnecting = false
    @Volatile private var manualDisconnecting = false  // v6: 手动断开标志，防止异步onClosed触发自动重连 (必须Volatile，OkHttp回调在其他线程)

    // SharedPreferences
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("autodial", Context.MODE_PRIVATE)
    }

    // 监听器
    private val listeners = mutableListOf<ConnectionStateListener>()

    // ==================== 公开属性 ====================

    // BUG-5修复: 使用局部快照避免多线程竞态，三个字段分别读取后做逻辑判断
    val isConnected: Boolean
        get() {
            val currentState = state          // @Volatile，单次读取
            val currentMode = transportMode   // @Volatile，单次读取
            if (currentState != ConnectionState.CONNECTED) return false
            val lanOk = lanWebSocket != null && currentMode.contains("lan")
            val cloudOk = cloudWebSocket != null && currentMode.contains("cloud")
            return lanOk || cloudOk
        }
    val connectionMode: String get() = transportMode
    val isCloudConnected: Boolean get() = cloudWebSocket != null && transportMode.contains("cloud")
    fun getState(): ConnectionState = state
    fun getTransportMode(): String = transportMode

    // ==================== 公开 API ====================

    /**
     * v6: 单一入口 connect。先 LAN 发现，失败 fallback Cloud。
     */
    fun connect(pin: String, hintIp: String = "") {
        v6LogI(TAG, pin, "connect(hintIp=$hintIp)")
        manualDisconnecting = false  // v6: 用户主动连接时清除手动断开标志
        cancelReconnect()
        cancelCloudReconnect()
        reconnectAttempts = 0
        cloudReconnectAttempts = 0

        lastPin = pin
        manualConnecting = true

        if (hintIp.isNotEmpty()) {
            lastLanIp = hintIp
            setState(ConnectionState.CONNECTING)
            connectLan(hintIp, pin)
        } else {
            lastLanIp = prefs.getString("ip", "") ?: ""
            setState(ConnectionState.DISCOVERING)
            startLanDiscovery(pin)
        }
    }

    /**
     * v6: 断开所有连接
     */
    fun disconnect() {
        v6LogI(TAG, lastPin, "disconnect()")
        manualConnecting = false
        manualDisconnecting = true  // v6: 标记手动断开，阻止异步 onClosed 回调触发自动重连
        cancelReconnect()
        cancelCloudReconnect()
        cancelLanRetry()  // v6: 停止LAN重试
        reconnectAttempts = 0
        cloudReconnectAttempts = 0

        try { lanWebSocket?.cancel() } catch (_: Exception) {}
        lanWebSocket = null
        try { cloudWebSocket?.cancel() } catch (_: Exception) {}
        cloudWebSocket = null

        setState(ConnectionState.DISCONNECTED)
    }

    /**
     * v6: 断开云端连接
     */
    fun disconnectCloud() {
        v6LogI(TAG, lastPin, "disconnectCloud()")
        cancelCloudReconnect()
        cloudReconnectAttempts = 0
        // A-5修复: 让正在跑的 tryConnectCloudAtIndex 闭包识别为旧 generation 并放弃
        cloudConnectGeneration++

        try { cloudWebSocket?.cancel() } catch (_: Exception) {}
        cloudWebSocket = null

        if (transportMode.contains("cloud")) {
            transportMode = if (transportMode.contains("lan")) "lan" else ""
            if (transportMode.isEmpty()) setState(ConnectionState.DISCONNECTED)
        }
    }

    /**
     * v6: 直接连云端（跳过 LAN）
     */
    fun connectCloudOnly(pin: String) {
        v6LogI(TAG, pin, "connectCloudOnly()")
        cancelReconnect()
        cancelCloudReconnect()
        reconnectAttempts = 0
        cloudReconnectAttempts = 0
        lastPin = pin
        manualConnecting = true

        if (cloudServerList.isEmpty()) {
            v6LogW(TAG, pin, "云端未配置, 放弃连接")
            setState(ConnectionState.DISCONNECTED)
            manualConnecting = false
            return
        }
        setState(ConnectionState.CONNECTING)
        connectCloud(cloudServerList, pin)
    }

    /**
     * v6: 发送消息，LAN 优先
     */
    fun send(msg: JSONObject): Boolean {
        // LAN 优先
        if (transportMode.contains("lan") && lanWebSocket != null) {
            try {
                val sent = lanWebSocket?.send(msg.toString()) ?: false
                if (sent) {
                    v6LogMsg("SEND-LAN", msg.optString("type", "?"), msg.toString(), lastPin)
                    return true
                }
            } catch (_: Exception) {
                v6LogW(TAG, lastPin, "LAN 发送失败, 尝试云端降级")
                lanWebSocket = null
                if (cloudWebSocket != null && transportMode.contains("cloud")) {
                    transportMode = "cloud"
                } else {
                    transportMode = ""
                    setState(ConnectionState.DISCONNECTED)
                }
            }
        }

        // Cloud 降级
        if (transportMode.contains("cloud") && cloudWebSocket != null) {
            try {
                val sent = cloudWebSocket?.send(msg.toString()) ?: false
                if (sent) {
                    v6LogMsg("SEND-CLOUD", msg.optString("type", "?"), msg.toString(), lastPin)
                    return true
                }
            } catch (_: Exception) {
                v6LogW(TAG, lastPin, "Cloud 发送失败")
                cloudWebSocket = null
                if (transportMode == "cloud") {
                    transportMode = ""
                    setState(ConnectionState.DISCONNECTED)
                }
            }
        }

        // 最后兜底
        if (lanWebSocket != null) {
            try { if (lanWebSocket?.send(msg.toString()) == true) return true } catch (_: Exception) {}
        }
        if (cloudWebSocket != null) {
            try { cloudWebSocket?.send(msg.toString()); return true } catch (_: Exception) {}
        }
        return false
    }

    fun addListener(listener: ConnectionStateListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: ConnectionStateListener) {
        listeners.remove(listener)
    }

    fun loadSavedConfig() {
        autoReconnect = prefs.getBoolean("auto_reconnect", true)
        val wasConnected = prefs.getBoolean("was_connected", false)
        lastPin = prefs.getString("pin", "") ?: ""
        lastLanIp = prefs.getString("ip", "") ?: ""
        currentCloudServer = prefs.getString("cloud_server", "") ?: ""
        val cloudEnabled = prefs.getBoolean("cloud_enabled", false)
        val serversJson = prefs.getString("cloud_servers", null)

        cloudServerList = if (serversJson != null) {
            try {
                val arr = JSONArray(serversJson)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                if (currentCloudServer.isNotEmpty()) listOf(currentCloudServer) else emptyList()
            }
        } else {
            if (currentCloudServer.isNotEmpty()) listOf(currentCloudServer) else emptyList()
        }

        // BUG-1修复: 云开关关闭时，清空运行时服务器列表，防止后续重连逻辑绕过开关
        if (!cloudEnabled && cloudServerList.isNotEmpty()) {
            v6LogI(TAG, lastPin, "云开关已关闭，清空运行时云端服务器列表")
            cloudServerList = emptyList()
        }

        // BUG-1修复: cloudEnabled 必须为 true 才允许连接云端
        if (wasConnected && lastLanIp.isNotEmpty() && lastPin.isNotEmpty() && !isConnected) {
            setState(ConnectionState.CONNECTING)
            connectLan(lastLanIp, lastPin)
            if (cloudEnabled && cloudServerList.isNotEmpty()) connectCloud(cloudServerList, lastPin)
        } else if (cloudEnabled && cloudServerList.isNotEmpty() && lastPin.isNotEmpty() && !isConnected) {
            connectCloud(cloudServerList, lastPin)
        }
        // was_connected 持久化修复: 云开关关闭时，不因 was_connected 触发云端连接
        // 上面的 cloudEnabled 检查已覆盖此场景
    }

    fun setCloudServers(servers: List<String>) {
        cloudServerList = servers
        if (servers.isNotEmpty()) currentCloudServer = servers[0]
    }

    fun cleanup() {
        unregisterNetworkMonitor()
        cancelLanRetry()  // v6: 停止LAN重试
        disconnect()
        listeners.clear()
    }

    // ==================== v6: 亮屏健康检查 ====================

    /**
     * v6: 屏幕亮起时快速检查连接是否存活
     * 发 ping 到当前通道，2s 无 pong → 判定为僵尸连接 → 重建
     * 解决 Doze 休眠后 WebSocket 未真实断开但已失效的问题
     */
    fun wakeAndReconnect() {
        if (!isConnected) {
            // 已确认断开 → 直接重连
            v6LogI(TAG, lastPin, "亮屏检测: 已断开, 触发重连")
            // Bug15修复: 取消已有重连任务，防止屏幕快速亮灭时多次 loadSavedConfig 叠加
            cancelReconnect()
            cancelCloudReconnect()
            reconnectAttempts = 0
            loadSavedConfig()
            return
        }

        val now = System.currentTimeMillis()
        val pingMsg = JSONObject().put("type", "ping")

        // LAN 检测（即使当前是 cloud-only 也尝试发现局域网）
        if (transportMode.contains("lan") && lanWebSocket != null) {
            val lastPong = lastLanPongTime
            try {
                lanWebSocket?.send(pingMsg.toString())
                handler.postDelayed({
                    if (lastLanPongTime == lastPong && transportMode.contains("lan")) {
                        v6LogW(TAG, lastPin, "亮屏检测: LAN 僵尸连接, 重建")
                        try { lanWebSocket?.cancel() } catch (_: Exception) {}
                        lanWebSocket = null
                        connect(lastPin, lastLanIp)
                    }
                }, 2000)
            } catch (_: Exception) {
                v6LogW(TAG, lastPin, "亮屏检测: LAN ping 发送失败, 重建")
                lanWebSocket = null
                connect(lastPin, lastLanIp)
            }
        } else if (transportMode == "cloud") {
            // v6: 亮屏时若仅为云端连接，主动尝试 LAN 发现
            v6LogI(TAG, lastPin, "亮屏检测: cloud-only, 尝试 LAN 发现")
            tryReconnectLanIfCloudOnly()
        }

        // 检测 Cloud 通道是否存活
        if (transportMode.contains("cloud") && cloudWebSocket != null) {
            val lastPong = lastCloudPongTime
            try {
                cloudWebSocket?.send(pingMsg.toString())
                handler.postDelayed({
                    if (lastCloudPongTime == lastPong && transportMode.contains("cloud")) {
                        v6LogW(TAG, lastPin, "亮屏检测: Cloud 僵尸连接, 重建")
                        try { cloudWebSocket?.cancel() } catch (_: Exception) {}
                        cloudWebSocket = null
                        connectCloud(cloudServerList, lastPin)
                    }
                }, 2000)
            } catch (_: Exception) {
                v6LogW(TAG, lastPin, "亮屏检测: Cloud ping 发送失败, 重建")
                cloudWebSocket = null
                connectCloud(cloudServerList, lastPin)
            }
        }
    }

    /**
     * v6: 收到 PC 端强制重连指令
     * 重置 ReconnectScheduler 重试计数，立即从第 1 次开始
     */
    fun onReconnectRequest() {
        v6LogI(TAG, lastPin, "收到云端唤醒 reconnect_request")
        cancelReconnect()
        reconnectAttempts = 0
        scheduleReconnect("pc_force")
    }

    // ==================== 上传协议桩方法 ====================

    fun sendFile(filePath: String, callback: ((Boolean, String?) -> Unit)?): Boolean {
        v6LogW(TAG, lastPin, "[UPLOAD-STUB] sendFile: $filePath")
        callback?.invoke(false, "Not implemented")
        return false
    }

    fun sendData(data: ByteArray, mimeType: String, fileName: String, callback: ((Boolean, String?) -> Unit)?): Boolean {
        v6LogW(TAG, lastPin, "[UPLOAD-STUB] sendData: $fileName (${data.size} bytes)")
        callback?.invoke(false, "Not implemented")
        return false
    }

    // ==================== v6: NetworkMonitor ====================

    fun registerNetworkMonitor() {
        unregisterNetworkMonitor()
        try {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scheduleNetworkDebounce("network_available")
                }
                override fun onLost(network: Network) {
                    scheduleNetworkDebounce("network_lost")
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    scheduleNetworkDebounce("network_caps_changed")
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            v6LogI(TAG, lastPin, "NetworkMonitor 已注册")
        } catch (e: Exception) {
            v6LogE(TAG, lastPin, "NetworkMonitor 注册失败: ${e.message}")
        }
    }

    fun unregisterNetworkMonitor() {
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            networkCallback = null
            networkDebounceRunnable?.let { handler.removeCallbacks(it) }
            networkDebounceRunnable = null
        } catch (_: Exception) {}
    }

    private fun scheduleNetworkDebounce(reason: String) {
        networkDebounceRunnable?.let { handler.removeCallbacks(it) }
        networkDebounceRunnable = Runnable {
            v6LogI(TAG, lastPin, "网络变化: $reason → 通知 ReconnectScheduler")

            if (autoReconnect && lastPin.isNotEmpty()) {
                if (!isConnected) {
                    // BUG-6修复: 只在完全断开时才重置计数器，避免频繁抖动导致上限永远达不到
                    // 但如果当前已经停止重连（达到上限），网络恢复时给一次重置机会
                    if (reconnectAttempts >= MAX_RETRY_ATTEMPTS) {
                        v6LogI(TAG, lastPin, "网络变化: 重连计数已达上限，给予一次重置机会")
                        reconnectAttempts = 0
                    }
                    cancelReconnect()
                    // 完全断开 → 全量重连
                    scheduleReconnect("network_change")
                } else if (transportMode == "cloud") {
                    // v6: 云端在线时网络变化 → 尝试LAN发现（UDP发现不依赖历史IP）
                    v6LogI(TAG, lastPin, "云端在线，尝试 LAN 发现")
                    tryReconnectLanIfCloudOnly()
                }
            }
        }
        handler.postDelayed(networkDebounceRunnable!!, NETWORK_DEBOUNCE_MS)
    }

    /**
     * v6 稳定性修复: 云端在线时尝试恢复 LAN 连接
     * 场景: 手机 WiFi 闪断切到云端 → WiFi 恢复后自动回到 LAN
     * 不依赖旧IP，先做UDP发现再连接，IP可能已变化
     */
    private fun tryReconnectLanIfCloudOnly() {
        if (transportMode != "cloud" || lastPin.isEmpty()) return
        v6LogI(TAG, lastPin, "云端在线，开始UDP发现LAN")
        // v6: 不直接用旧 IP（可能已过期），重新做 UP 发现
        startLanDiscovery(lastPin)
        // 发现成功后 startLanDiscovery 内部调用 connectLan(newIp, pin)
        // 失败不影响云端连接
    }

    /** v6: 云端在线模式下定期尝试LAN恢复 */
    private fun scheduleLanRetry() {
        cancelLanRetry()
        if (transportMode != "cloud" || lastPin.isEmpty()) return
        lanRetryRunnable = Runnable {
            tryReconnectLanIfCloudOnly()
            scheduleLanRetry()  // 递归调度下一次
        }
        handler.postDelayed(lanRetryRunnable!!, LAN_RETRY_INTERVAL_MS)
    }

    private fun cancelLanRetry() {
        lanRetryRunnable?.let { handler.removeCallbacks(it) }
        lanRetryRunnable = null
    }

    // ==================== 内部: LAN 发现 ====================

    private fun startLanDiscovery(pin: String) {
        // Bug2修复: 递增 generation，使旧发现线程在回调时检测到已过期并放弃
        val myGeneration = ++discoveryGeneration
        Thread {
            var discoveredIp: String? = null
            try {
                val socket = DatagramSocket(null)
                socket.soTimeout = DISCOVERY_TIMEOUT_MS.toInt()
                socket.reuseAddress = true

                val discoverMsg = JSONObject().apply {
                    put("type", "discover")
                    put("pin", pin)
                }.toString().toByteArray()

                // v6: 发送 N 次 UDP 广播
                for (i in 0 until DISCOVERY_RETRY_COUNT) {
                    try {
                        val packet = DatagramPacket(
                            discoverMsg, discoverMsg.size,
                            InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT
                        )
                        socket.send(packet)
                    } catch (_: Exception) {}
                    if (i < DISCOVERY_RETRY_COUNT - 1) Thread.sleep(DISCOVERY_INTERVAL_MS)
                }

                // 监听响应
                val buffer = ByteArray(1024)
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT_MS) {
                    try {
                        val response = DatagramPacket(buffer, buffer.size)
                        socket.receive(response)
                        val data = JSONObject(String(response.data, 0, response.length))
                        val type = data.optString("type", "")
                        if ((type == "found" || type == "announce") && data.optString("pin") == pin) {
                            discoveredIp = data.optString("ip", "")
                            if (discoveredIp!!.isNotEmpty()) break
                        }
                    } catch (_: Exception) {}
                }
                socket.close()
            } catch (e: Exception) {
                v6LogE(TAG, pin, "LAN 发现异常: ${e.message}")
            }

            handler.post {
                // Bug2修复: 检查 generation，若已过期则丢弃结果
                if (myGeneration != discoveryGeneration) {
                    v6LogW(TAG, pin, "LAN 发现结果已过期(generation=$myGeneration, current=$discoveryGeneration), 丢弃")
                    return@post
                }
                if (discoveredIp != null && discoveredIp!!.isNotEmpty()) {
                    v6LogI(TAG, pin, "LAN 发现: $discoveredIp")
                    lastLanIp = discoveredIp!!
                    setState(ConnectionState.CONNECTING)
                    connectLan(discoveredIp!!, pin)
                } else {
                    v6LogW(TAG, pin, "LAN 发现失败, 降级到云端")
                    connectCloud(cloudServerList, pin)
                }
            }
        }.start()
    }

    // ==================== 内部: LAN 连接 ====================

    private fun connectLan(ip: String, pin: String) {
        try {
            try { lanWebSocket?.cancel() } catch (_: Exception) {}
            lanWebSocket = null

            val url = "ws://$ip:$LAN_PORT"
            v6LogI(TAG, pin, "LAN 连接: $url")

            val request = Request.Builder().url(url).build()
            lanWebSocket = lanClient.newWebSocket(request, createLanListener(pin))
        } catch (e: Exception) {
            v6LogE(TAG, pin, "LAN 连接异常: ${e.message}")
            handler.post {
                notifyError(ConnectionError.LanConnectFailed(e.message ?: "unknown"))
                connectCloud(cloudServerList, pin)
            }
        }
    }

    private fun createLanListener(pin: String): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                v6LogI(TAG, pin, "LAN WebSocket 已打开")
                try {
                    val deviceName = android.os.Build.MODEL ?: android.os.Build.DEVICE ?: "Android"
                    ws.send(JSONObject().apply {
                        put("type", "phone_hello")
                        put("pin", pin)
                        put("deviceName", deviceName)
                        // C-1修复: 同型号多手机同 PIN 时云端用 deviceName 路由会串号，加唯一 deviceId
                        put("deviceId", getStableDeviceId())
                        // N-1修复: 云端模式下 PC 也能拿到手机最新局域网 IP（用于 UDP 唤醒）
                        getLocalWifiIp()?.let { put("wifiIp", it) }
                    }.toString())
                } catch (e: Exception) { v6LogE(TAG, pin, "LAN hello 发送失败: ${e.message}") }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    v6LogMsg("RECV-LAN", msg.optString("type", "?"), text, pin)
                    when (msg.optString("type", "")) {
                        "auth_ok" -> {
                            v6LogI(TAG, pin, "LAN 认证成功")
                            manualConnecting = false
                            reconnectAttempts = 0  // v6: 连接成功重置计数
                            transportMode = if (isCloudConnected) "lan+cloud" else "lan"
                            // v6 稳定性: LAN 恢复 → 停止定期重试
                            cancelLanRetry()
                            lastLanPongTime = System.currentTimeMillis()
                            lanPingInFlight = false
                            setState(ConnectionState.CONNECTED)
                        }
                        "auth_fail" -> {
                            v6LogW(TAG, pin, "LAN 认证失败: ${msg.optString("reason", "")}")
                            manualConnecting = false
                            handler.post { notifyError(ConnectionError.AuthFailed(msg.optString("reason", ""))) }
                            ws.close(1000, "auth_fail")
                            connectCloud(cloudServerList, pin)
                        }
                        "pong" -> {
                            lastLanPongTime = System.currentTimeMillis()
                            lanPingInFlight = false
                        }
                        "kicked" -> {
                            v6LogW(TAG, pin, "被 PC 踢出")
                            manualConnecting = false
                            setState(ConnectionState.DISCONNECTED)
                            handler.post { notifyError(ConnectionError.Disconnected("kicked")) }
                        }
                        "reconnect_request" -> {
                            v6LogI(TAG, pin, "收到 PC 端云端唤醒指令")
                            onReconnectRequest()
                        }
                        "wake_connect" -> {
                            v6LogI(TAG, pin, "收到局域网 UDP 唤醒包: ${msg.optString("ip", "?")}")
                            // 若已连接，忽略；否则触发重连
                            if (!isConnected) {
                                val wakeIp = msg.optString("ip", "")
                                if (wakeIp.isNotEmpty()) {
                                    cancelReconnect()
                                    reconnectAttempts = 0
                                    handler.post { connect(pin, wakeIp) }
                                } else {
                                    onReconnectRequest()
                                }
                            }
                        }
                        else -> {
                            handler.post { notifyMessage(msg) }
                        }
                    }
                } catch (e: Exception) { v6LogE(TAG, pin, "LAN 消息解析失败: ${e.message}") }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                try { ws.close(1000, null) } catch (_: Exception) {}
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                v6LogW(TAG, pin, "LAN closed code=$code")
                handleLanDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                v6LogE(TAG, pin, "LAN 失败: ${t.message}")
                handleLanDisconnect()
                if (state == ConnectionState.CONNECTING) {
                    connectCloud(cloudServerList, pin)
                }
            }
        }
    }

    private fun handleLanDisconnect() {
        try { lanWebSocket?.cancel() } catch (_: Exception) {}
        lanWebSocket = null
        v6LogW(TAG, lastPin, "handleLanDisconnect, transport=$transportMode")

        // v6: 手动断开时跳过自动重连
        if (manualDisconnecting) {
            v6LogI(TAG, lastPin, "手动断开中，跳过LAN自动重连")
            return
        }

        if (transportMode.contains("lan")) {
            if (isCloudConnected) {
                transportMode = "cloud"
                v6LogI(TAG, lastPin, "LAN 断开, 已切换至云端")
                handler.post { notifyStateChange(ConnectionState.CONNECTED, ConnectionState.CONNECTED) }
                // v6 稳定性: 启动定期 LAN 恢复尝试
                scheduleLanRetry()
            } else {
                setState(ConnectionState.DISCONNECTED)
                if (autoReconnect) scheduleReconnect("lan_disconnect")
            }
        } else {
            // Bug13修复: transportMode 不含 "lan"（如已切换到 cloud-only）时，
            // LAN 断开回调仍需检查是否需要重连（例如 cloud 也不可用时）
            if (!isCloudConnected && state != ConnectionState.DISCONNECTED) {
                setState(ConnectionState.DISCONNECTED)
                if (autoReconnect) scheduleReconnect("lan_disconnect_fallback")
            }
        }
    }

    // ==================== 内部: Cloud 连接 ====================

    /**
     * A-5修复: 云端遍历的 generation。每次新发起 connectCloud 递增，用于让旧闭包遍历自动放弃，
     * 防止用户禁用云端后旧 onFailure 仍在闭包中递归 tryConnectCloudAtIndex。
     */
    @Volatile private var cloudConnectGeneration = 0

    private fun connectCloud(servers: List<String>, pin: String) {
        if (servers.isEmpty() || pin.isEmpty()) {
            if (state != ConnectionState.CONNECTED) {
                setState(ConnectionState.DISCONNECTED)
                manualConnecting = false
            }
            return
        }
        // A-5修复: 用户禁用云端时立即返回，不发起新遍历
        val cloudEnabled = prefs.getBoolean("cloud_enabled", false)
        if (!cloudEnabled) {
            v6LogI(TAG, pin, "connectCloud 跳过: cloud_enabled=false")
            return
        }
        val myGen = ++cloudConnectGeneration
        tryConnectCloudAtIndex(servers, pin, 0, myGen)
    }

    private fun tryConnectCloudAtIndex(servers: List<String>, pin: String, index: Int, generation: Int = cloudConnectGeneration) {
        // A-5修复: 旧 generation 的遍历直接放弃（用户已切换/禁用云端）
        if (generation != cloudConnectGeneration) {
            v6LogI(TAG, pin, "tryConnectCloudAtIndex 旧 generation=$generation, 当前=$cloudConnectGeneration, 放弃")
            return
        }
        // A-5修复: 遍历途中云开关被关闭也立即停止
        if (!prefs.getBoolean("cloud_enabled", false)) {
            v6LogI(TAG, pin, "tryConnectCloudAtIndex 跳过: cloud_enabled=false")
            return
        }
        if (index >= servers.size) {
            v6LogW(TAG, pin, "所有云端服务器连接失败")
            if (state != ConnectionState.CONNECTED) {
                setState(ConnectionState.DISCONNECTED)
                manualConnecting = false
            }
            return
        }

        val server = servers[index]
        v6LogI(TAG, pin, "尝试云端 ${index + 1}/${servers.size}: $server")
        currentCloudServer = server

        if (state == ConnectionState.DISCOVERING || state == ConnectionState.DISCONNECTED) {
            setState(ConnectionState.CONNECTING)
        }

        try {
            cancelCloudReconnect()
            try { cloudWebSocket?.cancel() } catch (_: Exception) {}
            cloudWebSocket = null

            val url = if (server.startsWith("ws://") || server.startsWith("wss://")) server
                      else "ws://$server"

            val request = Request.Builder().url(url).build()
            cloudWebSocket = cloudClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    v6LogI(TAG, pin, "Cloud WebSocket 已打开: $currentCloudServer")
                    try {
                        val deviceName = android.os.Build.MODEL ?: android.os.Build.DEVICE ?: "Android"
                        ws.send(JSONObject().apply {
                            put("type", "phone_hello")
                            put("pin", pin)
                            put("deviceName", deviceName)
                            // C-1修复: 同型号多手机同 PIN 路由唯一标识
                            put("deviceId", getStableDeviceId())
                            // N-1修复: 携带局域网 IP 给 PC 用于 UDP 唤醒
                            getLocalWifiIp()?.let { put("wifiIp", it) }
                        }.toString())
                    } catch (e: Exception) { v6LogE(TAG, pin, "Cloud hello 发送失败: ${e.message}") }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val msg = JSONObject(text)
                        v6LogMsg("RECV-CLOUD", msg.optString("type", "?"), text, pin)
                        when (msg.optString("type", "")) {
                            "auth_ok" -> {
                                v6LogI(TAG, pin, "Cloud 认证成功")
                                cloudReconnectAttempts = 0
                                manualConnecting = false
                                transportMode = if (transportMode.contains("lan")) "lan+cloud" else "cloud"
                                lastCloudPongTime = System.currentTimeMillis()
                                cloudPingInFlight = false
                                setState(ConnectionState.CONNECTED)
                            }
                            "auth_fail" -> {
                                v6LogW(TAG, pin, "Cloud 认证失败: ${msg.optString("reason", "")}")
                                handler.post { notifyError(ConnectionError.AuthFailed(msg.optString("reason", ""))) }
                                ws.close(1000, "auth_fail")
                                tryConnectCloudAtIndex(servers, pin, index + 1, generation)
                            }
                            "pong" -> {
                                lastCloudPongTime = System.currentTimeMillis()
                                cloudPingInFlight = false
                            }
                            "reconnect_request" -> {
                                v6LogI(TAG, pin, "收到 PC 端云端唤醒指令 (via Cloud)")
                                onReconnectRequest()
                            }
                            else -> {
                                handler.post { notifyMessage(msg) }
                            }
                        }
                    } catch (e: Exception) { v6LogE(TAG, pin, "Cloud 消息解析失败: ${e.message}") }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    try { ws.close(1000, null) } catch (_: Exception) {}
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    v6LogW(TAG, pin, "Cloud closed code=$code reason=$reason")
                    // #13 修复: 旧 ws 的 onClosed 可能在新 ws 已替换 cloudWebSocket 之后到达
                    // （例如 auth_fail 路径会立刻 tryConnectCloudAtIndex 替换 cloudWebSocket）。
                    // 那种情况 handleCloudDisconnect() 会把刚替换上去的新 cloudWebSocket 也清掉。
                    if (cloudWebSocket !== ws) {
                        v6LogI(TAG, pin, "Cloud onClosed: 旧 ws 的延迟回调（非当前 cloudWebSocket），忽略")
                        return
                    }
                    handleCloudDisconnect()
                    // Bug6修复: 手动断开时不触发自动重连
                    if (autoReconnect && !manualDisconnecting) scheduleCloudReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    v6LogE(TAG, pin, "Cloud 失败: ${t.message}")
                    // #13 修复: 同 onClosed
                    if (cloudWebSocket !== ws) {
                        v6LogI(TAG, pin, "Cloud onFailure: 旧 ws 的延迟回调（非当前 cloudWebSocket），忽略")
                        return
                    }
                    handleCloudDisconnect()
                    if (state == ConnectionState.CONNECTING) {
                        tryConnectCloudAtIndex(servers, pin, index + 1, generation)
                    } else {
                        // Bug6修复: 手动断开时不触发自动重连
                        if (autoReconnect && !manualDisconnecting) scheduleCloudReconnect()
                    }
                }
            })
        } catch (e: Exception) {
            v6LogE(TAG, pin, "Cloud 连接异常: ${e.message}")
            tryConnectCloudAtIndex(servers, pin, index + 1, generation)
        }
    }

    private fun handleCloudDisconnect() {
        try { cloudWebSocket?.cancel() } catch (_: Exception) {}
        cloudWebSocket = null
        v6LogW(TAG, lastPin, "handleCloudDisconnect, transport=$transportMode")

        // v6: 手动断开时跳过自动重连
        if (manualDisconnecting) {
            v6LogI(TAG, lastPin, "手动断开中，跳过Cloud自动重连")
            return
        }

        if (transportMode.contains("cloud")) {
            if (transportMode.contains("lan")) {
                transportMode = "lan"
                v6LogI(TAG, lastPin, "Cloud 断开, LAN 仍活跃")
            } else {
                setState(ConnectionState.DISCONNECTED)
            }
        }
    }

    // ==================== v6: ReconnectScheduler ====================

    private fun scheduleReconnect(reason: String) {
        cancelReconnect()
        if (!autoReconnect || lastPin.isEmpty()) return
        if (isConnected) return
        if (reconnectAttempts >= MAX_RETRY_ATTEMPTS) {
            v6LogW(TAG, lastPin, "达到最大重试次数($MAX_RETRY_ATTEMPTS), 停止自动重连, 等待手动触发")
            return
        }

        val delayMs = getReconnectDelay(reconnectAttempts + 1)
        v6LogI(TAG, lastPin, "触发重连(第${reconnectAttempts + 1}次, 原因=$reason), 等待${delayMs}ms后执行")

        reconnectRunnable = Runnable {
            if (!isConnected && lastPin.isNotEmpty()) {
                reconnectAttempts++
                val cloudEnabled = prefs.getBoolean("cloud_enabled", false)
                if (lastLanIp.isNotEmpty()) {
                    setState(ConnectionState.CONNECTING)
                    connectLan(lastLanIp, lastPin)
                    // v6: 云开关开时才同时尝试云端
                    if (cloudEnabled && cloudServerList.isNotEmpty()) connectCloud(cloudServerList, lastPin)
                } else if (cloudEnabled && cloudServerList.isNotEmpty()) {
                    connectCloud(cloudServerList, lastPin)
                }
            }
        }
        handler.postDelayed(reconnectRunnable!!, delayMs)
    }

    private fun cancelReconnect() {
        try { reconnectRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
        reconnectRunnable = null
    }

    private fun scheduleCloudReconnect() {
        cancelCloudReconnect()
        // v6: 检查自动连接和云开关
        val cloudEnabled = prefs.getBoolean("cloud_enabled", false)
        if (!autoReconnect || !cloudEnabled || lastPin.isEmpty()) return
        if (isCloudConnected) return

        // v6: 30次上限
        if (cloudReconnectAttempts >= MAX_RETRY_ATTEMPTS) {
            v6LogW(TAG, lastPin, "Cloud 重连达到最大次数($MAX_RETRY_ATTEMPTS), 停止自动尝试")
            return
        }

        cloudReconnectAttempts++
        val delayMs = getReconnectDelay(cloudReconnectAttempts)

        cloudReconnectRunnable = Runnable {
            if (!isCloudConnected && cloudServerList.isNotEmpty()) {
                v6LogI(TAG, lastPin, "Cloud 重连(第${cloudReconnectAttempts}次)")
                connectCloud(cloudServerList, lastPin)
            }
        }
        handler.postDelayed(cloudReconnectRunnable!!, delayMs)
    }

    private fun cancelCloudReconnect() {
        try { cloudReconnectRunnable?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
        cloudReconnectRunnable = null
    }

    /** v6: 阶梯降频策略 */
    private fun getReconnectDelay(attempt: Int): Long {
        return when (attempt) {
            1 -> 0L              // 立即
            2 -> 1000L           // 1s
            3 -> 3000L           // 3s
            in 4..6 -> 5000L     // 5s
            in 7..10 -> 10000L   // 10s
            in 11..15 -> 30000L  // 30s
            in 16..20 -> 60000L  // 60s
            else -> 300000L      // 5 分钟
        }
    }

    // ==================== 内部: 状态管理 ====================

    private fun setState(newState: ConnectionState) {
        val oldState = state
        if (oldState == newState) return
        state = newState

        v6LogI(TAG, lastPin, "State: $oldState → $newState, transport=$transportMode")
        handler.post { notifyStateChange(oldState, newState) }

        if (newState == ConnectionState.CONNECTED) startHeartbeat()
        else if (newState == ConnectionState.DISCONNECTED) stopHeartbeat()
    }

    private fun notifyStateChange(oldState: ConnectionState, newState: ConnectionState) {
        listeners.forEach { listener ->
            try { listener.onStateChanged(newState, oldState) } catch (_: Exception) {}
        }
    }

    private fun notifyMessage(msg: JSONObject) {
        listeners.forEach { listener ->
            try { listener.onMessageReceived(msg) } catch (_: Exception) {}
        }
    }

    private fun notifyError(error: ConnectionError) {
        listeners.forEach { listener ->
            try { listener.onError(error) } catch (_: Exception) {}
        }
    }

    // ==================== v6: 心跳 ====================

    private fun startHeartbeat() {
        stopHeartbeat()
        lastLanPongTime = System.currentTimeMillis()
        lastCloudPongTime = System.currentTimeMillis()
        lanPingInFlight = false
        cloudPingInFlight = false

        heartbeatRunnable = Runnable {
            if (state == ConnectionState.CONNECTED) {
                val now = System.currentTimeMillis()
                val pingMsg = JSONObject().put("type", "ping")

                // LAN 通道
                if (lanWebSocket != null && transportMode.contains("lan")) {
                    if (lanPingInFlight && (now - lastLanPongTime) > PONG_TIMEOUT_MS) {
                        v6LogW(TAG, lastPin, "LAN pong 超时(${now - lastLanPongTime}ms), 降级")
                        lanPingInFlight = false
                        handleLanPongTimeout()
                    } else {
                        try {
                            lanWebSocket?.send(pingMsg.toString())
                            lanPingInFlight = true
                        } catch (_: Exception) {
                            v6LogW(TAG, lastPin, "LAN ping 发送失败")
                            lanPingInFlight = false
                        }
                    }
                }

                // Cloud 通道
                if (cloudWebSocket != null && transportMode.contains("cloud")) {
                    if (cloudPingInFlight && (now - lastCloudPongTime) > PONG_TIMEOUT_MS) {
                        v6LogW(TAG, lastPin, "Cloud pong 超时(${now - lastCloudPongTime}ms)")
                        cloudPingInFlight = false
                        handleCloudPongTimeout()
                    } else {
                        try {
                            cloudWebSocket?.send(pingMsg.toString())
                            cloudPingInFlight = true
                        } catch (_: Exception) {
                            v6LogW(TAG, lastPin, "Cloud ping 发送失败")
                            cloudPingInFlight = false
                        }
                    }
                }

                handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
            }
        }
        handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
        v6LogI(TAG, lastPin, "心跳已启动")
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
        lanPingInFlight = false
        cloudPingInFlight = false
    }

    private fun handleLanPongTimeout() {
        v6LogW(TAG, lastPin, "LAN 通道死亡, 降级")
        try { lanWebSocket?.cancel() } catch (_: Exception) {}
        lanWebSocket = null

        if (transportMode.contains("lan")) {
            if (isCloudConnected) {
                transportMode = "cloud"
                handler.post { notifyStateChange(ConnectionState.CONNECTED, ConnectionState.CONNECTED) }
            } else {
                setState(ConnectionState.DISCONNECTED)
                if (autoReconnect) scheduleReconnect("lan_pong_timeout")
            }
        }
    }

    private fun handleCloudPongTimeout() {
        v6LogW(TAG, lastPin, "Cloud 通道死亡")
        try { cloudWebSocket?.cancel() } catch (_: Exception) {}
        cloudWebSocket = null

        if (transportMode.contains("cloud")) {
            if (transportMode.contains("lan")) {
                transportMode = "lan"
                handler.post { notifyStateChange(ConnectionState.CONNECTED, ConnectionState.CONNECTED) }
            } else {
                setState(ConnectionState.DISCONNECTED)
            }
            if (autoReconnect) scheduleCloudReconnect()
        }
    }

    // ==================== v6: Logger ====================

    private fun v6LogI(module: String, pin: String, msg: String) {
        FileLogger.i(module, "[$pin] $msg")
    }

    private fun v6LogW(module: String, pin: String, msg: String) {
        FileLogger.w(module, "[$pin] $msg")
    }

    private fun v6LogE(module: String, pin: String, msg: String) {
        FileLogger.e(module, "[$pin] $msg")
    }

    private fun v6LogMsg(direction: String, type: String, content: String, pin: String) {
        val truncated = if (content.length > 500) content.substring(0, 500) + "...(truncated)" else content
        FileLogger.i(direction, "[$pin] [$type] $truncated")
    }

    // ==================== 工具方法 ====================

    /**
     * #12 修复: 暴露稳定 deviceId 给 DialService 用，让 dial_result/sms_result 能携带它做精准匹配。
     */
    fun getDeviceIdPublic(): String = getStableDeviceId()

    /**
     * C-1修复: 生成稳定唯一 deviceId（同型号多手机同 PIN 时区分用）
     * - 首次启动随机生成，存入 SharedPreferences，之后每次启动一致
     * - 不使用 ANDROID_ID（要求隐私权限），用应用私有随机串足够
     */
    private fun getStableDeviceId(): String {
        val saved = prefs.getString("device_id", null)
        if (!saved.isNullOrEmpty()) return saved
        val newId = "and-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        try { prefs.edit().putString("device_id", newId).apply() } catch (_: Exception) {}
        return newId
    }

    /**
     * N-1修复: 获取本机当前 WiFi 局域网 IPv4 地址，用于 phone_hello 上报给 PC，
     * 让 PC 在云端模式下也能记录手机 LAN IP，未来通过 UDP wake_connect 唤醒。
     *
     * #19 修复: 仅匹配 wlan/eth 接口在纯蜂窝场景下会返回 null。改为：
     * 优先 wlan/eth/en；其次任何非 loopback、非 link-local 的 IPv4 接口（包含蜂窝接口）；
     * 蜂窝 IP 虽然 PC 端无法直接 UDP 触达，但记录下来在 4G/5G 切回 WiFi 期间也不至于丢失"曾经在线"信息。
     */
    private fun getLocalWifiIp(): String? {
        return try {
            val nets = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            val all = java.util.Collections.list(nets)
            // 第一优先级：wlan/eth/en
            for (ni in all) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val name = (ni.name ?: "").lowercase()
                val isLan = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")
                if (!isLan) continue
                for (addr in java.util.Collections.list(ni.inetAddresses)) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress
                        && !addr.isLinkLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            // 第二优先级：任何非 loopback、非 link-local 的 IPv4（覆盖蜂窝）
            for (ni in all) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                for (addr in java.util.Collections.list(ni.inetAddresses)) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress
                        && !addr.isLinkLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }
}
