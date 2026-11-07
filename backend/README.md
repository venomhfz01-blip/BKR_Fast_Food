# BKR Fast Food Backend

This service provides the HTTP API used by the Java desktop client and Android app.

## Render deployment

1. Push this repository to GitHub.
2. In Render, choose **New > Blueprint** and select the repository.
3. Render will create the Node web service and PostgreSQL database from `render.yaml`.
4. Copy the web service URL into the desktop client's `config.properties` as `server.url=https://<service>.onrender.com/api`.

The server applies `schema.sql` automatically when `DATABASE_URL` is present. Render supplies that variable through the Blueprint database connection.

## Local development

Set `DATABASE_URL` to a PostgreSQL connection string, then run:

```text
npm install
npm start
```

The health endpoint is `GET /health`.

## Order payment type

- `PUT /api/orders/:id/payment-type` updates an order’s payment type. Send `{ "paymentType": "Cash" }`; supported values are `Cash`, `Card`, and `Online`. The response includes the order ID, order number, date, payment type, and total amount.

## Item purchase history

Purchase items and their records are stored in PostgreSQL and shared by both clients. The server creates the required tables and indexes from `schema.sql` at startup.

- `GET /api/purchase-history/items` lists items.
- `POST /api/purchase-history/items` adds an item with an `itemName`.
- `DELETE /api/purchase-history/items/:id` deletes an item and its purchase history.
- `GET /api/purchase-history/items/:id/records?from=YYYY-MM-DD&to=YYYY-MM-DD` returns the item’s records and the total paid in that date range. Either date can be omitted.
- `POST /api/purchase-history/items/:id/records` saves a purchase with `date`, `quantity`, `unit`, and `totalPaid`. The server calculates and stores the price per unit.

Supported units are `packet`, `kg`, `g`, `L`, `mL`, `piece`, and `box`.

## Daily closing reports

Daily closing reports are stored in PostgreSQL and shared by the desktop and Android clients.

- `GET /api/closing-reports?from=YYYY-MM-DD&to=YYYY-MM-DD` lists reports in an inclusive date range, including itemized expenses and bill references.
- `GET /api/closing-reports/:date` loads one report by date.
- `POST /api/closing-reports` creates or updates the report for `date`. The JSON body contains `openingCash`, `cashSales`, `onlineSales`, `sales`, `expensesTotal`, `endingCash`, and `expenses` (an array of `{ "supplier", "bill", "amount" }`).

The backend applies the `daily_closing_reports` table migration from `schema.sql` at startup.

## Desktop offline mode

The Java desktop client can cache server data and keep operating from one computer when the internet is unavailable.

1. Start the desktop app in Server mode while connected. The app refreshes the local cache; use **Sync Center > Prepare / Refresh Offline Data** if needed.
2. Select **Offline Mode** in the header to take orders, print bills, receive stock, manage the cached menu, and keep local reports and purchase history.
3. When connected again, open **Sync Center** and choose **Update Orders**, **Update Inventory**, or **Sync All Pending**. Sync is manual; a successful sync does not switch the app back to Server mode.

The local store is kept at `%LOCALAPPDATA%\BKR Fast Food\offline-store.json`. It is intended for a single desktop/register. Pending orders retain their original date and time. Sync uses stable client IDs for operations that need server-side de-duplication; the remaining updates are idempotent. The backend creates the required columns and tables from `schema.sql` at startup; deploy the updated backend before using offline sync.