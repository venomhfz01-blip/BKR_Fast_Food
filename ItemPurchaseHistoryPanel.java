package ui;

import dao.PurchaseHistoryDAO;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import model.PurchaseHistoryData;
import model.PurchaseHistoryItem;
import model.PurchaseHistoryRecord;
import util.UIHelper;

public class ItemPurchaseHistoryPanel extends JPanel {
    private static final String[] UNITS = {"packet", "kg", "g", "L", "mL", "piece", "box"};
    private final PurchaseHistoryDAO purchaseHistoryDAO = new PurchaseHistoryDAO();
    private final JPanel itemList = new JPanel();
    private final JLabel selectedItemLabel = new JLabel("Select an item to view its history");
    private final JLabel unitPricePreview = new JLabel("Price per unit: —");
    private final JLabel totalLabel = new JLabel("TOTAL PURCHASED · Rs 0");
    private final JLabel statusLabel = new JLabel(" ");
    private final JTextField fromField = new JTextField(LocalDate.now().withDayOfMonth(1).toString(), 10);
    private final JTextField toField = new JTextField(LocalDate.now().toString(), 10);
    private JButton deleteItemButton;
    private final JTextField purchaseDateField = new JTextField(LocalDate.now().toString(), 10);
    private final JTextField quantityField = new JTextField(8);
    private final JTextField totalPaidField = new JTextField(10);
    private final JComboBox<String> unitCombo = new JComboBox<>(UNITS);
    private final DefaultTableModel tableModel = new DefaultTableModel(
        new String[]{"Date", "Quantity", "Unit", "Price / Unit (Rs.)", "Paid (Rs.)"}, 0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private int selectedItemId = -1;

    public ItemPurchaseHistoryPanel() {
        setLayout(new BorderLayout(12, 12));
        setBackground(UIHelper.DARK_BG);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(buildItemSidebar(), BorderLayout.WEST);
        add(buildHistoryPanel(), BorderLayout.CENTER);
        refresh();
    }

    private JComponent buildItemSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(8, 8));
        sidebar.setBackground(UIHelper.PANEL_BG);
        sidebar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIHelper.BORDER_DARK),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        sidebar.setPreferredSize(new Dimension(215, 0));

        JButton addItemButton = UIHelper.createButton("+ Add Item", UIHelper.BKR_RED, Color.WHITE, 14);
        addItemButton.addActionListener(event -> addItem());
        sidebar.add(addItemButton, BorderLayout.NORTH);

        itemList.setLayout(new javax.swing.BoxLayout(itemList, javax.swing.BoxLayout.Y_AXIS));
        itemList.setBackground(UIHelper.PANEL_BG);
        JScrollPane scrollPane = new JScrollPane(itemList);
        UIHelper.styleScrollPane(scrollPane);
        sidebar.add(scrollPane, BorderLayout.CENTER);
        return sidebar;
    }

    private JComponent buildHistoryPanel() {
        JPanel history = new JPanel(new BorderLayout(10, 10));
        history.setBackground(UIHelper.DARK_BG);

        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.setBackground(UIHelper.DARK_BG);
        selectedItemLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        selectedItemLabel.setForeground(UIHelper.BKR_GOLD_BRIGHT);
        header.add(selectedItemLabel, BorderLayout.NORTH);
        header.add(buildFilterBar(), BorderLayout.CENTER);
        header.add(buildPurchaseForm(), BorderLayout.SOUTH);
        history.add(header, BorderLayout.NORTH);

        JTable table = new JTable(tableModel);
        UIHelper.styleTable(table);
        JScrollPane tableScrollPane = new JScrollPane(table);
        UIHelper.styleScrollPane(tableScrollPane);
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBackground(UIHelper.PANEL_BG);
        tablePanel.setBorder(UIHelper.createCustomTitledBorder("Purchase History"));
        tablePanel.add(tableScrollPane, BorderLayout.CENTER);
        history.add(tablePanel, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 4));
        footer.setBackground(UIHelper.DARK_BG);
        totalLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        totalLabel.setForeground(UIHelper.BKR_GOLD_BRIGHT);
        statusLabel.setForeground(UIHelper.TEXT_MUTED);
        footer.add(totalLabel, BorderLayout.WEST);
        footer.add(statusLabel, BorderLayout.SOUTH);
        history.add(footer, BorderLayout.SOUTH);
        return history;
    }

    private JComponent buildFilterBar() {
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        filters.setBackground(UIHelper.PANEL_BG);
        filters.setBorder(UIHelper.createCustomTitledBorder("Filter by Date"));
        styleField(fromField);
        styleField(toField);
        filters.add(label("From"));
        filters.add(fromField);
        filters.add(label("To"));
        filters.add(toField);

        JButton applyButton = UIHelper.createButton("Apply Filter", UIHelper.BKR_RED, Color.WHITE, 13);
        applyButton.addActionListener(event -> loadSelectedHistory());
        filters.add(applyButton);
        JButton clearButton = UIHelper.createButton("Show All", new Color(60, 60, 80), Color.WHITE, 13);
        clearButton.addActionListener(event -> {
            fromField.setText("");
            toField.setText("");
            loadSelectedHistory();
        });
        filters.add(clearButton);
        deleteItemButton = UIHelper.createButton("Delete Item", UIHelper.BKR_RED_DARK, Color.WHITE, 13);
        deleteItemButton.setEnabled(false);
        deleteItemButton.addActionListener(event -> deleteSelectedItem());
        filters.add(deleteItemButton);
        return filters;
    }

    private JComponent buildPurchaseForm() {
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        form.setBackground(UIHelper.PANEL_BG);
        form.setBorder(UIHelper.createCustomTitledBorder("Add Purchase"));
        styleField(purchaseDateField);
        styleField(quantityField);
        styleField(totalPaidField);
        form.add(label("Date"));
        form.add(purchaseDateField);
        form.add(label("Quantity"));
        form.add(quantityField);
        form.add(unitCombo);
        form.add(label("Full price (Rs.)"));
        form.add(totalPaidField);
        unitPricePreview.setForeground(UIHelper.BKR_GOLD_BRIGHT);
        form.add(unitPricePreview);

        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateUnitPricePreview(); }
            @Override public void removeUpdate(DocumentEvent event) { updateUnitPricePreview(); }
            @Override public void changedUpdate(DocumentEvent event) { updateUnitPricePreview(); }
        };
        quantityField.getDocument().addDocumentListener(listener);
        totalPaidField.getDocument().addDocumentListener(listener);
        unitCombo.addActionListener(event -> updateUnitPricePreview());

        JButton addPurchaseButton = UIHelper.createButton("Save Purchase", UIHelper.BKR_RED, Color.WHITE, 13);
        addPurchaseButton.addActionListener(event -> addPurchase());
        form.add(addPurchaseButton);
        return form;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UIHelper.TEXT_WHITE);
        return label;
    }

    private void styleField(JTextField field) {
        UIHelper.styleTextField(field);
        field.setPreferredSize(new Dimension(field.getPreferredSize().width, 32));
    }

    public void refresh() {
        int previousSelection = selectedItemId;
        try {
            List<PurchaseHistoryItem> items = purchaseHistoryDAO.getItems();
            itemList.removeAll();
            selectedItemId = -1;
            for (PurchaseHistoryItem item : items) {
                boolean selected = item.getItemId() == previousSelection;
                Color background = selected ? UIHelper.BKR_RED_DARK : UIHelper.CARD_BG;
                Color foreground = selected ? Color.WHITE : UIHelper.TEXT_WHITE;
                JButton itemButton = UIHelper.createButton(item.getItemName(), background, foreground, 13);
                itemButton.setAlignmentX(LEFT_ALIGNMENT);
                itemButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
                itemButton.addActionListener(event -> selectItem(item));
                itemList.add(itemButton);
                if (item.getItemId() == previousSelection) selectedItemId = previousSelection;
            }
            itemList.revalidate();
            itemList.repaint();
            if (deleteItemButton != null) deleteItemButton.setEnabled(selectedItemId > 0);
            if (selectedItemId > 0) {
                for (PurchaseHistoryItem item : items) {
                    if (item.getItemId() == selectedItemId) {
                        selectedItemLabel.setText(item.getItemName());
                        break;
                    }
                }
            } else if (!items.isEmpty()) {
                selectItem(items.get(0));
            } else {
                selectedItemLabel.setText("Add an item to begin");
                tableModel.setRowCount(0);
                totalLabel.setText("TOTAL PURCHASED · Rs 0");
            }
            statusLabel.setText(" ");
            if (selectedItemId > 0) loadSelectedHistory();
        } catch (RuntimeException exception) {
            showError(exception);
        }
    }

    public void refreshDates() {
        LocalDate today = LocalDate.now();
        fromField.setText(today.withDayOfMonth(1).toString());
        toField.setText(today.toString());
        purchaseDateField.setText(today.toString());
    }

    private void addItem() {
        String itemName = JOptionPane.showInputDialog(this, "Enter the item name:", "Add Purchase Item", JOptionPane.PLAIN_MESSAGE);
        if (itemName == null || itemName.trim().isEmpty()) return;
        try {
            PurchaseHistoryItem item = purchaseHistoryDAO.addItem(itemName);
            refresh();
            selectItem(item);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Unable to Add Item", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void selectItem(PurchaseHistoryItem item) {
        selectedItemId = item.getItemId();
        selectedItemLabel.setText(item.getItemName());
        deleteItemButton.setEnabled(true);
        refreshItemButtons();
        loadSelectedHistory();
    }

    private void deleteSelectedItem() {
        if (selectedItemId < 0) return;
        int result = JOptionPane.showConfirmDialog(
            this,
            "Delete \"" + selectedItemLabel.getText() + "\" and all of its purchase history?",
            "Confirm Delete Item",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.YES_OPTION) return;
        try {
            purchaseHistoryDAO.deleteItem(selectedItemId);
            selectedItemId = -1;
            tableModel.setRowCount(0);
            totalLabel.setText("TOTAL PURCHASED · Rs 0");
            statusLabel.setText(" ");
            refresh();
        } catch (RuntimeException exception) {
            showError(exception);
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Unable to Delete Item", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshItemButtons() {
        List<PurchaseHistoryItem> items;
        try {
            items = purchaseHistoryDAO.getItems();
        } catch (RuntimeException exception) {
            showError(exception);
            return;
        }
        itemList.removeAll();
        for (PurchaseHistoryItem item : items) {
            boolean selected = item.getItemId() == selectedItemId;
            Color background = selected ? UIHelper.BKR_RED_DARK : UIHelper.CARD_BG;
            Color foreground = selected ? Color.WHITE : UIHelper.TEXT_WHITE;
            JButton itemButton = UIHelper.createButton(item.getItemName(), background, foreground, 13);
            itemButton.setAlignmentX(LEFT_ALIGNMENT);
            itemButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            itemButton.addActionListener(event -> selectItem(item));
            itemList.add(itemButton);
        }
        itemList.revalidate();
        itemList.repaint();
    }

    private void loadSelectedHistory() {
        if (selectedItemId < 0) return;
        try {
            LocalDate from = parseOptionalDate(fromField.getText(), "From");
            LocalDate to = parseOptionalDate(toField.getText(), "To");
            if (from != null && to != null && from.isAfter(to)) {
                throw new IllegalArgumentException("The From date must not be after the To date.");
            }
            PurchaseHistoryData history = purchaseHistoryDAO.getRecords(selectedItemId, from, to);
            tableModel.setRowCount(0);
            for (PurchaseHistoryRecord record : history.getRecords()) {
                tableModel.addRow(new Object[]{
                    record.getDate(),
                    record.getQuantity().stripTrailingZeros().toPlainString(),
                    record.getUnit(),
                    record.getUnitPrice().stripTrailingZeros().toPlainString(),
                    record.getTotalPaid().stripTrailingZeros().toPlainString()
                });
            }
            String totalScope = from != null || to != null ? "TOTAL PURCHASED · FILTERED" : "TOTAL PURCHASED · ALL TIME";
            totalLabel.setText(totalScope + " · Rs " + history.getTotalPaid().stripTrailingZeros().toPlainString());
            statusLabel.setText(history.getRecords().size() + " purchase(s)");
        } catch (RuntimeException exception) {
            showError(exception);
        }
    }

    private LocalDate parseOptionalDate(String value, String field) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " date must use YYYY-MM-DD format.");
        }
    }

    private void addPurchase() {
        if (selectedItemId < 0) {
            JOptionPane.showMessageDialog(this, "Add or select an item first.", "No Item Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            LocalDate date = LocalDate.parse(purchaseDateField.getText().trim());
            BigDecimal quantity = new BigDecimal(quantityField.getText().trim());
            BigDecimal totalPaid = new BigDecimal(totalPaidField.getText().trim());
            if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3) {
                throw new IllegalArgumentException("Quantity must be greater than zero and have no more than 3 decimal places.");
            }
            if (totalPaid.signum() < 0 || totalPaid.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Full purchase price must be non-negative and have no more than 2 decimal places.");
            }
            String unit = (String) unitCombo.getSelectedItem();
            purchaseHistoryDAO.addRecord(selectedItemId, date, quantity, unit, totalPaid);
            quantityField.setText("");
            totalPaidField.setText("");
            purchaseDateField.setText(LocalDate.now().toString());
            loadSelectedHistory();
        } catch (DateTimeParseException exception) {
            JOptionPane.showMessageDialog(this, "Enter a valid purchase date in YYYY-MM-DD format.", "Invalid Date", JOptionPane.WARNING_MESSAGE);
        } catch (NumberFormatException exception) {
            JOptionPane.showMessageDialog(this, "Enter a valid quantity and full purchase price.", "Invalid Purchase", JOptionPane.WARNING_MESSAGE);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Unable to Save Purchase", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateUnitPricePreview() {
        try {
            BigDecimal quantity = new BigDecimal(quantityField.getText().trim());
            BigDecimal totalPaid = new BigDecimal(totalPaidField.getText().trim());
            if (quantity.signum() <= 0 || totalPaid.signum() < 0) throw new NumberFormatException();
            BigDecimal unitPrice = totalPaid.divide(quantity, 4, java.math.RoundingMode.HALF_UP);
            unitPricePreview.setText("Rs " + unitPrice.stripTrailingZeros().toPlainString() + " / " + unitCombo.getSelectedItem());
        } catch (NumberFormatException | ArithmeticException exception) {
            unitPricePreview.setText("Price per unit: —");
        }
    }

    private void showError(Exception exception) {
        statusLabel.setForeground(new Color(255, 154, 134));
        statusLabel.setText("Error: " + exception.getMessage());
    }
}
