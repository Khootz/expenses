CREATE TABLE IF NOT EXISTS transactions (
  id TEXT PRIMARY KEY,            -- client-side hash: sha256(source|ts|amount|merchant)
  ts INTEGER NOT NULL,            -- epoch millis
  amount_cents INTEGER NOT NULL,  -- positive = money out, negative = money in
  currency TEXT NOT NULL DEFAULT 'HKD',
  merchant TEXT,
  category TEXT,
  source TEXT NOT NULL,           -- alipayhk | mox | octopus | wechat | cash | mox_statement
  kind TEXT NOT NULL DEFAULT 'payment', -- payment | topup | refund | income | unknown
  is_transfer INTEGER NOT NULL DEFAULT 0, -- 1 = wallet top-up etc, excluded from spending
  raw TEXT                        -- original notification text for audit
);
CREATE INDEX IF NOT EXISTS idx_tx_ts ON transactions(ts);

CREATE TABLE IF NOT EXISTS category_rules (
  pattern TEXT PRIMARY KEY,       -- substring matched against merchant, case-insensitive
  category TEXT NOT NULL
);
