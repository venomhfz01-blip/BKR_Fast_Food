/*
 * Decompiled with CFR 0.152.
 */
package model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import model.OrderItem;

public class Order {
    private int orderId;
    private String orderNo;
    private LocalDate orderDate;
    private LocalTime orderTime;
    private String customerName;
    private String orderType;
    private String tableNumber = "";
    private String paymentType;
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal discount = BigDecimal.ZERO;
    private BigDecimal deliveryCharge = BigDecimal.ZERO;
    private BigDecimal serviceChargePercent = BigDecimal.ZERO;
    private BigDecimal serviceCharge = BigDecimal.ZERO;
    private BigDecimal totalAmount = BigDecimal.ZERO;
    private String status = "Completed";
    private String clientOrderId;
    private boolean notified;
    private List<OrderItem> items = new ArrayList<OrderItem>();

    public int getOrderId() {
        return this.orderId;
    }

    public void setOrderId(int n) {
        this.orderId = n;
    }

    public String getOrderNo() {
        return this.orderNo;
    }

    public void setOrderNo(String string) {
        this.orderNo = string;
    }

    public LocalDate getOrderDate() {
        return this.orderDate;
    }

    public void setOrderDate(LocalDate localDate) {
        this.orderDate = localDate;
    }

    public LocalTime getOrderTime() {
        return this.orderTime;
    }

    public void setOrderTime(LocalTime localTime) {
        this.orderTime = localTime;
    }

    public String getCustomerName() {
        return this.customerName;
    }

    public void setCustomerName(String string) {
        this.customerName = string;
    }

    public String getOrderType() {
        return this.orderType;
    }

    public void setOrderType(String string) {
        this.orderType = string;
    }

    public String getTableNumber() {
        return this.tableNumber;
    }

    public void setTableNumber(String string) {
        this.tableNumber = string == null ? "" : string;
    }

    public String getPaymentType() {
        return this.paymentType;
    }

    public void setPaymentType(String string) {
        this.paymentType = string;
    }

    public BigDecimal getSubtotal() {
        return this.subtotal;
    }

    public void setSubtotal(BigDecimal bigDecimal) {
        this.subtotal = bigDecimal;
    }

    public BigDecimal getDiscount() {
        return this.discount;
    }

    public void setDiscount(BigDecimal bigDecimal) {
        this.discount = bigDecimal;
    }

    public BigDecimal getDeliveryCharge() {
        return this.deliveryCharge;
    }

    public void setDeliveryCharge(BigDecimal bigDecimal) {
        this.deliveryCharge = bigDecimal;
    }

    public BigDecimal getServiceChargePercent() {
        return this.serviceChargePercent;
    }

    public void setServiceChargePercent(BigDecimal bigDecimal) {
        this.serviceChargePercent = bigDecimal;
    }

    public BigDecimal getServiceCharge() {
        return this.serviceCharge;
    }

    public void setServiceCharge(BigDecimal bigDecimal) {
        this.serviceCharge = bigDecimal;
    }

    public BigDecimal getTotalAmount() {
        return this.totalAmount;
    }

    public void setTotalAmount(BigDecimal bigDecimal) {
        this.totalAmount = bigDecimal;
    }

    public String getStatus() {
        return this.status;
    }

    public void setStatus(String string) {
        this.status = string;
    }

    public String getClientOrderId() {
        return this.clientOrderId;
    }

    public void setClientOrderId(String string) {
        this.clientOrderId = string;
    }

    public boolean isNotified() {
        return this.notified;
    }

    public void setNotified(boolean bl) {
        this.notified = bl;
    }

    public List<OrderItem> getItems() {
        return this.items;
    }

    public void setItems(List<OrderItem> list) {
        this.items = list;
    }

    public void addItem(OrderItem orderItem) {
        this.items.add(orderItem);
    }
}
