package com.shzxm.teslabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class BridgeService : Service() {

    companion object {
        const val ACTION_START = "com.shzxm.teslabridge.START"
        const val ACTION_STOP = "com.shzxm.teslabridge.STOP"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_CF_TOKEN = "cf_token"
        const val EXTRA_VIRTUAL_IP = "virtual_ip"
        const val EXTRA_LISTEN_PORT = "listen_port"
        const val EXTRA_TARGET_IP = "target_ip"
        const val EXTRA_TARGET_PORT = "target_port"
        private const val CHANNEL_ID = "tesla_bridge_channel"
        private const val NOTIF_ID = 1001

        @Volatile
        var isRunning: Boolean = false
    }

    private var caddyProcess: Process? = null
    private var activeVirtualIp: String = "100.64.0.1"
    private var activeListenPort: Int = 8443

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val domain = intent.getStringExtra(EXTRA_DOMAIN) ?: return START_NOT_STICKY
                val cfToken = intent.getStringExtra(EXTRA_CF_TOKEN) ?: ""
                val virtualIp = intent.getStringExtra(EXTRA_VIRTUAL_IP) ?: "100.64.0.1"
                val listenPort = intent.getIntExtra(EXTRA_LISTEN_PORT, 8443)
                val targetIp = intent.getStringExtra(EXTRA_TARGET_IP) ?: return START_NOT_STICKY
                val targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, 9888)
                startBridge(domain, cfToken, virtualIp, listenPort, targetIp, targetPort)
            }
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startBridge(
        domain: String,
        cfToken: String,
        virtualIp: String,
        listenPort: Int,
        targetIp: String,
        targetPort: Int
    ) {
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("运行中: https://$domain:$listenPort -> $targetIp:$targetPort"))

        Thread {
            try {
                stopCaddyOnly()
                activeVirtualIp = virtualIp
                activeListenPort = listenPort

                LogBus.log("检查 Root 权限...")
                if (!RootShell.hasRoot()) {
                    LogBus.log("错误：未获取到 Root 权限，请检查 Magisk / KernelSU 授权")
                    stopSelf()
                    return@Thread
                }

                LogBus.log("下发 iptables 规则: $virtualIp:$listenPort -> 本机:$listenPort")
                val ipt = RootShell.applyIptables(virtualIp, listenPort)
                if (ipt.exitCode != 0) {
                    LogBus.log("iptables 失败: ${ipt.output}")
                    stopSelf()
                    return@Thread
                }

                val caddyBin = File(applicationInfo.nativeLibraryDir, "libcaddy.so")
                if (!caddyBin.exists()) {
                    LogBus.log("错误：未找到内置 Caddy (${caddyBin.absolutePath})")
                    stopSelf()
                    return@Thread
                }

                val dataDir = File(filesDir, "caddy_data").apply { mkdirs() }
                val configDir = File(filesDir, "caddy_config").apply { mkdirs() }
                val caddyfile = File(filesDir, "Caddyfile")

                caddyfile.writeText(
                    """
                    {
                        admin off
                        storage file_system ${dataDir.absolutePath}
                    }

                    $domain:$listenPort {
                        tls {
                            dns cloudflare {env.CLOUDFLARE_API_TOKEN}
                        }
                        reverse_proxy http://$targetIp:$targetPort {
                            flush_interval -1
                        }
                    }
                    """.trimIndent()
                )

                val pb = ProcessBuilder(caddyBin.absolutePath, "run", "--config", caddyfile.absolutePath)
                pb.environment()["CLOUDFLARE_API_TOKEN"] = cfToken
                pb.environment()["XDG_DATA_HOME"] = dataDir.absolutePath
                pb.environment()["XDG_CONFIG_HOME"] = configDir.absolutePath
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.redirectErrorStream(true)

                LogBus.log("启动内置 Caddy，首次启动将自动通过 Cloudflare DNS-01 申请 Let's Encrypt 证书...")
                val proc = pb.start()
                caddyProcess = proc
                isRunning = true

                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        LogBus.log("[Caddy] $line")
                    }
                }
                val code = proc.waitFor()
                LogBus.log("Caddy 已退出 (code=$code)")
            } catch (e: Exception) {
                LogBus.log("启动异常: ${e.message}")
            } finally {
                isRunning = false
            }
        }.start()
    }

    private fun stopCaddyOnly() {
        caddyProcess?.destroy()
        caddyProcess = null
    }

    private fun stopBridge() {
        stopCaddyOnly()
        isRunning = false
        Thread {
            RootShell.cleanupIptables(activeVirtualIp, activeListenPort)
            LogBus.log("已停止 Caddy 并清理 iptables 规则")
        }.start()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopBridge()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Tesla CarWithin Bridge",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TeslaCarWithinBridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
