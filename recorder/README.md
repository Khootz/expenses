# Expense Recorder (Phase 0)

Minimal Android app that records raw payment notifications from AlipayHK, Mox, Octopus, and WeChat into a local database, so we can design the real parsers around actual notification wording.

## What it does
- Listens for notifications **only** from: `hk.alipay.wallet`, `com.mox.app`, `com.octopuscards.nfc_reader`, `com.tencent.mm`. Stores their full text (title/text/bigText/extras) in a local SQLite DB. Nothing leaves the phone.
- For every **other** app it stores the package name and a counter only — no content — so we can verify the payment apps' real package names.
- On first enable it scrapes whatever notifications are still in the tray (`tray_scrape` rows) — the only "past" data Android allows.
- **Export JSON** saves all captures to `Downloads/` and opens a share sheet.

## Install (one time)
1. Copy `app/build/outputs/apk/debug/app-debug.apk` to the phone (USB cable, Google Drive, or `adb install`).
2. On the phone, open the APK → allow "Install unknown apps" for the file manager/browser if prompted.
3. Open **Expense Recorder**:
   - Tap **Grant access** → enable Expense Recorder in the Notification access list (Android will warn it can read all notifications — the allowlist filter in `RecorderService.kt` is why that's OK).
   - Tap **Allow background** → confirm battery-optimization exemption.
4. Also enable Android's own safety net: Settings → Notifications → **Notification history** → ON.
5. Test: make any payment or a HK$1 AlipayHK transfer — it should appear in the list within a second.

## During the fixture week
- Spend normally. Check the app every couple of days — counters per app should keep rising.
- If one of the four apps never shows up despite real payments, tap **Other apps seen** and look for it under a different package name; tell Claude and we update the allowlist.
- After a reboot, confirm captures still arrive (listener should rebind automatically).

## After ~1 week
Tap **Export JSON**, get the file to the PC (it's also in `Downloads/`), and drop it into `C:\Users\User\Desktop\Expenses\fixtures\`. That file becomes the test suite for the Phase 1 parsers.

## Build from source
```
cd recorder
gradle assembleDebug   # or open in Android Studio
# APK: app/build/outputs/apk/debug/app-debug.apk
```
