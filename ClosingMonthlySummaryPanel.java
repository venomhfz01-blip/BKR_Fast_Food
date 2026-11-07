package ui;

import dao.DailyClosingReportDAO;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import model.DailyClosingReport;
import model.DailyClosingReport.Expense;
import util.UIHelper;

public class ClosingMonthlySummaryPanel extends JPanel {
    private final DailyClosingReportDAO dao = new DailyClosingReportDAO();
    private final JTextField monthField = new JTextField(7);
    private final JLabel cashTotal = new JLabel("Cash Sales: Rs 0");
    private final JLabel onlineTotal = new JLabel("Online Sales: Rs 0");
    private final JLabel expensesTotal = new JLabel("Total Expenses: Rs 0");
    private final JLabel salesTotal = new JLabel("Total Sales: Rs 0");
    private final JLabel statusLabel = new JLabel(" ");
    private final List<DailyClosingReport> reports = new ArrayList<>();
    private final DefaultTableModel tableModel = new DefaultTableModel(
        new String[]{"Date", "Opening Cash", "Cash Sales", "Online Sales", "Total Expenses", "Total Sales", "Ending Cash", "Details"}, 0
    ) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable reportTable = new JTable(tableModel);

    public ClosingMonthlySummaryPanel() {
        setLayout(new BorderLayout(10, 10));
        setBackground(UIHelper.DARK_BG);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        monthField.setText(YearMonth.now().toString());
        add(buildControls(), BorderLayout.NORTH);
        add(buildReportTable(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        refresh();
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel(new BorderLayout(10, 8));
        controls.setBackground(UIHelper.PANEL_BG);
        controls.setBorder(UIHelper.createCustomTitledBorder("Closing Monthly Summary"));

        JPanel filter = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        filter.setOpaque(false);
        JLabel monthLabel = new JLabel("Filter month (YYYY-MM):");
        monthLabel.setForeground(UIHelper.TEXT_WHITE);
        UIHelper.styleTextField(monthField);
        JButton apply = UIHelper.createButton("Apply Month", UIHelper.BKR_GOLD, Color.BLACK, 13);
        apply.addActionListener(event -> refresh());
        filter.add(monthLabel);
        filter.add(monthField);
        filter.add(apply);
        controls.add(filter, BorderLayout.NORTH);

        JPanel totals = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        totals.setOpaque(false);
        styleTotal(cashTotal, new Color(60, 130, 70));
        styleTotal(onlineTotal, new Color(40, 100, 180));
        styleTotal(expensesTotal, UIHelper.BKR_GOLD);
        styleTotal(salesTotal, UIHelper.BKR_RED);
        totals.add(cashTotal);
        totals.add(onlineTotal);
        totals.add(expensesTotal);
        totals.add(salesTotal);
        controls.add(totals, BorderLayout.CENTER);
        return controls;
    }

    private void styleTotal(JLabel label, Color background) {
        label.setFont(new Font("Segoe UI", Font.BOLD, 14));
        label.setOpaque(true);
        label.setBackground(background);
        label.setForeground(background.equals(UIHelper.BKR_GOLD) ? Color.BLACK : Color.WHITE);
        label.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
    }

    private JScrollPane buildReportTable() {
        reportTable.setRowHeight(34);
        UIHelper.styleTable(reportTable);
        reportTable.getColumnModel().getColumn(7).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean selected, boolean focus, int row, int column) {
                JButton button = UIHelper.createButton("See Report in Details", UIHelper.BKR_RED, Color.WHITE, 12);
                button.setPreferredSize(new java.awt.Dimension(170, 28));
                return button;
            }
        });
        reportTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                int row = reportTable.rowAtPoint(event.getPoint());
                int column = reportTable.columnAtPoint(event.getPoint());
                if (row >= 0 && column == 7) showDetails(reportAt(row));
            }
        });
        JScrollPane scroll = new JScrollPane(reportTable);
        UIHelper.styleScrollPane(scroll);
        return scroll;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        statusLabel.setForeground(UIHelper.TEXT_MUTED);
        footer.add(statusLabel, BorderLayout.WEST);
        return footer;
    }

    private DailyClosingReport reportAt(int viewRow) {
        int modelRow = reportTable.convertRowIndexToModel(viewRow);
        return reports.get(modelRow);
    }

    public void refresh() {
        try {
            YearMonth month = YearMonth.parse(monthField.getText().trim());
            List<DailyClosingReport> loaded = dao.getReports(month.atDay(1), month.atEndOfMonth());
            reports.clear();
            reports.addAll(loaded);
            tableModel.setRowCount(0);

            BigDecimal cash = BigDecimal.ZERO;
            BigDecimal online = BigDecimal.ZERO;
            BigDecimal expenses = BigDecimal.ZERO;
            BigDecimal sales = BigDecimal.ZERO;
            for (DailyClosingReport report : reports) {
                cash = cash.add(report.getCashSales());
                online = online.add(report.getOnlineSales());
                expenses = expenses.add(report.getExpensesTotal());
                sales = sales.add(report.getSales());
                tableModel.addRow(new Object[]{
                    report.getDate().toString(),
                    money(report.getOpeningCash()),
                    money(report.getCashSales()),
                    money(report.getOnlineSales()),
                    money(report.getExpensesTotal()),
                    money(report.getSales()),
                    money(report.getEndingCash()),
                    "See Report in Details"
                });
            }
            cashTotal.setText("Cash Sales: " + money(cash));
            onlineTotal.setText("Online Sales: " + money(online));
            expensesTotal.setText("Total Expenses: " + money(expenses));
            salesTotal.setText("Total Sales: " + money(sales));
            statusLabel.setForeground(UIHelper.TEXT_MUTED);
            statusLabel.setText(reports.size() + " daily report(s) for " + month);
        } catch (DateTimeParseException exception) {
            showError("Month must use YYYY-MM format.");
        } catch (RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private String money(BigDecimal value) {
        return "Rs " + value.stripTrailingZeros().toPlainString();
    }

    private void showDetails(DailyClosingReport report) {
        JPanel details = new JPanel(new BorderLayout(8, 8));
        details.setBackground(UIHelper.PANEL_BG);
        details.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel totals = new JLabel("<html><b>Opening Cash:</b> " + money(report.getOpeningCash())
            + "&nbsp;&nbsp; <b>Cash Sales:</b> " + money(report.getCashSales())
            + "&nbsp;&nbsp; <b>Online Sales:</b> " + money(report.getOnlineSales())
            + "<br><b>Total Sales:</b> " + money(report.getSales())
            + "&nbsp;&nbsp; <b>Total Expenses:</b> " + money(report.getExpensesTotal())
            + "&nbsp;&nbsp; <b>Ending Cash:</b> " + money(report.getEndingCash()) + "</html>");
        details.add(totals, BorderLayout.NORTH);

        DefaultTableModel expenseModel = new DefaultTableModel(
            new String[]{"Supplier / Store", "Bill Amount"}, 0
        ) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (Expense expense : report.getExpenses()) {
            expenseModel.addRow(new Object[]{expense.getSupplier(), money(expense.getAmount())});
        }
        JTable expenseTable = new JTable(expenseModel);
        expenseTable.setRowHeight(28);
        UIHelper.styleTable(expenseTable);
        JScrollPane scroll = new JScrollPane(expenseTable);
        scroll.setPreferredSize(new java.awt.Dimension(620, Math.min(300, Math.max(100, expenseModel.getRowCount() * 30 + 35))));
        details.add(scroll, BorderLayout.CENTER);
        JLabel heading = new JLabel("Daily report and grocery purchase bills · " + report.getDate());
        heading.setFont(new Font("Segoe UI", Font.BOLD, 15));
        heading.setForeground(UIHelper.BKR_GOLD);
        details.add(heading, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, details, "Closing Report Details", JOptionPane.PLAIN_MESSAGE);
    }

    private void showError(String message) {
        statusLabel.setForeground(new Color(190, 60, 50));
        statusLabel.setText("Error: " + message);
    }
}
