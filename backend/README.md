# BKR Fast Food Backend

This service provides the HTTP API used by the packaged Java desktop client.

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

## Item purchase history

Purchase items and their records are stored in PostgreSQL and shared by both clients. The server creates the required tables and indexes from `schema.sql` at startup.

- `GET /api/purchase-history/items` lists items.
- `POST /api/purchase-history/items` adds an item with an `itemName`.
- `DELETE /api/purchase-history/items/:id` deletes an item and its purchase history.
- `GET /api/purchase-history/items/:id/records?from=YYYY-MM-DD&to=YYYY-MM-DD` returns the item's records and the total paid in that date range. Either date can be omitted.
- `POST /api/purchase-history/items/:id/records` saves a purchase with `date`, `quantity`, `unit`, and `totalPaid`. The server calculates and stores the price per unit.

Supported units are `packet`, `kg`, `g`, `L`, `mL`, `piece`, and `box`.
