package hk.expenses.recorder.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Synthetic fixtures only — replaced by real captured notifications after the
 * fixture week (drop the export into Desktop/Expenses/fixtures/ and these
 * tests get regenerated from it).
 */
class ParsersTest {

    @Test
    fun amountFormats() {
        assertEquals(2350L, Parsers.amountCents("paid HK$23.50 to X"))
        assertEquals(123456L, Parsers.amountCents("HKD 1,234.56 at Y"))
        assertEquals(500L, Parsers.amountCents("$5"))
        assertEquals(1050L, Parsers.amountCents("HK$ 10.5"))
        assertNull(Parsers.amountCents("no money here"))
    }

    @Test
    fun alipayPayment() {
        val p = Parsers.parse(
            "hk.alipay.wallet", "AlipayHK",
            "You have successfully paid HK$23.50 to McDonald's", null
        )!!
        assertEquals(2350L, p.amountCents)
        assertEquals(Kind.PAYMENT, p.kind)
        assertEquals("McDonald's", p.merchant)
    }

    @Test
    fun moxSpend() {
        val p = Parsers.parse(
            "com.mox.app", "Mox",
            null, "You spent HKD 45.00 at WELLCOME with Mox card"
        )!!
        assertEquals(4500L, p.amountCents)
        assertEquals("WELLCOME", p.merchant)
    }

    @Test
    fun octopusTopUpIsNotPayment() {
        val p = Parsers.parse(
            "com.octopuscards.nfc_reader", "Octopus",
            "Top up successful HK$500.00", null
        )!!
        assertEquals(Kind.TOPUP, p.kind)
        assertEquals(50000L, p.amountCents)
    }

    @Test
    fun wechatChatMessageIgnored() {
        assertNull(
            Parsers.parse("com.tencent.mm", "Alice", "dinner was $200 lol", null)
        )
    }

    // ---- Real fixtures captured 2026-06-12 from the device tray ----

    @Test
    fun bochkFpsPayment_realFixture() {
        val p = Parsers.parse(
            "com.bochk.app.aos", "BOCHK 中銀香港",
            "Your FPS payment of HKD 1.00 through Mobile Banking from account " +
                "012...932 to +852-44...625 at 2026/06/12 23:01 has been " +
                "successfully transferred to the payee.",
            null
        )!!
        assertEquals(100L, p.amountCents)
        assertEquals(Kind.PAYMENT, p.kind)
        assertEquals("FPS +852-44...625", p.merchant)
    }

    @Test
    fun bochkContentFreeVariantIgnored() {
        assertNull(
            Parsers.parse(
                "com.bochk.app.aos", "BOCHK 中銀香港",
                "Your payment has been transferred to the payee", null
            )
        )
    }

    @Test
    fun hangSengDeposit_realFixture() {
        val p = Parsers.parse(
            "com.hangseng.rbmobile", "#HASEnotice",
            "恒生︰付款人 KHOO T**** Z**於2026-06-12存入港元1.00至閣下之賬戶361-637XXX-888。查詢: 2822 0228",
            null
        )!!
        assertEquals(100L, p.amountCents)
        assertEquals(Kind.INCOME, p.kind)
        assertEquals("From KHOO T**** Z**", p.merchant)
    }

    @Test
    fun hangSengSmsViaGoogleMessages_realFixture() {
        val p = Parsers.parse(
            "com.google.android.apps.messaging", "#HASEnotice",
            "恒生︰付款人 KHOO T**** Z**於2026-06-12存入港元1.00至閣下之賬戶361-637XXX-888。查詢: 2822 0228",
            null
        )!!
        assertEquals(100L, p.amountCents)
        assertEquals(Kind.INCOME, p.kind)
    }

    @Test
    fun personalSmsIgnored() {
        assertNull(
            Parsers.parse(
                "com.google.android.apps.messaging", "Mum",
                "can you send me HK$300 for groceries", null
            )
        )
    }

    // ---- Mobile-wallet front-ends (PROVISIONAL wording) ----

    @Test
    fun octopusTapViaSamsungWallet_routesToOctopus() {
        val p = Parsers.parse(
            "com.samsung.android.spay", "Octopus",
            "You paid HK$5.20 with Octopus. Remaining value HK$44.80.", null
        )!!
        assertEquals(520L, p.amountCents)
        assertEquals(Kind.PAYMENT, p.kind)
        assertEquals("octopus", p.source) // so it categorizes + dedups as Octopus
        assertNull(p.merchant)             // Octopus taps carry no merchant
    }

    @Test
    fun moxTapViaGoogleWallet_routesToMox() {
        val p = Parsers.parse(
            "com.google.android.apps.walletnfcrel", "Google Wallet",
            "HK$45.00 paid at WELLCOME with Mox Debit ····1234", null
        )!!
        assertEquals(4500L, p.amountCents)
        assertEquals(Kind.PAYMENT, p.kind)
        assertEquals("mox", p.source)
        assertEquals("WELLCOME", p.merchant)
    }

    @Test
    fun walletPromoWithAmountIgnored() {
        // An amount alone is not a payment — no spend signal → kept raw, not tracked.
        assertNull(
            Parsers.parse(
                "com.samsung.android.spay", "Samsung Wallet",
                "Add money now and get HK$20 cashback on your next ride!", null
            )
        )
    }

    @Test
    fun unrecognisedWalletCardFallsBackToWalletSource() {
        val p = Parsers.parse(
            "com.google.android.apps.walletnfcrel", "Google Wallet",
            "You paid HK$88.00 at PARKnSHOP", null
        )!!
        assertEquals(8800L, p.amountCents)
        assertEquals("googlewallet", p.source) // unknown card: not dropped, just generic
        assertEquals("PARKnSHOP", p.merchant)
    }

    @Test
    fun octopusReloadViaSamsungWalletIsTopUp() {
        val p = Parsers.parse(
            "com.samsung.android.spay", "Octopus",
            "Top up HK$100.00 to your Octopus was successful.", null
        )!!
        assertEquals(Kind.TOPUP, p.kind)   // flagged is_transfer downstream
        assertEquals("octopus", p.source)
        assertEquals(10000L, p.amountCents)
    }
}
