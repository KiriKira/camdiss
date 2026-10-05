package com.kirikira.camdiss

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PairingService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectAttemptInProgress = AtomicBoolean(false)

    private var pairingMdns: AdbMdns? = null
    private var connectMdns: AdbMdns? = null
    private var pairingHost = "127.0.0.1"
    private var pairingPort = -1

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startConnectionFlow()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                val host = intent.getStringExtra(EXTRA_HOST) ?: pairingHost
                val port = intent.getIntExtra(EXTRA_PORT, pairingPort)
                pairAndApply(host, port, code)
            }
            ACTION_STOP -> stopAndRemove()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startConnectionFlow() {
        stopDiscovery()
        FlowStateStore.write(this, FlowState(FlowStatus.SEARCHING))
        startForegroundCompat(searchingNotification())

        executor.execute {
            val adb = CameraAdbManager.getInstance(applicationContext)
            try {
                runCatching { adb.disconnect() }
                if (adb.connectTls(this, EXISTING_CONNECT_TIMEOUT_MS)) {
                    applyAndVerify(adb)
                    return@execute
                }
            } catch (_: Throwable) {
                // No authorised connection yet, or Wireless debugging is currently off.
                // Fall through to the Settings-assisted discovery flow.
            } finally {
                if (!adb.isConnected()) {
                    runCatching { adb.disconnect() }
                }
            }

            mainHandler.post { waitForWirelessDebugging() }
        }
    }

    private fun waitForWirelessDebugging() {
        FlowStateStore.write(this, FlowState(FlowStatus.WAITING_FOR_WIRELESS))
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            waitingForWirelessNotification(),
        )
        startConnectDiscovery()
        startPairingDiscovery()
    }

    private fun startConnectDiscovery() {
        connectMdns?.stop()
        connectMdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { host, port ->
            if (host == null || port <= 0 || !connectAttemptInProgress.compareAndSet(false, true)) {
                return@AdbMdns
            }

            val hostAddress = host.hostAddress ?: "127.0.0.1"
            executor.execute {
                val adb = CameraAdbManager.getInstance(applicationContext)
                try {
                    runCatching { adb.disconnect() }
                    if (adb.connect(hostAddress, port)) {
                        stopDiscovery()
                        applyAndVerify(adb)
                    }
                } catch (_: Throwable) {
                    // The TLS endpoint exists but this key is not authorised yet.
                    // Keep listening: opening the system pairing dialog will trigger
                    // SERVICE_TYPE_TLS_PAIRING and allow the user to enter a code.
                    runCatching { adb.disconnect() }
                } finally {
                    connectAttemptInProgress.set(false)
                }
            }
        }.also { it.start() }
    }

    private fun startPairingDiscovery() {
        pairingMdns?.stop()
        pairingMdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (host != null && port > 0) {
                pairingHost = host.hostAddress ?: "127.0.0.1"
                pairingPort = port
                FlowStateStore.write(this, FlowState(FlowStatus.CODE_REQUIRED))
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    pairingCodeNotification(pairingHost, pairingPort),
                )
            }
        }.also { it.start() }
    }

    private fun pairAndApply(host: String, port: Int, code: String) {
        if (port <= 0 || !code.matches(Regex("\\d{6}"))) {
            fail("Invalid pairing port or code. Open the pairing dialog again and enter its 6-digit code.")
            return
        }

        stopDiscovery()
        FlowStateStore.write(this, FlowState(FlowStatus.PAIRING))
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, progressNotification(R.string.notification_pairing_title))

        executor.execute {
            val adb = CameraAdbManager.getInstance(applicationContext)
            try {
                runCatching { adb.disconnect() }

                val paired = adb.pair(host, port, code)
                if (!paired) error("ADB pairing was rejected")

                var connected = false
                var lastError: Throwable? = null
                repeat(3) {
                    if (!connected) {
                        try {
                            connected = adb.connectTls(this, 8_000)
                        } catch (t: Throwable) {
                            lastError = t
                            Thread.sleep(500)
                        }
                    }
                }
                if (!connected) {
                    throw lastError ?: IllegalStateException("Could not connect to local ADB after pairing")
                }

                applyAndVerify(adb)
            } catch (t: Throwable) {
                fail(t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { adb.disconnect() }
            }
        }
    }

    private fun applyAndVerify(adb: CameraAdbManager) {
        FlowStateStore.write(this, FlowState(FlowStatus.APPLYING))
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, progressNotification(R.string.notification_applying_title))

        runShellAndWait(adb, DISABLE_COMMAND)

        var value = ""
        repeat(3) {
            if (value != "0") {
                value = runShellForSingleLine(adb, VERIFY_COMMAND).trim()
                if (value != "0") Thread.sleep(100)
            }
        }
        if (value != "0") error("Verification returned '$value' instead of '0'")

        FlowStateStore.write(this, FlowState(FlowStatus.SUCCESS))
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            finalNotification(
                R.string.notification_success_title,
                getString(R.string.notification_success_text),
            ),
        )
        stopSelf()
    }

    /**
     * Execute a command that is expected not to produce output. ADB shell v1 reports
     * normal remote process termination by closing the stream, so stream closure here
     * is success, not an IOException to surface to the user.
     */
    private fun runShellAndWait(adb: CameraAdbManager, command: String) {
        val stream: AdbStream = adb.openStream("shell:$command")
        try {
            val deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS
            while (!stream.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(SHELL_POLL_MS)
            }
            if (!stream.isClosed) {
                throw IOException("ADB shell command timed out")
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * Read only the first line without calling InputStream.readBytes(). libadb's
     * AdbStream throws "Stream closed." after the peer has normally sent CLSE, so
     * polling available data avoids mistaking a normal shell EOF for a command error.
     */
    private fun runShellForSingleLine(adb: CameraAdbManager, command: String): String {
        val stream: AdbStream = adb.openStream("shell:$command")
        val output = ByteArrayOutputStream()
        try {
            val deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val available = try {
                    stream.available()
                } catch (e: IOException) {
                    if (stream.isClosed) 0 else throw e
                }

                if (available > 0) {
                    val buffer = ByteArray(minOf(available, 4096))
                    val count = try {
                        stream.read(buffer, 0, buffer.size)
                    } catch (e: IOException) {
                        if (stream.isClosed) -1 else throw e
                    }
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        if (output.toString(StandardCharsets.UTF_8.name()).contains('\n')) {
                            break
                        }
                    }
                } else if (stream.isClosed) {
                    break
                } else {
                    Thread.sleep(SHELL_POLL_MS)
                }
            }
            return output.toString(StandardCharsets.UTF_8.name())
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun fail(message: String) {
        stopDiscovery()
        FlowStateStore.write(this, FlowState(FlowStatus.ERROR, message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            finalNotification(R.string.notification_failed_title, message),
        )
        stopSelf()
    }

    private fun stopAndRemove() {
        stopDiscovery()
        FlowStateStore.write(this, FlowState(FlowStatus.IDLE))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopDiscovery() {
        pairingMdns?.stop()
        pairingMdns = null
        connectMdns?.stop()
        connectMdns = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun baseBuilder(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())

    private fun searchingNotification(): Notification =
        baseBuilder()
            .setContentTitle(getString(R.string.notification_searching_title))
            .setContentText(getString(R.string.notification_searching_text))
            .setOngoing(true)
            .addAction(stopAction())
            .build()

    private fun waitingForWirelessNotification(): Notification =
        baseBuilder()
            .setContentTitle(getString(R.string.notification_waiting_wireless_title))
            .setContentText(getString(R.string.notification_waiting_wireless_text))
            .setOngoing(true)
            .addAction(stopAction())
            .build()

    private fun pairingCodeNotification(host: String, port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(getString(R.string.notification_code_hint))
            .build()
        val replyIntent = Intent(this, PairingService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_PORT, port)
        val replyPendingIntent = PendingIntent.getForegroundService(
            this,
            REQUEST_REPLY,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
        )
        val replyAction = Notification.Action.Builder(
            null,
            getString(R.string.notification_enter_code),
            replyPendingIntent,
        ).addRemoteInput(remoteInput).build()

        return baseBuilder()
            .setContentTitle(getString(R.string.notification_pair_found_title))
            .setContentText(getString(R.string.notification_pair_found_text))
            .setOngoing(true)
            .addAction(replyAction)
            .addAction(stopAction())
            .build()
    }

    private fun progressNotification(title: Int): Notification =
        baseBuilder()
            .setContentTitle(getString(title))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()

    private fun finalNotification(title: Int, text: String): Notification =
        baseBuilder()
            .setContentTitle(getString(title))
            .setContentText(text)
            .setAutoCancel(true)
            .build()

    private fun stopAction(): Notification.Action {
        val pendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, PairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            null,
            getString(R.string.notification_stop),
            pendingIntent,
        ).build()
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    override fun onDestroy() {
        stopDiscovery()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATUS_CHANGED = "com.kirikira.camdiss.STATUS_CHANGED"

        private const val CHANNEL_ID = "wireless_adb_pairing"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_REPLY = 1002
        private const val REQUEST_STOP = 1003
        private const val REMOTE_INPUT_KEY = "pairing_code"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"

        private const val ACTION_START = "com.kirikira.camdiss.START"
        private const val ACTION_REPLY = "com.kirikira.camdiss.REPLY"
        private const val ACTION_STOP = "com.kirikira.camdiss.STOP"

        private const val EXISTING_CONNECT_TIMEOUT_MS = 2_500L
        private const val SHELL_TIMEOUT_MS = 4_000L
        private const val SHELL_POLL_MS = 20L

        private const val DISABLE_COMMAND =
            "settings put system csc_pref_camera_forced_shuttersound_key 0"
        private const val VERIFY_COMMAND =
            "settings get system csc_pref_camera_forced_shuttersound_key"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, PairingService::class.java).setAction(ACTION_START)
            )
        }
    }
}
