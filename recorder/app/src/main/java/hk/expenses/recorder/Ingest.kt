package hk.expenses.recorder

import android.content.Context
import hk.expenses.recorder.parse.Kind
import hk.expenses.recorder.parse.Parsers

object Categories {
    // Lean set: enough to act on, not so many that tagging is a chore. Food &
    // Groceries is one bucket (all food); Lifestyle is shopping + fun; Rent &
    // Bills is the fixed monthly cost. WORK is reimbursable — it's tracked but
    // left out of the spend/savings math (see FIXED / excluded handling).
    val ALL = listOf(
        "Food & Groceries", "Transport", "Rent & Bills", "Lifestyle",
        "Work", "Income", "Transfer", "Other"
    )
    const val SALARY_CENTS = 2_700_000L // HK$27,000 fixed monthly salary

    /** Fixed monthly costs — amortised evenly across the month for burn-rate. */
    const val FIXED = "Rent & Bills"

    /** Reimbursable — tracked but excluded from spending and savings. */
    const val WORK = "Work"

    // Single source of truth for how a category looks. Bright, modern tones in
    // the same violet→cyan family as the hero gradient (vivid, not muddy), each
    // a clearly different hue so the donut/legend/list read at a glance. The
    // color stays the SAME everywhere it appears, and pairing it with an emoji
    // is deliberate "dual coding" — the row is still readable color-blind.
    //
    // Icons are kept GENERIC, not literal: "Food & Groceries" is a plate, not a
    // noodle bowl, so logging chicken / ice cream / a supermarket run all fit
    // under it without feeling wrong.
    private val META = linkedMapOf(
        "Food & Groceries" to (0xFFFF6B6B.toInt() to "🍽️"), // 🍽️ coral red
        "Transport" to (0xFFFF922B.toInt() to "🚇"),         // 🚇 orange
        "Rent & Bills" to (0xFF845EF7.toInt() to "🏠"),      // 🏠 violet
        "Lifestyle" to (0xFFAE3EC9.toInt() to "🛍️"),        // 🛍️ grape
        "Work" to (0xFF0CA678.toInt() to "💼"),              // 💼 deep teal
        "Income" to (0xFF37B24D.toInt() to "💰"),            // 💰 green
        "Transfer" to (0xFFB08968.toInt() to "🔄"),          // 🔄 bronze
        "Other" to (0xFF94D82D.toInt() to "🧾")              // 🧾 lime
    )
    private val FALLBACK = 0xFFADB5BD.toInt() to "💸"    // 💸 uncategorized

    fun color(cat: String?): Int = (META[cat] ?: FALLBACK).first
    fun icon(cat: String?): String = (META[cat] ?: FALLBACK).second
}

/** Turns stored raw captures into transactions (parse → categorize → insert). */
object Ingest {

    fun sourceOf(pkg: String) = when (pkg) {
        "hk.alipay.wallet" -> "alipayhk"
        "com.mox.app" -> "mox"
        "com.octopuscards.nfc_reader" -> "octopus"
        "com.tencent.mm" -> "wechat"
        "com.bochk.app.aos" -> "bochk"
        "com.hangseng.rbmobile" -> "hangseng"
        "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "sms"
        // Wallet fallbacks — used only when the parser couldn't identify the
        // underlying card (a recognised card routes to octopus/mox instead).
        "com.samsung.android.spay" -> "samsungwallet"
        "com.google.android.apps.walletnfcrel" -> "googlewallet"
        else -> pkg
    }

    data class Tracked(
        val amountCents: Long, // signed
        val merchant: String?,
        val category: String?,
        val source: String
    )

    fun ingest(ctx: Context, c: Capture): Tracked? {
        val p = Parsers.parse(c.pkg, c.title, c.text, c.bigText) ?: return null
        val db = Db.get(ctx)
        val src = p.source ?: sourceOf(c.pkg)
        val signed =
            if (p.kind == Kind.INCOME || p.kind == Kind.REFUND) -p.amountCents else p.amountCents
        // Content-level dedup: same instant + amount + source = same transaction,
        // even if the notification was stored twice. A deleted match also skips —
        // backfill must never resurrect something the user deleted.
        if (db.findTxnByContent(c.ts, signed, src) != null) return null
        return insertParsed(db, c, p, signed, src)
    }

    /** User-initiated re-add from the Log — restores an accidental delete. */
    fun reAdd(ctx: Context, c: Capture): String {
        val db = Db.get(ctx)
        db.txnForCapture(c.id)?.let { (id, deleted) ->
            return if (deleted) {
                db.undeleteTxn(id); "Transaction restored"
            } else "Already tracked — nothing to restore"
        }
        val p = Parsers.parse(c.pkg, c.title, c.text, c.bigText)
            ?: return "Couldn't parse this notification — add it manually with +"
        val src = p.source ?: sourceOf(c.pkg)
        val signed =
            if (p.kind == Kind.INCOME || p.kind == Kind.REFUND) -p.amountCents else p.amountCents
        db.findTxnByContent(c.ts, signed, src)?.let { (id, deleted) ->
            return if (deleted) {
                db.undeleteTxn(id); "Transaction restored"
            } else "Already tracked — nothing to restore"
        }
        insertParsed(db, c, p, signed, src)
        return "Added to transactions"
    }

    private fun insertParsed(
        db: Db, c: Capture, p: hk.expenses.recorder.parse.ParsedTxn, signed: Long, src: String
    ): Tracked {
        var category = db.ruleFor(p.merchant)
        // Octopus taps carry no merchant (whether from the Octopus app or via
        // Samsung Wallet) — default to Transport, editable.
        if (category == null && src == "octopus" && p.kind == Kind.PAYMENT) {
            category = "Transport"
        }
        db.insertTxn(
            captureId = c.id, ts = c.ts, amountCents = signed, currency = p.currency,
            merchant = p.merchant, category = category, source = src,
            kind = p.kind.name.lowercase(), isTransfer = p.kind == Kind.TOPUP
        )
        return Tracked(signed, p.merchant, category, src)
    }

    /** Parses any captures that don't have a transaction yet. Idempotent. */
    fun backfill(ctx: Context) {
        Db.get(ctx).unparsedCaptures().forEach { ingest(ctx, it) }
    }
}
