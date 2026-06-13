import { DASHBOARD_HTML } from "./dashboard.js";

// All times are computed in Hong Kong time (UTC+8, no DST).
const HK_OFFSET_SECONDS = 8 * 3600;

function monthExpr() {
  return `strftime('%Y-%m', ts / 1000 + ${HK_OFFSET_SECONDS}, 'unixepoch')`;
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function unauthorized() {
  return json({ error: "unauthorized" }, 401);
}

function authed(request, env) {
  const h = request.headers.get("authorization") || "";
  return h === `Bearer ${env.AUTH_TOKEN}`;
}

async function applyCategoryRules(env, merchant) {
  if (!merchant) return null;
  const { results } = await env.DB.prepare(
    "SELECT pattern, category FROM category_rules"
  ).all();
  const m = merchant.toLowerCase();
  for (const r of results) {
    if (m.includes(r.pattern.toLowerCase())) return r.category;
  }
  return null;
}

async function postTransactions(request, env) {
  const body = await request.json();
  const txns = body.transactions || [];
  if (!Array.isArray(txns) || txns.length === 0) {
    return json({ error: "transactions array required" }, 400);
  }
  let inserted = 0;
  for (const t of txns) {
    if (!t.id || !t.ts || typeof t.amountCents !== "number" || !t.source) {
      return json({ error: "each txn needs id, ts, amountCents, source" }, 400);
    }
    const category =
      t.category || (await applyCategoryRules(env, t.merchant)) || null;
    const res = await env.DB.prepare(
      `INSERT OR IGNORE INTO transactions
       (id, ts, amount_cents, currency, merchant, category, source, kind, is_transfer, raw)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        t.id,
        t.ts,
        t.amountCents,
        t.currency || "HKD",
        t.merchant || null,
        category,
        t.source,
        t.kind || "payment",
        t.isTransfer ? 1 : 0,
        t.raw || null
      )
      .run();
    inserted += res.meta.changes;
  }
  return json({ received: txns.length, inserted });
}

async function getTransactions(env, month) {
  const { results } = await env.DB.prepare(
    `SELECT id, ts, amount_cents AS amountCents, currency, merchant, category,
            source, kind, is_transfer AS isTransfer
     FROM transactions
     WHERE ${monthExpr()} = ?
     ORDER BY ts DESC`
  )
    .bind(month)
    .all();
  return json({ month, transactions: results });
}

async function getSummary(env, month) {
  const income = parseInt(env.MONTHLY_INCOME || "27000", 10);
  // Spending = outflows, NETTED against inflows the user assigned to a
  // spending category (a friend FPS-ing you back for dinner, tagged "Food",
  // reduces Food). Inflows left uncategorized or tagged "Income" are NOT
  // spending — they're listed separately in moneyIn.
  const { results: byCategory } = await env.DB.prepare(
    `SELECT COALESCE(category, 'Uncategorized') AS category,
            SUM(amount_cents) AS cents, COUNT(*) AS count
     FROM transactions
     WHERE ${monthExpr()} = ? AND is_transfer = 0
       AND (amount_cents > 0 OR (category IS NOT NULL AND category <> 'Income'))
     GROUP BY COALESCE(category, 'Uncategorized')
     ORDER BY cents DESC`
  )
    .bind(month)
    .all();
  const { results: moneyIn } = await env.DB.prepare(
    `SELECT COALESCE(category, 'Uncategorized') AS category,
            SUM(-amount_cents) AS cents, COUNT(*) AS count
     FROM transactions
     WHERE ${monthExpr()} = ? AND is_transfer = 0 AND amount_cents < 0
       AND (category IS NULL OR category = 'Income')
     GROUP BY COALESCE(category, 'Uncategorized')`
  )
    .bind(month)
    .all();
  const spentCents = byCategory.reduce((a, r) => a + r.cents, 0);
  return json({
    month,
    incomeHkd: income,
    spentCents,
    savingsCents: income * 100 - spentCents,
    byCategory,
    moneyIn,
  });
}

async function patchTransaction(request, env, id) {
  const body = await request.json();
  if (!body.category) return json({ error: "category required" }, 400);
  await env.DB.prepare("UPDATE transactions SET category = ? WHERE id = ?")
    .bind(body.category, id)
    .run();
  // Learn a rule so future transactions from this merchant auto-categorize.
  if (body.rulePattern) {
    await env.DB.prepare(
      "INSERT OR REPLACE INTO category_rules (pattern, category) VALUES (?, ?)"
    )
      .bind(body.rulePattern, body.category)
      .run();
  }
  return json({ ok: true });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/" && request.method === "GET") {
      return new Response(DASHBOARD_HTML, {
        headers: { "content-type": "text/html; charset=utf-8" },
      });
    }

    if (!path.startsWith("/api/")) return json({ error: "not found" }, 404);
    if (!authed(request, env)) return unauthorized();

    const month =
      url.searchParams.get("month") ||
      new Date(Date.now() + HK_OFFSET_SECONDS * 1000)
        .toISOString()
        .slice(0, 7);

    try {
      if (path === "/api/transactions" && request.method === "POST") {
        return await postTransactions(request, env);
      }
      if (path === "/api/transactions" && request.method === "GET") {
        return await getTransactions(env, month);
      }
      if (path === "/api/summary" && request.method === "GET") {
        return await getSummary(env, month);
      }
      const m = path.match(/^\/api\/transactions\/([^/]+)$/);
      if (m && request.method === "PATCH") {
        return await patchTransaction(request, env, m[1]);
      }
      return json({ error: "not found" }, 404);
    } catch (e) {
      return json({ error: String(e) }, 500);
    }
  },
};
