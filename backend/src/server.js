import express from 'express';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const { Pool } = pg;
const app = express();
const port = Number(process.env.PORT || 10000);
const pool = process.env.DATABASE_URL
  ? new Pool({ connectionString: process.env.DATABASE_URL, ssl: { rejectUnauthorized: false } })
  : null;

app.use(express.json({ limit: '1mb' }));
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', process.env.ALLOWED_ORIGIN || '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

const query = (text, values = []) => {
  if (!pool) throw new Error('DATABASE_URL is not configured');
  return pool.query(text, values);
};

const number = (value, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback;
const dateRange = (req) => [req.query.from || req.query.startDate || req.query.date || '1900-01-01', req.query.to || req.query.endDate || req.query.date || '2999-12-31'];
const sendError = (res, error) => res.status(error.code === '23505' ? 409 : 500).json({ error: error.message });

const menuSelect = `SELECT DISTINCT ON (LOWER(BTRIM(category)), LOWER(BTRIM(name)), LOWER(BTRIM(size))) menu_item_id AS "menuItemId", name, category, price, size, active FROM menu_items`;
const inventorySelect = `SELECT item_id AS "itemId", item_name AS "itemName", quantity, reorder_level AS "reorderLevel", last_unit_price AS "lastUnitPrice" FROM inventory_items`;

app.get('/health', async (_req, res) => {
  if (!pool) return res.status(503).json({ status: 'ok', database: 'not-configured' });
  try { await query('SELECT 1'); res.json({ status: 'ok', database: 'connected' }); }
  catch (_error) { res.status(503).json({ status: 'ok', database: 'unavailable' }); }
});

const api = express.Router();
api.get('/status', (_req, res) => res.json({ status: 'ok' }));

api.get('/menu/active', async (_req, res) => { try { res.json((await query(`${menuSelect} WHERE active = TRUE ORDER BY LOWER(BTRIM(category)), LOWER(BTRIM(name)), LOWER(BTRIM(size)), menu_item_id`)).rows); } catch (e) { sendError(res, e); } });
api.get('/menu', async (_req, res) => { try { res.json((await query(`${menuSelect} ORDER BY LOWER(BTRIM(category)), LOWER(BTRIM(name)), LOWER(BTRIM(size)), menu_item_id`)).rows); } catch (e) { sendError(res, e); } });
api.post('/menu/flyer', async (_req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const saved = await client.query('SELECT name, category, price, size, active FROM menu_items ORDER BY menu_item_id');
    if (!saved.rowCount) return res.status(400).json({ error: 'The current menu is empty.' });
    await client.query('DELETE FROM menu_flyer_items');
    await client.query('INSERT INTO menu_flyer_items (name, category, price, size, active) SELECT name, category, price, size, active FROM menu_items ORDER BY menu_item_id');
    await client.query('COMMIT');
    res.json({ savedCount: saved.rowCount });
  } catch (e) { await client.query('ROLLBACK'); sendError(res, e); } finally { client.release(); }
});
api.put('/menu/flyer/restore', async (_req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const saved = await client.query('SELECT name, category, price, size, active FROM menu_flyer_items ORDER BY flyer_item_id');
    if (!saved.rowCount) return res.status(404).json({ error: 'No BKR Flyer menu has been saved yet.' });
    await client.query('DELETE FROM menu_items');
    await client.query('INSERT INTO menu_items (name, category, price, size, active) SELECT name, category, price, size, active FROM menu_flyer_items ORDER BY flyer_item_id');
    await client.query('COMMIT');
    res.json({ restoredCount: saved.rowCount });
  } catch (e) { await client.query('ROLLBACK'); sendError(res, e); } finally { client.release(); }
});
api.post('/menu', async (req, res) => {
  try {
    const name = req.body.name || '';
    const category = req.body.category || '';
    const size = req.body.size || '';
    const existing = await query('SELECT menu_item_id AS "menuItemId" FROM menu_items WHERE LOWER(BTRIM(name)) = LOWER(BTRIM($1)) AND LOWER(BTRIM(category)) = LOWER(BTRIM($2)) AND LOWER(BTRIM(size)) = LOWER(BTRIM($3)) LIMIT 1', [name, category, size]);
    if (existing.rowCount) return res.json(existing.rows[0]);
    const r = await query('INSERT INTO menu_items (name, category, price, size, active) VALUES ($1,$2,$3,$4,$5) RETURNING menu_item_id AS "menuItemId"', [name, category, number(req.body.price), size, req.body.active !== false]); res.status(201).json(r.rows[0]);
  } catch (e) { sendError(res, e); }
});
api.put('/menu', async (req, res) => {
  try { const r = await query('UPDATE menu_items SET name = COALESCE($1, name), category = COALESCE($2, category), price = COALESCE($3, price), size = COALESCE($4, size), active = COALESCE($5, active) WHERE menu_item_id = $6 RETURNING menu_item_id AS "menuItemId"', [req.body.name, req.body.category, req.body.price == null ? null : number(req.body.price), req.body.size, req.body.active == null ? null : Boolean(req.body.active), req.body.menuItemId || req.body.id]); if (!r.rowCount) return res.sendStatus(404); res.json(r.rows[0]); } catch (e) { sendError(res, e); }
});
api.delete('/menu/:id', async (req, res) => { try { await query('DELETE FROM menu_items WHERE menu_item_id = $1', [req.params.id]); res.sendStatus(204); } catch (e) { sendError(res, e); } });

api.get('/inventory', async (req, res) => { try { const search = String(req.query.search || '').trim(); const r = await query(`${inventorySelect} ${search ? 'WHERE item_name ILIKE $1' : ''} ORDER BY item_name`, search ? [`%${search}%`] : []); res.json(r.rows); } catch (e) { sendError(res, e); } });
api.get('/inventory/low-stock', async (_req, res) => { try { res.json((await query(`${inventorySelect} WHERE quantity <= reorder_level ORDER BY item_name`)).rows); } catch (e) { sendError(res, e); } });
api.post('/inventory', async (req, res) => { try { const r = await query('INSERT INTO inventory_items (item_name, quantity, reorder_level, last_unit_price) VALUES ($1,$2,$3,$4) RETURNING item_id AS "itemId"', [req.body.itemName || req.body.name || '', number(req.body.quantity), number(req.body.reorderLevel), number(req.body.lastUnitPrice)]); res.status(201).json(r.rows[0]); } catch (e) { sendError(res, e); } });
api.post('/inventory/adjust', async (req, res) => {
  const items = Array.isArray(req.body?.items) ? req.body.items : Array.isArray(req.body) ? req.body : [];
  if (!items.length) return res.status(400).json({ error: 'No inventory adjustments supplied.' });

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const updated = [];
    for (const entry of items) {
      const itemName = String(entry.itemName || entry.name || '').trim();
      const delta = Number(entry.quantity || 0);
      if (!itemName || !Number.isFinite(delta)) continue;

      const result = await client.query(
        `UPDATE inventory_items
         SET quantity = GREATEST(quantity + $1, 0)
         WHERE LOWER(BTRIM(item_name)) = LOWER(BTRIM($2))
         RETURNING item_id AS "itemId", item_name AS "itemName", quantity`,
        [delta, itemName]
      );

      if (result.rowCount) {
        updated.push(result.rows[0]);
        continue;
      }

      const created = await client.query(
        `INSERT INTO inventory_items (item_name, quantity, reorder_level, last_unit_price)
         VALUES ($1, GREATEST($2, 0), 0, 0)
         RETURNING item_id AS "itemId", item_name AS "itemName", quantity`,
        [itemName, delta]
      );
      updated.push(created.rows[0]);
    }

    await client.query('COMMIT');
    res.json({ updated });
  } catch (e) {
    await client.query('ROLLBACK');
    sendError(res, e);
  } finally {
    client.release();
  }
});
api.delete('/inventory/:id', async (req, res) => { try { await query('DELETE FROM inventory_items WHERE item_id = $1', [req.params.id]); res.sendStatus(204); } catch (e) { sendError(res, e); } });

const nextOrderNo = async (client = pool) => {
  const result = await client.query(`
    SELECT COALESCE(MAX(
      CASE
        WHEN order_no ~ '^[0-9]+$' THEN order_no::int
      END
    ), 0) + 1 AS next_no,
    (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::date AS order_date
    FROM orders
    WHERE order_date = (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::date
  `);
  return String(result.rows[0].next_no);
};

const drinkNames = new Set(['regular', '1 ltr', '1.5 ltr', 'water (small)', 'water (large)', 'ten pack']);
const dealInventoryRules = [
  { match: 'family deal', deductions: [['1.5 Ltr', 1], ['Burger Bun', 4], ['Wings', 6], ['Chicken Thigh', 4]] },
  { match: 'student deal', deductions: [['Regular', 1], ['Burger Bun', 1], ['Chicken Patty', 1]] },
  { match: 'bkr super platter', deductions: [['1 Ltr', 1], ['Nuggets', 10], ['Paratha Roll', 2], ['Burger Bun', 2]] },
  { match: 'deal 1', deductions: [['1 Ltr', 1], ['Burger Bun', 2], ['Chicken Thigh', 2]] },
  { match: 'deal 2', deductions: [['1 Ltr', 1], ['Chicken Broast', 2]] },
  { match: 'deal 3', deductions: [['1 Ltr', 1], ['Wings', 6]] }
];

const normalizeInventoryName = (value) => String(value ?? '').trim().replace(/\s+/g, ' ');

const inventoryNameVariants = (value) => {
  const raw = normalizeInventoryName(value);
  if (!raw) return [];

  const variants = new Set([raw]);
  const lower = raw.toLowerCase();
  const compact = lower.replace(/[^a-z0-9]+/g, ' ').trim();
  const withoutUnitCount = compact.replace(/\b\d+\s*(pcs|pc|ltr|ltrs|litre|liter)\b/g, '').trim();
  const withoutPcs = compact.replace(/\bpcs?\b/g, '').trim();
  const withoutDrink = compact.replace(/\bdrink\b/g, '').trim();

  [raw, lower, compact, withoutUnitCount, withoutPcs, withoutDrink].forEach((item) => {
    if (item) variants.add(item);
  });

  const aliasGroups = [
    { matches: ['nugget'], values: ['nuggets', 'nuggets 10 pcs', 'nuggets 10 pc', 'nuggets 10', 'chicken nuggets', 'nugget'] },
    { matches: ['wing'], values: ['wings', 'wing', 'hot wings', 'hot wings 6 pcs', 'wings 6 pcs', 'wings 6', 'chicken wings'] },
    { matches: ['shwarma', 'shawarma'], values: ['shwarma bread', 'shawarma bread', 'shwarma bread 1', 'shawarma bread 1', 'shwarma', 'shawarma'] },
    { matches: ['burger bun'], values: ['burger bun', 'burger buns', 'bun', 'burger bun 1'] },
    { matches: ['paratha roll'], values: ['paratha roll', 'paratha rolls', 'roll', 'paratha'] },
    { matches: ['wrap'], values: ['wrap', 'wraps', 'chicken wrap'] },
    { matches: ['chicken thigh'], values: ['chicken thigh', 'chicken thighs', 'thigh'] },
    { matches: ['chicken patty'], values: ['chicken patty', 'chicken patties', 'patty'] },
    { matches: ['regular'], values: ['regular', 'regular drink', 'regular 250ml', 'regular 500ml'] },
    { matches: ['1 ltr', '1 liter', '1 litre'], values: ['1 ltr', '1 litre', '1 liter', '1 ltr drink', '1 litre drink', '1 liter drink'] },
    { matches: ['1.5 ltr', '1.5 liter', '1.5 litre'], values: ['1.5 ltr', '1.5 litre', '1.5 liter', '1.5 ltr drink', '1.5 litre drink', '1.5 liter drink'] },
    { matches: ['beef burger'], values: ['beef burger', 'beef burger 1'] }
  ];

  for (const group of aliasGroups) {
    if (group.matches.some((match) => lower.includes(match))) {
      group.values.forEach((item) => variants.add(item));
    }
  }

  return [...variants].map((item) => item.trim()).filter(Boolean);
};

const deductInventoryAmount = async (client, inventoryName, amount) => {
  if (amount <= 0 || !inventoryName) return;

  const variants = inventoryNameVariants(inventoryName);
  if (!variants.length) return;

  const normalizedVariants = variants.map((value) => value.toLowerCase().trim());
  await client.query(
    `UPDATE inventory_items
     SET quantity = GREATEST(quantity - $1, 0)
     WHERE LOWER(BTRIM(item_name)) = ANY($2)`,
    [amount, normalizedVariants]
  );
};

const decrementInventory = async (client, item, quantity) => {
  if (quantity <= 0 || !item.menuItemName) return;

  const menuItemId = Number(item.menuItemId);
  const normalizedName = normalizeInventoryName(item.menuItemName).toLowerCase().replace(/[-_]/g, ' ');
  const compactName = normalizedName.replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim();
  let category = '';
  if (Number.isInteger(menuItemId) && menuItemId > 0) {
    const menuItem = await client.query('SELECT category FROM menu_items WHERE menu_item_id = $1', [menuItemId]);
    category = menuItem.rows[0]?.category?.trim().toLowerCase() || '';
  }

  let deductions = [];
  if (category === 'deals' || (!category && dealInventoryRules.some(({ match }) => compactName.includes(match)))) {
    deductions = dealInventoryRules.find(({ match }) => compactName.includes(match))?.deductions || [];
  } else if (category === 'drinks' || (!category && drinkNames.has(compactName))) {
    deductions = [[item.menuItemName, 1]];
  } else if (category === 'chicken broast' || (!category && compactName.includes('broast'))) {
    if (compactName.includes('full')) {
      deductions = [['Chicken Broast', 8]];
    } else if (compactName.includes('half')) {
      deductions = [['Chicken Broast', 4]];
    } else if (compactName.includes('quarter')) {
      deductions = [['Chicken Broast', 2]];
    }
  } else if (category === 'burger' || (!category && (compactName.includes('burger') || compactName.includes('zinger') || compactName.includes('tower') || compactName.includes('patty fatty') || compactName.includes('beef burger') || compactName.includes('crispy burger')))) {
    deductions = [['Burger Bun', 1]];
    if (compactName.includes('patty fatty') || compactName.includes('beef burger') || compactName.includes('patty burger')) {
      deductions.push(['Chicken Patty', 1]);
    } else if (compactName.includes('tower burger')) {
      deductions.push(['Chicken Patty', 1]);
      deductions.push(['Chicken Thigh', 1]);
    } else if (compactName.includes('zinger burger') || compactName.includes('zinger chees') || compactName.includes('bkr special burger') || compactName.includes('crispy burger')) {
      deductions.push(['Chicken Thigh', 1]);
    }
  } else if (category === 'wrap' || (!category && (compactName.includes('wrap') || compactName.includes('fajita wrap') || compactName.includes('tikka wrap')))) {
    deductions = [['Wrap', 1]];
  } else if (category === 'paratha roll' || (!category && (compactName.includes('roll') || compactName.includes('paratha roll') || compactName.includes('zinger roll')))) {
    deductions = [['Paratha Roll', 1]];
  } else if (category === 'hot wings' || (!category && (compactName.includes('wing') || compactName.includes('nugget')))) {
    deductions = compactName.includes('nugget') ? [['Nuggets', 10]] : [['Wings', 6]];
  } else if (category === 'shawarma' || (!category && (compactName.includes('shawarma') || compactName.includes('shwarma')))) {
    deductions = [['Shawarma Bread', 1]];
  }

  for (const [inventoryName, unitsPerOrder] of deductions) {
    await deductInventoryAmount(client, inventoryName, quantity * unitsPerOrder);
  }
};

api.get('/orders/generate-no', async (_req, res) => {
  try { res.json({ orderNo: await nextOrderNo() }); } catch (e) { sendError(res, e); }
});
api.get('/orders', async (req, res) => { try { const [from, to] = dateRange(req); res.json((await query('SELECT order_id AS "orderId", order_no AS "orderNo", order_date::text AS "orderDate", order_time::text AS "orderTime", customer_name AS "customerName", order_type AS "orderType", table_number AS "tableNumber", payment_type AS "paymentType", subtotal, discount, delivery_charge AS "deliveryCharge", service_charge_percent AS "serviceChargePercent", service_charge AS "serviceCharge", total_amount AS "totalAmount", status FROM orders WHERE order_date BETWEEN $1 AND $2 ORDER BY order_date DESC, order_time DESC', [from, to])).rows); } catch (e) { sendError(res, e); } });
api.get('/orders/range', async (req, res) => { try { const [from, to] = dateRange(req); res.json((await query('SELECT order_id AS "orderId", order_no AS "orderNo", order_date::text AS "orderDate", order_time::text AS "orderTime", customer_name AS "customerName", order_type AS "orderType", table_number AS "tableNumber", payment_type AS "paymentType", subtotal, discount, delivery_charge AS "deliveryCharge", service_charge_percent AS "serviceChargePercent", service_charge AS "serviceCharge", total_amount AS "totalAmount", status FROM orders WHERE order_date BETWEEN $1 AND $2 ORDER BY order_date DESC, order_time DESC', [from, to])).rows); } catch (e) { sendError(res, e); } });
api.get('/orders/sales', async (req, res) => { try { const [from, to] = dateRange(req); const r = await query('SELECT COALESCE(SUM(total_amount),0) AS "totalSales" FROM orders WHERE order_date BETWEEN $1 AND $2', [from, to]); res.json(r.rows[0]); } catch (e) { sendError(res, e); } });
api.get('/sales/date/:date', async (req, res) => { try { const r = await query('SELECT COALESCE(SUM(total_amount),0) AS "totalSales", COUNT(*)::int AS "orderCount" FROM orders WHERE order_date = $1', [req.params.date]); res.json(r.rows[0]); } catch (e) { sendError(res, e); } });
api.get('/orders/count', async (req, res) => { try { const [from, to] = dateRange(req); const r = await query('SELECT COUNT(*)::int AS "orderCount" FROM orders WHERE order_date BETWEEN $1 AND $2', [from, to]); res.json(r.rows[0]); } catch (e) { sendError(res, e); } });
api.get('/orders/item-sales', async (req, res) => { try { const [from, to] = dateRange(req); res.json((await query('SELECT menu_item_name AS "itemName", SUM(quantity)::int AS quantity, SUM(line_total) AS "totalSales" FROM order_items oi JOIN orders o USING (order_id) WHERE o.order_date BETWEEN $1 AND $2 GROUP BY menu_item_name ORDER BY menu_item_name', [from, to])).rows); } catch (e) { sendError(res, e); } });
api.get('/orders/:id', async (req, res) => { try { const order = (await query('SELECT order_id AS "orderId", order_no AS "orderNo", order_date::text AS "orderDate", order_time::text AS "orderTime", customer_name AS "customerName", order_type AS "orderType", table_number AS "tableNumber", payment_type AS "paymentType", subtotal, discount, delivery_charge AS "deliveryCharge", service_charge_percent AS "serviceChargePercent", service_charge AS "serviceCharge", total_amount AS "totalAmount", status FROM orders WHERE order_id = $1', [req.params.id])).rows[0]; if (!order) return res.sendStatus(404); order.items = (await query('SELECT menu_item_id AS "menuItemId", menu_item_name AS "menuItemName", quantity, unit_price AS "unitPrice", line_total AS "lineTotal" FROM order_items WHERE order_id = $1 ORDER BY order_item_id', [req.params.id])).rows; res.json(order); } catch (e) { sendError(res, e); } });
api.post('/orders', async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query("SELECT pg_advisory_xact_lock(hashtext('orders-' || (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::date::text))");
    const b = req.body;
    const orderNo = await nextOrderNo(client);
    const order = (await client.query("INSERT INTO orders (order_no, order_date, order_time, customer_name, order_type, table_number, payment_type, subtotal, discount, delivery_charge, service_charge_percent, service_charge, total_amount, status) VALUES ($1, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::date, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::time, $2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12) RETURNING order_id AS \"orderId\", order_no AS \"orderNo\"", [orderNo, b.customerName || '', b.orderType || 'Dine In', b.tableNumber || '', b.paymentType || 'Cash', number(b.subtotal), number(b.discount), number(b.deliveryCharge), number(b.serviceChargePercent), number(b.serviceCharge), number(b.totalAmount), b.status || 'Completed'])).rows[0];
    for (const item of b.items || []) {
      const qty = number(item.quantity, 1);
      await client.query('INSERT INTO order_items (order_id, menu_item_id, menu_item_name, quantity, unit_price, line_total) VALUES ($1,$2,$3,$4,$5,$6)', [order.orderId, item.menuItemId || 0, item.menuItemName || '', qty, number(item.unitPrice), number(item.lineTotal)]);
      await decrementInventory(client, item, qty);
    }
    await client.query('COMMIT');
    res.status(201).json(order);
  } catch (e) { await client.query('ROLLBACK'); sendError(res, e); } finally { client.release(); }
});

api.get('/stock-in/generate-no', (_req, res) => res.json({ billNo: `STK-${new Date().toISOString().slice(0, 10).replaceAll('-', '')}-${String(Date.now()).slice(-4)}` }));
api.get('/stock-in', async (req, res) => { try { const [from, to] = dateRange(req); res.json((await query('SELECT stock_in_id AS "stockInId", bill_no AS "billNo", stock_date::text AS "stockDate", total_amount AS "totalAmount", remarks FROM stock_ins WHERE stock_date BETWEEN $1 AND $2 ORDER BY stock_date DESC, stock_in_id DESC', [from, to])).rows); } catch (e) { sendError(res, e); } });
api.get('/stock-in/range', async (req, res) => { try { const [from, to] = dateRange(req); res.json((await query('SELECT stock_in_id AS "stockInId", bill_no AS "billNo", stock_date::text AS "stockDate", total_amount AS "totalAmount", remarks FROM stock_ins WHERE stock_date BETWEEN $1 AND $2 ORDER BY stock_date DESC, stock_in_id DESC', [from, to])).rows); } catch (e) { sendError(res, e); } });
api.get('/stock-in/:id', async (req, res) => { try { const r = (await query('SELECT stock_in_id AS "stockInId", bill_no AS "billNo", stock_date::text AS "stockDate", total_amount AS "totalAmount", remarks FROM stock_ins WHERE stock_in_id = $1', [req.params.id])).rows[0]; if (!r) return res.sendStatus(404); r.items = (await query('SELECT item_id AS "itemId", item_name AS "itemName", quantity, unit_price AS "unitPrice", line_total AS "lineTotal" FROM stock_in_items WHERE stock_in_id = $1', [req.params.id])).rows; res.json(r); } catch (e) { sendError(res, e); } });
api.post('/stock-in', async (req, res) => { const client = await pool.connect(); try { await client.query('BEGIN'); const b = req.body; const stock = (await client.query('INSERT INTO stock_ins (bill_no, remarks, total_amount) VALUES ($1,$2,$3) RETURNING stock_in_id AS "stockInId"', [b.billNo, b.remarks || '', number(b.totalAmount)])).rows[0]; for (const item of b.items || []) { const itemId = number(item.itemId); const values = [stock.stockInId, itemId, item.itemName || '', number(item.quantity), number(item.unitPrice), number(item.lineTotal)]; await client.query('INSERT INTO stock_in_items (stock_in_id, item_id, item_name, quantity, unit_price, line_total) VALUES ($1,$2,$3,$4,$5,$6)', values); if (itemId > 0) await client.query('INSERT INTO inventory_items (item_id, item_name, quantity, last_unit_price) VALUES ($1,$2,$3,$4) ON CONFLICT (item_name) DO UPDATE SET quantity = inventory_items.quantity + EXCLUDED.quantity, last_unit_price = EXCLUDED.last_unit_price', [itemId, item.itemName || '', number(item.quantity), number(item.unitPrice)]); else await client.query('INSERT INTO inventory_items (item_name, quantity, last_unit_price) VALUES ($1,$2,$3) ON CONFLICT (item_name) DO UPDATE SET quantity = inventory_items.quantity + EXCLUDED.quantity, last_unit_price = EXCLUDED.last_unit_price', [item.itemName || '', number(item.quantity), number(item.unitPrice)]); } await client.query('COMMIT'); res.status(201).json(stock); } catch (e) { await client.query('ROLLBACK'); sendError(res, e); } finally { client.release(); } });

const purchaseUnits = new Set(['packet', 'kg', 'g', 'L', 'mL', 'piece', 'box']);
api.get('/purchase-history/items', async (_req, res) => {
  try {
    res.json((await query('SELECT item_id AS "itemId", item_name AS "itemName" FROM purchase_history_items ORDER BY LOWER(item_name), item_id')).rows);
  } catch (e) { sendError(res, e); }
});
api.post('/purchase-history/items', async (req, res) => {
  const itemName = String(req.body?.itemName || '').trim();
  if (!itemName || itemName.length > 100) return res.status(400).json({ error: 'Enter an item name up to 100 characters.' });
  try {
    const result = await query('INSERT INTO purchase_history_items (item_name) VALUES ($1) ON CONFLICT (LOWER(BTRIM(item_name))) DO NOTHING RETURNING item_id AS "itemId", item_name AS "itemName"', [itemName]);
    if (result.rowCount) return res.status(201).json(result.rows[0]);
    const existing = await query('SELECT item_id AS "itemId", item_name AS "itemName" FROM purchase_history_items WHERE LOWER(BTRIM(item_name)) = LOWER(BTRIM($1))', [itemName]);
    return res.status(409).json({ error: `"${existing.rows[0]?.itemName || itemName}" already exists.` });
  } catch (e) { sendError(res, e); }
});
api.delete('/purchase-history/items/:id', async (req, res) => {
  try {
    const result = await query('DELETE FROM purchase_history_items WHERE item_id = $1 RETURNING item_id', [req.params.id]);
    if (!result.rowCount) return res.sendStatus(404);
    res.sendStatus(204);
  } catch (e) { sendError(res, e); }
});
api.get('/purchase-history/items/:id/records', async (req, res) => {
  const [from, to] = dateRange(req);
  try {
    const records = await query(
      `SELECT purchase_id AS "purchaseId", purchase_date::text AS date, quantity, unit,
              unit_price AS "unitPrice", total_paid AS "totalPaid"
       FROM purchase_history_records
       WHERE item_id = $1 AND purchase_date BETWEEN $2 AND $3
       ORDER BY purchase_date DESC, purchase_id DESC`,
      [req.params.id, from, to]
    );
    const total = await query(
      'SELECT COALESCE(SUM(total_paid), 0) AS "totalPaid" FROM purchase_history_records WHERE item_id = $1 AND purchase_date BETWEEN $2 AND $3',
      [req.params.id, from, to]
    );
    res.json({ records: records.rows, totalPaid: total.rows[0].totalPaid });
  } catch (e) { sendError(res, e); }
});
api.post('/purchase-history/items/:id/records', async (req, res) => {
  const { date, unit } = req.body || {};
  const quantity = Number(req.body?.quantity);
  const totalPaid = Number(req.body?.totalPaid);
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(`${date}T00:00:00Z`)) || new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) !== date) {
    return res.status(400).json({ error: 'Enter a valid purchase date.' });
  }
  if (!Number.isFinite(quantity) || quantity <= 0 || quantity > 999999999.999 || Math.abs(quantity * 1000 - Math.round(quantity * 1000)) > 1e-7) {
    return res.status(400).json({ error: 'Quantity must be greater than zero and have no more than 3 decimal places.' });
  }
  if (!Number.isFinite(totalPaid) || totalPaid < 0 || totalPaid > 9999999999.99 || Math.abs(totalPaid * 100 - Math.round(totalPaid * 100)) > 1e-6) {
    return res.status(400).json({ error: 'Total purchase price must be valid and have no more than 2 decimal places.' });
  }
  if (!purchaseUnits.has(unit)) return res.status(400).json({ error: 'Select a supported unit.' });
  try {
    const result = await query(
      `INSERT INTO purchase_history_records (item_id, purchase_date, quantity, unit, total_paid, unit_price)
       SELECT item_id, $2, $3, $4, $5, ROUND($5::numeric / $3::numeric, 4)
       FROM purchase_history_items WHERE item_id = $1
       RETURNING purchase_id AS "purchaseId", purchase_date::text AS date, quantity, unit,
                 unit_price AS "unitPrice", total_paid AS "totalPaid"`,
      [req.params.id, date, quantity, unit, totalPaid]
    );
    if (!result.rowCount) return res.sendStatus(404);
    res.status(201).json(result.rows[0]);
  } catch (e) { sendError(res, e); }
});

app.use('/api', api);
app.use((err, _req, res, _next) => sendError(res, err));

async function start() {
  if (pool) { const schema = await fs.readFile(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'schema.sql'), 'utf8'); await pool.query(schema); }
  app.listen(port, '0.0.0.0', () => console.log(`BKR backend listening on ${port}`));
}

start().catch((error) => { console.error(error); process.exit(1); });
