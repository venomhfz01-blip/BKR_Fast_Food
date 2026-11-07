package api;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OfflineStore {
    private static final Object LOCK = new Object();
    private static final Path STORE_PATH = resolveStorePath();
    private static final List<String> COLLECTIONS = Arrays.asList(
        "menu", "inventory", "orders", "stockIns", "adjustments",
        "purchaseItems", "purchaseRecords", "closingReports", "pending"
    );

    private OfflineStore() {
    }

    private static Path resolveStorePath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
            ? Path.of(System.getProperty("user.home"), ".local", "share")
            : Path.of(localAppData);
        return base.resolve("BKR Fast Food").resolve("offline-store.json");
    }

    public static boolean isReady() {
        synchronized (LOCK) {
            try {
                return Boolean.TRUE.equals(readState().get("bootstrapped"));
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read offline data: " + exception.getMessage(), exception);
            }
        }
    }

    public static String getStoreLocation() {
        return STORE_PATH.toString();
    }

    public static int getServerId(String collection, String localIdKey, int localId) {
        synchronized (LOCK) {
            try {
                Map<String, Object> row = findById(readState(), collection, localIdKey, String.valueOf(localId));
                return row == null ? 0 : integer(row.get("server" + Character.toUpperCase(localIdKey.charAt(0))
                    + localIdKey.substring(1)), 0);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not resolve a synced local record: " + exception.getMessage(), exception);
            }
        }
    }

    public static int getPendingCount() {
        synchronized (LOCK) {
            try {
                return (int) maps(readState(), "pending").stream()
                    .filter(record -> "pending".equals(string(record.get("status")))).count();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read the offline queue: " + exception.getMessage(), exception);
            }
        }
    }

    public static void saveBootstrap(Map<String, Object> snapshot) throws IOException {
        synchronized (LOCK) {
            Map<String, Object> previous = readState();
            if (Boolean.TRUE.equals(previous.get("bootstrapped"))
                    && !list(previous, "pending").isEmpty()) {
                throw new IllegalStateException("Sync pending offline changes before refreshing the offline data.");
            }
            Map<String, Object> next = emptyState();
            next.put("bootstrapped", Boolean.TRUE);
            next.put("bootstrappedAt", LocalDate.now(ZoneId.of("Asia/Karachi")).toString());
            for (String collection : COLLECTIONS) {
                Object value = snapshot.get(collection);
                next.put(collection, value instanceof List ? value : new ArrayList<>());
            }
            Object flyerMenu = snapshot.get("flyerMenu");
            next.put("flyerMenu", flyerMenu instanceof List ? flyerMenu : new ArrayList<>());
            writeState(next);
        }
    }

    public static String handle(String method, String requestPath, String body) throws IOException {
        synchronized (LOCK) {
            Map<String, Object> state = readState();
            if (!Boolean.TRUE.equals(state.get("bootstrapped"))) {
                throw new IllegalStateException("Offline data is not ready. Connect to the server once and prepare offline data.");
            }
            String[] parts = requestPath.split("\\?", 2);
            String path = parts[0];
            Map<String, String> query = parseQuery(parts.length == 2 ? parts[1] : "");
            Map<String, Object> payload;
            if (body == null || body.isBlank()) payload = new LinkedHashMap<>();
            else if (body.trim().startsWith("[")) {
                payload = new LinkedHashMap<>();
                payload.put("items", SimpleJson.parseArray(body));
            } else payload = SimpleJson.parseObject(body);
            String result = route(method, path, query, payload, state);
            if (!"GET".equals(method)) writeState(state);
            return result;
        }
    }

    public static List<Map<String, Object>> pendingRecords() {
        synchronized (LOCK) {
            try {
                List<Map<String, Object>> copy = new ArrayList<>();
                for (Object value : list(readState(), "pending")) {
                    if (value instanceof Map && "pending".equals(string(((Map<?, ?>) value).get("status")))) {
                        copy.add(stringMap(value));
                    }
                }
                return copy;
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read offline sync records: " + exception.getMessage(), exception);
            }
        }
    }

    public static void markSynced(String clientId, Map<String, Object> response) throws IOException {
        synchronized (LOCK) {
            Map<String, Object> state = readState();
            String recordType = "";
            Map<String, Object> recordData = new LinkedHashMap<>();
            for (Map<String, Object> record : maps(state, "pending")) {
                if (!clientId.equals(string(record.get("clientId")))) continue;
                recordType = string(record.get("type"));
                recordData = stringMap(record.get("data"));
                record.put("status", "synced");
                record.put("serverId", response.getOrDefault("orderId",
                    response.getOrDefault("stockInId", response.getOrDefault("adjustmentId", 0))));
                break;
            }
            updateCollectionStatus(state, clientId, "synced", response);
            if ("flyer-restore".equals(recordType) && response.get("items") instanceof List) {
                applyMenuIdMappings(state, recordData, (List<?>) response.get("items"));
            }
            writeState(state);
        }
    }

    private static String route(String method, String path, Map<String, String> query,
            Map<String, Object> payload, Map<String, Object> state) throws IOException {
        if ("GET".equals(method)) {
            if ("/menu".equals(path) || "/menu/active".equals(path)) {
                List<Object> rows = new ArrayList<>();
                for (Object value : list(state, "menu")) {
                    if (!(value instanceof Map)) continue;
                    Map<String, Object> item = stringMap(value);
                    if ("/menu/active".equals(path) && !Boolean.TRUE.equals(item.get("active"))) continue;
                    rows.add(item);
                }
                return json(rows);
            }
            if ("/inventory".equals(path) || "/inventory/low-stock".equals(path)) {
                List<Object> rows = new ArrayList<>();
                String search = query.getOrDefault("search", "").toLowerCase();
                for (Object value : list(state, "inventory")) {
                    if (!(value instanceof Map)) continue;
                    Map<String, Object> item = stringMap(value);
                    String name = string(item.get("itemName"));
                    if (!name.toLowerCase().contains(search)) continue;
                    if ("/inventory/low-stock".equals(path)
                            && number(item.get("quantity")).compareTo(number(item.get("reorderLevel"))) > 0) continue;
                    rows.add(item);
                }
                return json(rows);
            }
            if (path.equals("/orders") || path.equals("/orders/range")) {
                return json(filteredOrders(state, query));
            }
            if (path.equals("/orders/item-sales")) return json(itemSales(state, query));
            if (path.startsWith("/orders/")) {
                String id = path.substring("/orders/".length());
                if ("generate-no".equals(id)) return json(Map.of("orderNo", nextOrderNo(state)));
                Map<String, Object> order = findById(state, "orders", "orderId", id);
                return order == null ? "null" : json(order);
            }
            if (path.startsWith("/sales/date/")) {
                String date = path.substring("/sales/date/".length());
                List<Map<String, Object>> orders = filteredOrders(state, Map.of("from", date, "to", date));
                BigDecimal total = orders.stream().map(order -> number(order.get("totalAmount")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                return json(Map.of("totalSales", total, "orderCount", orders.size()));
            }
            if (path.equals("/stock-in") || path.equals("/stock-in/range")) {
                return json(filteredStockIns(state, query));
            }
            if (path.startsWith("/stock-in/")) {
                if (path.equals("/stock-in/generate-no")) {
                    return json(Map.of("billNo", "OFF-" + String.format("%05d", list(state, "stockIns").size() + 1)));
                }
                Map<String, Object> bill = findById(state, "stockIns", "stockInId", path.substring("/stock-in/".length()));
                return bill == null ? "null" : json(bill);
            }
            if (path.equals("/purchase-history/items")) return json(list(state, "purchaseItems"));
            if (path.matches("/purchase-history/items/\\d+/records")) {
                int id = integer(path.split("/")[3], 0);
                return json(purchaseHistory(state, id, query));
            }
            if (path.equals("/closing-reports") || path.equals("/closing-reports/range")) {
                return json(filteredReports(state, query));
            }
            if (path.startsWith("/closing-reports/")) {
                String date = path.substring("/closing-reports/".length());
                for (Object value : list(state, "closingReports")) {
                    if (value instanceof Map && date.equals(string(((Map<?, ?>) value).get("date")))) return json(value);
                }
                return "null";
            }
        }
        if ("POST".equals(method)) {
            if ("/orders".equals(path)) return createOrder(state, payload);
            if ("/inventory".equals(path)) return createInventoryItem(state, payload);
            if ("/inventory/adjust".equals(path)) return adjustInventory(state, payload);
            if ("/stock-in".equals(path)) return createStockIn(state, payload);
            if ("/purchase-history/items".equals(path)) return createPurchaseItem(state, payload);
            if (path.matches("/purchase-history/items/\\d+/records")) return createPurchaseRecord(state, path, payload);
            if ("/closing-reports".equals(path)) return saveClosingReport(state, payload);
            if ("/menu".equals(path)) return saveMenuItem(state, payload);
            if ("/menu/flyer".equals(path)) {
                List<Object> items = copyRows(list(state, "menu"));
                if (items.isEmpty()) throw new IllegalStateException("The current menu is empty.");
                state.put("flyerMenu", items);
                String clientId = UUID.randomUUID().toString();
                enqueue(state, "flyer-save", clientId, Map.of("items", items));
                return json(Map.of("savedCount", items.size()));
            }
        }
        if ("PUT".equals(method)) {
            if ("/menu".equals(path)) return updateMenuItem(state, payload);
            if (path.matches("/menu/-?\\d+/status")) return setMenuStatus(state, path, payload);
            if ("/menu/flyer/restore".equals(path)) {
                List<Object> saved = list(state, "flyerMenu");
                if (saved.isEmpty()) throw new IllegalStateException("No BKR Flyer menu has been saved yet.");
                List<Object> restored = new ArrayList<>();
                for (Object value : saved) {
                    if (!(value instanceof Map)) continue;
                    Map<String, Object> item = stringMap(value);
                    item.put("menuItemId", nextLocalId(state, "nextMenuId"));
                    item.putIfAbsent("active", Boolean.TRUE);
                    restored.add(item);
                }
                state.put("menu", restored);
                String clientId = UUID.randomUUID().toString();
                enqueue(state, "flyer-restore", clientId, Map.of("items", copyRows(restored)));
                return json(Map.of("restoredCount", restored.size()));
            }
        }
        if ("DELETE".equals(method)) {
            if (path.matches("/inventory/-?\\d+")) return deleteById(state, "inventory", "itemId", path, "inventory-delete");
            if (path.matches("/menu/-?\\d+")) return deleteById(state, "menu", "menuItemId", path, "menu-delete");
            if ("/inventory".equals(path) || "/menu".equals(path)) {
                String target = "/inventory".equals(path) ? "inventory" : "menu";
                state.put(target, new ArrayList<>());
                String clientId = UUID.randomUUID().toString();
                enqueue(state, target + "-clear", clientId, Map.of("target", target));
                return "";
            }
            if (path.matches("/purchase-history/items/-?\\d+")) return deleteById(state, "purchaseItems", "itemId", path, "purchase-item-delete");
        }
        throw new UnsupportedOperationException("This action is not available in offline mode: " + method + " " + path);
    }

    private static String createOrder(Map<String, Object> state, Map<String, Object> payload) {
        String clientId = UUID.randomUUID().toString();
        int localId = nextLocalId(state, "nextOrderId");
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Karachi"));
        LocalTime time = LocalTime.now(ZoneId.of("Asia/Karachi")).withNano(0);
        Map<String, Object> order = new LinkedHashMap<>(payload);
        order.put("orderId", localId);
        order.put("orderNo", "OFF-" + String.format("%05d", Math.abs(localId)));
        order.put("orderDate", date.toString());
        order.put("orderTime", time.toString());
        order.put("status", "Completed");
        order.put("clientId", clientId);
        order.put("syncStatus", "pending");
        if (!(order.get("items") instanceof List)) order.put("items", new ArrayList<>());
        list(state, "orders").add(order);
        deductLocalOrderInventory(state, order);
        enqueue(state, "order", clientId, order);
        return json(Map.of("orderId", localId, "orderNo", order.get("orderNo"),
            "orderDate", date.toString(), "orderTime", time.toString()));
    }

    private static void deductLocalOrderInventory(Map<String, Object> state, Map<String, Object> order) {
        List<Object> menu = list(state, "menu");
        for (Object itemValue : (List<?>) order.get("items")) {
            if (!(itemValue instanceof Map)) continue;
            Map<String, Object> item = stringMap(itemValue);
            String name = string(item.get("menuItemName")).toLowerCase();
            int menuId = integer(item.get("menuItemId"), 0);
            String category = "";
            for (Object menuValue : menu) {
                if (!(menuValue instanceof Map)) continue;
                Map<String, Object> menuItem = stringMap(menuValue);
                if (integer(menuItem.get("menuItemId"), 0) == menuId) {
                    category = string(menuItem.get("category")).toLowerCase();
                    break;
                }
            }
            if (!category.isBlank()) item.put("category", category);
            int quantity = integer(item.get("quantity"), 1);
            Map<String, Integer> deductions = new LinkedHashMap<>();
            String compactName = name.replaceAll("[^a-z0-9]+", " ").trim();
            if (category.equals("deals") || category.isEmpty() && (
                    compactName.contains("family deal") || compactName.contains("student deal")
                    || compactName.contains("bkr super platter") || compactName.contains("deal 1")
                    || compactName.contains("deal 2") || compactName.contains("deal 3"))) {
                if (compactName.contains("family deal")) {
                    deductions.put("1.5 Ltr", 1); deductions.put("Burger Bun", 4);
                    deductions.put("Wings", 6); deductions.put("Chicken Thigh", 4);
                } else if (compactName.contains("student deal")) {
                    deductions.put("Regular", 1); deductions.put("Burger Bun", 1);
                    deductions.put("Chicken Patty", 1);
                } else if (compactName.contains("bkr super platter")) {
                    deductions.put("1 Ltr", 1); deductions.put("Burger Bun", 2);
                    deductions.put("Nuggets", 10); deductions.put("Paratha Roll", 2);
                    deductions.put("Chicken Patty", 2);
                } else if (compactName.contains("deal 1")) {
                    deductions.put("1 Ltr", 1); deductions.put("Burger Bun", 2);
                    deductions.put("Chicken Thigh", 2);
                } else if (compactName.contains("deal 2")) deductions.put("1 Ltr", 1);
                else if (compactName.contains("deal 3")) {
                    deductions.put("1 Ltr", 1); deductions.put("Wings", 6);
                }
            } else if (category.equals("drinks") || category.isEmpty() && Arrays.asList(
                    "regular", "1 ltr", "1.5 ltr", "water small", "water large", "ten pack").contains(compactName)) {
                deductions.put(string(item.get("menuItemName")), 1);
            } else if (category.equals("burger") || category.isEmpty()
                    && (compactName.contains("burger") || compactName.contains("zinger")
                        || compactName.contains("tower") || compactName.contains("patty fatty")
                        || compactName.contains("crispy burger"))) {
                deductions.put("Burger Bun", 1);
                if (compactName.contains("patty fatty") || compactName.contains("beef burger")
                        || compactName.contains("patty burger") || compactName.contains("bkr special burger")) {
                    deductions.put("Beef Patty", 1);
                } else if (compactName.contains("tower burger")) {
                    deductions.put("Chicken Patty", 1);
                    deductions.put("Chicken Thigh", 1);
                } else if (compactName.contains("zinger burger") || compactName.contains("zinger chees")
                        || compactName.contains("crispy burger") || compactName.contains("chicken burger")) {
                    deductions.put("Chicken Thigh", 1);
                } else if (compactName.contains("burger")) deductions.put("Chicken Patty", 1);
            } else if (category.equals("wrap") || category.isEmpty() && compactName.contains("wrap")) {
                deductions.put("Wrap", 1);
            } else if (category.equals("paratha roll") || category.isEmpty()
                    && (compactName.contains("roll") || compactName.contains("paratha roll")
                        || compactName.contains("zinger roll"))) deductions.put("Paratha Roll", 1);
            else if (category.equals("hot wings") || category.isEmpty()
                    && (compactName.contains("wing") || compactName.contains("nugget"))) {
                boolean nuggets = compactName.contains("nugget");
                deductions.put(nuggets ? "Nuggets" : "Wings", nuggets ? 10 : 6);
            } else if (category.equals("shawarma") || category.isEmpty()
                    && (compactName.contains("shawarma") || compactName.contains("shwarma"))) {
                deductions.put("Shawarma Bread", 1);
            }
            for (Map.Entry<String, Integer> deduction : deductions.entrySet()) {
                adjustQuantity(state, deduction.getKey(),
                    BigDecimal.valueOf(-(long) quantity * deduction.getValue()));
            }
        }
    }

    private static String createInventoryItem(Map<String, Object> state, Map<String, Object> payload) {
        int id = nextLocalId(state, "nextInventoryId");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", id);
        item.put("itemName", string(payload.getOrDefault("itemName", payload.get("name"))));
        item.put("quantity", number(payload.get("quantity")));
        item.put("reorderLevel", number(payload.get("reorderLevel")));
        item.put("lastUnitPrice", number(payload.get("lastUnitPrice")));
        String clientId = UUID.randomUUID().toString();
        item.put("clientId", clientId);
        list(state, "inventory").add(item);
        enqueue(state, "inventory-create", clientId, item);
        return json(Map.of("itemId", id));
    }

    private static String adjustInventory(Map<String, Object> state, Map<String, Object> payload) {
        Object rawItems = payload.get("items");
        if (!(rawItems instanceof List)) rawItems = payload;
        List<?> entries = rawItems instanceof List ? (List<?>) rawItems : List.of(rawItems);
        List<Object> updated = new ArrayList<>();
        for (Object value : entries) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> entry = stringMap(value);
            String name = string(entry.getOrDefault("itemName", entry.get("name"))).trim();
            BigDecimal delta = number(entry.get("quantity"));
            if (name.isEmpty() || delta.signum() == 0) continue;
            adjustQuantity(state, name, delta);
            String clientId = UUID.randomUUID().toString();
            Map<String, Object> adjustment = new LinkedHashMap<>();
            adjustment.put("itemName", name);
            adjustment.put("quantity", delta);
            adjustment.put("date", LocalDate.now(ZoneId.of("Asia/Karachi")).toString());
            adjustment.put("note", string(entry.get("note")));
            adjustment.put("clientId", clientId);
            adjustment.put("syncStatus", "pending");
            list(state, "adjustments").add(adjustment);
            enqueue(state, "adjustment", clientId, adjustment);
            Map<String, Object> inventory = findInventoryByName(state, name);
            if (inventory != null) updated.add(inventory);
        }
        return json(Map.of("updated", updated));
    }

    private static String createStockIn(Map<String, Object> state, Map<String, Object> payload) {
        String clientId = UUID.randomUUID().toString();
        int id = nextLocalId(state, "nextStockInId");
        Map<String, Object> bill = new LinkedHashMap<>(payload);
        bill.put("stockInId", id);
        String billNo = string(payload.getOrDefault("billNo", ""));
        bill.put("billNo", billNo.startsWith("OFF-") ? billNo : "OFF-" + String.format("%05d", Math.abs(id)));
        bill.put("stockDate", LocalDate.now(ZoneId.of("Asia/Karachi")).toString());
        bill.put("clientId", clientId);
        bill.put("syncStatus", "pending");
        List<?> items = bill.get("items") instanceof List ? (List<?>) bill.get("items") : new ArrayList<>();
        bill.put("items", items);
        for (Object value : items) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> item = stringMap(value);
            adjustQuantity(state, string(item.get("itemName")), number(item.get("quantity")));
        }
        list(state, "stockIns").add(bill);
        enqueue(state, "stock-in", clientId, bill);
        return json(Map.of("stockInId", id, "clientId", clientId));
    }

    private static String saveMenuItem(Map<String, Object> state, Map<String, Object> payload) {
        int id = integer(payload.get("menuItemId"), 0);
        Map<String, Object> item;
        if (id > 0) {
            item = findById(state, "menu", "menuItemId", String.valueOf(id));
            if (item != null) item.putAll(payload);
        } else {
            item = new LinkedHashMap<>(payload);
            item.put("menuItemId", nextLocalId(state, "nextMenuId"));
            item.put("active", Boolean.TRUE);
            list(state, "menu").add(item);
        }
        if (item == null) return json(Map.of("menuItemId", 0));
        item.putIfAbsent("size", "");
        item.put("syncStatus", "pending");
        item.putIfAbsent("clientId", UUID.randomUUID().toString());
        enqueue(state, "menu", string(item.get("clientId")), item);
        return json(Map.of("menuItemId", item.get("menuItemId")));
    }

    private static String updateMenuItem(Map<String, Object> state, Map<String, Object> payload) {
        Map<String, Object> item = findById(state, "menu", "menuItemId", string(payload.get("menuItemId")));
        if (item == null) return "{}";
        item.putAll(payload);
        item.put("syncStatus", "pending");
        String clientId = string(item.getOrDefault("clientId", UUID.randomUUID().toString()));
        item.put("clientId", clientId);
        enqueue(state, "menu", clientId, item);
        return "{}";
    }

    private static String setMenuStatus(Map<String, Object> state, String path, Map<String, Object> payload) {
        String id = path.substring("/menu/".length(), path.length() - "/status".length());
        Map<String, Object> item = findById(state, "menu", "menuItemId", id);
        if (item != null) {
            item.putAll(payload);
            item.put("syncStatus", "pending");
            item.putIfAbsent("clientId", UUID.randomUUID().toString());
            enqueue(state, "menu", string(item.get("clientId")), item);
        }
        return "{}";
    }

    private static String createPurchaseItem(Map<String, Object> state, Map<String, Object> payload) {
        String name = string(payload.get("itemName")).trim();
        for (Object value : list(state, "purchaseItems")) {
            if (value instanceof Map && name.equalsIgnoreCase(string(((Map<?, ?>) value).get("itemName")))) return json(value);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", nextLocalId(state, "nextPurchaseItemId"));
        item.put("itemName", name);
        item.put("clientId", UUID.randomUUID().toString());
        list(state, "purchaseItems").add(item);
        enqueue(state, "purchase-item", string(item.get("clientId")), item);
        return json(item);
    }

    private static String createPurchaseRecord(Map<String, Object> state, String path, Map<String, Object> payload) {
        int itemId = integer(path.split("/")[3], 0);
        Map<String, Object> item = findById(state, "purchaseItems", "itemId", String.valueOf(itemId));
        if (item == null) throw new IllegalArgumentException("Select a saved purchase item.");
        BigDecimal quantity = number(payload.get("quantity"));
        BigDecimal total = number(payload.get("totalPaid"));
        Map<String, Object> record = new LinkedHashMap<>(payload);
        record.put("purchaseId", nextLocalId(state, "nextPurchaseId"));
        record.put("itemId", itemId);
        record.put("itemName", item.get("itemName"));
        record.put("unitPrice", quantity.signum() == 0 ? BigDecimal.ZERO : total.divide(quantity, 4, java.math.RoundingMode.HALF_UP));
        record.put("clientId", UUID.randomUUID().toString());
        list(state, "purchaseRecords").add(record);
        enqueue(state, "purchase-record", string(record.get("clientId")), record);
        return json(record);
    }

    private static String saveClosingReport(Map<String, Object> state, Map<String, Object> payload) {
        String date = string(payload.get("date"));
        Map<String, Object> existing = null;
        for (Map<String, Object> report : maps(state, "closingReports")) {
            if (date.equals(string(report.get("date")))) { existing = report; break; }
        }
        Map<String, Object> report = existing == null ? new LinkedHashMap<>() : existing;
        report.putAll(payload);
        report.put("clientId", report.getOrDefault("clientId", UUID.randomUUID().toString()));
        report.put("syncStatus", "pending");
        if (existing == null) list(state, "closingReports").add(report);
        enqueue(state, "closing-report", string(report.get("clientId")), report);
        return json(report);
    }

    private static String deleteById(Map<String, Object> state, String collection, String key,
            String path, String type) {
        String id = path.substring(path.lastIndexOf('/') + 1);
        Map<String, Object> deleted = findById(state, collection, key, id);
        list(state, collection).removeIf(value -> value instanceof Map
            && id.equals(string(((Map<?, ?>) value).get(key))));
        if (deleted != null) {
            String clientId = string(deleted.get("clientId"));
            String serverKey = "server" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            int serverId = integer(deleted.get(serverKey), 0);
            if (serverId > 0) {
                Map<String, Object> remoteRecord = new LinkedHashMap<>(deleted);
                remoteRecord.put(key, serverId);
                if (!clientId.isEmpty()) {
                    list(state, "pending").removeIf(value -> value instanceof Map
                        && clientId.equals(string(((Map<?, ?>) value).get("clientId"))));
                }
                enqueue(state, type, UUID.randomUUID().toString(), remoteRecord);
            } else if (integer(deleted.get(key), 0) < 0 && !clientId.isEmpty()) {
                list(state, "pending").removeIf(value -> value instanceof Map
                    && clientId.equals(string(((Map<?, ?>) value).get("clientId"))));
            } else {
                enqueue(state, type, UUID.randomUUID().toString(), deleted);
            }
        }
        return "";
    }

    private static List<Map<String, Object>> filteredOrders(Map<String, Object> state, Map<String, String> query) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String from = query.getOrDefault("from", "1900-01-01");
        String to = query.getOrDefault("to", "2999-12-31");
        for (Map<String, Object> order : maps(state, "orders")) {
            String date = string(order.get("orderDate"));
            if (date.compareTo(from) < 0 || date.compareTo(to) > 0) continue;
            rows.add(order);
        }
        rows.sort((a, b) -> string(b.get("orderDate")).concat(string(b.get("orderTime")))
            .compareTo(string(a.get("orderDate")).concat(string(a.get("orderTime")))));
        return rows;
    }

    private static List<Map<String, Object>> itemSales(Map<String, Object> state, Map<String, String> query) {
        Map<String, Map<String, Object>> totals = new LinkedHashMap<>();
        for (Map<String, Object> order : filteredOrders(state, query)) {
            Object rawItems = order.get("items");
            if (!(rawItems instanceof List)) continue;
            for (Object value : (List<?>) rawItems) {
                if (!(value instanceof Map)) continue;
                Map<String, Object> item = stringMap(value);
                String name = string(item.get("menuItemName"));
                Map<String, Object> total = totals.computeIfAbsent(name, key -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("itemName", key); data.put("quantity", 0); data.put("totalSales", BigDecimal.ZERO);
                    return data;
                });
                total.put("quantity", integer(total.get("quantity"), 0) + integer(item.get("quantity"), 0));
                total.put("totalSales", number(total.get("totalSales")).add(number(item.get("lineTotal"))));
            }
        }
        return new ArrayList<>(totals.values());
    }

    private static List<Map<String, Object>> filteredStockIns(Map<String, Object> state, Map<String, String> query) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String from = query.getOrDefault("from", "1900-01-01");
        String to = query.getOrDefault("to", "2999-12-31");
        for (Map<String, Object> row : maps(state, "stockIns")) {
            String date = string(row.get("stockDate"));
            if (date.compareTo(from) >= 0 && date.compareTo(to) <= 0) rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> filteredReports(Map<String, Object> state, Map<String, String> query) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String from = query.getOrDefault("from", "1900-01-01");
        String to = query.getOrDefault("to", "2999-12-31");
        for (Map<String, Object> report : maps(state, "closingReports")) {
            String date = string(report.get("date"));
            if (date.compareTo(from) >= 0 && date.compareTo(to) <= 0) rows.add(report);
        }
        return rows;
    }

    private static Map<String, Object> purchaseHistory(Map<String, Object> state, int itemId, Map<String, String> query) {
        List<Object> records = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String from = query.getOrDefault("from", "1900-01-01");
        String to = query.getOrDefault("to", "2999-12-31");
        for (Object value : list(state, "purchaseRecords")) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> row = stringMap(value);
            String date = string(row.get("date"));
            if (integer(row.get("itemId"), 0) == itemId && date.compareTo(from) >= 0 && date.compareTo(to) <= 0) {
                records.add(row);
                total = total.add(number(row.get("totalPaid")));
            }
        }
        return Map.of("records", records, "totalPaid", total);
    }

    private static Map<String, Object> findById(Map<String, Object> state, String collection, String key, String id) {
        for (Map<String, Object> value : maps(state, collection)) {
            if (id.equals(string(value.get(key)))) return value;
        }
        return null;
    }

    private static Map<String, Object> findInventoryByName(Map<String, Object> state, String name) {
        for (Map<String, Object> item : maps(state, "inventory")) {
            if (name.equalsIgnoreCase(string(item.get("itemName")))) return item;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", nextLocalId(state, "nextInventoryId"));
        item.put("itemName", name);
        item.put("quantity", BigDecimal.ZERO);
        item.put("reorderLevel", BigDecimal.ZERO);
        item.put("lastUnitPrice", BigDecimal.ZERO);
        list(state, "inventory").add(item);
        return item;
    }

    private static void adjustQuantity(Map<String, Object> state, String name, BigDecimal delta) {
        Map<String, Object> item = findInventoryMatch(state, name);
        if (item == null && delta.signum() > 0) item = findInventoryByName(state, name);
        if (item == null) return;
        BigDecimal quantity = number(item.get("quantity")).add(delta);
        item.put("quantity", quantity.max(BigDecimal.ZERO));
    }

    private static Map<String, Object> findInventoryMatch(Map<String, Object> state, String name) {
        String target = normalizedInventoryName(name);
        for (Map<String, Object> item : maps(state, "inventory")) {
            String stored = normalizedInventoryName(string(item.get("itemName")));
            if (target.equals(stored) || target.contains("nugget") && stored.contains("nugget")
                    || target.contains("wing") && stored.contains("wing")
                    || (target.contains("shawarma") || target.contains("shwarma"))
                        && (stored.contains("shawarma") || stored.contains("shwarma"))
                    || target.contains("paratha roll") && (stored.contains("paratha") || stored.equals("roll"))
                    || target.equals("wrap") && stored.contains("wrap")
                    || target.contains("chicken thigh") && stored.contains("thigh")
                    || target.contains("chicken patty") && stored.contains("patty") && !stored.contains("beef")
                    || target.contains("beef patty") && (stored.contains("beef patty") || stored.contains("beef burger"))
                    || target.equals("regular") && stored.startsWith("regular")
                    || target.equals("1 ltr") && stored.matches("1 (ltr|liter|litre)( drink)?")
                    || target.equals("1.5 ltr") && stored.matches("1\\.5 (ltr|liter|litre)( drink)?")) return item;
        }
        return null;
    }

    private static String normalizedInventoryName(String name) {
        return name.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    private static void enqueue(Map<String, Object> state, String type, String clientId, Map<String, Object> data) {
        for (Map<String, Object> pending : maps(state, "pending")) {
            if (clientId.equals(string(pending.get("clientId")))) {
                pending.put("type", type);
                pending.put("status", "pending");
                pending.put("data", data);
                return;
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", type);
        record.put("clientId", clientId);
        record.put("status", "pending");
        record.put("data", data);
        list(state, "pending").add(record);
    }

    private static List<Object> copyRows(List<?> rows) {
        List<Object> copy = new ArrayList<>();
        for (Object row : rows) copy.add(row instanceof Map ? new LinkedHashMap<>(stringMap(row)) : row);
        return copy;
    }

    private static void updateCollectionStatus(Map<String, Object> state, String clientId,
            String status, Map<String, Object> response) {
        for (String collection : Arrays.asList("orders", "stockIns", "adjustments", "purchaseItems", "purchaseRecords", "closingReports", "menu", "inventory")) {
            for (Map<String, Object> row : maps(state, collection)) {
                if (!clientId.equals(string(row.get("clientId")))) continue;
                row.put("syncStatus", status);
                for (String key : Arrays.asList("orderId", "stockInId", "adjustmentId", "itemId", "purchaseId", "menuItemId")) {
                    if (response.containsKey(key)) row.put("server" + Character.toUpperCase(key.charAt(0)) + key.substring(1), response.get(key));
                }
            }
        }
    }

    private static void applyMenuIdMappings(Map<String, Object> state,
            Map<String, Object> recordData, List<?> serverItems) {
        Object rawItems = recordData.get("items");
        if (!(rawItems instanceof List)) return;
        for (Object localValue : (List<?>) rawItems) {
            if (!(localValue instanceof Map)) continue;
            Map<String, Object> local = stringMap(localValue);
            for (Object serverValue : serverItems) {
                if (!(serverValue instanceof Map)) continue;
                Map<String, Object> remote = stringMap(serverValue);
                if (string(local.get("name")).equalsIgnoreCase(string(remote.get("name")))
                        && string(local.get("category")).equalsIgnoreCase(string(remote.get("category")))
                        && string(local.get("size")).equalsIgnoreCase(string(remote.get("size")))) {
                    Map<String, Object> stored = findById(state, "menu", "menuItemId",
                        string(local.get("menuItemId")));
                    if (stored != null) {
                        stored.put("serverMenuItemId", remote.get("menuItemId"));
                        stored.put("syncStatus", "synced");
                    }
                    break;
                }
            }
        }
    }

    private static String nextOrderNo(Map<String, Object> state) {
        int nextNumber = Math.abs(integer(state.get("nextOrderId"), -1));
        return "OFF-" + String.format("%05d", Math.max(1, nextNumber));
    }

    private static int nextLocalId(Map<String, Object> state, String key) {
        int next = integer(state.get(key), -1);
        state.put(key, next - 1);
        return next;
    }

    private static Map<String, Object> emptyState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("bootstrapped", Boolean.FALSE);
        for (String collection : COLLECTIONS) state.put(collection, new ArrayList<>());
        state.put("flyerMenu", new ArrayList<>());
        state.put("nextOrderId", -1);
        state.put("nextInventoryId", -1);
        state.put("nextStockInId", -1);
        state.put("nextMenuId", -1);
        state.put("nextPurchaseItemId", -1);
        state.put("nextPurchaseId", -1);
        return state;
    }

    private static Map<String, Object> readState() throws IOException {
        if (!Files.exists(STORE_PATH)) return emptyState();
        String content = Files.readString(STORE_PATH, StandardCharsets.UTF_8);
        Map<String, Object> state = SimpleJson.parseObject(content);
        for (String collection : COLLECTIONS) {
            if (!(state.get(collection) instanceof List)) state.put(collection, new ArrayList<>());
        }
        return state;
    }

    private static void writeState(Map<String, Object> state) throws IOException {
        Files.createDirectories(STORE_PATH.getParent());
        Path temporary = STORE_PATH.resolveSibling(STORE_PATH.getFileName() + ".tmp");
        Files.writeString(temporary, json(state), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, STORE_PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, STORE_PATH, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> state, String key) {
        Object value = state.get(key);
        if (value instanceof List) return (List<Object>) value;
        List<Object> result = new ArrayList<>();
        state.put(key, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> state, String key) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list(state, key)) {
            if (value instanceof Map) result.add((Map<String, Object>) value);
        }
        return result;
    }

    private static Map<String, Object> stringMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() instanceof String) result.put((String) entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query.isBlank()) return result;
        for (String entry : query.split("&")) {
            String[] pair = entry.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static BigDecimal number(Object value) {
        try { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
        catch (NumberFormatException exception) { return BigDecimal.ZERO; }
    }
    private static int integer(Object value, int fallback) {
        try { return value == null ? fallback : new BigDecimal(value.toString()).intValue(); }
        catch (NumberFormatException exception) { return fallback; }
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + SimpleJson.escape((String) value) + "\"";
        if (value instanceof Number || value instanceof BigDecimal || value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            List<String> fields = new ArrayList<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                fields.add(json(entry.getKey().toString()) + ":" + json(entry.getValue()));
            }
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : (Iterable<?>) value) values.add(json(item));
            return "[" + String.join(",", values) + "]";
        }
        return json(value.toString());
    }
}
