CREATE TABLE IF NOT EXISTS menu_items (
  menu_item_id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  category TEXT NOT NULL DEFAULT '',
  price NUMERIC(12, 2) NOT NULL DEFAULT 0,
  size TEXT NOT NULL DEFAULT '',
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE menu_items ADD COLUMN IF NOT EXISTS size TEXT NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS menu_flyer_items (
  flyer_item_id SERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  category TEXT NOT NULL DEFAULT '',
  price NUMERIC(12, 2) NOT NULL DEFAULT 0,
  size TEXT NOT NULL DEFAULT '',
  active BOOLEAN NOT NULL DEFAULT TRUE,
  saved_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS inventory_items (
  item_id SERIAL PRIMARY KEY,
  item_name TEXT NOT NULL UNIQUE,
  quantity NUMERIC(12, 3) NOT NULL DEFAULT 0,
  reorder_level NUMERIC(12, 3) NOT NULL DEFAULT 0,
  last_unit_price NUMERIC(12, 2) NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS orders (
  order_id SERIAL PRIMARY KEY,
  order_no TEXT NOT NULL,
  order_date DATE NOT NULL DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::date,
  order_time TIME NOT NULL DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::time,
  customer_name TEXT NOT NULL DEFAULT '',
  order_type TEXT NOT NULL DEFAULT 'Dine In',
  table_number TEXT NOT NULL DEFAULT '',
  payment_type TEXT NOT NULL DEFAULT 'Cash',
  subtotal NUMERIC(12, 2) NOT NULL DEFAULT 0,
  discount NUMERIC(12, 2) NOT NULL DEFAULT 0,
  delivery_charge NUMERIC(12, 2) NOT NULL DEFAULT 0,
  service_charge_percent NUMERIC(6, 2) NOT NULL DEFAULT 0,
  service_charge NUMERIC(12, 2) NOT NULL DEFAULT 0,
  total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'Completed',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE orders ALTER COLUMN order_date SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::date;
ALTER TABLE orders ALTER COLUMN order_time SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Karachi')::time;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS table_number TEXT NOT NULL DEFAULT '';
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_order_no_key;

CREATE TABLE IF NOT EXISTS order_items (
  order_item_id SERIAL PRIMARY KEY,
  order_id INTEGER NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
  menu_item_id INTEGER NOT NULL DEFAULT 0,
  menu_item_name TEXT NOT NULL,
  quantity INTEGER NOT NULL DEFAULT 1,
  unit_price NUMERIC(12, 2) NOT NULL DEFAULT 0,
  line_total NUMERIC(12, 2) NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS stock_ins (
  stock_in_id SERIAL PRIMARY KEY,
  bill_no TEXT NOT NULL UNIQUE,
  stock_date DATE NOT NULL DEFAULT CURRENT_DATE,
  remarks TEXT NOT NULL DEFAULT '',
  total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS stock_in_items (
  stock_in_item_id SERIAL PRIMARY KEY,
  stock_in_id INTEGER NOT NULL REFERENCES stock_ins(stock_in_id) ON DELETE CASCADE,
  item_id INTEGER NOT NULL,
  item_name TEXT NOT NULL,
  quantity NUMERIC(12, 3) NOT NULL DEFAULT 0,
  unit_price NUMERIC(12, 2) NOT NULL DEFAULT 0,
  line_total NUMERIC(12, 2) NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS orders_order_date_idx ON orders(order_date);
CREATE INDEX IF NOT EXISTS stock_ins_stock_date_idx ON stock_ins(stock_date);

CREATE TABLE IF NOT EXISTS purchase_history_items (
  item_id SERIAL PRIMARY KEY,
  item_name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS purchase_history_items_name_unique
  ON purchase_history_items (LOWER(BTRIM(item_name)));

CREATE TABLE IF NOT EXISTS purchase_history_records (
  purchase_id SERIAL PRIMARY KEY,
  item_id INTEGER NOT NULL REFERENCES purchase_history_items(item_id) ON DELETE CASCADE,
  purchase_date DATE NOT NULL DEFAULT CURRENT_DATE,
  quantity NUMERIC(12, 3) NOT NULL CHECK (quantity > 0),
  unit TEXT NOT NULL,
  total_paid NUMERIC(12, 2) NOT NULL CHECK (total_paid >= 0),
  unit_price NUMERIC(18, 4) NOT NULL CHECK (unit_price >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS purchase_history_records_item_date_idx
  ON purchase_history_records(item_id, purchase_date DESC, purchase_id DESC);

DROP INDEX IF EXISTS menu_items_variant_unique_idx;

WITH duplicate_pizza_prices AS (
  SELECT menu_item_id,
         ROW_NUMBER() OVER (
           PARTITION BY LOWER(BTRIM(category)), LOWER(BTRIM(name)), price
           ORDER BY menu_item_id
         ) AS duplicate_number
  FROM menu_items
  WHERE category ILIKE 'pizza'
)
DELETE FROM menu_items
WHERE menu_item_id IN (
  SELECT menu_item_id
  FROM duplicate_pizza_prices
  WHERE duplicate_number > 1
);

WITH extra_pizza_prices AS (
  SELECT menu_item_id,
         ROW_NUMBER() OVER (
           PARTITION BY LOWER(BTRIM(category)), LOWER(BTRIM(name))
           ORDER BY price, menu_item_id
         ) AS price_number
  FROM menu_items
  WHERE category ILIKE 'pizza'
)
DELETE FROM menu_items
WHERE menu_item_id IN (
  SELECT menu_item_id
  FROM extra_pizza_prices
  WHERE price_number > 4
);

WITH pizza_sizes AS (
  SELECT menu_item_id,
         ROW_NUMBER() OVER (
           PARTITION BY LOWER(BTRIM(category)), LOWER(BTRIM(name))
           ORDER BY price, menu_item_id
         ) AS size_number,
         COUNT(*) OVER (
           PARTITION BY LOWER(BTRIM(category)), LOWER(BTRIM(name))
         ) AS size_count
  FROM menu_items
  WHERE category ILIKE 'pizza'
)
UPDATE menu_items AS menu
SET size = CASE
  WHEN pizza_sizes.size_count = 3 AND pizza_sizes.size_number = 1 THEN 'Medium'
  WHEN pizza_sizes.size_count = 3 AND pizza_sizes.size_number = 2 THEN 'Large'
  WHEN pizza_sizes.size_count = 3 AND pizza_sizes.size_number = 3 THEN 'XL'
  WHEN pizza_sizes.size_number = 1 THEN 'Small'
  WHEN pizza_sizes.size_number = 2 THEN 'Medium'
  WHEN pizza_sizes.size_number = 3 THEN 'Large'
  WHEN pizza_sizes.size_number = 4 THEN 'XL'
  ELSE ''
END
FROM pizza_sizes
WHERE menu.menu_item_id = pizza_sizes.menu_item_id;

WITH sized_items AS (
  SELECT menu_item_id,
         ROW_NUMBER() OVER (PARTITION BY category, name ORDER BY menu_item_id) AS size_number
  FROM menu_items
  WHERE category IN ('BKR Special Karahi', 'BKR Handi (Boneless)')
    AND (size IS NULL OR BTRIM(size) = '')
)
UPDATE menu_items AS menu
SET size = CASE sized_items.size_number WHEN 1 THEN 'Half' WHEN 2 THEN 'Full' ELSE '' END
FROM sized_items
WHERE menu.menu_item_id = sized_items.menu_item_id;

WITH duplicate_menu_items AS (
  SELECT menu_item_id,
         ROW_NUMBER() OVER (
           PARTITION BY LOWER(BTRIM(category)), LOWER(BTRIM(name)), LOWER(BTRIM(size))
           ORDER BY menu_item_id
         ) AS duplicate_number
  FROM menu_items
)
DELETE FROM menu_items
WHERE menu_item_id IN (
  SELECT menu_item_id
  FROM duplicate_menu_items
  WHERE duplicate_number > 1
);

DROP INDEX IF EXISTS menu_items_variant_unique_idx;
CREATE UNIQUE INDEX IF NOT EXISTS menu_items_variant_unique_idx
  ON menu_items (LOWER(BTRIM(category)), LOWER(BTRIM(name)), LOWER(BTRIM(size)));

CREATE TABLE IF NOT EXISTS daily_closing_reports (
  report_date DATE PRIMARY KEY,
  opening_cash NUMERIC(12, 2) NOT NULL DEFAULT 0,
  cash_sales NUMERIC(12, 2) NOT NULL DEFAULT 0,
  online_sales NUMERIC(12, 2) NOT NULL DEFAULT 0,
  total_sales NUMERIC(12, 2) NOT NULL DEFAULT 0,
  total_expenses NUMERIC(12, 2) NOT NULL DEFAULT 0,
  ending_cash NUMERIC(12, 2) NOT NULL DEFAULT 0,
  expenses JSONB NOT NULL DEFAULT '[]'::jsonb,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS daily_closing_reports_date_idx
  ON daily_closing_reports(report_date DESC);
