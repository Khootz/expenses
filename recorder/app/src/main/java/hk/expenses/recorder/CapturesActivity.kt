package hk.expenses.recorder

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Raw captured-notification log: permissions, live capture list, JSON export. */
class CapturesActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var countsView: TextView
    private lateinit var emptyView: TextView
    private lateinit var permissionButtons: View
    private lateinit var btnAccess: Button
    private lateinit var btnBattery: Button
    private lateinit var list: RecyclerView
    private val adapter = CaptureAdapter()

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captures)

        statusView = findViewById(R.id.status)
        countsView = findViewById(R.id.counts)
        emptyView = findViewById(R.id.empty)
        permissionButtons = findViewById(R.id.permissionButtons)
        btnAccess = findViewById(R.id.btnAccess)
        btnBattery = findViewById(R.id.btnBattery)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btnBattery.setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { export() }
        findViewById<Button>(R.id.btnPkgs).setOnClickListener { showPackagesSeen() }

        val sw = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchConfirm)
        sw.isChecked = Prefs.confirmTracked(this)
        sw.setOnCheckedChangeListener { _, checked ->
            Prefs.setConfirmTracked(this, checked)
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, refreshReceiver, IntentFilter(RecorderService.ACTION_NEW_CAPTURE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(refreshReceiver)
    }

    private fun refresh() {
        val accessOk = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        statusView.text = buildString {
            append(if (accessOk) "✅ Notification access on" else "❌ Notification access off — tap Grant access")
            append("\n")
            append(if (batteryOk) "✅ Running in background" else "⚠️ Battery optimization on — tap Allow background")
        }
        btnAccess.visibility = if (accessOk) View.GONE else View.VISIBLE
        btnBattery.visibility = if (batteryOk) View.GONE else View.VISIBLE
        permissionButtons.visibility = if (accessOk && batteryOk) View.GONE else View.VISIBLE

        val db = Db.get(this)
        val counts = db.countsByPackage()
        val total = counts.values.sum()
        countsView.text = if (total == 0) ""
        else "Captured: $total    " + counts.entries.joinToString("    ") { "${AppNames.shortName(it.key)} ${it.value}" }
        adapter.items = db.recent(100)
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (total == 0) View.VISIBLE else View.GONE
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun export() {
        val json = Db.get(this).allCapturesJson()
        val name = "notif-captures-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"

        var savedToDownloads = false
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    savedToDownloads = true
                }
            } catch (_: Exception) {
            }
        }

        val f = File(cacheDir, name)
        f.writeText(json)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Export captures"
            )
        )
        if (savedToDownloads) {
            Toast.makeText(this, "Also saved to Downloads/$name", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPackagesSeen() {
        val seen = Db.get(this).packagesSeen()
        val msg = if (seen.isEmpty()) "Nothing seen yet."
        else seen.take(40).joinToString("\n") { "${it.label ?: "?"}  (${it.pkg})  ×${it.count}" }
        AlertDialog.Builder(this)
            .setTitle("Other apps posting notifications")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    inner class CaptureAdapter : RecyclerView.Adapter<CaptureAdapter.VH>() {
        var items: List<Capture> = emptyList()
        private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val app: TextView = v.findViewById(R.id.rowApp)
            val header: TextView = v.findViewById(R.id.rowHeader)
            val title: TextView = v.findViewById(R.id.rowTitle)
            val body: TextView = v.findViewById(R.id.rowBody)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_capture, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            val tag = if (c.source == "tray_scrape") " · tray" else ""
            holder.app.text = AppNames.shortName(c.pkg)
            holder.app.setTextColor(AppNames.color(c.pkg))
            holder.header.text = fmt.format(Date(c.ts)) + tag
            holder.title.text = c.title ?: ""
            holder.title.visibility = if (c.title.isNullOrBlank()) View.GONE else View.VISIBLE
            val body = c.bigText ?: c.text ?: c.textLines ?: ""
            holder.body.text = body
            holder.body.visibility = if (body.isBlank()) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener {
                AlertDialog.Builder(this@CapturesActivity)
                    .setTitle("Add to transactions?")
                    .setMessage(
                        "Use this if you deleted a transaction by accident — " +
                            "it will be restored from this notification."
                    )
                    .setPositiveButton("Add") { _, _ ->
                        Toast.makeText(
                            this@CapturesActivity,
                            Ingest.reAdd(this@CapturesActivity, c),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
