package api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OfflineSyncService {
    private OfflineSyncService() {
    }

    public static void prepareOfflineData() throws Exception {
        Map<String, Object> snapshot = SimpleJson.parseObject(ApiClient.getOnline("/offline/bootstrap"));
        OfflineStore.saveBootstrap(snapshot);
    }

    public static List<String> syncPending() {
        return syncPending(null);
    }

    public static List<String> syncOrders() {
        return syncPending(Set.of("order"));
    }

    public static List<String> syncInventoryChanges() {
        return syncPending(Set.of("stock-in", "adjustment", "inventory-create",
            "inventory-delete", "inventory-clear"));
    }

    private static List<String> syncPending(Set<String> selectedTypes) {
        List<String> results = new ArrayList<>();
        for (Map<String, Object> record : OfflineStore.pendingRecords()) {
            String clientId = value(record.get("clientId"));
            String type = value(record.get("type"));
            if (selectedTypes != null && !selectedTypes.contains(type)) continue;
            Map<String, Object> data = map(record.get("data"));
            try {
                Map<String, Object> response = syncOne(type, clientId, data);
                OfflineStore.markSynced(clientId, response);
                results.add("Synced " + type + " (" + clientId + ").");
            } catch (Exception exception) {
                if (isConflict(exception) && "purchase-item".equals(type)
                        && resolveExistingPurchaseItem(clientId, data)) {
                    results.add("Matched existing purchase item (" + clientId + ").");
                } else {
                    results.add("Could not sync " + type + " (" + clientId + "): " + exception.getMessage());
                }
            }
        }
        return results;
    }

    private static Map<String, Object> syncOne(String type, String clientId,
            Map<String, Object> data) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        String response;
        switch (type) {
            case "order":
                resolveOrderMenuIds(payload);
                payload.put("clientOrderId", clientId);
                response = ApiClient.postOnline("/orders", json(payload));
                break;
            case "stock-in":
                payload.put("clientStockId", clientId);
                response = ApiClient.postOnline("/stock-in", json(payload));
                break;
            case "adjustment":
                payload.put("clientAdjustmentId", clientId);
                payload.put("items", List.of(adjustmentEntry(data, clientId)));
                response = ApiClient.postOnline("/inventory/adjust", json(payload));
                break;
            case "inventory-create":
                payload.put("clientInventoryId", clientId);
                response = ApiClient.postOnline("/inventory", json(payload));
                break;
            case "inventory-delete":
                response = deleteByLocalId("/inventory/", data, "itemId");
                break;
            case "menu":
                response = syncMenu(payload);
                break;
            case "menu-delete":
                response = deleteByLocalId("/menu/", data, "menuItemId");
                break;
            case "purchase-item":
                response = ApiClient.postOnline("/purchase-history/items", json(payload));
                break;
            case "purchase-item-delete":
                response = deleteByLocalId("/purchase-history/items/", data, "itemId");
                break;
            case "purchase-record":
                response = syncPurchaseRecord(payload, clientId);
                break;
            case "closing-report":
                response = ApiClient.postOnline("/closing-reports", json(payload));
                break;
            case "inventory-clear":
            case "menu-clear":
                payload.put("clientOperationId", clientId);
                payload.put("target", "inventory-clear".equals(type) ? "inventory" : "menu");
                response = ApiClient.postOnline("/offline/sync/clear", json(payload));
                break;
            case "flyer-save":
            case "flyer-restore":
                payload.put("clientOperationId", clientId);
                payload.put("operation", "flyer-save".equals(type) ? "save" : "restore");
                response = ApiClient.postOnline("/offline/sync/flyer", json(payload));
                break;
            default:
                throw new IllegalStateException("Unsupported offline record type: " + type);
        }
        return response == null || response.isBlank()
            ? new LinkedHashMap<>() : SimpleJson.parseObject(response);
    }

    private static String syncMenu(Map<String, Object> data) throws Exception {
        int menuItemId = integer(data.get("menuItemId"));
        if (menuItemId < 0) {
            menuItemId = OfflineStore.getServerId("menu", "menuItemId", menuItemId);
            if (menuItemId > 0) data.put("menuItemId", menuItemId);
        }
        if (menuItemId > 0) return ApiClient.putOnline("/menu", json(data));
        return ApiClient.postOnline("/menu", json(data));
    }

    @SuppressWarnings("unchecked")
    private static void resolveOrderMenuIds(Map<String, Object> order) {
        Object rawItems = order.get("items");
        if (!(rawItems instanceof List)) return;
        for (Object value : (List<?>) rawItems) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> item = (Map<String, Object>) value;
            int menuItemId = integer(item.get("menuItemId"));
            if (menuItemId < 0) {
                int serverId = OfflineStore.getServerId("menu", "menuItemId", menuItemId);
                if (serverId > 0) item.put("menuItemId", serverId);
            }
        }
    }

    private static String syncPurchaseRecord(Map<String, Object> data, String clientId) throws Exception {
        int itemId = integer(data.get("itemId"));
        if (itemId < 0) {
            itemId = OfflineStore.getServerId("purchaseItems", "itemId", itemId);
        }
        if (itemId <= 0) throw new IllegalStateException("Its purchase-history item must sync before this purchase record.");
        data.put("itemId", itemId);
        data.put("clientPurchaseId", clientId);
        return ApiClient.postOnline("/purchase-history/items/" + itemId + "/records", json(data));
    }

    private static String deleteByLocalId(String path, Map<String, Object> data, String key) throws Exception {
        int id = integer(data.get(key));
        if (id < 0) {
            String collection = path.startsWith("/inventory/") ? "inventory"
                : path.startsWith("/menu/") ? "menu" : "purchaseItems";
            id = OfflineStore.getServerId(collection, key, id);
        }
        if (id <= 0) {
            String name = value(data.get(path.startsWith("/inventory/") ? "itemName"
                : path.startsWith("/menu/") ? "name" : "itemName"));
            if (!name.isBlank()) {
                String endpoint = path.startsWith("/inventory/") ? "/inventory"
                    : path.startsWith("/menu/") ? "/menu" : "/purchase-history/items";
                for (Map<String, Object> row : SimpleJson.parseArray(ApiClient.getOnline(endpoint))) {
                    boolean matches = name.equalsIgnoreCase(value(row.get(path.startsWith("/menu/") ? "name" : "itemName")));
                    if (matches && path.startsWith("/menu/")) {
                        matches = value(data.get("category")).equalsIgnoreCase(value(row.get("category")))
                            && value(data.get("size")).equalsIgnoreCase(value(row.get("size")));
                    }
                    if (matches) {
                        id = integer(row.get(key));
                        break;
                    }
                }
            }
        }
        if (id > 0) {
            try {
                return ApiClient.deleteOnline(path + id);
            } catch (Exception exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("(404)")) return "";
                throw exception;
            }
        }
        return "";
    }

    private static Map<String, Object> adjustmentEntry(Map<String, Object> data, String clientId) {
        Map<String, Object> entry = new LinkedHashMap<>(data);
        entry.put("clientAdjustmentId", clientId);
        return entry;
    }

    private static boolean resolveExistingPurchaseItem(String clientId, Map<String, Object> data) {
        try {
            String itemName = value(data.get("itemName"));
            for (Map<String, Object> item : SimpleJson.parseArray(ApiClient.getOnline("/purchase-history/items"))) {
                if (itemName.equalsIgnoreCase(value(item.get("itemName")))) {
                    OfflineStore.markSynced(clientId, item);
                    return true;
                }
            }
        } catch (Exception exception) {
            System.err.println("Could not resolve purchase item sync retry: " + exception.getMessage());
        }
        return false;
    }

    private static boolean isConflict(Exception exception) {
        return exception.getMessage() != null && exception.getMessage().contains("(409)");
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() instanceof String) result.put((String) entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + SimpleJson.escape((String) value) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
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

    private static int integer(Object value) {
        try { return value == null ? 0 : Integer.parseInt(value.toString()); }
        catch (NumberFormatException exception) { return 0; }
    }

    private static String value(Object object) {
        return object == null ? "" : object.toString();
    }
}
