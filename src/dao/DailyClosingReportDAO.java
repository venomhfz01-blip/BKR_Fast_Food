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
import model.DailyClosingReport;
import model.DailyClosingReport.Expense;

public class DailyClosingReportDAO {
    public List<DailyClosingReport> getReports(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Select a valid report date range.");
        }
        try {
            String path = "/closing-reports?from="
                + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
                + "&to=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8);
            List<DailyClosingReport> reports = new ArrayList<>();
            for (Map<String, Object> row : SimpleJson.parseArray(ApiClient.get(path))) {
                reports.add(toReport(row));
            }
            return reports;
        } catch (Exception exception) {
            throw new RuntimeException("Failed to load daily closing reports: " + exception.getMessage(), exception);
        }
    }

    public DailyClosingReport getReport(LocalDate date) {
        List<DailyClosingReport> reports = getReports(date, date);
        return reports.isEmpty() ? null : reports.get(0);
    }

    public DailyClosingReport save(DailyClosingReport report) {
        try {
            StringBuilder payload = new StringBuilder("{")
                .append("\"date\":\"").append(report.getDate()).append("\",")
                .append("\"openingCash\":").append(report.getOpeningCash().toPlainString()).append(',')
                .append("\"cashSales\":").append(report.getCashSales().toPlainString()).append(',')
                .append("\"onlineSales\":").append(report.getOnlineSales().toPlainString()).append(',')
                .append("\"sales\":").append(report.getSales().toPlainString()).append(',')
                .append("\"expensesTotal\":").append(report.getExpensesTotal().toPlainString()).append(',')
                .append("\"endingCash\":").append(report.getEndingCash().toPlainString()).append(',')
                .append("\"expenses\":[");
            List<Expense> expenses = report.getExpenses();
            for (int i = 0; i < expenses.size(); i++) {
                Expense expense = expenses.get(i);
                if (i > 0) payload.append(',');
                payload.append("{\"supplier\":\"").append(SimpleJson.escape(expense.getSupplier()))
                    .append("\",\"bill\":\"").append(SimpleJson.escape(expense.getBill()))
                    .append("\",\"amount\":").append(expense.getAmount().toPlainString()).append('}');
            }
            payload.append("]}");
            return toReport(SimpleJson.parseObject(ApiClient.post("/closing-reports", payload.toString())));
        } catch (Exception exception) {
            throw new RuntimeException("Failed to save daily closing report: " + exception.getMessage(), exception);
        }
    }

    private DailyClosingReport toReport(Map<String, Object> row) {
        LocalDate date = SimpleJson.getLocalDate(row, "date");
        List<Expense> expenses = new ArrayList<>();
        Object value = row.get("expenses");
        if (value instanceof List) {
            for (Object entry : (List<?>) value) {
                if (!(entry instanceof Map)) continue;
                Map<String, Object> expense = new HashMap<>();
                for (Map.Entry<?, ?> field : ((Map<?, ?>) entry).entrySet()) {
                    if (field.getKey() instanceof String) {
                        expense.put((String) field.getKey(), field.getValue());
                    }
                }
                expenses.add(new Expense(
                    SimpleJson.getString(expense, "supplier", ""),
                    SimpleJson.getString(expense, "bill", ""),
                    SimpleJson.getBigDecimal(expense, "amount", BigDecimal.ZERO)
                ));
            }
        }
        return new DailyClosingReport(
            date,
            SimpleJson.getBigDecimal(row, "openingCash", BigDecimal.ZERO),
            SimpleJson.getBigDecimal(row, "cashSales", BigDecimal.ZERO),
            SimpleJson.getBigDecimal(row, "onlineSales", BigDecimal.ZERO),
            SimpleJson.getBigDecimal(row, "sales", BigDecimal.ZERO),
            SimpleJson.getBigDecimal(row, "expensesTotal", BigDecimal.ZERO),
            SimpleJson.getBigDecimal(row, "endingCash", BigDecimal.ZERO),
            expenses
        );
    }
}
