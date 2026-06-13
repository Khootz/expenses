package hk.expenses.recorder

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class RecorderService : NotificationListenerService() {

    companion object {
        val ALLOWLIST = setOf(
            "hk.alipay.wallet",          // AlipayHK
            "com.mox.app",               // Mox
            "com.octopuscards.nfc_reader", // Octopus
            "com.tencent.mm",            // WeChat (Pay HK)
            "com.bochk.app.aos",         // BOCHK (HK app — not the mainland BOC id)
            "com.hangseng.rbmobile",     // Hang Seng
            "com.samsung.android.spay",  // Samsung Wallet (Octopus taps)
            "com.google.android.apps.walletnfcrel" // Google Wallet (Mox tap-to-pay)
        )

        // Mobile wallets fire a payment notification when a hosted card is used
        // (Octopus → Samsung Wallet, Mox → Google Wallet) instead of the card
        // app. They're also chatty (promos, "tap to pay ready", card setup), so
        // — like WeChat — only store one when it mentions an amount.
        val WALLET_PACKAGES = setOf(
            "com.samsung.android.spay",
            "com.google.android.apps.walletnfcrel"
        )

        // HK banks (e.g. Hang Seng "#HASEnotice") send transaction alerts as SMS.
        // Capture from SMS apps ONLY when the sender uses HK's registered "#" prefix
        // AND the message mentions an amount — personal texts and OTPs are skipped.
        val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )
        private val MONEY = Regex("""HK\$|HKD|港元""")

        const val ACTION_NEW_CAPTURE = "hk.expenses.recorder.NEW_CAPTURE"
    }

    private var lastKey: String? = null
    private var lastTime = 0L

    // Tally throttle: batch counts in memory, write to DB at most once per
    // minute per package — keeps background cost near zero on chatty phones.
    private val pendingTally = HashMap<String, Int>()
    private val lastTallyWrite = HashMap<String, Long>()

    private fun tally(db: Db, pkg: String, ts: Long) {
        val n = (pendingTally[pkg] ?: 0) + 1
        if (ts - (lastTallyWrite[pkg] ?: 0L) >= 60_000) {
            db.tallyPackage(pkg, appLabel(pkg), ts, n)
            pendingTally[pkg] = 0
            lastTallyWrite[pkg] = ts
        } else {
            pendingTally[pkg] = n
        }
    }

    override fun onListenerConnected() {
        // Only chance to see "past" notifications: whatever is still in the tray.
        try {
            activeNotifications?.forEach { handle(it, "tray_scrape") }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn, "posted")
    }

    private fun handle(sbn: StatusBarNotification, source: String) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val db = Db.get(this)

        val isSmsApp = pkg in SMS_PACKAGES
        if (pkg !in ALLOWLIST && !isSmsApp) {
            // Name + count only, never content: lets us verify expected package names
            // without recording anything personal from other apps.
            tally(db, pkg, sbn.postTime)
            return
        }

        val n = sbn.notification ?: return
        // Group-summary wrappers (e.g. BOCHK's) carry no text — skip them.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        fun str(key: String) = ex.getCharSequence(key)?.toString()

        val title = str(Notification.EXTRA_TITLE)
        val text = str(Notification.EXTRA_TEXT)

        if (isSmsApp) {
            val body = (text ?: "") + (str(Notification.EXTRA_BIG_TEXT) ?: "")
            val isBankAlert = title?.startsWith("#") == true && MONEY.containsMatchIn(body)
            if (!isBankAlert) {
                tally(db, pkg, sbn.postTime)
                return
            }
        }

        // Wallets post promos and card-setup notices alongside real payments;
        // require an amount before storing (parser further drops non-spends).
        if (pkg in WALLET_PACKAGES) {
            val body = (text ?: "") + (str(Notification.EXTRA_BIG_TEXT) ?: "")
            if (!MONEY.containsMatchIn(body)) {
                tally(db, pkg, sbn.postTime)
                return
            }
        }

        // WeChat is mostly chat noise: only store payment-related notifications
        // (privacy + battery — chats are never written to the database).
        if (pkg == "com.tencent.mm") {
            val body = (text ?: "") + (str(Notification.EXTRA_BIG_TEXT) ?: "")
            val isPay = title?.contains("WeChat Pay", true) == true ||
                title?.contains("微信支付") == true || MONEY.containsMatchIn(body)
            if (!isPay) {
                tally(db, pkg, sbn.postTime)
                return
            }
        }
        val big = str(Notification.EXTRA_BIG_TEXT)
        val sub = str(Notification.EXTRA_SUB_TEXT)
        val info = str(Notification.EXTRA_INFO_TEXT)
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }

        // Apps repost/update the same notification rapidly; skip exact repeats within 3s.
        val key = "$pkg|$title|$text|$big"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastTime < 3000) return
        lastKey = key
        lastTime = now

        val extras = JSONObject()
        for (k in ex.keySet()) {
            try {
                val v = ex.get(k)
                extras.put(k, v?.toString()?.take(300) ?: JSONObject.NULL)
            } catch (_: Exception) {
            }
        }

        val cap = Capture(
            0, sbn.postTime, pkg, source,
            title, text, big, sub, info, lines, extras.toString()
        )
        val id = db.insertCapture(cap)
        if (id > 0) {
            val tracked = try {
                Ingest.ingest(this, cap.copy(id = id))
            } catch (_: Exception) {
                // Parsing must never break capture; raw row is kept either way.
                null
            }
            // Live captures only — tray scrapes on reconnect would spam old items.
            if (tracked != null && source == "posted" && Prefs.confirmTracked(this)) {
                confirmNotification(tracked)
            }
            sendBroadcast(Intent(ACTION_NEW_CAPTURE).setPackage(packageName))
        }
    }

    private fun confirmNotification(t: Ingest.Tracked) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    "tracked", "Tracked expenses",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
            )
            val v = Math.abs(t.amountCents)
            val amt = "HK$%,d.%02d".format(v / 100, v % 100)
            val title = if (t.amountCents < 0) "✓ Tracked money in +$amt" else "✓ Tracked $amt"
            val text = listOfNotNull(t.merchant, t.category ?: "uncategorized", t.source)
                .joinToString(" · ")
            val pi = android.app.PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val n = android.app.Notification.Builder(this, "tracked")
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify((System.currentTimeMillis() % 1_000_000).toInt(), n)
        } catch (_: Exception) {
            // A failed confirmation must never affect tracking itself.
        }
    }

    private fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        null
    }
}
