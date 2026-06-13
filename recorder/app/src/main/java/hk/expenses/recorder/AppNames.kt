package hk.expenses.recorder

object AppNames {
    fun shortName(pkg: String) = when (pkg) {
        "hk.alipay.wallet" -> "AlipayHK"
        "com.mox.app" -> "Mox"
        "com.octopuscards.nfc_reader" -> "Octopus"
        "com.tencent.mm" -> "WeChat"
        "com.bochk.app.aos" -> "BOCHK"
        "com.hangseng.rbmobile" -> "HangSeng"
        "com.samsung.android.spay" -> "Samsung Wallet"
        "com.google.android.apps.walletnfcrel" -> "Google Wallet"
        "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "SMS"
        else -> pkg
    }

    fun color(pkg: String) = when (pkg) {
        "hk.alipay.wallet" -> 0xFF1677FF.toInt()
        "com.mox.app" -> 0xFF00B383.toInt()
        "com.octopuscards.nfc_reader" -> 0xFFF7941D.toInt()
        "com.tencent.mm" -> 0xFF07C160.toInt()
        "com.bochk.app.aos" -> 0xFFC41230.toInt()
        "com.hangseng.rbmobile" -> 0xFF00785A.toInt()
        "com.samsung.android.spay" -> 0xFF1428A0.toInt()
        "com.google.android.apps.walletnfcrel" -> 0xFF1A73E8.toInt()
        "com.google.android.apps.messaging", "com.samsung.android.messaging" -> 0xFF7B5EA7.toInt()
        else -> 0xFF888888.toInt()
    }

    fun sourceColor(source: String) = when (source) {
        "alipayhk" -> 0xFF1677FF.toInt()
        "mox" -> 0xFF00B383.toInt()
        "octopus" -> 0xFFF7941D.toInt()
        "wechat" -> 0xFF07C160.toInt()
        "bochk" -> 0xFFC41230.toInt()
        "hangseng" -> 0xFF00785A.toInt()
        "samsungwallet" -> 0xFF1428A0.toInt()
        "googlewallet" -> 0xFF1A73E8.toInt()
        "sms" -> 0xFF7B5EA7.toInt()
        "cash" -> 0xFF607D8B.toInt()
        else -> 0xFF888888.toInt()
    }
}
