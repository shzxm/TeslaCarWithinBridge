package com.shzxm.teslabridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etRootDomain: TextInputEditText
    private lateinit var etSubDomain: TextInputEditText
    private lateinit var etCfToken: TextInputEditText
    private lateinit var etVirtualIp: TextInputEditText
    private lateinit var etListenPort: TextInputEditText
    private lateinit var etTargetIp: TextInputEditText
    private lateinit var etTargetPort: TextInputEditText
    private lateinit var btnToggle: MaterialButton

    private val logListener: (String) -> Unit = { text ->
        runOnUiThread {
            tvLog.text = text
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()
        etRootDomain = findViewById(R.id.etRootDomain)
        etSubDomain = findViewById(R.id.etSubDomain)
        etCfToken = findViewById(R.id.etCfToken)
        etVirtualIp = findViewById(R.id.etVirtualIp)
        etListenPort = findViewById(R.id.etListenPort)
        etTargetIp = findViewById(R.id.etTargetIp)
        etTargetPort = findViewById(R.id.etTargetPort)
        btnToggle = findViewById(R.id.btnToggle)

        loadPrefs()
        requestNotificationPermissionIfNeeded()

        findViewById<MaterialButton>(R.id.btnScan).setOnClickListener { scanHotspotDevices() }
        findViewById<MaterialButton>(R.id.btnUpdateDns).setOnClickListener { updateCloudflareDns() }
        findViewById<MaterialButton>(R.id.btnCopyUrl).setOnClickListener { copyAccessUrl() }
        btnToggle.setOnClickListener { toggleService() }

        LogBus.subscribe(logListener)
        refreshStatus()
    }

    override fun onDestroy() {
        LogBus.unsubscribe(logListener)
        scope.cancel()
        super.onDestroy()
    }

    private fun loadPrefs() {
        val sp = getSharedPreferences("bridge_cfg", Context.MODE_PRIVATE)
        etRootDomain.setText(sp.getString("root_domain", "shzxm.com"))
        etSubDomain.setText(sp.getString("sub_domain", "tesla.shzxm.com"))
        etCfToken.setText(sp.getString("cf_token", ""))
        etVirtualIp.setText(sp.getString("virtual_ip", "100.64.0.1"))
        etListenPort.setText(sp.getString("listen_port", "8443"))
        etTargetIp.setText(sp.getString("target_ip", ""))
        etTargetPort.setText(sp.getString("target_port", "9888"))
    }

    private fun savePrefs() {
        getSharedPreferences("bridge_cfg", Context.MODE_PRIVATE).edit()
            .putString("root_domain", etRootDomain.text.toString().trim())
            .putString("sub_domain", etSubDomain.text.toString().trim())
            .putString("cf_token", etCfToken.text.toString().trim())
            .putString("virtual_ip", etVirtualIp.text.toString().trim())
            .putString("listen_port", etListenPort.text.toString().trim())
            .putString("target_ip", etTargetIp.text.toString().trim())
            .putString("target_port", etTargetPort.text.toString().trim())
            .apply()
    }

    private fun refreshStatus() {
        if (BridgeService.isRunning) {
            val url = "https://${etSubDomain.text}:${etListenPort.text}"
            tvStatus.text = "状态：运行中 ($url)"
            btnToggle.text = "停止服务"
        } else {
            tvStatus.text = "状态：未运行"
            btnToggle.text = "启动服务 (Root + Caddy)"
        }
    }

    private fun scanHotspotDevices() {
        scope.launch {
            LogBus.log("正在通过 Root 扫描热点连接设备...")
            val peers = withContext(Dispatchers.IO) { RootShell.scanHotspotPeers() }
            if (peers.isEmpty()) {
                LogBus.log("未扫描到热点客户端，请确认已开启热点且主 iPhone 已连上")
                Toast.makeText(this@MainActivity, "未发现热点设备", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = peers.map { "${it.ip}  (${it.mac} / ${it.iface})" }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("选择主 iPhone (CarWithin)")
                .setItems(labels) { _, which ->
                    etTargetIp.setText(peers[which].ip)
                    savePrefs()
                    LogBus.log("已选择目标设备: ${peers[which].ip} (${peers[which].mac})")
                }
                .show()
        }
    }

    private fun updateCloudflareDns() {
        savePrefs()
        val root = etRootDomain.text.toString().trim()
        val sub = etSubDomain.text.toString().trim()
        val token = etCfToken.text.toString().trim()
        val vip = etVirtualIp.text.toString().trim()
        if (root.isEmpty() || sub.isEmpty() || token.isEmpty() || vip.isEmpty()) {
            Toast.makeText(this, "请先填写域名、CF Token 和虚拟 IP", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            LogBus.log("正在更新 Cloudflare DNS: $sub -> $vip ...")
            val result = withContext(Dispatchers.IO) {
                CloudflareApi.upsertARecord(token, root, sub, vip)
            }
            result.onSuccess {
                LogBus.log(it)
                Toast.makeText(this@MainActivity, "DNS 更新成功", Toast.LENGTH_SHORT).show()
            }.onFailure {
                LogBus.log("DNS 更新失败: ${it.message}")
                Toast.makeText(this@MainActivity, "DNS 更新失败，见日志", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleService() {
        savePrefs()
        if (BridgeService.isRunning) {
            startService(Intent(this, BridgeService::class.java).apply { action = BridgeService.ACTION_STOP })
            return
        }
        val sub = etSubDomain.text.toString().trim()
        val token = etCfToken.text.toString().trim()
        val vip = etVirtualIp.text.toString().trim().ifEmpty { "100.64.0.1" }
        val lPort = etListenPort.text.toString().trim().toIntOrNull() ?: 8443
        val tIp = etTargetIp.text.toString().trim()
        val tPort = etTargetPort.text.toString().trim().toIntOrNull() ?: 9888

        if (sub.isEmpty() || token.isEmpty() || tIp.isEmpty()) {
            Toast.makeText(this, "请填全访问域名、CF Token 和主 iPhone IP", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START
            putExtra(BridgeService.EXTRA_DOMAIN, sub)
            putExtra(BridgeService.EXTRA_CF_TOKEN, token)
            putExtra(BridgeService.EXTRA_VIRTUAL_IP, vip)
            putExtra(BridgeService.EXTRA_LISTEN_PORT, lPort)
            putExtra(BridgeService.EXTRA_TARGET_IP, tIp)
            putExtra(BridgeService.EXTRA_TARGET_PORT, tPort)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun copyAccessUrl() {
        val url = "https://${etSubDomain.text.toString().trim()}:${etListenPort.text.toString().trim()}"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TeslaURL", url))
        Toast.makeText(this, "已复制: $url", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }
}
