/*
 * Decompiled with CFR 0.152.
 */
package dao;

import api.ApiClient;
import api.SimpleJson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.Order;
import model.OrderItem;

public class OrderDAO {
    public String generateOrderNo() {
        try {
            String string = ApiClient.get("/orders/generate-no");
            Map<String, Object> map = SimpleJson.parseObject(string);
            return SimpleJson.getString(map, "orderNo", "1");
        }
        catch (Exception exception) {
            return "1";
        }
    }

    public int saveOrder(Order order) {
        return saveOrderInternal(order);
    }

    @Deprecated
    public int saveOrder(Order order, boolean ignoredAllowDuplicate) {
        return saveOrderInternal(order);
    }

    private int saveOrderInternal(Order order) {
        try {
            if (order.getClientOrderId() == null || order.getClientOrderId().isBlank()) {
                order.setClientOrderId(java.util.UUID.randomUUID().toString());
            }
            Object object;
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("{");
            stringBuilder.append("\"clientOrderId\":\"").append(SimpleJson.escape(order.getClientOrderId())).append("\",");
            stringBuilder.append("\"orderNo\":\"").append(SimpleJson.escape(order.getOrderNo())).append("\",");
            stringBuilder.append("\"customerName\":\"").append(SimpleJson.escape(order.getCustomerName())).append("\",");
            stringBuilder.append("\"orderType\":\"").append(SimpleJson.escape(order.getOrderType())).append("\",");
            stringBuilder.append("\"tableNumber\":\"").append(SimpleJson.escape(order.getTableNumber())).append("\",");
            stringBuilder.append("\"paymentType\":\"").append(SimpleJson.escape(order.getPaymentType())).append("\",");
            stringBuilder.append("\"subtotal\":").append(order.getSubtotal() != null ? order.getSubtotal().toPlainString() : "0").append(",");
            stringBuilder.append("\"discount\":").append(order.getDiscount() != null ? order.getDiscount().toPlainString() : "0").append(",");
            stringBuilder.append("\"deliveryCharge\":").append(order.getDeliveryCharge() != null ? order.getDeliveryCharge().toPlainString() : "0").append(",");
            stringBuilder.append("\"serviceChargePercent\":").append(order.getServiceChargePercent() != null ? order.getServiceChargePercent().toPlainString() : "0").append(",");
            stringBuilder.append("\"serviceCharge\":").append(order.getServiceCharge() != null ? order.getServiceCharge().toPlainString() : "0").append(",");
            stringBuilder.append("\"totalAmount\":").append(order.getTotalAmount() != null ? order.getTotalAmount().toPlainString() : "0").append(",");
            stringBuilder.append("\"items\":[");
            List<OrderItem> list = order.getItems();
            for (int i = 0; i < list.size(); ++i) {
                object = list.get(i);
                if (i > 0) {
                    stringBuilder.append(",");
                }
                stringBuilder.append("{");
                stringBuilder.append("\"menuItemId\":").append(((OrderItem)object).getMenuItemId()).append(",");
                stringBuilder.append("\"menuItemName\":\"").append(SimpleJson.escape(((OrderItem)object).getMenuItemName())).append("\",");
                stringBuilder.append("\"quantity\":").append(((OrderItem)object).getQuantity()).append(",");
                stringBuilder.append("\"unitPrice\":").append(((OrderItem)object).getUnitPrice() != null ? ((OrderItem)object).getUnitPrice().toPlainString() : "0").append(",");
                stringBuilder.append("\"lineTotal\":").append(((OrderItem)object).getLineTotal() != null ? ((OrderItem)object).getLineTotal().toPlainString() : "0");
                stringBuilder.append("}");
            }
            stringBuilder.append("]}");
            String string = ApiClient.post("/orders", stringBuilder.toString());
            object = SimpleJson.parseObject(string);
            int n = SimpleJson.getInt((Map)object, "orderId", 0);
            order.setOrderId(n);
            order.setOrderNo(SimpleJson.getString((Map)object, "orderNo", order.getOrderNo()));
            return n;
        }
        catch (Exception exception) {
            throw new RuntimeException("Failed to save order: " + exception.getMessage(), exception);
        }
    }

    public List<Order> getOrdersBetween(LocalDate localDate, LocalDate localDate2) {
        try {
            String string = ApiClient.get("/orders/range?from=" + localDate.toString() + "&to=" + localDate2.toString());
            return this.parseOrderList(string);
        }
        catch (Exception exception) {
            System.err.println("Warning: Failed to fetch orders: " + exception.getMessage());
            return new ArrayList<Order>();
        }
    }

    public List<Order> getOrdersBetweenWithItems(LocalDate localDate, LocalDate localDate2) {
        List<Order> orders = this.getOrdersBetween(localDate, localDate2);
        ArrayList<Order> detailedOrders = new ArrayList<Order>();
        for (Order order : orders) {
            Order detailedOrder = this.getOrderWithItems(order.getOrderId());
            detailedOrders.add(detailedOrder != null ? detailedOrder : order);
        }
        return detailedOrders;
    }

    public Order getOrderWithItems(int n) {
        try {
            String string = ApiClient.get("/orders/" + n);
            return this.parseFullOrder(string);
        }
        catch (Exception exception) {
            System.err.println("Warning: Failed to load order " + n + ": " + exception.getMessage());
            return null;
        }
    }

    public BigDecimal getTotalSalesForDate(LocalDate localDate) {
        try {
            String string = ApiClient.get("/sales/date/" + localDate.toString());
            Map<String, Object> map = SimpleJson.parseObject(string);
            return SimpleJson.getBigDecimal(map, "totalSales", BigDecimal.ZERO);
        }
        catch (Exception exception) {
            List<Order> list = this.getOrdersBetween(localDate, localDate);
            BigDecimal bigDecimal = BigDecimal.ZERO;
            for (Order order : list) {
                if (!"Completed".equalsIgnoreCase(order.getStatus())) continue;
                bigDecimal = bigDecimal.add(order.getTotalAmount());
            }
            return bigDecimal;
        }
    }

    public int getOrderCountForDate(LocalDate localDate) {
        try {
            String string = ApiClient.get("/sales/date/" + localDate.toString());
            Map<String, Object> map = SimpleJson.parseObject(string);
            return SimpleJson.getInt(map, "orderCount", 0);
        }
        catch (Exception exception) {
            return this.getOrdersBetween(localDate, localDate).size();
        }
    }

    public List<Object[]> getItemWiseSales(LocalDate localDate, LocalDate localDate2) {
        ArrayList<Object[]> arrayList = new ArrayList<Object[]>();
        List<Order> list = this.getOrdersBetween(localDate, localDate2);
        LinkedHashMap<String, Integer> linkedHashMap = new LinkedHashMap<String, Integer>();
        LinkedHashMap<String, BigDecimal> linkedHashMap2 = new LinkedHashMap<String, BigDecimal>();
        for (Order object : list) {
            Order order = this.getOrderWithItems(object.getOrderId());
            for (OrderItem orderItem : order.getItems()) {
                String string = orderItem.getMenuItemName();
                linkedHashMap.put(string, linkedHashMap.getOrDefault(string, 0) + orderItem.getQuantity());
                BigDecimal bigDecimal = linkedHashMap2.getOrDefault(string, BigDecimal.ZERO);
                linkedHashMap2.put(string, bigDecimal.add(orderItem.getLineTotal() != null ? orderItem.getLineTotal() : BigDecimal.ZERO));
            }
        }
        for (String string : linkedHashMap.keySet()) {
            arrayList.add(new Object[]{string, linkedHashMap.get(string), linkedHashMap2.get(string)});
        }
        return arrayList;
    }

    private List<Order> parseOrderList(String string) {
        ArrayList<Order> arrayList = new ArrayList<Order>();
        List<Map<String, Object>> list = SimpleJson.parseArray(string);
        for (Map<String, Object> map : list) {
            arrayList.add(this.mapOrder(map));
        }
        return arrayList;
    }

    private Order parseFullOrder(String string) {
        Map<String, Object> map = SimpleJson.parseObject(string);
        return map != null ? this.mapOrder(map) : null;
    }

    private Order mapOrder(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Order order = new Order();
        order.setOrderId(SimpleJson.getInt(map, "orderId", 0));
        order.setOrderNo(SimpleJson.getString(map, "orderNo", ""));
        order.setOrderDate(SimpleJson.getLocalDate(map, "orderDate"));
        order.setOrderTime(SimpleJson.getLocalTime(map, "orderTime"));
        order.setCustomerName(SimpleJson.getString(map, "customerName", ""));
        order.setOrderType(SimpleJson.getString(map, "orderType", "Dine In"));
        order.setTableNumber(SimpleJson.getString(map, "tableNumber", ""));
        order.setPaymentType(SimpleJson.getString(map, "paymentType", "Cash"));
        order.setSubtotal(SimpleJson.getBigDecimal(map, "subtotal", BigDecimal.ZERO));
        order.setDiscount(SimpleJson.getBigDecimal(map, "discount", BigDecimal.ZERO));
        order.setDeliveryCharge(SimpleJson.getBigDecimal(map, "deliveryCharge", BigDecimal.ZERO));
        order.setServiceChargePercent(SimpleJson.getBigDecimal(map, "serviceChargePercent", BigDecimal.ZERO));
        order.setServiceCharge(SimpleJson.getBigDecimal(map, "serviceCharge", BigDecimal.ZERO));
        order.setTotalAmount(SimpleJson.getBigDecimal(map, "totalAmount", BigDecimal.ZERO));
        order.setStatus(SimpleJson.getString(map, "status", "Completed"));
        order.setClientOrderId(SimpleJson.getString(map, "clientOrderId", null));
        Object object = map.get("items");
        if (object instanceof List) {
            for (Object e : (List)object) {
                if (!(e instanceof Map)) continue;
                Map map2 = (Map)e;
                OrderItem orderItem = new OrderItem();
                orderItem.setMenuItemName(SimpleJson.getString(map2, "menuItemName", ""));
                orderItem.setQuantity(SimpleJson.getInt(map2, "quantity", 1));
                orderItem.setUnitPrice(SimpleJson.getBigDecimal(map2, "unitPrice", BigDecimal.ZERO));
                orderItem.setLineTotal(SimpleJson.getBigDecimal(map2, "lineTotal", BigDecimal.ZERO));
                order.addItem(orderItem);
            }
        }
        return order;
    }
}
