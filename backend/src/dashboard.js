export const DASHBOARD_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Expenses</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body { font-family: -apple-system, "Segoe UI", Roboto, sans-serif; margin: 0;
         background: #f4f5f7; color: #1a1a2e; }
  @media (prefers-color-scheme: dark) { body { background: #14151a; color: #eee; } }
  .wrap { max-width: 640px; margin: 0 auto; padding: 16px; }
  .topbar { display: flex; justify-content: space-between; align-items: center; }
  h1 { font-size: 20px; margin: 8px 0; }
  input[type=month] { font-size: 15px; padding: 6px 8px; border-radius: 8px;
         border: 1px solid #ccc; background: inherit; color: inherit; }
  .card { background: #fff; border-radius: 16px; padding: 16px; margin-top: 12px;
          box-shadow: 0 1px 3px rgba(0,0,0,.06); }
  @media (prefers-color-scheme: dark) { .card { background: #1f2128; } }
  .savings { font-size: 34px; font-weight: 700; }
  .savings.neg { color: #e5484d; }
  .savings.pos { color: #30a46c; }
  .sub { color: #888; font-size: 13px; margin-top: 4px; }
  .bar-row { margin-top: 10px; }
  .bar-label { display: flex; justify-content: space-between; font-size: 13px; }
  .bar-track { height: 8px; border-radius: 4px; background: rgba(128,128,128,.18); margin-top: 3px; }
  .bar-fill { height: 100%; border-radius: 4px; background: #4f76f6; }
  .txn { display: flex; justify-content: space-between; padding: 9px 0;
         border-bottom: 1px solid rgba(128,128,128,.15); font-size: 14px; cursor: pointer; }
  .txn:last-child { border-bottom: none; }
  .txn .meta { color: #888; font-size: 12px; }
  .txn .amt { font-weight: 600; white-space: nowrap; margin-left: 10px; }
  .pill { display: inline-block; font-size: 11px; padding: 1px 8px; border-radius: 10px;
          background: rgba(79,118,246,.15); color: #4f76f6; margin-left: 6px; }
  .muted { color: #888; }
</style>
</head>
<body>
<div class="wrap">
  <div class="topbar">
    <h1>Expenses</h1>
    <input type="month" id="month">
  </div>
  <div class="card">
    <div id="savings" class="savings">–</div>
    <div id="savingsSub" class="sub"></div>
  </div>
  <div class="card">
    <strong style="font-size:14px">By category</strong>
    <div id="bars"><div class="muted" style="margin-top:8px">No data</div></div>
  </div>
  <div class="card">
    <strong style="font-size:14px">Transactions</strong>
    <div id="txns"><div class="muted" style="margin-top:8px">No data</div></div>
  </div>
</div>
<script>
const fmt = c => 'HK$' + (c / 100).toLocaleString('en-HK', {minimumFractionDigits: 2});
function token() {
  let t = localStorage.getItem('token');
  if (!t) { t = prompt('API token:'); if (t) localStorage.setItem('token', t); }
  return t;
}
async function api(path) {
  const r = await fetch(path, { headers: { authorization: 'Bearer ' + token() } });
  if (r.status === 401) { localStorage.removeItem('token'); throw new Error('bad token — reload'); }
  return r.json();
}
function hkMonth() {
  return new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 7);
}
async function load() {
  const month = document.getElementById('month').value;
  const [s, t] = await Promise.all([
    api('/api/summary?month=' + month),
    api('/api/transactions?month=' + month),
  ]);
  const sav = document.getElementById('savings');
  sav.textContent = fmt(s.savingsCents);
  sav.className = 'savings ' + (s.savingsCents >= 0 ? 'pos' : 'neg');
  document.getElementById('savingsSub').textContent =
    'saved of HK$' + s.incomeHkd.toLocaleString() + ' income · spent ' + fmt(s.spentCents);

  const bars = document.getElementById('bars');
  bars.innerHTML = '';
  const max = Math.max(...s.byCategory.map(c => c.cents), 1);
  for (const c of s.byCategory) {
    const row = document.createElement('div');
    row.className = 'bar-row';
    row.innerHTML = '<div class="bar-label"><span>' + c.category + ' <span class="muted">×' +
      c.count + '</span></span><span>' + fmt(c.cents) + '</span></div>' +
      '<div class="bar-track"><div class="bar-fill" style="width:' +
      (100 * c.cents / max) + '%"></div></div>';
    bars.appendChild(row);
  }
  if (!s.byCategory.length) bars.innerHTML = '<div class="muted" style="margin-top:8px">No spending recorded</div>';
  if (s.moneyIn && s.moneyIn.length) {
    const hdr = document.createElement('div');
    hdr.className = 'muted';
    hdr.style.marginTop = '12px';
    hdr.style.fontSize = '12px';
    hdr.textContent = 'Money in (not counted as spending — tap a transaction below to assign it to a category and offset it):';
    bars.appendChild(hdr);
    for (const c of s.moneyIn) {
      const row = document.createElement('div');
      row.className = 'bar-label';
      row.style.marginTop = '4px';
      row.innerHTML = '<span>' + c.category + ' <span class="muted">×' + c.count +
        '</span></span><span style="color:#30a46c">+' + fmt(c.cents) + '</span>';
      bars.appendChild(row);
    }
  }

  const txns = document.getElementById('txns');
  txns.innerHTML = '';
  for (const x of t.transactions) {
    const d = new Date(x.ts).toLocaleString('en-HK', {timeZone: 'Asia/Hong_Kong',
      month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'});
    const row = document.createElement('div');
    row.className = 'txn';
    row.innerHTML = '<div><div>' + (x.merchant || '(unknown)') +
      (x.isTransfer ? '<span class="pill">transfer</span>' : '') +
      (x.category ? '<span class="pill">' + x.category + '</span>' : '') +
      '</div><div class="meta">' + d + ' · ' + x.source + '</div></div>' +
      '<div class="amt">' + fmt(x.amountCents) + '</div>';
    row.onclick = async () => {
      const cat = prompt('Category for "' + (x.merchant || '?') + '":', x.category || '');
      if (!cat) return;
      const rule = x.merchant && confirm('Always categorize "' + x.merchant + '" as ' + cat + '?');
      await fetch('/api/transactions/' + x.id, {
        method: 'PATCH',
        headers: { authorization: 'Bearer ' + token(), 'content-type': 'application/json' },
        body: JSON.stringify({ category: cat, rulePattern: rule ? x.merchant : undefined }),
      });
      load();
    };
    txns.appendChild(row);
  }
  if (!t.transactions.length) txns.innerHTML = '<div class="muted" style="margin-top:8px">No transactions</div>';
}
const m = document.getElementById('month');
m.value = hkMonth();
m.onchange = load;
load().catch(e => alert(e.message));
</script>
</body>
</html>`;
