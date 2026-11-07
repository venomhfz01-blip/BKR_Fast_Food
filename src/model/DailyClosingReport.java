package model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class DailyClosingReport {
    private final LocalDate date;
    private BigDecimal openingCash;
    private BigDecimal cashSales;
    private BigDecimal onlineSales;
    private BigDecimal sales;
    private BigDecimal expensesTotal;
    private BigDecimal endingCash;
    private List<Expense> expenses;

    public DailyClosingReport(LocalDate date, BigDecimal openingCash, BigDecimal cashSales,
            BigDecimal onlineSales, BigDecimal sales, BigDecimal expensesTotal,
            BigDecimal endingCash, List<Expense> expenses) {
        this.date = date;
        this.openingCash = valueOrZero(openingCash);
        this.cashSales = valueOrZero(cashSales);
        this.onlineSales = valueOrZero(onlineSales);
        this.sales = valueOrZero(sales);
        this.expensesTotal = valueOrZero(expensesTotal);
        this.endingCash = valueOrZero(endingCash);
        this.expenses = expenses == null ? new ArrayList<>() : new ArrayList<>(expenses);
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public LocalDate getDate() { return date; }
    public BigDecimal getOpeningCash() { return openingCash; }
    public void setOpeningCash(BigDecimal value) { openingCash = valueOrZero(value); }
    public BigDecimal getCashSales() { return cashSales; }
    public void setCashSales(BigDecimal value) { cashSales = valueOrZero(value); }
    public BigDecimal getOnlineSales() { return onlineSales; }
    public void setOnlineSales(BigDecimal value) { onlineSales = valueOrZero(value); }
    public BigDecimal getSales() { return sales; }
    public void setSales(BigDecimal value) { sales = valueOrZero(value); }
    public BigDecimal getExpensesTotal() { return expensesTotal; }
    public void setExpensesTotal(BigDecimal value) { expensesTotal = valueOrZero(value); }
    public BigDecimal getEndingCash() { return endingCash; }
    public void setEndingCash(BigDecimal value) { endingCash = valueOrZero(value); }
    public List<Expense> getExpenses() { return new ArrayList<>(expenses); }
    public void setExpenses(List<Expense> value) {
        expenses = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public static class Expense {
        private final String supplier;
        private final String bill;
        private final BigDecimal amount;

        public Expense(String supplier, String bill, BigDecimal amount) {
            this.supplier = supplier == null ? "" : supplier;
            this.bill = bill == null ? "" : bill;
            this.amount = valueOrZero(amount);
        }

        public String getSupplier() { return supplier; }
        public String getBill() { return bill; }
        public BigDecimal getAmount() { return amount; }
    }
}
