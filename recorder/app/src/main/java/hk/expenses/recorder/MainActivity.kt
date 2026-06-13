package hk.expenses.recorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Main screen: monthly savings, category breakdown, transactions. */
class MainActivity : AppCompatActivity() {

    private lateinit var monthLabel: TextView
    private lateinit var savingsValue: TextView
    private lateinit var savingsSub: TextView
    private lateinit var budgetFill: View
    private lateinit var budgetRest: View
    private lateinit var burnRate: TextView
    private lateinit var catContainer: LinearLayout
    private lateinit var donut: DonutChartView
    private lateinit var heatmap: HeatmapView
    private lateinit var txnList: RecyclerView
    private lateinit var emptyTxns: TextView
    private lateinit var permBanner: TextView
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private var ym: YearMonth = YearMonth.now()

    /** First month with data — navigation floor (tracking started June 2026). */
    private fun minMonth(): YearMonth {
        val earliest = Db.get(this).earliestTxnTs() ?: return YearMonth.now()
        return YearMonth.from(
            java.time.Instant.ofEpochMilli(earliest).atZone(ZoneId.systemDefault())
        )
    }
    private val adapter = TxnAdapter()

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        monthLabel = findViewById(R.id.monthLabel)
        savingsValue = findViewById(R.id.savingsValue)
        savingsSub = findViewById(R.id.savingsSub)
        budgetFill = findViewById(R.id.budgetFill)
        budgetRest = findViewById(R.id.budgetRest)
        catContainer = findViewById(R.id.catContainer)
        donut = findViewById(R.id.donut)
        burnRate = findViewById(R.id.burnRate)
        heatmap = findViewById(R.id.heatmap)
        heatmap.onDayClick = { day -> showDayDetail(day) }
        txnList = findViewById(R.id.txnList)
        emptyTxns = findViewById(R.id.emptyTxns)
        permBanner = findViewById(R.id.permBanner)

        txnList.layoutManager = LinearLayoutManager(this)
        txnList.adapter = adapter

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        findViewById<MaterialButton>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, CapturesActivity::class.java))
        }
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnPrev.setOnClickListener {
            if (ym > minMonth()) { ym = ym.minusMonths(1); refresh() }
        }
        btnNext.setOnClickListener {
            if (ym < YearMonth.now()) { ym = ym.plusMonths(1); refresh() }
        }
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { addExpenseDialog() }
        permBanner.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // For the "tracked" confirmation notifications (Android 13+).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, refreshReceiver, IntentFilter(RecorderService.ACTION_NEW_CAPTURE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Ingest.backfill(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(refreshReceiver)
    }

    private fun fmt(cents: Long): String {
        val sign = if (cents < 0) "−" else ""
        val v = Math.abs(cents)
        return "${sign}HK$%,d.%02d".format(v / 100, v % 100)
    }

    private fun isTransferLike(t: Txn) = t.isTransfer || t.category == "Transfer"

    /** Not your spending: transfers, salary, and reimbursable Work (net-zero). */
    private fun excludedFromSpend(t: Txn) =
        isTransferLike(t) || t.category == "Income" || t.category == Categories.WORK

    private fun refresh() {
        val accessOk = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        permBanner.visibility = if (accessOk) View.GONE else View.VISIBLE

        // Keep within [first month with data, current month]; clamp if a
        // delete moved the floor past the currently viewed month.
        val floor = minMonth()
        if (ym < floor) ym = floor
        if (ym > YearMonth.now()) ym = YearMonth.now()
        btnPrev.isEnabled = ym > floor
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else 0.3f
        btnNext.isEnabled = ym < YearMonth.now()
        btnNext.alpha = if (btnNext.isEnabled) 1f else 0.3f

        monthLabel.text = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
        val zone = ZoneId.systemDefault()
        val from = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val txns = Db.get(this).txnsBetween(from, to)

        // Spending: outflows plus inflows assigned to a spending category
        // (a friend paying you back, tagged Food, reduces Food). Work is
        // reimbursable so it never lands here.
        val spendRows = txns.filter {
            !excludedFromSpend(it) && (it.amountCents > 0 || it.category != null)
        }
        val byCat = spendRows
            .groupBy { it.category ?: "Uncategorized" }
            .mapValues { e -> e.value.sumOf { it.amountCents } }
            .toList()
            .sortedByDescending { it.second }
        val spent = byCat.sumOf { it.second }
        // Money in not assigned to spending: salary, untagged incoming transfers.
        val moneyIn = txns
            .filter { !isTransferLike(it) && it.amountCents < 0 && (it.category == null || it.category == "Income") }
            .sumOf { -it.amountCents }

        val savings = Categories.SALARY_CENTS - spent
        savingsValue.text = fmt(savings)
        savingsValue.setTextColor(if (savings >= 0) 0xFFFFFFFF.toInt() else 0xFFFFB4AB.toInt())
        savingsSub.text = buildString {
            append("spent ${fmt(spent)} of HK$27,000 salary")
            if (moneyIn > 0) append("  ·  money in ${fmt(moneyIn)}")
        }

        // Budget gauge: width = fraction of salary spent; color crosses from
        // healthy green to amber (≥80%) to red (over budget).
        val frac = (spent.toFloat() / Categories.SALARY_CENTS).coerceIn(0f, 1f)
        budgetFill.setBackgroundColor(
            when {
                spent > Categories.SALARY_CENTS -> 0xFFEF5350.toInt()
                frac >= 0.8f -> 0xFFFFB300.toInt()
                else -> 0xFF4ADE80.toInt()
            }
        )
        (budgetFill.layoutParams as LinearLayout.LayoutParams).weight = frac
        (budgetRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - frac
        budgetFill.requestLayout()
        budgetRest.requestLayout()

        // Tracking only began when the first transaction landed. Days before
        // that — and days still in the future — aren't "spent nothing", they're
        // "no data", so the heatmap greys them out and the daily average divides
        // by days *actually tracked*, not by the calendar day-of-month.
        val zoneForDays = ZoneId.systemDefault()
        val trackingStart = Db.get(this).earliestTxnTs()?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zoneForDays).toLocalDate()
        }
        val monthFirst = ym.atDay(1)
        val today = java.time.LocalDate.now()
        val activeFrom =
            if (trackingStart != null && trackingStart.isAfter(monthFirst)) trackingStart else monthFirst
        val activeTo = if (ym == YearMonth.now()) today else ym.atEndOfMonth()

        // Heatmap shows day-to-day *discretionary* spend, so a once-a-month rent
        // payment doesn't swamp the color scale. Fixed costs go into the burn
        // rate instead (amortised across the month). Today's cell still fills in
        // live as you spend.
        val dayTotals = spendRows
            .filter { it.category != Categories.FIXED }
            .groupBy {
                java.time.Instant.ofEpochMilli(it.ts).atZone(zoneForDays).toLocalDate().dayOfMonth
            }
            .mapValues { e -> e.value.sumOf { it.amountCents }.coerceAtLeast(0) }
        heatmap.setData(ym, dayTotals, activeFrom.dayOfMonth, activeTo.dayOfMonth)

        // Daily burn rate averages only over *completed* days — today is still
        // in progress, so counting it would skew the figure all day long. It
        // rolls over once at midnight. Fixed costs are spread evenly across the
        // month (rent paid on the 1st reads the same as the 28th).
        val daysInMonth = ym.lengthOfMonth()
        val lastComplete = if (ym == YearMonth.now()) today.minusDays(1) else ym.atEndOfMonth()
        val completedDays =
            if (lastComplete.isBefore(activeFrom)) 0
            else (java.time.temporal.ChronoUnit.DAYS.between(activeFrom, lastComplete) + 1).toInt()
        val fixedSpent = spendRows.filter { it.category == Categories.FIXED }
            .sumOf { it.amountCents }.coerceAtLeast(0)
        val variableComplete = spendRows
            .filter { it.category != Categories.FIXED }
            .filter {
                !java.time.Instant.ofEpochMilli(it.ts).atZone(zoneForDays).toLocalDate()
                    .isAfter(lastComplete)
            }
            .sumOf { it.amountCents }.coerceAtLeast(0)
        if (completedDays <= 0) {
            // First day of tracking — no full day to average yet.
            burnRate.text = "≈ daily average starts after today"
            burnRate.setTextColor(0xFF888888.toInt())
        } else {
            val burnPerDay = variableComplete / completedDays + fixedSpent / daysInMonth
            val projected = variableComplete * daysInMonth / completedDays + fixedSpent
            burnRate.text = "≈ ${fmt(burnPerDay)}/day  ·  projected ${fmt(projected)} this month"
            burnRate.setTextColor(
                if (projected > Categories.SALARY_CENTS) 0xFFEF5350.toInt() else 0xFF30A46C.toInt()
            )
        }

        // Donut: positive net categories only; legend shows everything
        // (a negative net category appears in the legend as an offset).
        val positives = byCat.filter { it.second > 0 }
        donut.setData(
            positives.map { DonutChartView.Slice(it.first, it.second) },
            fmt(spent), "spent"
        )
        // Rebuild legend rows (children 0..1 are the title and the donut).
        while (catContainer.childCount > 2) catContainer.removeViewAt(2)
        if (byCat.isEmpty()) {
            val t = TextView(this)
            t.text = getString(R.string.no_spending)
            t.textSize = 13f
            t.setPadding(0, dp(8), 0, 0)
            catContainer.addView(t)
        } else {
            val posTotal = positives.sumOf { it.second }.coerceAtLeast(1)
            byCat.forEach { (cat, cents) ->
                val pct = if (cents > 0) " · ${100 * cents / posTotal}%" else ""
                addLegendRow(Categories.color(cat), cat, cents, pct)
            }
        }

        adapter.items = txns
        adapter.notifyDataSetChanged()
        emptyTxns.visibility = if (txns.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addLegendRow(color: Int, label: String, cents: Long, pct: String) {
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = android.view.Gravity.CENTER_VERTICAL
        line.setPadding(0, dp(8), 0, 0)

        // Colored dot keeps the legend mapped to the donut by color; the emoji
        // in the label is the redundant (color-blind-safe) second cue.
        val dot = TextView(this)
        dot.text = "●"
        dot.textSize = 13f
        dot.setTextColor(color)
        dot.setPadding(0, 0, dp(8), 0)

        val name = TextView(this)
        name.text = "${Categories.icon(label)} $label$pct"
        name.textSize = 13f

        val amt = TextView(this)
        amt.text = if (cents < 0) "+" + fmt(-cents) else fmt(cents)
        amt.textSize = 13f
        amt.setTypeface(null, android.graphics.Typeface.BOLD)
        if (cents < 0) amt.setTextColor(0xFF30A46C.toInt())

        line.addView(dot)
        line.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(amt)
        catContainer.addView(line)
    }

    private fun showDayDetail(day: Int) {
        val zone = ZoneId.systemDefault()
        val from = ym.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = from + 24 * 3600 * 1000
        val txns = Db.get(this).txnsBetween(from, to)
        val spend = txns
            .filter { !excludedFromSpend(it) && (it.amountCents > 0 || it.category != null) }
            .sumOf { it.amountCents }
        val date = ym.atDay(day).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH))
        val lines = if (txns.isEmpty()) "No transactions."
        else txns.joinToString("\n") { t ->
            val name = t.merchant ?: t.kind.replaceFirstChar { it.uppercase() }
            val amt = if (t.amountCents < 0) "+" + fmt(-t.amountCents) else fmt(t.amountCents)
            "$name  ·  ${t.category ?: "uncategorized"}  ·  $amt"
        }
        AlertDialog.Builder(this)
            .setTitle("$date — spent ${fmt(spend)}")
            .setMessage(lines)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Tap a transaction to edit it. Name, amount, and category are three
     * aligned outlined fields; category is a dropdown (opens up or down to
     * fit). Save commits all three; Delete sits on the left as its own button.
     */
    private fun txnActionDialog(t: Txn) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_txn, null)
        val name = view.findViewById<TextInputEditText>(R.id.editName)
        val amount = view.findViewById<TextInputEditText>(R.id.editAmount)
        val category = view.findViewById<MaterialAutoCompleteTextView>(R.id.editCategory)

        name.setText(t.merchant ?: t.kind.replaceFirstChar { it.uppercase() })
        name.setSelectAllOnFocus(true)
        amount.setText("%.2f".format(Math.abs(t.amountCents) / 100.0))

        category.setSimpleItems(Categories.ALL.toTypedArray())
        if (t.category != null) category.setText(t.category, false)

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit transaction")
            .setView(view)
            .setNeutralButton("Delete") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setMessage("Delete this transaction (${fmt(t.amountCents)})?")
                    .setPositiveButton("Delete") { _, _ -> Db.get(this).deleteTxn(t.id); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val hkd = amount.text.toString().toDoubleOrNull()
                val sign = if (t.amountCents < 0) -1 else 1
                Db.get(this).updateTxnDetails(
                    t.id,
                    name.text.toString().trim().ifBlank { null },
                    if (hkd != null) sign * Math.round(hkd * 100) else t.amountCents
                )
                val cat = category.text.toString().takeIf { it in Categories.ALL }
                if (cat != null && cat != t.category) Db.get(this).setTxnCategory(t.id, cat)
                refresh()
            }
            .show()
    }

    private fun addExpenseDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val amount = EditText(this)
        amount.hint = getString(R.string.hint_amount)
        amount.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val note = EditText(this)
        note.hint = getString(R.string.hint_note)
        val catSpinner = Spinner(this)
        catSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Categories.ALL.filter { it != "Income" && it != "Transfer" }
        )
        val methods = listOf("Cash", "AlipayHK", "Mox", "Octopus", "WeChat", "BOCHK", "HangSeng", "Other")
        val srcSpinner = Spinner(this)
        srcSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, methods
        )
        val srcLabel = TextView(this)
        srcLabel.text = getString(R.string.paid_with)
        srcLabel.textSize = 12f
        srcLabel.setPadding(0, dp(8), 0, 0)
        box.addView(amount)
        box.addView(note)
        box.addView(catSpinner)
        box.addView(srcLabel)
        box.addView(srcSpinner)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_expense))
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val hkd = amount.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                Db.get(this).insertTxn(
                    captureId = null, ts = System.currentTimeMillis(),
                    amountCents = Math.round(hkd * 100), currency = "HKD",
                    merchant = note.text.toString().ifBlank { null },
                    category = catSpinner.selectedItem as String,
                    source = (srcSpinner.selectedItem as String).lowercase(),
                    kind = "payment", isTransfer = false, manual = true
                )
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class TxnAdapter : RecyclerView.Adapter<TxnAdapter.VH>() {
        var items: List<Txn> = emptyList()
        private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.ENGLISH)

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.txnIcon)
            val title: TextView = v.findViewById(R.id.txnTitle)
            val meta: TextView = v.findViewById(R.id.txnMeta)
            val amount: TextView = v.findViewById(R.id.txnAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_txn, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            holder.title.text = t.merchant
                ?: t.kind.replaceFirstChar { it.uppercase() }

            // Category badge: the emoji on a soft tint of the category's color.
            // This is the row's primary color cue; it matches the donut/legend.
            val catColor = Categories.color(t.category)
            holder.icon.text = Categories.icon(t.category)
            val badge = android.graphics.drawable.GradientDrawable()
            badge.shape = android.graphics.drawable.GradientDrawable.OVAL
            badge.setColor((catColor and 0x00FFFFFF) or 0x33000000) // ~20% alpha
            holder.icon.background = badge

            val bits = mutableListOf(timeFmt.format(Date(t.ts)))
            bits.add(t.category ?: "tap to categorize")
            if (isTransferLike(t)) bits.add("transfer")
            holder.meta.text = bits.joinToString("  ·  ")
            holder.meta.setTextColor(
                if (t.category == null && !isTransferLike(t)) 0xFFE5484D.toInt()
                else 0xFF888888.toInt()
            )
            if (t.amountCents < 0) {
                holder.amount.text = "+" + fmt(-t.amountCents)
                holder.amount.setTextColor(0xFF30A46C.toInt())
            } else {
                // Spend stays neutral (theme default) for legibility — the badge
                // already carries the category color, so coloring the number too
                // would just be a harder-to-read duplicate of the same signal.
                holder.amount.text = fmt(t.amountCents)
                holder.amount.setTextColor(holder.title.currentTextColor)
            }
            holder.itemView.setOnClickListener { txnActionDialog(t) }
        }
    }
}
