package hk.expenses.recorder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class Capture(
    val id: Long,
    val ts: Long,
    val pkg: String,
    val source: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val infoText: String?,
    val textLines: String?,
    val extras: String?
)

data class PkgSeen(val pkg: String, val label: String?, val count: Int)

data class Txn(
    val id: Long,
    val ts: Long,
    val amountCents: Long, // positive = money out, negative = money in
    val currency: String,
    val merchant: String?,
    val category: String?,
    val source: String,
    val kind: String,
    val isTransfer: Boolean,
    val manual: Boolean
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "recorder.db", null, 4) {

    companion object {
        @Volatile
        private var inst: Db? = null
        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }

        private val SEED_RULES = mapOf(
            "mtr" to "Transport",
            "kmb" to "Transport",
            "citybus" to "Transport",
            "7-eleven" to "Food & Groceries",
            "circle k" to "Food & Groceries",
            "mcdonald" to "Food & Groceries",
            "麥當勞" to "Food & Groceries",
            "kfc" to "Food & Groceries",
            "fairwood" to "Food & Groceries",
            "大快活" to "Food & Groceries",
            "cafe de coral" to "Food & Groceries",
            "大家樂" to "Food & Groceries",
            "wellcome" to "Food & Groceries",
            "惠康" to "Food & Groceries",
            "parknshop" to "Food & Groceries",
            "百佳" to "Food & Groceries",
            "watsons" to "Other",
            "mannings" to "Other",
            "netflix" to "Lifestyle",
            "spotify" to "Lifestyle"
        )

        // v3 categories → the leaner v4 set. Applied to both saved txns and
        // the merchant rules so existing data keeps showing the right buckets.
        private val CATEGORY_RENAMES = mapOf(
            "Food" to "Food & Groceries",
            "Groceries" to "Food & Groceries",
            "Snacks" to "Food & Groceries",
            "Rent" to "Rent & Bills",
            "Bills" to "Rent & Bills",
            "Shopping" to "Lifestyle",
            "Entertainment" to "Lifestyle",
            "Health" to "Other",
            "Travel" to "Other"
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE captures(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, pkg TEXT NOT NULL, " +
                "source TEXT NOT NULL, title TEXT, text TEXT, big_text TEXT, sub_text TEXT, " +
                "info_text TEXT, text_lines TEXT, extras TEXT)"
        )
        db.execSQL(
            "CREATE TABLE pkg_seen(pkg TEXT PRIMARY KEY, label TEXT, " +
                "count INTEGER NOT NULL DEFAULT 0, last_ts INTEGER)"
        )
        createV2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE txns ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {
                // column already exists (fresh install created it via createV2)
            }
        }
        if (oldVersion < 4) {
            // Remap the old, wider category set onto the leaner one so existing
            // transactions and rules don't end up with orphaned labels.
            for ((from, to) in CATEGORY_RENAMES) {
                db.execSQL("UPDATE txns SET category=? WHERE category=?", arrayOf(to, from))
                db.execSQL("UPDATE rules SET category=? WHERE category=?", arrayOf(to, from))
            }
        }
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS txns(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, capture_id INTEGER UNIQUE, " +
                "ts INTEGER NOT NULL, amount_cents INTEGER NOT NULL, " +
                "currency TEXT NOT NULL DEFAULT 'HKD', merchant TEXT, category TEXT, " +
                "source TEXT NOT NULL, kind TEXT NOT NULL, " +
                "is_transfer INTEGER NOT NULL DEFAULT 0, manual INTEGER NOT NULL DEFAULT 0, " +
                "deleted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_txns_ts ON txns(ts)")
        db.execSQL("CREATE TABLE IF NOT EXISTS rules(pattern TEXT PRIMARY KEY, category TEXT NOT NULL)")
        for ((p, c) in SEED_RULES) {
            db.execSQL("INSERT OR IGNORE INTO rules(pattern, category) VALUES(?, ?)", arrayOf(p, c))
        }
    }

    // ---------- captures ----------

    /** @return new row id, or -1 if this notification was already stored. */
    fun insertCapture(c: Capture): Long {
        readableDatabase.rawQuery(
            "SELECT 1 FROM captures WHERE ts = ? AND pkg = ? AND IFNULL(title,'') = ? AND IFNULL(text,'') = ? LIMIT 1",
            arrayOf(c.ts.toString(), c.pkg, c.title ?: "", c.text ?: "")
        ).use { if (it.moveToFirst()) return -1 }
        val v = ContentValues().apply {
            put("ts", c.ts)
            put("pkg", c.pkg)
            put("source", c.source)
            put("title", c.title)
            put("text", c.text)
            put("big_text", c.bigText)
            put("sub_text", c.subText)
            put("info_text", c.infoText)
            put("text_lines", c.textLines)
            put("extras", c.extras)
        }
        return writableDatabase.insert("captures", null, v)
    }

    fun tallyPackage(pkg: String, label: String?, ts: Long, inc: Int = 1) {
        val db = writableDatabase
        db.execSQL(
            "INSERT OR IGNORE INTO pkg_seen(pkg, label, count, last_ts) VALUES(?, ?, 0, ?)",
            arrayOf(pkg, label, ts)
        )
        db.execSQL(
            "UPDATE pkg_seen SET count = count + ?, last_ts = ?, label = ? WHERE pkg = ?",
            arrayOf(inc, ts, label, pkg)
        )
    }

    private fun captureFromCursor(c: android.database.Cursor) = Capture(
        c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
        c.getString(4), c.getString(5), c.getString(6), c.getString(7),
        c.getString(8), c.getString(9), c.getString(10)
    )

    private val captureCols =
        "id, ts, pkg, source, title, text, big_text, sub_text, info_text, text_lines, extras"

    fun recent(limit: Int): List<Capture> {
        val out = ArrayList<Capture>()
        readableDatabase.rawQuery(
            "SELECT $captureCols FROM captures ORDER BY ts DESC, id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(captureFromCursor(c)) }
        return out
    }

    fun unparsedCaptures(): List<Capture> {
        val out = ArrayList<Capture>()
        readableDatabase.rawQuery(
            "SELECT $captureCols FROM captures c " +
                "WHERE NOT EXISTS (SELECT 1 FROM txns t WHERE t.capture_id = c.id) " +
                "ORDER BY c.id",
            null
        ).use { c -> while (c.moveToNext()) out.add(captureFromCursor(c)) }
        return out
    }

    fun countsByPackage(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT pkg, COUNT(*) FROM captures GROUP BY pkg ORDER BY COUNT(*) DESC", null
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) }
        return out
    }

    fun packagesSeen(): List<PkgSeen> {
        val out = ArrayList<PkgSeen>()
        readableDatabase.rawQuery(
            "SELECT pkg, label, count FROM pkg_seen ORDER BY count DESC", null
        ).use { c -> while (c.moveToNext()) out.add(PkgSeen(c.getString(0), c.getString(1), c.getInt(2))) }
        return out
    }

    fun allCapturesJson(): String {
        val arr = JSONArray()
        readableDatabase.rawQuery(
            "SELECT $captureCols FROM captures ORDER BY ts ASC, id ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getLong(0))
                o.put("ts", c.getLong(1))
                o.put("pkg", c.getString(2))
                o.put("source", c.getString(3))
                o.put("title", c.getString(4) ?: JSONObject.NULL)
                o.put("text", c.getString(5) ?: JSONObject.NULL)
                o.put("bigText", c.getString(6) ?: JSONObject.NULL)
                o.put("subText", c.getString(7) ?: JSONObject.NULL)
                o.put("infoText", c.getString(8) ?: JSONObject.NULL)
                o.put("textLines", c.getString(9) ?: JSONObject.NULL)
                val ex = c.getString(10)
                o.put("extras", if (ex != null) JSONObject(ex) else JSONObject.NULL)
                arr.put(o)
            }
        }
        return arr.toString(2)
    }

    // ---------- transactions ----------

    /** @return (txn id, deleted flag) for a content match, or null. */
    fun findTxnByContent(ts: Long, amountCents: Long, source: String): Pair<Long, Boolean>? {
        readableDatabase.rawQuery(
            "SELECT id, deleted FROM txns WHERE ts = ? AND amount_cents = ? AND source = ? LIMIT 1",
            arrayOf(ts.toString(), amountCents.toString(), source)
        ).use { if (it.moveToFirst()) return Pair(it.getLong(0), it.getInt(1) != 0) }
        return null
    }

    fun txnForCapture(captureId: Long): Pair<Long, Boolean>? {
        readableDatabase.rawQuery(
            "SELECT id, deleted FROM txns WHERE capture_id = ? LIMIT 1",
            arrayOf(captureId.toString())
        ).use { if (it.moveToFirst()) return Pair(it.getLong(0), it.getInt(1) != 0) }
        return null
    }

    fun undeleteTxn(id: Long) {
        writableDatabase.execSQL("UPDATE txns SET deleted = 0 WHERE id = ?", arrayOf(id.toString()))
    }

    fun insertTxn(
        captureId: Long?, ts: Long, amountCents: Long, currency: String,
        merchant: String?, category: String?, source: String, kind: String,
        isTransfer: Boolean, manual: Boolean = false
    ) {
        val v = ContentValues().apply {
            put("capture_id", captureId)
            put("ts", ts)
            put("amount_cents", amountCents)
            put("currency", currency)
            put("merchant", merchant)
            put("category", category)
            put("source", source)
            put("kind", kind)
            put("is_transfer", if (isTransfer) 1 else 0)
            put("manual", if (manual) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("txns", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun txnsBetween(fromTs: Long, toTs: Long): List<Txn> {
        val out = ArrayList<Txn>()
        readableDatabase.rawQuery(
            "SELECT id, ts, amount_cents, currency, merchant, category, source, kind, is_transfer, manual " +
                "FROM txns WHERE ts >= ? AND ts < ? AND deleted = 0 ORDER BY ts DESC, id DESC",
            arrayOf(fromTs.toString(), toTs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Txn(
                        c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getString(6), c.getString(7),
                        c.getInt(8) != 0, c.getInt(9) != 0
                    )
                )
            }
        }
        return out
    }

    /** Timestamp of the oldest visible transaction, or null if none. */
    fun earliestTxnTs(): Long? {
        readableDatabase.rawQuery("SELECT MIN(ts) FROM txns WHERE deleted = 0", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    fun setTxnCategory(id: Long, category: String) {
        writableDatabase.execSQL(
            "UPDATE txns SET category = ? WHERE id = ?", arrayOf(category, id.toString())
        )
    }

    fun updateTxnDetails(id: Long, merchant: String?, amountCents: Long) {
        val v = ContentValues().apply {
            put("merchant", merchant)
            put("amount_cents", amountCents)
        }
        writableDatabase.update("txns", v, "id = ?", arrayOf(id.toString()))
    }

    /** Soft delete: keeps the row so backfill never re-imports it from its capture. */
    fun deleteTxn(id: Long) {
        writableDatabase.execSQL("UPDATE txns SET deleted = 1 WHERE id = ?", arrayOf(id.toString()))
    }

    fun ruleFor(merchant: String?): String? {
        if (merchant.isNullOrBlank()) return null
        readableDatabase.rawQuery("SELECT pattern, category FROM rules", null).use { c ->
            while (c.moveToNext()) {
                if (merchant.contains(c.getString(0), ignoreCase = true)) return c.getString(1)
            }
        }
        return null
    }

    /** Saves a rule and applies it to existing uncategorized transactions. */
    fun addRule(pattern: String, category: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO rules(pattern, category) VALUES(?, ?)",
            arrayOf(pattern, category)
        )
        writableDatabase.execSQL(
            "UPDATE txns SET category = ? WHERE category IS NULL AND merchant LIKE '%' || ? || '%'",
            arrayOf(category, pattern)
        )
    }
}
