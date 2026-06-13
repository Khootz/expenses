# Expenses

An expense tracker I built for myself because keeping track of spending in Hong Kong is annoying. Everything is split across AlipayHK, Mox, Octopus, WeChat Pay, the banks, the wallets — and unlike some other countries there's no personal-finance API here that ties it all together.

So instead of trying to plug into each app, this just reads the payment notifications they already drop on my phone and turns them into a running expense log. No linking accounts, no giving anyone my banking login.

Everything stays on the phone. There's an optional backend (Cloudflare Worker + D1) if I want a web dashboard and sync, but the app works fully offline on its own.

## How it works

The Android app runs a `NotificationListenerService` that only watches a fixed allowlist of payment apps (see `recorder/.../RecorderService.kt`). When one of them posts a notification that mentions an amount, the app parses out the merchant and amount and saves it as a transaction. Everything else is ignored — for apps not on the allowlist it keeps only a package name and a counter, no content.

After that it's a normal expense tracker:

- saved this month vs my fixed monthly salary
- spending by category — donut chart + legend
- a daily-spending heatmap
- a daily burn rate that spreads fixed costs (rent / bills) evenly across the whole month, so paying rent on the 1st doesn't show up as one giant spike
- categories are colour + emoji coded so I can read the list at a glance instead of squinting at text

### Categories

Kept short on purpose — enough to be useful, not so many that tagging becomes a chore:

`Food & Groceries` · `Transport` · `Rent & Bills` · `Lifestyle` (shopping + going out) · `Work` · `Other`

`Work` is for things I pay for but get reimbursed (APIs, subscriptions) — it's still tracked, but it doesn't count against my spending or savings. `Income` and `Transfer` exist behind the scenes so moving money between my own accounts doesn't get counted as spending.

## Layout

- `recorder/` — the Android app (Kotlin)
- `backend/` — optional Cloudflare Worker + D1 database for sync and a web dashboard

## Building the app

Open `recorder/` in Android Studio and hit run, or from the command line:

```
cd recorder
gradle assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

Then on the phone: install it, open it, tap **Grant access** to give it notification access, and allow it to run in the background. Make any payment (or a HK$1 transfer to test) and it should show up within a second.

## Backend (optional)

See `backend/README.md`. One Cloudflare Worker serves both the JSON API and the dashboard, backed by a D1 database. Free tier easily covers personal volume. Nothing syncs unless I deploy it and turn it on.

## Notes

- This is Hong Kong specific — the parsers and the built-in merchant rules (MTR, ParknShop, Watsons, etc.) are tuned for HK apps and both Chinese and English notification wording.
- It started as a notification-capture experiment to collect real notification text before writing the parsers, which is why there's a captures screen and a JSON export in there.
- The monthly salary figure is hard-coded in the app for the savings math — change it in `Categories.SALARY_CENTS` if you fork this.
