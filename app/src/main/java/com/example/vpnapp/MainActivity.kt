plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.vpnapp"
    compileSdk = 34
    defaultConfig { applicationId = "com.example.vpnapp"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core-ktx:1.13.1")
}

=== app/src/main/AndroidManifest.xml ===
<?xml version="1.0" encoding="utf-8"?>


=== app/src/main/java/com/example/vpnapp/VlessConfig.kt ===
package com.example.vpnapp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class VlessServer(
    val link: String, val id: String, val host: String, val port: Int,
    val network: String, val security: String, val hostHeader: String,
    val path: String, val sni: String, val name: String
) {
    fun toXrayJson(): String {
        val stream = JSONObject().put("network", network).put("security", security)
        if (network == "ws") stream.put("wsSettings", JSONObject().put("path", path)
            .put("headers", JSONObject().put("Host", hostHeader)))
        if (security == "tls") stream.put("tlsSettings", JSONObject().put("serverName", sni.ifEmpty { hostHeader }))
        val vless = JSONObject().put("tag", "proxy").put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", host).put("port", port)
                .put("users", JSONArray().put(JSONObject().put("id", id).put("encryption", "none"))))))
            .put("streamSettings", stream)
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
            .put("inbounds", JSONArray().put(JSONObject().put("tag", "socks").put("listen", "127.0.0.1")
                .put("port", 10808).put("protocol", "socks")
                .put("settings", JSONObject().put("udp", true))))
            .put("outbounds", JSONArray().put(vless)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .toString()
    }
    companion object {
        fun parse(raw: String): VlessServer? = try {
            val link = raw.trim()
            require(link.startsWith("vless://"))
            val u = URI(link)
            val q = (u.rawQuery ?: "").split("&").filter { it.contains("=") }.associate {
                val (k, v) = it.split("=", limit = 2); k to URLDecoder.decode(v, "UTF-8") }
            VlessServer(link, u.userInfo, u.host, u.port, q["type"] ?: "tcp", q["security"] ?: "none",
                q["host"] ?: u.host, q["path"] ?: "/", q["sni"] ?: "",
                URLDecoder.decode(u.rawFragment ?: u.host, "UTF-8"))
        } catch (e: Exception) { null }
    }
}

=== app/src/main/java/com/example/vpnapp/LogStore.kt ===
package com.example.vpnapp
import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object LogStore {
    private val lines = ArrayDeque<String>()
    var listener: (() -> Unit)? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun prefs(c: Context) = c.getSharedPreferences("logs", Context.MODE_PRIVATE)

    fun load(c: Context) {
        lines.clear()
        prefs(c).getString("l", "")!!.split("\n").filter { it.isNotBlank() }.forEach { lines.add(it) }
    }
    @Synchronized fun add(c: Context, msg: String) {
        lines.addFirst("[${fmt.format(Date())}] $msg")
        while (lines.size > 300) lines.removeLast()
        prefs(c).edit().putString("l", lines.joinToString("\n")).apply()
        listener?.invoke()
    }
    @Synchronized fun clear(c: Context) { lines.clear(); prefs(c).edit().clear().apply(); listener?.invoke() }
    @Synchronized fun text(): String = if (lines.isEmpty()) "لا توجد سجلات" else lines.joinToString("\n")
}

=== app/src/main/java/com/example/vpnapp/XrayVpnService.kt ===
package com.example.vpnapp
import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class XrayVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var core: CoreController? = null

    private val handler = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long { s?.let { LogStore.add(this@XrayVpnService, it) }; return 0 }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        val server = VlessServer.parse(intent?.getStringExtra("link") ?: "") ?: run {
            LogStore.add(this, "رابط غير صالح"); stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification())
        Thread { startVpn(server) }.start()
        return START_STICKY
    }

    private fun startVpn(s: VlessServer) {
        try {
            LogStore.add(this, "بدء الاتصال بـ ${s.name} (${s.host}:${s.port})")
            tun = Builder().setSession("VPN").setMtu(1500)
                .addAddress("10.10.10.2", 30).addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName).establish()
                ?: throw IllegalStateException("تعذر إنشاء واجهة TUN")
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            core = Libv2ray.newCoreController(handler)
            core!!.startLoop(s.toXrayJson(), tun!!.fd)
            running = true
            LogStore.add(this, "تم الاتصال")
        } catch (e: Throwable) {
            LogStore.add(this, "فشل الاتصال: ${e.message}")
            stopVpn()
        }
    }

    private fun stopVpn() {
        try { core?.stopLoop() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Throwable) {}
        tun = null; core = null; running = false
        LogStore.add(this, "تم قطع الاتصال")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { if (running) stopVpn(); super.onDestroy() }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "vpn").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("VPN متصل").setContentIntent(pi).setOngoing(true).build()
    }

    companion object {
        const val ACTION_STOP = "stop"
        @Volatile var running = false
    }
}

=== app/src/main/java/com/example/vpnapp/MainActivity.kt ===
package com.example.vpnapp
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import java.net.InetSocketAddress
import java.net.Socket

const val DEFAULT_LINK = "vless://5812fa9d-81fd-4f45-bcdb-628db1508e15@81.168.70.210:8080?encryption=none&host=www.pubgmobile.com&path=%2F&security=none&type=ws#%D8%A7%D9%88%D9%88%D8%AF%D9%8A-vbn"

class MainActivity : Activity() {
    private val links = mutableListOf<String>()
    private val ms = mutableMapOf<String, String>()
    private var sel = 0
    private lateinit var list: LinearLayout
    private lateinit var btn: Button
    private lateinit var logView: TextView
    private lateinit var input: EditText
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        links.addAll(prefs.getString("links", DEFAULT_LINK)!!.split("\n").filter { it.isNotBlank() })
        LogStore.load(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        btn = Button(this).apply { textSize = 18f; setOnClickListener { toggle() } }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pingAll = Button(this).apply { text = "قياس زمن استجابة الكل"; setOnClickListener { links.indices.forEach { pingOne(it) } } }
        input = EditText(this).apply { hint = "vless://..."; textDirection = View.TEXT_DIRECTION_LTR }
        val add = Button(this).apply { text = "إضافة خادم"; setOnClickListener { addLink() } }
        val clr = Button(this).apply { text = "مسح السجلات"; setOnClickListener { LogStore.clear(this@MainActivity) } }
        logView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR }
        listOf(btn, TextView(this).apply { text = "الخوادم"; textSize = 16f; setPadding(0, 24, 0, 8) }, list, pingAll, input, add,
            TextView(this).apply { text = "سجلات الاتصال"; textSize = 16f; setPadding(0, 24, 0, 8) }, clr, logView)
            .forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
        LogStore.listener = { runOnUiThread { logView.text = LogStore.text(); refreshBtn() } }
        render(); logView.text = LogStore.text(); refreshBtn()
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startVpn() else LogStore.add(this, "تم رفض إذن VPN")
    }

    private fun toggle() {
        if (XrayVpnService.running) {
            startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP)); return
        }
        val i = VpnService.prepare(this)
        if (i != null) vpnPermission.launch(i) else startVpn()
    }
    private fun startVpn() = startForegroundService(Intent(this, XrayVpnService::class.java).putExtra("link", links[sel]))

    private fun refreshBtn() { btn.text = if (XrayVpnService.running) "قطع...
