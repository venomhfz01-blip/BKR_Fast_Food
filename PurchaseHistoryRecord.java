package model;

import java.math.BigDecimal;
import java.time.LocalDate;

public class PurchaseHistoryRecord {
    private final int purchaseId;
    private final LocalDate date;
    private final BigDecimal quantity;
    private final String unit;
    private final BigDecimal unitPrice;
    private final BigDecimal totalPaid;

    public PurchaseHistoryRecord(int purchaseId, LocalDate date, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal totalPaid) {
        this.purchaseId = purchaseId;
        this.date = date;
        this.quantity = quantity;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.totalPaid = totalPaid;
    }

    public int getPurchaseId() {
        return purchaseId;
    }

    public LocalDate getDate() {
        return date;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getTotalPaid() {
        return totalPaid;
    }
}
