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

api.get('/offline/bootstrap', async (_req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
    const menu = (await client.query(`${menuSelect} ORDER BY LOWER(BTRIM(category)), LOWER(BTRIM(name)), LOWER(BTRIM(size)), menu_item_id`)).rows;
    const inventory = (await client.query(`${inventorySelect} ORDER BY item_name`)).rows;
    const orders = (await client.query(
      `SELECT order_id AS "orderId", order_no AS "orderNo", order_date::text AS "orderDate",
              order_time::text AS "orderTime", customer_name AS "customerName", order_type AS "orderType",
              table_number AS "tableNumber", payment_type AS "paymentType", subtotal, discount,
              delivery_charge AS "deliveryCharge", service_charge_percent AS "serviceChargePercent",
              service_charge AS "serviceCharge", total_amount AS "totalAmount", status,
              client_order_id AS "clientOrderId"
       FROM orders ORDER BY order_date, order_time, order_id`
    )).rows;
    const orderItems = (await client.query(
      `SELECT order_id AS "orderId", menu_item_id AS "menuItemId", menu_item_name AS "menuItemName",
              quantity, unit_price AS "unitPrice", line_total AS "lineTotal"
       FROM order_items ORDER BY order_item_id`
    )).rows;
    const orderById = new Map(orders.map((order) => [order.orderId, order]));
    for (const item of orderItems) {
      const order = orderById.get(item.orderId);
      if (order) (order.items ||= []).push(item);
    }

    const stockIns = (await client.query(
      `SELECT stock_in_id AS "stockInId", bill_no AS "billNo", stock_date::text AS "stockDate",
              total_amount AS "totalAmount", remarks, client_stock_id AS "clientStockId"
       FROM stock_ins ORDER BY stock_date, stock_in_id`
    )).rows;
    const stockItems = (await client.query(
      `SELECT stock_in_id AS "stockInId", item_id AS "itemId", item_name AS "itemName",
              quantity, unit_price AS "unitPrice", line_total AS "lineTotal"
       FROM stock_in_items ORDER BY stock_in_item_id`
    )).rows;
    const stockById = new Map(stockIns.map((bill) => [bill.stockInId, bill]));
    for (const item of stockItems) {
      const bill = stockById.get(item.stockInId);
      if (bill) (bill.items ||= []).push(item);
    }

    const purchaseItems = (await client.query(
      'SELECT item_id AS "itemId", item_name AS "itemName" FROM purchase_history_items ORDER BY LOWER(item_name), item_id'
    )).rows;
    const purchaseRecords = (await client.query(
      `SELECT purchase_id AS "purchaseId", item_id AS "itemId", purchase_date::text AS date,
              quantity, unit, unit_price AS "unitPrice", total_paid AS "totalPaid"
       FROM purchase_history_records ORDER BY purchase_date, purchase_id`
    )).rows;
    const flyerMenu = (await client.query(
      'SELECT name, category, price, size, active FROM menu_flyer_items ORDER BY flyer_item_id'
    )).rows;
    const closingReports = (await client.query(
      `SELECT report_date::text AS date, opening_cash AS "openingCash", cash_sales AS "cashSales",
              online_sales AS "onlineSales", total_sales AS sales, total_expenses AS "expensesTotal",
              ending_cash AS "endingCash", expenses
       FROM daily_closing_reports ORDER BY report_date`
    )).rows;

    await client.query('COMMIT');
    res.json({ menu, inventory, orders, stockIns, purchaseItems, purchaseRecords, closingReports, flyerMenu, adjustments: [], pending: [] });
  } catch (e) {
    await client.query('ROLLBACK');
    sendError(res, e);
  } finally {
    client.release();
  }
});

api.post('/offline/sync/clear', async (req, res) => {
  const { clientOperationId, target } = req.body || {};
  if (typeof clientOperationId !== 'string' || !clientOperationId.trim()
      || !['inventory', 'menu'].includes(target)) {
    return res.status(400).json({ error: 'A unique operation ID and a supported clear target are required.' });
  }
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`offline-clear-${clientOperationId}`]);
    const existing = await client.query(
      'SELECT 1 FROM offline_sync_operations WHERE client_operation_id = $1',
      [clientOperationId]
    );
    if (existing.rowCount) {
      await client.query('COMMIT');
      return res.json({ clientOperationId, target });
    }
    await client.query(target === 'inventory'
      ? 'DELETE FROM inventory_items'
      : 'DELETE FROM menu_items');
    await client.query(
      'INSERT INTO offline_sync_operations (client_operation_id, operation_type) VALUES ($1, $2)',
      [clientOperationId, `clear-${target}`]
    );
    await client.query('COMMIT');
    res.json({ clientOperationId, target });
  } catch (e) {
    await client.query('ROLLBACK');
    sendError(res, e);
  } finally {
    client.release();
  }
});

api.post('/offline/sync/flyer', async (req, res) => {
  const { clientOperationId, operation, items } = req.body || {};
  if (typeof clientOperationId !== 'string' || !clientOperationId.trim()
      || !['save', 'restore'].includes(operation)
      || !Array.isArray(items) || items.length > 500) {
    return res.status(400).json({ error: 'A unique operation ID and a valid flyer menu are required.' });
  }
  const menuItems = [];
  for (const item of items) {
    const name = String(item?.name || '').trim();
    const category = String(item?.category || '').trim();
    const size = String(item?.size || '').trim();
    const price = Number(item?.price);
    if (!name || !Number.isFinite(price) || price < 0) {
      return res.status(400).json({ error: 'Each flyer menu item needs a name, category, and valid price.' });
    }
    menuItems.push({ name, category, size, price, active: item?.active !== false });
  }
  if (!menuItems.length) return res.status(400).json({ error: 'The flyer menu cannot be empty.' });

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`offline-flyer-${clientOperationId}`]);
    const existing = await client.query(
      'SELECT 1 FROM offline_sync_operations WHERE client_operation_id = $1',
      [clientOperationId]
    );
    if (existing.rowCount) {
      const restoredItems = operation === 'restore'
        ? (await client.query(
          'SELECT menu_item_id AS "menuItemId", name, category, price, size, active FROM menu_items ORDER BY menu_item_id'
        )).rows : [];
      await client.query('COMMIT');
      return res.json({ clientOperationId, operation, count: menuItems.length, items: restoredItems });
    }
    if (operation === 'save') {
      await client.query('DELETE FROM menu_flyer_items');
      for (const item of menuItems) {
        await client.query(
          'INSERT INTO menu_flyer_items (name, category, price, size, active) VALUES ($1,$2,$3,$4,$5)',
          [item.name, item.category, item.price, item.size, item.active]
        );
      }
    } else {
      await client.query('DELETE FROM menu_items');
      for (const item of menuItems) {
        const inserted = await client.query(
          `INSERT INTO menu_items (name, category, price, size, active)
           VALUES ($1,$2,$3,$4,$5)
           RETURNING menu_item_id AS "menuItemId", name, category, price, size, active`,
          [item.name, item.category, item.price, item.size, item.active]
        );
        item.menuItemId = inserted.rows[0].menuItemId;
      }
    }
    await client.query(
      'INSERT INTO offline_sync_operations (client_operation_id, operation_type) VALUES ($1, $2)',
      [clientOperationId, `flyer-${operation}`]
    );
    await client.query('COMMIT');
    res.json({ clientOperationId, operation, count: menuItems.length,
      items: operation === 'restore' ? menuItems : [] });
  } catch (e) {
    await client.query('ROLLBACK');
    sendError(res, e);
  } finally {
    client.release();
  }
});

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
api.post('/inventory', async (req, res) => {
  try {
    const clientInventoryId = typeof req.body?.clientInventoryId === 'string'
      && req.body.clientInventoryId.trim() ? req.body.clientInventoryId.trim() : null;
    if (clientInventoryId) {
      const inserted = await query(
        `INSERT INTO inventory_items
           (item_name, quantity, reorder_level, last_unit_price, client_inventory_id)
         VALUES ($1,$2,$3,$4,$5)
         ON CONFLICT (client_inventory_id) WHERE client_inventory_id IS NOT NULL DO NOTHING
         RETURNING item_id AS "itemId"`,
        [req.body.itemName || req.body.name || '', number(req.body.quantity),
          number(req.body.reorderLevel), number(req.body.lastUnitPrice), clientInventoryId]
      );
      if (inserted.rowCount) return res.status(201).json(inserted.rows[0]);
      const existing = await query(
        'SELECT item_id AS "itemId" FROM inventory_items WHERE client_inventory_id = $1',
        [clientInventoryId]
      );
      if (existing.rowCount) return res.json(existing.rows[0]);
      return res.status(409).json({ error: 'The inventory item name is already in use.' });
    }
    const inserted = await query(
      'INSERT INTO inventory_items (item_name, quantity, reorder_level, last_unit_price) VALUES ($1,$2,$3,$4) RETURNING item_id AS "itemId"',
      [req.body.itemName || req.body.name || '', number(req.body.quantity),
        number(req.body.reorderLevel), number(req.body.lastUnitPrice)]
    );
    res.status(201).json(inserted.rows[0]);
  } catch (e) { sendError(res, e); }
});
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
      const clientAdjustmentId = String(req.body?.clientAdjustmentId || entry.clientAdjustmentId || '').trim();
      if (clientAdjustmentId) {
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`inventory-adjustment-${clientAdjustmentId}`]);
        const existing = await client.query(
          'SELECT 1 FROM inventory_adjustments WHERE client_adjustment_id = $1',
          [clientAdjustmentId]
        );
        if (existing.rowCount) continue;
      }

      const result = await client.query(
        `UPDATE inventory_items
         SET quantity = GREATEST(quantity + $1, 0)
         WHERE LOWER(BTRIM(item_name)) = LOWER(BTRIM($2))
         RETURNING item_id AS "itemId", item_name AS "itemName", quantity`,
        [delta, itemName]
      );

      if (result.rowCount) {
        updated.push(result.rows[0]);
        if (clientAdjustmentId) {
          await client.query(
            `INSERT INTO inventory_adjustments
              (client_adjustment_id, item_name, quantity_delta, adjustment_date, note)
             VALUES ($1, $2, $3, COALESCE($4::date, CURRENT_DATE), $5)`,
            [clientAdjustmentId, itemName, delta, entry.date || null, entry.note || '']
          );
        }
        continue;
      }

      const created = await client.query(
        `INSERT INTO inventory_items (item_name, quantity, reorder_level, last_unit_price)
         VALUES ($1, GREATEST($2, 0), 0, 0)
         RETURNING item_id AS "itemId", item_name AS "itemName", quantity`,
        [itemName, delta]
      );
      updated.push(created.rows[0]);
      if (clientAdjustmentId) {
        await client.query(
          `INSERT INTO inventory_adjustments
            (client_adjustment_id, item_name, quantity_delta, adjustment_date, note)
           VALUES ($1, $2, $3, COALESCE($4::date, CURRENT_DATE), $5)`,
          [clientAdjustmentId, itemName, delta, entry.date || null, entry.note || '']
        );
      }
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
  { match: 'bkr super platter', deductions: [['1 Ltr', 1], ['Burger Bun', 2], ['Nuggets', 10], ['Paratha Roll', 2], ['Chicken Patty', 2]] },
  { match: 'deal 1', deductions: [['1 Ltr', 1], ['Burger Bun', 2], ['Chicken Thigh', 2]] },
  { match: 'deal 2', deductions: [['1 Ltr', 1]] },
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
    { matches: ['shwarma', 'shawarma'], values: ['shawarma bread', 'shwarma bread', 'shwarma bread 1', 'shawarma bread 1', 'shawarma', 'shwarma'] },
    { matches: ['paratha roll'], values: ['paratha roll', 'paratha rolls', 'roll', 'paratha'] },
    { matches: ['wrap'], values: ['wrap', 'wraps', 'chicken wrap'] },
    { matches: ['chicken thigh'], values: ['chicken thigh', 'chicken thighs', 'thigh'] },
    { matches: ['chicken patty'], values: ['chicken patty', 'chicken patties', 'patty'] },
    { matches: ['beef patty'], values: ['beef patty', 'beef patties', 'beef burger'] },
    { matches: ['regular'], values: ['regular', 'regular drink', 'regular 250ml', 'regular 500ml'] },
    { matches: ['1 ltr', '1 liter', '1 litre'], values: ['1 ltr', '1 litre', '1 liter', '1 ltr drink', '1 litre drink', '1 liter drink'] },
    { matches: ['1.5 ltr', '1.5 liter', '1.5 litre'], values: ['1.5 ltr', '1.5 litre', '1.5 liter', '1.5 ltr drink', '1.5 litre drink', '1.5 liter drink'] }
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
  let category = String(item.category || '').trim().toLowerCase();
  if (Number.isInteger(menuItemId) && menuItemId > 0) {
    const menuItem = await client.query('SELECT category FROM menu_items WHERE menu_item_id = $1', [menuItemId]);
    category = menuItem.rows[0]?.category?.trim().toLowerCase() || category;
  }

  let deductions = [];
  if (category === 'deals' || (!category && dealInventoryRules.some(({ match }) => compactName.includes(match)))) {
    deductions = dealInventoryRules.find(({ match }) => compactName.includes(match))?.deductions || [];
  } else if (category === 'drinks' || (!category && drinkNames.has(compactName))) {
    deductions = [[item.menuItemName, 1]];
  } else if (category === 'burger' || (!category && (compactName.includes('burger') || compactName.includes('zinger') || compactName.includes('tower') || compactName.includes('patty fatty') || compactName.includes('beef burger') || compactName.includes('crispy burger')))) {
    deductions = [['Burger Bun', 1]];
    if (compactName.includes('patty fatty') || compactName.includes('beef burger') || compactName.includes('patty burger')) {
      deductions.push(['Beef Patty', 1]);
    } else if (compactName.includes('bkr special burger')) {
      deductions.push(['Beef Patty', 1]);
    } else if (compactName.includes('tower burger')) {
      deductions.push(['Chicken Patty', 1]);
      deductions.push(['Chicken Thigh', 1]);
    } else if (compactName.includes('zinger burger') || compactName.includes('zinger chees') || compactName.includes('crispy burger') || compactName.includes('chicken burger')) {
      deductions.push(['Chicken Thigh', 1]);
    } else if (compactName.includes('burger')) {
      deductions.push(['Chicken Patty', 1]);
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
    const b = req.body;
    const clientOrderId = typeof b.clientOrderId === 'string' && b.clientOrderId.trim()
      ? b.clientOrderId.trim() : null;
    if (clientOrderId) {
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`offline-order-${clientOrderId}`]);
      const existing = (await client.query(
        `SELECT order_id AS "orderId", order_no AS "orderNo", order_date::text AS "orderDate",
                order_time::text AS "orderTime", client_order_id AS "clientOrderId"
         FROM orders WHERE client_order_id = $1`,
        [clientOrderId]
      )).rows[0];
      if (existing) {
        await client.query('COMMIT');
        return res.json(existing);
      }
    } else {
      await client.query("SELECT pg_advisory_xact_lock(hashtext('orders-' || (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::date::text))");
    }
    const orderNo = clientOrderId && b.orderNo ? String(b.orderNo) : await nextOrderNo(client);
    const order = (await client.query(
      `INSERT INTO orders
         (order_no, order_date, order_time, customer_name, order_type, table_number, payment_type,
          subtotal, discount, delivery_charge, service_charge_percent, service_charge, total_amount,
          status, client_order_id)
       VALUES ($1, COALESCE($2::date, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::date),
          COALESCE($3::time, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::time),
          $4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
       RETURNING order_id AS "orderId", order_no AS "orderNo", order_date::text AS "orderDate",
          order_time::text AS "orderTime", client_order_id AS "clientOrderId"`,
      [orderNo, b.orderDate || null, b.orderTime || null, b.customerName || '',
        b.orderType || 'Dine In', b.tableNumber || '', b.paymentType || 'Cash',
        number(b.subtotal), number(b.discount), number(b.deliveryCharge),
        number(b.serviceChargePercent), number(b.serviceCharge), number(b.totalAmount),
        b.status || 'Completed', clientOrderId]
    )).rows[0];
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
api.post('/stock-in', async (req, res) => {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const b = req.body;
    const clientStockId = typeof b.clientStockId === 'string' && b.clientStockId.trim()
      ? b.clientStockId.trim() : null;
    if (clientStockId) {
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`offline-stock-${clientStockId}`]);
      const existing = (await client.query(
        `SELECT stock_in_id AS "stockInId", bill_no AS "billNo", stock_date::text AS "stockDate",
                total_amount AS "totalAmount", client_stock_id AS "clientStockId"
         FROM stock_ins WHERE client_stock_id = $1`,
        [clientStockId]
      )).rows[0];
      if (existing) {
        await client.query('COMMIT');
        return res.json(existing);
      }
    }
    const stock = (await client.query(
      `INSERT INTO stock_ins (bill_no, stock_date, remarks, total_amount, client_stock_id)
       VALUES ($1, COALESCE($2::date, CURRENT_DATE), $3, $4, $5)
       RETURNING stock_in_id AS "stockInId", bill_no AS "billNo",
          stock_date::text AS "stockDate", total_amount AS "totalAmount",
          client_stock_id AS "clientStockId"`,
      [b.billNo, b.stockDate || null, b.remarks || '', number(b.totalAmount), clientStockId]
    )).rows[0];
    for (const item of b.items || []) {
      const itemId = number(item.itemId);
      const values = [stock.stockInId, itemId, item.itemName || '', number(item.quantity), number(item.unitPrice), number(item.lineTotal)];
      await client.query('INSERT INTO stock_in_items (stock_in_id, item_id, item_name, quantity, unit_price, line_total) VALUES ($1,$2,$3,$4,$5,$6)', values);
      await client.query(
        `INSERT INTO inventory_items (item_name, quantity, last_unit_price)
         VALUES ($1,$2,$3)
         ON CONFLICT (item_name) DO UPDATE SET
           quantity = inventory_items.quantity + EXCLUDED.quantity,
           last_unit_price = EXCLUDED.last_unit_price`,
        [item.itemName || '', number(item.quantity), number(item.unitPrice)]
      );
    }
    await client.query('COMMIT');
    res.status(201).json(stock);
  } catch (e) { await client.query('ROLLBACK'); sendError(res, e); } finally { client.release(); }
});

const isClosingDate = (value) => typeof value === 'string'
  && /^\d{4}-\d{2}-\d{2}$/.test(value)
  && !Number.isNaN(Date.parse(`${value}T00:00:00Z`))
  && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
const parseClosingAmount = (value) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 && parsed <= 9999999999.99
    && Math.abs(parsed * 100 - Math.round(parsed * 100)) < 1e-6
    ? parsed
    : null;
};
const closingReportSelect = `SELECT report_date::text AS date, opening_cash AS "openingCash",
  cash_sales AS "cashSales", online_sales AS "onlineSales", total_sales AS sales,
  total_expenses AS "expensesTotal", ending_cash AS "endingCash", expenses
  FROM daily_closing_reports`;

api.get('/closing-reports', async (req, res) => {
  const [from, to] = dateRange(req);
  if (!isClosingDate(from) || !isClosingDate(to) || from > to) {
    return res.status(400).json({ error: 'Use a valid date range in YYYY-MM-DD format.' });
  }
  try {
    res.json((await query(
      `${closingReportSelect} WHERE report_date BETWEEN $1 AND $2 ORDER BY report_date`,
      [from, to]
    )).rows);
  } catch (e) { sendError(res, e); }
});

api.get('/closing-reports/:date', async (req, res) => {
  if (!isClosingDate(req.params.date)) return res.status(400).json({ error: 'Enter a valid report date.' });
  try {
    const report = (await query(
      `${closingReportSelect} WHERE report_date = $1`,
      [req.params.date]
    )).rows[0];
    if (!report) return res.sendStatus(404);
    res.json(report);
  } catch (e) { sendError(res, e); }
});

api.post('/closing-reports', async (req, res) => {
  const body = req.body || {};
  if (!isClosingDate(body.date)) return res.status(400).json({ error: 'Enter a valid report date.' });
  const amountFields = [
    ['openingCash', 'Opening cash'],
    ['cashSales', 'Cash sales'],
    ['onlineSales', 'Online sales'],
    ['sales', 'Total sales'],
    ['expensesTotal', 'Total expenses'],
    ['endingCash', 'Ending cash']
  ];
  const amounts = {};
  for (const [field, label] of amountFields) {
    amounts[field] = parseClosingAmount(body[field]);
    if (amounts[field] === null) return res.status(400).json({ error: `${label} must be a non-negative amount with at most two decimal places.` });
  }
  if (!Array.isArray(body.expenses) || body.expenses.length > 200) {
    return res.status(400).json({ error: 'Expenses must be a list of no more than 200 entries.' });
  }
  const expenses = [];
  for (const entry of body.expenses) {
    const supplier = String(entry?.supplier || '').trim();
    const bill = String(entry?.bill || '').trim();
    const amount = parseClosingAmount(entry?.amount);
    if (supplier.length > 150 || bill.length > 100 || amount === null) {
      return res.status(400).json({ error: 'Each expense needs a valid amount, supplier up to 150 characters, and bill number up to 100 characters.' });
    }
    expenses.push({ supplier, bill, amount });
  }
  try {
    const report = (await query(
      `INSERT INTO daily_closing_reports
        (report_date, opening_cash, cash_sales, online_sales, total_sales, total_expenses, ending_cash, expenses)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8::jsonb)
       ON CONFLICT (report_date) DO UPDATE SET
         opening_cash = EXCLUDED.opening_cash,
         cash_sales = EXCLUDED.cash_sales,
         online_sales = EXCLUDED.online_sales,
         total_sales = EXCLUDED.total_sales,
         total_expenses = EXCLUDED.total_expenses,
         ending_cash = EXCLUDED.ending_cash,
         expenses = EXCLUDED.expenses,
         updated_at = NOW()
       RETURNING report_date::text AS date, opening_cash AS "openingCash",
         cash_sales AS "cashSales", online_sales AS "onlineSales", total_sales AS sales,
         total_expenses AS "expensesTotal", ending_cash AS "endingCash", expenses`,
      [
        body.date, amounts.openingCash, amounts.cashSales, amounts.onlineSales,
        amounts.sales, amounts.expensesTotal, amounts.endingCash, JSON.stringify(expenses)
      ]
    )).rows[0];
    res.json(report);
  } catch (e) { sendError(res, e); }
});

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
    const clientPurchaseId = typeof req.body?.clientPurchaseId === 'string'
      && req.body.clientPurchaseId.trim() ? req.body.clientPurchaseId.trim() : null;
    const result = await query(
      `INSERT INTO purchase_history_records
         (item_id, purchase_date, quantity, unit, total_paid, unit_price, client_purchase_id)
       SELECT item_id, $2, $3, $4, $5, ROUND($5::numeric / $3::numeric, 4), $6
       FROM purchase_history_items WHERE item_id = $1
       ON CONFLICT (client_purchase_id) WHERE client_purchase_id IS NOT NULL DO NOTHING
       RETURNING purchase_id AS "purchaseId", purchase_date::text AS date, quantity, unit,
                 unit_price AS "unitPrice", total_paid AS "totalPaid"`,
      [req.params.id, date, quantity, unit, totalPaid, clientPurchaseId]
    );
    if (!result.rowCount && clientPurchaseId) {
      const existing = await query(
        `SELECT purchase_id AS "purchaseId", purchase_date::text AS date, quantity, unit,
                unit_price AS "unitPrice", total_paid AS "totalPaid"
         FROM purchase_history_records WHERE client_purchase_id = $1`,
        [clientPurchaseId]
      );
      if (existing.rowCount) return res.json(existing.rows[0]);
    }
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