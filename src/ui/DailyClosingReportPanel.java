package ui;

import dao.DailyClosingReportDAO;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
import model.DailyClosingReport;
import model.DailyClosingReport.Expense;
import util.UIHelper;

public class DailyClosingReportPanel extends JPanel {
    private final DailyClosingReportDAO dao = new DailyClosingReportDAO();
    private final JTextField dateField = new JTextField(LocalDate.now().toString(), 10);
    private final JTextField openingCashField = new JTextField("0", 10);
    private final JTextField cashSalesField = new JTextField("0", 10);
    private final JTextField onlineSalesField = new JTextField("0", 10);
    private final JTextField totalSalesField = new JTextField("0", 10);
    private final JTextField totalExpensesField = new JTextField("0", 10);
    private final JTextField endingCashField = new JTextField("0", 10);
    private final JLabel statusLabel = new JLabel(" ");
    private final DefaultTableModel expenseModel = new DefaultTableModel(
        new String[]{"Supplier / Store", "Bill Amount (Rs.)", "Legacy Bill / Receipt"}, 0
    ) {
        @Override public boolean isCellEditable(int row, int column) { return true; }
    };
    private final JTable expenseTable = new JTable(expenseModel);
    private boolean loading;
    private boolean updatingTotals;
    private boolean salesOverridden;
    private boolean expensesOverridden;
    private boolean endingCashOverridden;

    public DailyClosingReportPanel() {
        setLayout(new BorderLayout(10, 10));
        setBackground(UIHelper.DARK_BG);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(buildDateBar(), BorderLayout.NORTH);
        add(buildReportContent(), BorderLayout.CENTER);
        installCalculationListeners();
        loadReport(LocalDate.now());
    }

    private JPanel buildDateBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        bar.setBackground(UIHelper.PANEL_BG);
        bar.setBorder(UIHelper.createCustomTitledBorder("Daily Closing Report"));
        JLabel dateLabel = new JLabel("Report date (YYYY-MM-DD):");
        dateLabel.setForeground(UIHelper.TEXT_WHITE);
        UIHelper.styleTextField(dateField);
        JButton loadButton = UIHelper.createButton("Load Date", UIHelper.BKR_GOLD, Color.BLACK, 13);
        loadButton.addActionListener(event -> loadDateFromField());
        bar.add(dateLabel);
        bar.add(dateField);
        bar.add(loadButton);
        return bar;
    }

    private JPanel buildReportContent() {
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setOpaque(false);
        content.add(buildSummaryFields(), BorderLayout.NORTH);
        content.add(buildExpenses(), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(10, 5));
        footer.setOpaque(false);
        statusLabel.setForeground(UIHelper.TEXT_MUTED);
        JButton addExpense = UIHelper.createButton("+ Add Payment / Expense", UIHelper.BKR_GOLD, Color.BLACK, 13);
        addExpense.addActionListener(event -> addExpense());
        JButton editExpense = UIHelper.createButton("Edit Selected", UIHelper.BKR_GOLD, Color.BLACK, 13);
        editExpense.addActionListener(event -> editSelectedExpense());
        JButton removeExpense = UIHelper.createButton("Remove Selected", UIHelper.BKR_RED_DARK, Color.WHITE, 13);
        removeExpense.addActionListener(event -> {
            int row = expenseTable.getSelectedRow();
            if (row >= 0) expenseModel.removeRow(expenseTable.convertRowIndexToModel(row));
        });
        JButton saveButton = UIHelper.createButton("Save Daily Report", UIHelper.BKR_RED, Color.WHITE, 14);
        saveButton.addActionListener(event -> saveReport());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(addExpense);
        buttons.add(editExpense);
        buttons.add(removeExpense);
        buttons.add(saveButton);
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
        return content;
    }

    private JPanel buildSummaryFields() {
        JPanel fields = new JPanel(new GridLayout(2, 3, 10, 8));
        fields.setBackground(UIHelper.PANEL_BG);
        fields.setBorder(UIHelper.createCustomTitledBorder("Closing Totals"));
        addSummaryField(fields, "Opening Cash", openingCashField);
        addSummaryField(fields, "Cash Sales", cashSalesField);
        addSummaryField(fields, "Online Sales", onlineSalesField);
        addSummaryField(fields, "Total Sales", totalSalesField);
        addSummaryField(fields, "Total Expenses", totalExpensesField);
        addSummaryField(fields, "Ending Cash", endingCashField);
        return fields;
    }

    private void addSummaryField(JPanel parent, String title, JTextField field) {
        JPanel item = new JPanel(new BorderLayout(4, 4));
        item.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setForeground(UIHelper.TEXT_MUTED);
        label.setFont(new Font("Segoe UI", Font.BOLD, 12));
        UIHelper.styleTextField(field);
        field.setPreferredSize(new Dimension(120, 34));
        item.add(label, BorderLayout.NORTH);
        item.add(field, BorderLayout.CENTER);
        parent.add(item);
    }

    private JPanel buildExpenses() {
        expenseTable.setRowHeight(30);
        UIHelper.styleTable(expenseTable);
        expenseTable.removeColumn(expenseTable.getColumnModel().getColumn(2));
        JScrollPane scroll = new JScrollPane(expenseTable);
        UIHelper.styleScrollPane(scroll);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(UIHelper.PANEL_BG);
        panel.setBorder(UIHelper.createCustomTitledBorder("Payments / Grocery Purchases / Bills"));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void addExpense() {
        JTextField supplierField = new JTextField(18);
        JTextField billAmountField = new JTextField("0", 18);
        if (!showExpenseEditor("Add Payment / Expense", supplierField, billAmountField)) return;
        expenseModel.addRow(new Object[]{
            supplierField.getText().trim(),
            parseAmount(billAmountField.getText(), "Bill amount").toPlainString(),
            ""
        });
    }

    private void editSelectedExpense() {
        int selectedRow = expenseTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "Select an expense to edit.", "Edit Expense", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int row = expenseTable.convertRowIndexToModel(selectedRow);
        JTextField supplierField = new JTextField(valueAt(row, 0), 18);
        JTextField billAmountField = new JTextField(valueAt(row, 1), 18);
        if (!showExpenseEditor("Edit Payment / Expense", supplierField, billAmountField)) return;
        expenseModel.setValueAt(supplierField.getText().trim(), row, 0);
        expenseModel.setValueAt(parseAmount(billAmountField.getText(), "Bill amount").toPlainString(), row, 1);
    }

    private boolean showExpenseEditor(String title, JTextField supplierField, JTextField billAmountField) {
        JPanel editor = new JPanel(new GridLayout(0, 1, 4, 4));
        editor.add(new JLabel("Supplier / Store"));
        editor.add(supplierField);
        editor.add(new JLabel("Bill Amount (Rs.)"));
        editor.add(billAmountField);
        if (JOptionPane.showConfirmDialog(this, editor, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return false;
        }
        try {
            parseAmount(billAmountField.getText(), "Bill amount");
            if (supplierField.getText().trim().length() > 150) {
                throw new IllegalArgumentException("Supplier names are limited to 150 characters.");
            }
            return true;
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return false;
        }
    }

    private void installCalculationListeners() {
        DocumentListener inputListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { recalculate(true); }
            @Override public void removeUpdate(DocumentEvent event) { recalculate(true); }
            @Override public void changedUpdate(DocumentEvent event) { recalculate(true); }
        };
        openingCashField.getDocument().addDocumentListener(inputListener);
        cashSalesField.getDocument().addDocumentListener(inputListener);
        onlineSalesField.getDocument().addDocumentListener(inputListener);
        addOverrideListener(totalSalesField, () -> salesOverridden = true);
        addOverrideListener(totalExpensesField, () -> {
            expensesOverridden = true;
            endingCashOverridden = false;
        });
        addOverrideListener(endingCashField, () -> endingCashOverridden = true);
        expenseModel.addTableModelListener(event -> {
            if (!loading && !updatingTotals && event.getType() != TableModelEvent.DELETE) {
                expensesOverridden = false;
                endingCashOverridden = false;
                recalculate(false);
            } else if (!loading && event.getType() == TableModelEvent.DELETE) {
                expensesOverridden = false;
                endingCashOverridden = false;
                recalculate(false);
            }
        });
    }

    private void addOverrideListener(JTextField field, Runnable markOverridden) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!loading && !updatingTotals) markOverridden.run();
                if (!loading && !updatingTotals && field == totalExpensesField) recalculate(false);
            }
            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
        });
    }

    private void recalculate(boolean resetOverrides) {
        if (loading) return;
        if (resetOverrides) {
            salesOverridden = false;
            expensesOverridden = false;
            endingCashOverridden = false;
        }
        try {
            BigDecimal cash = parseAmount(cashSalesField.getText(), "Cash sales");
            BigDecimal online = parseAmount(onlineSalesField.getText(), "Online sales");
            BigDecimal opening = parseAmount(openingCashField.getText(), "Opening cash");
            BigDecimal expenses = sumExpenseRows();
            updatingTotals = true;
            if (!salesOverridden) totalSalesField.setText(cash.add(online).toPlainString());
            if (!expensesOverridden) totalExpensesField.setText(expenses.toPlainString());
            if (!endingCashOverridden) {
                endingCashField.setText(opening.add(cash).subtract(parseAmount(totalExpensesField.getText(), "Total expenses")).toPlainString());
            }
            statusLabel.setForeground(UIHelper.TEXT_MUTED);
            statusLabel.setText("Ending cash = opening cash + cash sales − total expenses.");
        } catch (IllegalArgumentException exception) {
            statusLabel.setForeground(new Color(190, 60, 50));
            statusLabel.setText(exception.getMessage());
        } finally {
            updatingTotals = false;
        }
    }

    private BigDecimal sumExpenseRows() {
        BigDecimal total = BigDecimal.ZERO;
        for (int row = 0; row < expenseModel.getRowCount(); row++) {
            Object value = expenseModel.getValueAt(row, 1);
            total = total.add(parseAmount(value == null ? "" : value.toString(), "Bill amount"));
        }
        return total;
    }

    private BigDecimal parseAmount(String value, String field) {
        try {
            BigDecimal amount = value == null || value.trim().isEmpty()
                ? BigDecimal.ZERO : new BigDecimal(value.trim());
            if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2
                    || amount.compareTo(new BigDecimal("9999999999.99")) > 0) {
                throw new IllegalArgumentException(field + " must be non-negative with at most two decimal places.");
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be a valid amount.");
        }
    }

    private void loadDateFromField() {
        try {
            loadReport(LocalDate.parse(dateField.getText().trim()));
        } catch (DateTimeParseException exception) {
            showError("Enter a valid report date in YYYY-MM-DD format.");
        }
    }

    private void loadReport(LocalDate date) {
        try {
            loading = true;
            DailyClosingReport report = dao.getReport(date);
            dateField.setText(date.toString());
            expenseModel.setRowCount(0);
            if (report == null) {
                setFieldValues(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
                statusLabel.setText("No saved report for this date. Enter values and save.");
            } else {
                setFieldValues(report.getOpeningCash(), report.getCashSales(), report.getOnlineSales(),
                    report.getSales(), report.getExpensesTotal(), report.getEndingCash());
                for (Expense expense : report.getExpenses()) {
                    expenseModel.addRow(new Object[]{expense.getSupplier(), expense.getAmount().toPlainString(), expense.getBill()});
                }
                statusLabel.setText("Saved report loaded.");
            }
            salesOverridden = report != null;
            expensesOverridden = report != null;
            endingCashOverridden = report != null;
        } catch (RuntimeException exception) {
            showError(exception.getMessage());
            return;
        } finally {
            loading = false;
        }
        recalculate(false);
    }

    private void setFieldValues(BigDecimal opening, BigDecimal cash, BigDecimal online,
            BigDecimal sales, BigDecimal expenses, BigDecimal ending) {
        openingCashField.setText(opening.toPlainString());
        cashSalesField.setText(cash.toPlainString());
        onlineSalesField.setText(online.toPlainString());
        totalSalesField.setText(sales.toPlainString());
        totalExpensesField.setText(expenses.toPlainString());
        endingCashField.setText(ending.toPlainString());
    }

    private void saveReport() {
        try {
            if (expenseTable.isEditing()) expenseTable.getCellEditor().stopCellEditing();
            LocalDate date = LocalDate.parse(dateField.getText().trim());
            List<Expense> expenses = new ArrayList<>();
            for (int row = 0; row < expenseModel.getRowCount(); row++) {
                String supplier = valueAt(row, 0);
                String legacyBillReference = valueAt(row, 2);
                if (supplier.length() > 150) {
                    throw new IllegalArgumentException("Supplier names are limited to 150 characters.");
                }
                expenses.add(new Expense(supplier, legacyBillReference, parseAmount(valueAt(row, 1), "Bill amount")));
            }
            DailyClosingReport report = new DailyClosingReport(
                date,
                parseAmount(openingCashField.getText(), "Opening cash"),
                parseAmount(cashSalesField.getText(), "Cash sales"),
                parseAmount(onlineSalesField.getText(), "Online sales"),
                parseAmount(totalSalesField.getText(), "Total sales"),
                parseAmount(totalExpensesField.getText(), "Total expenses"),
                parseAmount(endingCashField.getText(), "Ending cash"),
                expenses
            );
            dao.save(report);
            statusLabel.setForeground(new Color(60, 130, 70));
            statusLabel.setText("Report saved. It is now available in Closing Monthly Summary and the Android app.");
        } catch (DateTimeParseException exception) {
            showError("Enter a valid report date in YYYY-MM-DD format.");
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        } catch (RuntimeException exception) {
            showError(exception.getMessage());
        }
    }

    private String valueAt(int row, int column) {
        Object value = expenseModel.getValueAt(row, column);
        return value == null ? "" : value.toString().trim();
    }

    public void refresh() {
        loadDateFromField();
    }

    private void showError(String message) {
        statusLabel.setForeground(new Color(190, 60, 50));
        statusLabel.setText("Error: " + message);
    }
}
