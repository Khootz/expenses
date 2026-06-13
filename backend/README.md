# Expenses backend (Phase 2)

Cloudflare Worker + D1 database. One worker serves both the JSON API and the dashboard at `/`. Free tier covers this easily (personal volume is a few hundred requests/month).

## Deploy (one time, ~5 min)

Run these from the `backend/` folder. The login step is interactive — in Claude Code, run it yourself with the `!` prefix.

```
npx wrangler login
npx wrangler d1 create expenses        # copy the database_id it prints into wrangler.toml
npx wrangler d1 execute expenses --remote --file=schema.sql
npx wrangler secret put AUTH_TOKEN     # paste a long random string; same token goes in the phone app later
npx wrangler deploy                    # prints your URL, e.g. https://expenses-api.<you>.workers.dev
```

Open the printed URL in a browser → enter the token → dashboard.

## API

All `/api/*` calls need `Authorization: Bearer <AUTH_TOKEN>`.

- `POST /api/transactions` — body `{"transactions": [{id, ts, amountCents, currency?, merchant?, category?, source, kind?, isTransfer?, raw?}]}`. Idempotent: `id` is the client hash, re-posts are ignored.
- `GET /api/transactions?month=2026-06`
- `GET /api/summary?month=2026-06` — spending by category (transfers excluded), savings vs `MONTHLY_INCOME` (27,000, set in wrangler.toml).
- `PATCH /api/transactions/:id` — `{"category": "Food & Groceries", "rulePattern": "7-Eleven"}`; rulePattern is optional and auto-categorizes future matches.

Months are computed in Hong Kong time.

## Not built yet (on purpose)

- Phone→backend sync lands in the recorder app once this is deployed (needs the URL + token).
- Transfer auto-detection (matching Mox top-up ↔ wallet reload pairs) — needs real data from the fixture week first; until then `isTransfer` is set by the parser when the notification itself says "top-up/reload".
