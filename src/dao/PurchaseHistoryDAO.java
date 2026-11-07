package dao;

import api.ApiClient;
import api.SimpleJson;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import model.PurchaseHistoryData;
import model.PurchaseHistoryItem;
import model.PurchaseHistoryRecord;

public class PurchaseHistoryDAO {
    public List<PurchaseHistoryItem> getItems() {
        try {
            List<PurchaseHistoryItem> items = new ArrayList<>();
            for (Map<String, Object> row : SimpleJson.parseArray(ApiClient.get("/purchase-history/items"))) {
                items.add(new PurchaseHistoryItem(
                    SimpleJson.getInt(row, "itemId", 0),
                    SimpleJson.getString(row, "itemName", "")
                ));
            }
            return items;
        } catch (Exception exception) {
            throw new RuntimeException("Failed to load purchase-history items: " + exception.getMessage(), exception);
        }
    }

    public PurchaseHistoryItem addItem(String itemName) {
        try {
            String payload = "{\"itemName\":\"" + SimpleJson.escape(itemName.trim()) + "\"}";
            Map<String, Object> result = SimpleJson.parseObject(ApiClient.post("/purchase-history/items", payload));
            return new PurchaseHistoryItem(
                SimpleJson.getInt(result, "itemId", 0),
                SimpleJson.getString(result, "itemName", itemName.trim())
            );
        } catch (Exception exception) {
            throw new RuntimeException("Failed to add purchase-history item: " + exception.getMessage(), exception);
        }
    }

    public void deleteItem(int itemId) {
        try {
            ApiClient.delete("/purchase-history/items/" + itemId);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to delete purchase-history item: " + exception.getMessage(), exception);
        }
    }

    public PurchaseHistoryData getRecords(int itemId, LocalDate from, LocalDate to) {
        try {
            StringBuilder path = new StringBuilder("/purchase-history/items/").append(itemId).append("/records");
            List<String> filters = new ArrayList<>();
            if (from != null) filters.add("from=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8));
            if (to != null) filters.add("to=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8));
            if (!filters.isEmpty()) path.append("?").append(String.join("&", filters));

            Map<String, Object> response = SimpleJson.parseObject(ApiClient.get(path.toString()));
            List<PurchaseHistoryRecord> records = new ArrayList<>();
            Object rows = response.get("records");
            if (rows instanceof List) {
                for (Object value : (List<?>) rows) {
                    if (!(value instanceof Map)) continue;
                    Map<String, Object> row = new HashMap<>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                        if (entry.getKey() instanceof String) row.put((String) entry.getKey(), entry.getValue());
                    }
                    records.add(new PurchaseHistoryRecord(
                        SimpleJson.getInt(row, "purchaseId", 0),
                        SimpleJson.getLocalDate(row, "date"),
                        SimpleJson.getBigDecimal(row, "quantity", BigDecimal.ZERO),
                        SimpleJson.getString(row, "unit", ""),
                        SimpleJson.getBigDecimal(row, "unitPrice", BigDecimal.ZERO),
                        SimpleJson.getBigDecimal(row, "totalPaid", BigDecimal.ZERO)
                    ));
                }
            }
            return new PurchaseHistoryData(records, SimpleJson.getBigDecimal(response, "totalPaid", BigDecimal.ZERO));
        } catch (Exception exception) {
            throw new RuntimeException("Failed to load purchase history: " + exception.getMessage(), exception);
        }
    }

    public void addRecord(int itemId, LocalDate date, BigDecimal quantity, String unit, BigDecimal totalPaid) {
        try {
            String payload = "{\"date\":\"" + date + "\","
                + "\"quantity\":" + quantity.toPlainString() + ","
                + "\"unit\":\"" + SimpleJson.escape(unit) + "\","
                + "\"totalPaid\":" + totalPaid.toPlainString() + "}";
            ApiClient.post("/purchase-history/items/" + itemId + "/records", payload);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to save purchase: " + exception.getMessage(), exception);
        }
    }
}
