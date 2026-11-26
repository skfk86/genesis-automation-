package com.genesis.app.hidden

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.genesis.app.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Connection to the privileged host through Shizuku. Tracks Shizuku's state, binds the user
 * service, notices when it dies (Shizuku stopped, phone rebooted, process killed) and reconnects
 * when Shizuku comes back. Every change is kept in [events] for the report.
 */
object HiddenDisplayManager {

    enum class State { SHIZUKU_NOT_INSTALLED, SHIZUKU_NOT_RUNNING, SHIZUKU_TOO_OLD, NO_PERMISSION, CONNECTING, CONNECTED }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val TAG = "GenesisHidden"
    private const val PERMISSION_REQUEST = 4207

    @Volatile var host: IHiddenDisplayHost? = null
        private set
    @Volatile var state: State = State.SHIZUKU_NOT_RUNNING
        private set
    @Volatile var mode: String = "none"
        private set

    /** Timestamped connection events, oldest first (no app content). */
    val events: MutableList<String> = CopyOnWriteArrayList()
    private val lostListeners = CopyOnWriteArrayList<(String) -> Unit>()
    @Volatile private var lostAt = 0L
    @Volatile private var autoReconnect = true
    private var initialized = false
    @Volatile private var binding = false
    private lateinit var appContext: Context

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, HiddenDisplayUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("hidden_display")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                log("user service connected without a live binder")
                return
            }
            binding = false
            host = IHiddenDisplayHost.Stub.asInterface(binder)
            mode = "shizuku"
            state = State.CONNECTED
            val since = if (lostAt > 0) " (reconnected ${SystemClock.uptimeMillis() - lostAt} ms after loss)" else ""
            lostAt = 0
            log("host connected$since")
            runCatching { binder.linkToDeath({ onLost("host process died") }, 0) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onLost("host disconnected")
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        log("Shizuku is running (version ${runCatching { Shizuku.getVersion() }.getOrDefault(-1)})")
        refresh()
        if (autoReconnect && state == State.NO_PERMISSION) log("waiting for Shizuku permission")
        if (autoReconnect && hasPermission()) connect()
    }
    private val binderDead = Shizuku.OnBinderDeadListener { onLost("Shizuku stopped") }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        log(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku permission granted" else "Shizuku permission denied")
        refresh()
        if (result == PackageManager.PERMISSION_GRANTED) connect()
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh(): State {
        if (mode == "local" && host != null) return State.CONNECTED.also { state = it }
        state = when {
            !isShizukuInstalled() && !Shizuku.pingBinder() -> State.SHIZUKU_NOT_INSTALLED
            !Shizuku.pingBinder() -> State.SHIZUKU_NOT_RUNNING
            Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> State.SHIZUKU_TOO_OLD
            !hasPermission() -> State.NO_PERMISSION
            host != null -> State.CONNECTED
            else -> State.CONNECTING
        }
        if (state == State.CONNECTING && !binding && autoReconnect) connect()
        return state
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !hasPermission()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    fun connect() {
        if (host != null || binding || !Shizuku.pingBinder() || !hasPermission()) return
        state = State.CONNECTING
        autoReconnect = true
        binding = true
        try {
            Shizuku.bindUserService(args, connection)
            log("binding host")
        } catch (t: Throwable) {
            binding = false
            log("bind failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Ends the host. With [kill] the host process is removed, the same as losing Shizuku, which
     * the loss test uses; [reconnect] decides whether to bind again automatically.
     */
    fun disconnect(kill: Boolean, reconnect: Boolean) {
        autoReconnect = reconnect
        if (mode == "shizuku") runCatching { Shizuku.unbindUserService(args, connection, kill) }
        onLost(if (kill) "host removed on purpose (test)" else "host unbound")
    }

    /** For emulator tests: a host running inside the test process. */
    fun useLocalHost(h: IHiddenDisplayHost?) {
        host = h
        mode = if (h != null) "local" else "none"
        state = if (h != null) State.CONNECTED else State.SHIZUKU_NOT_RUNNING
        log(if (h != null) "local host attached" else "local host detached")
    }

    /** Simulates a lost host in tests that use a local host. */
    fun simulateLoss(reason: String) = onLost(reason)

    fun addLostListener(l: (String) -> Unit) { lostListeners += l }
    fun removeLostListener(l: (String) -> Unit) { lostListeners -= l }

    private fun onLost(reason: String) {
        if (host == null && state != State.CONNECTING) {
            log("lost signal while not connected: $reason")
        }
        host = null
        binding = false
        if (lostAt == 0L) lostAt = SystemClock.uptimeMillis()
        if (mode != "local") state = if (Shizuku.pingBinder()) State.CONNECTING else State.SHIZUKU_NOT_RUNNING
        log("host lost: $reason")
        lostListeners.forEach { runCatching { it(reason) } }
        if (mode == "shizuku" && autoReconnect && Shizuku.pingBinder() && hasPermission()) {
            // Shizuku itself is still there: the host process died, so start a new one.
            connect()
        }
    }

    fun hasPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun shizukuVersion(): Int = runCatching { if (Shizuku.pingBinder()) Shizuku.getVersion() else -1 }.getOrDefault(-1)

    private fun isShizukuInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
    }.getOrDefault(false)

    private fun log(msg: String) {
        val line = "${System.currentTimeMillis()} $msg"
        events += line
        while (events.size > 200) events.removeAt(0)
        Log.i(TAG, msg)
    }
}
