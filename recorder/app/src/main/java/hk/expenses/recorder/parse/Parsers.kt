package hk.expenses.recorder.parse

enum class Kind { PAYMENT, TOPUP, REFUND, INCOME, UNKNOWN }

data class ParsedTxn(
    val amountCents: Long,
    val currency: String,
    val merchant: String?,
    val kind: Kind,
    // When non-null, overrides the package→source mapping. Mobile wallets
    // (Samsung/Google) front several cards, so the parser reads which card
    // was used and routes to that card's source (e.g. "octopus", "mox").
    val source: String? = null,
)

/**
 * Turns raw payment-app notification text into structured transactions.
 *
 * ALL PATTERNS ARE PROVISIONAL until the fixture week's export confirms the
 * real wording (English/Chinese variants, exact phrasing). The contract that
 * will not change: parse() returns null for anything it can't confidently
 * read — callers must keep the raw text and surface unparsed rows for review.
 */
object Parsers {

    // "HK$1,234.50" / "HKD 1234.5" / "$12" / "港元1.00"
    private val AMOUNT = Regex("""(?:HK\$|HKD\s?|港元\s?|\$)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")

    fun parse(pkg: String, title: String?, text: String?, bigText: String?): ParsedTxn? {
        val body = bigText ?: text ?: return null
        val full = listOfNotNull(title, body).joinToString(" | ")
        val amountCents = amountCents(full) ?: return null
        return when (pkg) {
            "hk.alipay.wallet" -> alipay(full, amountCents)
            "com.mox.app" -> mox(full, amountCents)
            "com.octopuscards.nfc_reader" -> octopus(full, amountCents)
            "com.tencent.mm" -> wechat(full, amountCents)
            "com.bochk.app.aos" -> bochk(full, amountCents)
            "com.hangseng.rbmobile" -> hangseng(full, amountCents)
            // Mobile-wallet front-ends: an Octopus tap surfaces via Samsung
            // Wallet, and the Mox card via Google Wallet (tap-to-pay), instead
            // of the card app's own push. Route to the underlying card's source.
            "com.samsung.android.spay" -> samsungWallet(full, amountCents)
            "com.google.android.apps.walletnfcrel" -> googleWallet(full, amountCents)
            // Bank alerts arriving as SMS — routed by registered sender ID.
            "com.google.android.apps.messaging", "com.samsung.android.messaging" ->
                when {
                    title?.startsWith("#HASE") == true -> hangseng(full, amountCents)
                    title?.startsWith("#BOC") == true -> bochk(full, amountCents)
                    else -> null // unknown sender: kept as raw, surfaced for review
                }
            else -> null
        }
    }

    fun amountCents(s: String): Long? {
        val m = AMOUNT.find(s) ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return Math.round(n * 100)
    }

    private fun kindOf(s: String): Kind = when {
        listOf("top up", "top-up", "topup", "reload", "增值").any { s.contains(it, true) } -> Kind.TOPUP
        listOf("refund", "退款").any { s.contains(it, true) } -> Kind.REFUND
        listOf("received", "收到", "salary", "credited").any { s.contains(it, true) } -> Kind.INCOME
        listOf("paid", "payment", "spent", "付款", "支付", "消費").any { s.contains(it, true) } -> Kind.PAYMENT
        else -> Kind.UNKNOWN
    }

    // PROVISIONAL: e.g. "You have successfully paid HK$23.50 to McDonald's"
    private fun alipay(s: String, cents: Long): ParsedTxn? {
        val merchant = Regex("""(?:to|向)\s+(.{2,40}?)(?:\s*[|.。]|$)""")
            .find(s)?.groupValues?.get(1)?.trim()
        return ParsedTxn(cents, "HKD", merchant, kindOf(s))
    }

    // PROVISIONAL: e.g. "You spent HKD 45.00 at WELLCOME with Mox card"
    private fun mox(s: String, cents: Long): ParsedTxn? {
        val merchant = Regex("""\bat\s+(.{2,40}?)(?:\s+with\b|\s*[|.。]|$)""")
            .find(s)?.groupValues?.get(1)?.trim()
        return ParsedTxn(cents, "HKD", merchant, kindOf(s))
    }

    // PROVISIONAL: mobile Octopus tap, merchant often absent (just deduction + balance)
    private fun octopus(s: String, cents: Long): ParsedTxn? =
        ParsedTxn(cents, "HKD", null, kindOf(s).let { if (it == Kind.UNKNOWN) Kind.PAYMENT else it })

    // PROVISIONAL: WeChat package is noisy (chat etc.) — only accept clear payment wording
    private fun wechat(s: String, cents: Long): ParsedTxn? {
        if (kindOf(s) == Kind.UNKNOWN) return null
        val merchant = Regex("""(?:to|向)\s+(.{2,40}?)(?:\s*[|.。]|$)""")
            .find(s)?.groupValues?.get(1)?.trim()
        return ParsedTxn(cents, "HKD", merchant, kindOf(s))
    }

    // CONFIRMED format (2026-06-12 fixture): "Your FPS payment of HKD 1.00 through
    // Mobile Banking from account 012...932 to +852-44...625 at 2026/06/12 23:01
    // has been successfully transferred to the payee."
    // Content-free variants ("Your payment has been transferred to the payee")
    // carry no amount, so parse() already returns null for them via amountCents.
    private fun bochk(s: String, cents: Long): ParsedTxn? {
        val payee = Regex("""\bto\s+([^\s]+)\s+at\s+\d{4}/""").find(s)?.groupValues?.get(1)
        val kind = when {
            s.contains("FPS payment", true) || s.contains("transferred to the payee", true) ->
                Kind.PAYMENT
            else -> kindOf(s)
        }
        return ParsedTxn(cents, "HKD", payee?.let { "FPS $it" }, kind)
    }

    // CONFIRMED format (2026-06-12 fixture): "恒生︰付款人 KHOO T**** Z**於2026-06-12
    // 存入港元1.00至閣下之賬戶361-637XXX-888。查詢: 2822 0228"
    private fun hangseng(s: String, cents: Long): ParsedTxn? {
        val kind = when {
            s.contains("存入") -> Kind.INCOME   // deposit into your account
            s.contains("付款") && !s.contains("付款人") -> Kind.PAYMENT
            else -> kindOf(s)
        }
        val payer = Regex("""付款人\s*(.{2,30}?)於""").find(s)?.groupValues?.get(1)?.trim()
        return ParsedTxn(cents, "HKD", payer?.let { "From $it" }, kind)
    }

    // ---- Mobile-wallet front-ends (PROVISIONAL wording) ----
    // The device confirms these packages are installed and posting; the exact
    // text awaits a real fixture. A wallet holds several cards, so we read which
    // card was used and route to that card's source — keeping categorization and
    // top-up dedup intact. Cards we don't recognise stay under a generic wallet
    // source rather than being dropped. Notifications with an amount but no spend
    // signal (promos, "card added", balance/reward notices) return null and are
    // kept as raw captures for review — we never invent a payment.

    private val WALLET_SPEND = listOf(
        "paid", "payment", "spent", "used", "purchase", "transaction",
        "付款", "支付", "消費", "消费", "扣賬", "扣账"
    )

    private fun walletKind(s: String): Kind = when {
        listOf("top up", "top-up", "topup", "reload", "增值")
            .any { s.contains(it, true) } -> Kind.TOPUP
        listOf("refund", "退款").any { s.contains(it, true) } -> Kind.REFUND
        WALLET_SPEND.any { s.contains(it, true) } -> Kind.PAYMENT
        else -> Kind.UNKNOWN
    }

    private fun walletMerchant(s: String): String? =
        Regex("""\bat\s+(.{2,40}?)(?:\s+with\b|\s*[|.。]|$)""")
            .find(s)?.groupValues?.get(1)?.trim()

    // Samsung Wallet (com.samsung.android.spay) — Octopus taps in HK.
    private fun samsungWallet(s: String, cents: Long): ParsedTxn? {
        val isOctopus = s.contains("Octopus", true) || s.contains("八達通")
        // An Octopus tap is a payment even when the wording is a bare deduction.
        val kind = walletKind(s).let { if (it == Kind.UNKNOWN && isOctopus) Kind.PAYMENT else it }
        if (kind == Kind.UNKNOWN) return null
        // Octopus taps carry no merchant; other cards may name one.
        val merchant = if (isOctopus) null else walletMerchant(s)
        return ParsedTxn(cents, "HKD", merchant, kind, if (isOctopus) "octopus" else "samsungwallet")
    }

    // Google Wallet (com.google.android.apps.walletnfcrel) — Mox card tap-to-pay.
    private fun googleWallet(s: String, cents: Long): ParsedTxn? {
        val isMox = s.contains("Mox", true)
        val kind = walletKind(s)
        if (kind == Kind.UNKNOWN) return null
        return ParsedTxn(cents, "HKD", walletMerchant(s), kind, if (isMox) "mox" else "googlewallet")
    }
}
