package model;

import java.math.BigDecimal;
import java.util.List;

public class PurchaseHistoryData {
    private final List<PurchaseHistoryRecord> records;
    private final BigDecimal totalPaid;

    public PurchaseHistoryData(List<PurchaseHistoryRecord> records, BigDecimal totalPaid) {
        this.records = records;
        this.totalPaid = totalPaid;
    }

    public List<PurchaseHistoryRecord> getRecords() {
        return records;
    }

    public BigDecimal getTotalPaid() {
        return totalPaid;
    }
}
