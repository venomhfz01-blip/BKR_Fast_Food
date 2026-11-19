/*
 * Decompiled with CFR 0.152.
 */
package ui;

import dao.InventoryDAO;
import dao.MenuDAO;
import dao.OrderDAO;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import model.MenuItem;
import model.Order;
import model.OrderItem;
import util.BillPrinter;
import util.UIHelper;
import util.WrapLayout;

public class NewOrderPanel
extends JPanel {
    private static final List<String> CATEGORY_ORDER = Arrays.asList("Pizza", "Burger", "Chicken Broast", "Hot Wings", "Fries", "Drinks", "Juices", "Deals", "Pasta", "Paratha Roll", "Shawarma", "Wrap", "BKR Special Karahi", "BKR Handi (Boneless)", "BKR Soup (Boneless)", "BKR Naan (Roti)");
    private final MenuDAO menuDAO = new MenuDAO();
    private final InventoryDAO inventoryDAO = new InventoryDAO();
    private final OrderDAO orderDAO = new OrderDAO();
    private final Runnable onSaved;
    private final LinkedHashMap<String, LinkedHashMap<String, List<MenuItem>>> categorizedItems = new LinkedHashMap<String, LinkedHashMap<String, List<MenuItem>>>();
    private JPanel categoryListPanel;
    private JButton activeCategoryBtn = null;
    private JPanel itemGridPanel;
    private JScrollPane itemScrollPane;
    private JSpinner qtySpinner;
    private JComboBox<String> orderTypeCombo;
    private JComboBox<String> paymentTypeCombo;
    private JCheckBox tableNumberCheck;
    private JTextField tableNumberField;
    private JTextField discountField;
    private JCheckBox deliveryChargeCheck;
    private JTextField deliveryChargeField;
    private JCheckBox serviceChargeCheck;
    private JTextField serviceChargeField;
    private DefaultTableModel lineTableModel;
    private JTable lineTable;
    private JLabel subtotalLabel;
    private JLabel totalLabel;
    private JLabel lowStockLabel;
    private JPanel lowStockPanel;
    private List<MenuItem> rawMenuItems = new ArrayList<MenuItem>();
    private final Map<Integer, String> displayedSizes = new LinkedHashMap<Integer, String>();

    public NewOrderPanel(Runnable runnable) {
        this.onSaved = runnable;
        this.setLayout(new BorderLayout(10, 10));
        this.setBackground(UIHelper.DARK_BG);
        this.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, this.buildMenuBrowserSection(), this.buildOrderSummarySection());
        splitPane.setResizeWeight(0.62);
        splitPane.setDividerLocation(0.62);
        splitPane.setDividerSize(8);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setContinuousLayout(true);
        splitPane.setBackground(UIHelper.DARK_BG);
        splitPane.setOpaque(false);
        if (splitPane.getUI() instanceof BasicSplitPaneUI) {
            ((BasicSplitPaneUI) splitPane.getUI()).getDivider().setBackground(UIHelper.DARK_BG);
        }
        this.add((Component) splitPane, BorderLayout.CENTER);
        this.refreshMenuItems();
    }

    private JComponent buildMenuBrowserSection() {
        JPanel jPanel = new JPanel(new BorderLayout(8, 6));
        jPanel.setBackground(UIHelper.DARK_BG);
        jPanel.setMinimumSize(new Dimension(0, 220));

        JPanel jPanel2 = new JPanel(new FlowLayout(0, 12, 3));
        jPanel2.setBackground(UIHelper.PANEL_BG);
        jPanel2.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIHelper.BORDER_DARK),
            BorderFactory.createEmptyBorder(2, 8, 2, 8)
        ));

        JLabel jLabel = new JLabel("Quantity per click:");
        jLabel.setFont(new Font("SansSerif", 1, 14));
        jLabel.setForeground(UIHelper.TEXT_WHITE);

        this.qtySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
        this.qtySpinner.setFont(new Font("SansSerif", 1, 13));
        this.qtySpinner.setPreferredSize(new Dimension(70, 26));

        JLabel jLabel2 = new JLabel("Tip: Select category on left, then click any item or size button to add to bill.");
        jLabel2.setFont(new Font("SansSerif", 0, 13));
        jLabel2.setForeground(UIHelper.TEXT_MUTED);

        jPanel2.add(jLabel);
        jPanel2.add(this.qtySpinner);
        jPanel2.add(Box.createHorizontalStrut(10));
        jPanel2.add(jLabel2);

        this.categoryListPanel = new JPanel();
        this.categoryListPanel.setLayout(new BoxLayout(this.categoryListPanel, 1));
        this.categoryListPanel.setBackground(new Color(81, 48, 37));
        this.categoryListPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JScrollPane jScrollPane = new JScrollPane(this.categoryListPanel);
        jScrollPane.setPreferredSize(new Dimension(195, 260));
        jScrollPane.setHorizontalScrollBarPolicy(31);
        jScrollPane.setVerticalScrollBarPolicy(20);
        UIHelper.styleScrollPane(jScrollPane);
        jScrollPane.setBorder(BorderFactory.createLineBorder(new Color(81, 48, 37), 1));

        this.itemGridPanel = new JPanel(new WrapLayout(0, 10, 10));
        this.itemGridPanel.setBackground(new Color(250, 246, 239));

        this.itemScrollPane = new JScrollPane(this.itemGridPanel);
        this.itemScrollPane.setVerticalScrollBarPolicy(20);
        this.itemScrollPane.setHorizontalScrollBarPolicy(31);
        UIHelper.styleScrollPane(this.itemScrollPane);
        this.itemScrollPane.getVerticalScrollBar().setUnitIncrement(18);
        this.itemScrollPane.getViewport().setBackground(new Color(250, 246, 239));

        JPanel jPanel3 = new JPanel(new BorderLayout(6, 0));
        jPanel3.setBackground(new Color(250, 246, 239));
        jPanel3.add((Component)jScrollPane, "West");
        jPanel3.add((Component)this.itemScrollPane, "Center");

        jPanel.add((Component)jPanel2, "North");
        jPanel.add((Component)jPanel3, "Center");
        return jPanel;
    }

    private JComponent buildOrderSummarySection() {
        JPanel jPanel = new JPanel(new BorderLayout(8, 8));
        jPanel.setBackground(UIHelper.PANEL_BG);
        jPanel.setPreferredSize(new Dimension(0, 230));
        jPanel.setMinimumSize(new Dimension(0, 170));

        Object[] objectArray = new String[]{"Item", "Qty", "Price", "Total (Rs.)"};
        this.lineTableModel = new DefaultTableModel(objectArray, 0){

            @Override
            public boolean isCellEditable(int n, int n2) {
                return false;
            }
        };
        this.lineTable = new JTable(this.lineTableModel);
        UIHelper.styleTable(this.lineTable);

        DefaultTableCellRenderer defaultTableCellRenderer = new DefaultTableCellRenderer();
        defaultTableCellRenderer.setHorizontalAlignment(4);
        this.lineTable.getColumnModel().getColumn(1).setCellRenderer(defaultTableCellRenderer);
        this.lineTable.getColumnModel().getColumn(2).setCellRenderer(defaultTableCellRenderer);

        DefaultTableCellRenderer defaultTableCellRenderer2 = new DefaultTableCellRenderer(){

            @Override
            public Component getTableCellRendererComponent(JTable jTable, Object object, boolean bl, boolean bl2, int n, int n2) {
                Component component = super.getTableCellRendererComponent(jTable, object, bl, bl2, n, n2);
                this.setHorizontalAlignment(4);
                this.setFont(this.getFont().deriveFont(Font.BOLD));
                if (!bl) {
                    this.setForeground(UIHelper.BKR_GOLD_BRIGHT);
                }
                return component;
            }
        };
        this.lineTable.getColumnModel().getColumn(3).setCellRenderer(defaultTableCellRenderer2);
        this.lineTable.getColumnModel().getColumn(0).setPreferredWidth(320);
        this.lineTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        this.lineTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        this.lineTable.getColumnModel().getColumn(3).setPreferredWidth(110);

        JScrollPane jScrollPane = new JScrollPane(this.lineTable);
        UIHelper.styleScrollPane(jScrollPane);

        JPanel jPanel2 = new JPanel(new BorderLayout(10, 6));
        jPanel2.setBackground(UIHelper.PANEL_BG);
        jPanel2.setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 4));

        JPanel jPanel3 = new JPanel(new WrapLayout(0, 6, 2));
        jPanel3.setBackground(UIHelper.PANEL_BG);

        Font font = new Font("SansSerif", 1, 13);
        Font font2 = new Font("SansSerif", 0, 13);

        this.orderTypeCombo = new JComboBox<String>(new String[]{"Takeaway", "Dine-in", "Delivery"});
        this.orderTypeCombo.setFont(font2);
        this.orderTypeCombo.setPreferredSize(new Dimension(100, 32));
        UIHelper.styleComboBox(this.orderTypeCombo);

        this.paymentTypeCombo = new JComboBox<String>(new String[]{"Cash", "Card", "Online"});
        this.paymentTypeCombo.setFont(font2);
        this.paymentTypeCombo.setPreferredSize(new Dimension(90, 32));
        UIHelper.styleComboBox(this.paymentTypeCombo);

        this.tableNumberCheck = new JCheckBox("Table No.");
        this.tableNumberCheck.setFont(font);
        this.tableNumberCheck.setForeground(UIHelper.TEXT_WHITE);
        this.tableNumberCheck.setOpaque(false);

        this.tableNumberField = new JTextField("", 5);
        UIHelper.styleTextField(this.tableNumberField);
        this.tableNumberField.setPreferredSize(new Dimension(60, 32));
        this.tableNumberField.setEnabled(false);

        this.discountField = new JTextField("0", 6);
        UIHelper.styleTextField(this.discountField);
        this.discountField.setPreferredSize(new Dimension(72, 32));
        this.discountField.addActionListener(actionEvent -> this.recalcTotals());
        this.discountField.addFocusListener(new FocusAdapter(){

            @Override
            public void focusLost(FocusEvent focusEvent) {
                NewOrderPanel.this.recalcTotals();
            }
        });

        this.deliveryChargeCheck = new JCheckBox("Delivery (Rs.)");
        this.deliveryChargeCheck.setFont(font);
        this.deliveryChargeCheck.setForeground(UIHelper.TEXT_WHITE);
        this.deliveryChargeCheck.setOpaque(false);

        this.deliveryChargeField = new JTextField("0", 5);
        UIHelper.styleTextField(this.deliveryChargeField);
        this.deliveryChargeField.setPreferredSize(new Dimension(60, 32));

        this.serviceChargeCheck = new JCheckBox("Service (%)");
        this.serviceChargeCheck.setFont(font);
        this.serviceChargeCheck.setForeground(UIHelper.TEXT_WHITE);
        this.serviceChargeCheck.setOpaque(false);

        this.serviceChargeField = new JTextField("0", 4);
        UIHelper.styleTextField(this.serviceChargeField);
        this.serviceChargeField.setPreferredSize(new Dimension(50, 32));

        this.deliveryChargeCheck.addActionListener(actionEvent -> this.recalcTotals());
        this.tableNumberCheck.addActionListener(actionEvent -> this.tableNumberField.setEnabled(this.tableNumberCheck.isSelected()));
        this.serviceChargeCheck.addActionListener(actionEvent -> this.recalcTotals());
        this.deliveryChargeField.addActionListener(actionEvent -> this.recalcTotals());
        this.serviceChargeField.addActionListener(actionEvent -> this.recalcTotals());
        this.deliveryChargeField.addFocusListener(new FocusAdapter(){

            @Override
            public void focusLost(FocusEvent focusEvent) {
                NewOrderPanel.this.recalcTotals();
            }
        });
        this.serviceChargeField.addFocusListener(new FocusAdapter(){

            @Override
            public void focusLost(FocusEvent focusEvent) {
                NewOrderPanel.this.recalcTotals();
            }
        });

        JButton jButton = UIHelper.createButton("+ Qty", new Color(40, 100, 180), Color.WHITE, 12);
        jButton.setPreferredSize(new Dimension(68, 32));
        jButton.addActionListener(actionEvent -> this.modifySelectedQty(1));

        JButton jButton2 = UIHelper.createButton("- Qty", new Color(100, 100, 110), Color.WHITE, 12);
        jButton2.setPreferredSize(new Dimension(68, 32));
        jButton2.addActionListener(actionEvent -> this.modifySelectedQty(-1));

        JButton jButton3 = UIHelper.createButton("Remove", UIHelper.BKR_RED, Color.WHITE, 12);
        jButton3.setPreferredSize(new Dimension(75, 32));
        jButton3.addActionListener(actionEvent -> {
            int n = this.lineTable.getSelectedRow();
            if (n >= 0) {
                this.lineTableModel.removeRow(n);
                this.recalcTotals();
            } else {
                JOptionPane.showMessageDialog(this, "Select an item from table to remove.", "No Selection", 2);
            }
        });

        JLabel jLabel = new JLabel("Type:");
        jLabel.setFont(font);
        jLabel.setForeground(UIHelper.TEXT_WHITE);

        JLabel jLabel2 = new JLabel("Pay:");
        jLabel2.setFont(font);
        jLabel2.setForeground(UIHelper.TEXT_WHITE);

        JLabel jLabel3 = new JLabel("Disc (Rs.):");
        jLabel3.setFont(font);
        jLabel3.setForeground(UIHelper.TEXT_WHITE);

        jPanel3.add(jLabel);
        jPanel3.add(this.orderTypeCombo);
        jPanel3.add(jLabel2);
        jPanel3.add(this.paymentTypeCombo);
        jPanel3.add(this.tableNumberCheck);
        jPanel3.add(this.tableNumberField);
        jPanel3.add(jLabel3);
        jPanel3.add(this.discountField);
        jPanel3.add(this.deliveryChargeCheck);
        jPanel3.add(this.deliveryChargeField);
        jPanel3.add(this.serviceChargeCheck);
        jPanel3.add(this.serviceChargeField);
        jPanel3.add(Box.createHorizontalStrut(8));
        jPanel3.add(jButton);
        jPanel3.add(jButton2);
        jPanel3.add(jButton3);

        JPanel jPanel4 = new JPanel(new FlowLayout(2, 15, 0));
        jPanel4.setBackground(UIHelper.PANEL_BG);

        this.lowStockPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        this.lowStockPanel.setBackground(UIHelper.PANEL_BG);
        this.lowStockPanel.setVisible(false);
        this.lowStockPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));

        this.lowStockLabel = new JLabel();
        this.lowStockLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        this.lowStockLabel.setForeground(new Color(255, 183, 77));
        this.lowStockLabel.setHorizontalAlignment(SwingConstants.CENTER);
        this.lowStockPanel.add(this.lowStockLabel);

        this.subtotalLabel = new JLabel("Subtotal: Rs. 0.00");
        this.subtotalLabel.setFont(new Font("SansSerif", 1, 14));
        this.subtotalLabel.setForeground(UIHelper.TEXT_MUTED);

        this.totalLabel = new JLabel("TOTAL: Rs. 0.00");
        this.totalLabel.setFont(new Font("SansSerif", 1, 22));
        this.totalLabel.setForeground(UIHelper.BKR_GOLD_BRIGHT);

        JButton saveOnlyButton = UIHelper.createButton("Save Only", UIHelper.BKR_RED, Color.WHITE, 15);
        saveOnlyButton.setPreferredSize(new Dimension(150, 42));
        saveOnlyButton.addActionListener(actionEvent -> this.saveOrder(false));

        JButton savePrintButton = UIHelper.createButton("Save & Print", UIHelper.BKR_RED, Color.WHITE, 15);
        savePrintButton.setPreferredSize(new Dimension(180, 42));
        savePrintButton.addActionListener(actionEvent -> this.saveOrder(true));

        jPanel4.add(this.lowStockPanel);
        jPanel4.add(this.subtotalLabel);
        jPanel4.add(this.totalLabel);
        jPanel4.add(saveOnlyButton);
        jPanel4.add(savePrintButton);
        this.refreshLowStockWarnings();

        jPanel2.add((Component)jPanel3, "Center");
        jPanel2.add((Component)jPanel4, "South");
        jPanel.add((Component)jScrollPane, "Center");
        jPanel.add((Component)jPanel2, "South");
        return jPanel;
    }

    private void refreshLowStockWarnings() {
        try {
            List<model.InventoryItem> lowStockItems = this.inventoryDAO.getLowStockItems();
            if (lowStockItems == null || lowStockItems.isEmpty()) {
                this.lowStockPanel.setVisible(false);
                this.lowStockLabel.setText("");
                return;
            }

            StringBuilder text = new StringBuilder("Low Stock: ");
            for (int i = 0; i < lowStockItems.size(); i++) {
                model.InventoryItem item = lowStockItems.get(i);
                if (i > 0) {
                    text.append("  |  ");
                }
                text.append(item.getItemName()).append(" (").append(item.getQuantity()).append(")");
            }
            this.lowStockLabel.setText(text.toString());
            this.lowStockPanel.setVisible(true);
        }
        catch (Exception exception) {
            this.lowStockPanel.setVisible(false);
            this.lowStockLabel.setText("");
        }
    }

    public void refreshMenuItems() {
        if (this.categoryListPanel == null) {
            return;
        }
        this.rawMenuItems = this.menuDAO.getAllActiveMenuItems();
        this.displayedSizes.clear();
        this.categorizedItems.clear();
        for (String cat : CATEGORY_ORDER) {
            this.categorizedItems.put(cat, new LinkedHashMap<String, List<MenuItem>>());
        }
        for (MenuItem menuItem : this.rawMenuItems) {
            String cat = menuItem.getCategory() != null && !menuItem.getCategory().isBlank() ? menuItem.getCategory().trim() : "General";
            String name = menuItem.getName().trim();
            this.categorizedItems.computeIfAbsent(cat, k -> new LinkedHashMap<String, List<MenuItem>>()).computeIfAbsent(name, k -> new ArrayList<MenuItem>()).add(menuItem);
        }
        this.categorizedItems.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        this.categoryListPanel.removeAll();
        this.activeCategoryBtn = null;
        if (this.categorizedItems.isEmpty()) {
            JLabel noItemsLabel = new JLabel("<html><center>No menu items.<br>Add from Menu<br>Management tab.</center></html>");
            noItemsLabel.setForeground(Color.WHITE);
            noItemsLabel.setFont(new Font("SansSerif", 2, 13));
            noItemsLabel.setAlignmentX(0.5f);
            this.categoryListPanel.add(noItemsLabel);
            this.itemGridPanel.removeAll();
        } else {
            for (String catName : this.categorizedItems.keySet()) {
                JButton btn = this.createCategoryTabButton(catName);
                this.categoryListPanel.add(btn);
                this.categoryListPanel.add(Box.createVerticalStrut(4));
            }
            String firstCat = this.categorizedItems.keySet().iterator().next();
            JButton jButton = (JButton)this.categoryListPanel.getComponent(0);
            this.selectCategory(firstCat, jButton);
        }
        this.categoryListPanel.revalidate();
        this.categoryListPanel.repaint();
    }

    private JButton createCategoryTabButton(String string) {
        JButton jButton = new JButton("\u25B8  " + string);
        jButton.setFont(new Font("SansSerif", Font.BOLD, 13));
        jButton.setForeground(new Color(255, 238, 215));
        jButton.setBackground(new Color(91, 57, 43));
        jButton.setFocusPainted(false);
        jButton.setBorderPainted(false);
        jButton.setOpaque(true);
        jButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        jButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        jButton.setPreferredSize(new Dimension(180, 42));
        jButton.setAlignmentX(0.0f);
        jButton.setHorizontalAlignment(2);
        jButton.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 6));
        jButton.addActionListener(actionEvent -> this.selectCategory(string, jButton));
        return jButton;
    }



    private void selectCategory(String string, JButton jButton) {
        if (this.activeCategoryBtn != null) {
            this.activeCategoryBtn.setBackground(new Color(91, 57, 43));
            this.activeCategoryBtn.setForeground(new Color(255, 238, 215));
            this.activeCategoryBtn.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 6));
        }
        jButton.setBackground(UIHelper.BKR_RED);
        jButton.setForeground(Color.WHITE);
        jButton.setBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, UIHelper.BKR_GOLD_BRIGHT));
        this.activeCategoryBtn = jButton;
        this.renderCategoryProducts(string);
    }

    private void renderCategoryProducts(String string) {
        this.itemGridPanel.removeAll();
        LinkedHashMap<String, List<MenuItem>> linkedHashMap = this.categorizedItems.getOrDefault(string, new LinkedHashMap<String, List<MenuItem>>());
        if (linkedHashMap.isEmpty()) {
            JLabel jLabel = new JLabel("No items found in " + string + ".");
            jLabel.setFont(new Font("SansSerif", Font.ITALIC, 14));
            jLabel.setForeground(UIHelper.TEXT_MUTED);
            this.itemGridPanel.add(jLabel);
        } else {
            for (Map.Entry<String, List<MenuItem>> entry : linkedHashMap.entrySet()) {
                String string2 = entry.getKey();
                List<MenuItem> list = entry.getValue();
                this.itemGridPanel.add(this.createProductCard(string2, list, string));
            }
        }
        this.itemGridPanel.revalidate();
        this.itemGridPanel.repaint();
        SwingUtilities.invokeLater(() -> this.itemScrollPane.getVerticalScrollBar().setValue(0));
    }

    private JPanel createProductCard(String string, List<MenuItem> list, String categoryName) {
        boolean bl;
        boolean pizzaCategory = categoryName.equalsIgnoreCase("Pizza");
        boolean halfFullCategory = categoryName.equalsIgnoreCase("BKR Special Karahi") || categoryName.equalsIgnoreCase("BKR Handi (Boneless)");
        JPanel jPanel = new JPanel(new BorderLayout(6, 6));
        jPanel.setBackground(Color.WHITE);
        jPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIHelper.BORDER_DARK, 1),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));

        JLabel jLabel = new JLabel(string, SwingConstants.CENTER);
        jLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        jLabel.setForeground(UIHelper.BKR_RED);
        jPanel.add((Component)jLabel, "North");
        int titleWidth = jLabel.getFontMetrics(jLabel.getFont()).stringWidth(string);

        JPanel jPanel2 = new JPanel();
        boolean bl2 = bl = list.size() > 1 || list.size() == 1 && list.get(0).hasSizeVariant();
        if (bl) {
            jPanel2.setOpaque(false);
            List<MenuItem> variants = new ArrayList<MenuItem>();
            java.util.Set<String> seenSizes = new java.util.HashSet<String>();
            for (int variantIndex = 0; variantIndex < list.size(); variantIndex++) {
                MenuItem menuItem = list.get(variantIndex);
                String size = menuItem.getSize();
                if (size == null || size.isBlank() || size.equalsIgnoreCase("None")) {
                    if (pizzaCategory) {
                        boolean crownCrust = string.equalsIgnoreCase("Crown Crust");
                        String[] pizzaSizes = crownCrust
                            ? new String[]{"Medium", "Large", "XL"}
                            : new String[]{"Small", "Medium", "Large", "XL"};
                        size = pizzaSizes[Math.min(variantIndex, pizzaSizes.length - 1)];
                    } else if (halfFullCategory) {
                        String[] halfFullSizes = new String[]{"Half", "Full"};
                        size = halfFullSizes[Math.min(variantIndex, halfFullSizes.length - 1)];
                    } else {
                        size = "Regular";
                    }
                }
                if (seenSizes.add(size.toLowerCase())) {
                    variants.add(menuItem);
                }
            }
            if (pizzaCategory || halfFullCategory) {
                variants.sort((left, right) -> left.getPrice().compareTo(right.getPrice()));
            }
            jPanel2.setLayout(new GridLayout(variants.size(), 1, 4, 4));
            for (int variantIndex = 0; variantIndex < variants.size(); variantIndex++) {
                MenuItem menuItem = variants.get(variantIndex);
                String string2 = menuItem.getSize();
                if (string2 == null || string2.isBlank() || string2.equalsIgnoreCase("None")) {
                    if (pizzaCategory) {
                        boolean crownCrust = string.equalsIgnoreCase("Crown Crust");
                        String[] pizzaSizes = crownCrust
                            ? new String[]{"Medium", "Large", "XL"}
                            : new String[]{"Small", "Medium", "Large", "XL"};
                        string2 = pizzaSizes[Math.min(variantIndex, pizzaSizes.length - 1)];
                    } else if (halfFullCategory) {
                        String[] halfFullSizes = new String[]{"Half", "Full"};
                        string2 = halfFullSizes[Math.min(variantIndex, halfFullSizes.length - 1)];
                    } else {
                        string2 = "Regular";
                    }
                }
                this.displayedSizes.put(menuItem.getMenuItemId(), string2);
                String string3 = "<html><font color='#4b3024'><b>" + string2 + "</b></font> &nbsp;—&nbsp; <font color='#a66c0d'><b>Rs. " + menuItem.getPrice().toPlainString() + "</b></font></html>";
                final JButton jButton = new JButton(string3);
                jButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
                jButton.setBackground(new Color(255, 249, 240));
                jButton.setForeground(UIHelper.TEXT_WHITE);
                jButton.setFocusPainted(false);
                jButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
                jButton.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIHelper.BKR_RED_DARK, 1),
                    BorderFactory.createEmptyBorder(5, 8, 5, 8)
                ));
                jButton.addMouseListener(new MouseAdapter(){

                    @Override
                    public void mouseEntered(MouseEvent mouseEvent) {
                        jButton.setBackground(new Color(250, 224, 211));
                    }

                    @Override
                    public void mouseExited(MouseEvent mouseEvent) {
                        jButton.setBackground(new Color(255, 249, 240));
                    }
                });
                jButton.addActionListener(actionEvent -> this.addItemToOrder(menuItem));
                jPanel2.add(jButton);
            }
            jPanel.add((Component)jPanel2, "Center");
            int n = Math.max(180, titleWidth + 40);
            int naturalHeight = jPanel.getPreferredSize().height;
            jPanel.setPreferredSize(new Dimension(n, naturalHeight));
            return jPanel;
        } else {
            MenuItem menuItem = list.get(0);
            jPanel2.setLayout(new BorderLayout());
            jPanel2.setOpaque(false);
            String string4 = "<html><font color='#ffffff'><b>Rs. " + menuItem.getPrice().toPlainString() + "</b></font></html>";
            final JButton jButton = new JButton(string4);
            jButton.setFont(new Font("SansSerif", Font.BOLD, 14));
            jButton.setBackground(UIHelper.BKR_RED);
            jButton.setForeground(Color.WHITE);
            jButton.setFocusPainted(false);
            jButton.setOpaque(true);
            jButton.setBorderPainted(false);
            jButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            jButton.setPreferredSize(new Dimension(150, 38));
            jButton.addMouseListener(new MouseAdapter(){

                @Override
                public void mouseEntered(MouseEvent mouseEvent) {
                    jButton.setBackground(UIHelper.BKR_RED_HOVER);
                }

                @Override
                public void mouseExited(MouseEvent mouseEvent) {
                    jButton.setBackground(UIHelper.BKR_RED);
                }
            });
            jButton.addActionListener(actionEvent -> this.addItemToOrder(menuItem));
            jPanel2.add((Component)jButton, "Center");
            jPanel.add((Component)jPanel2, "Center");
            int n = Math.max(150, titleWidth + 40);
            int naturalHeight = jPanel.getPreferredSize().height;
            jPanel.setPreferredSize(new Dimension(n, naturalHeight));
        }
        return jPanel;
    }

    private void addItemToOrder(MenuItem menuItem) {
        int n = (Integer)this.qtySpinner.getValue();
        if (n <= 0) {
            n = 1;
        }
        String string = this.getOrderItemName(menuItem);
        BigDecimal bigDecimal = menuItem.getPrice();
        BigDecimal bigDecimal2 = bigDecimal.multiply(BigDecimal.valueOf(n));
        for (int i = 0; i < this.lineTableModel.getRowCount(); ++i) {
            if (!this.lineTableModel.getValueAt(i, 0).equals(string)) continue;
            int n2 = (Integer)this.lineTableModel.getValueAt(i, 1);
            int n3 = n2 + n;
            BigDecimal bigDecimal3 = bigDecimal.multiply(BigDecimal.valueOf(n3));
            this.lineTableModel.setValueAt(n3, i, 1);
            this.lineTableModel.setValueAt(bigDecimal3, i, 3);
            this.recalcTotals();
            this.qtySpinner.setValue(1);
            return;
        }
        this.lineTableModel.addRow(new Object[]{string, n, bigDecimal, bigDecimal2});
        this.recalcTotals();
        this.qtySpinner.setValue(1);
    }

    private String getOrderItemName(MenuItem menuItem) {
        if (menuItem.hasSizeVariant()) {
            return menuItem.getDisplayName();
        }
        String displayedSize = this.displayedSizes.get(menuItem.getMenuItemId());
        if (displayedSize != null && !displayedSize.equalsIgnoreCase("Regular")) {
            return menuItem.getName() + " (" + displayedSize + ")";
        }
        return menuItem.getDisplayName();
    }

    private void modifySelectedQty(int n) {
        int n2 = this.lineTable.getSelectedRow();
        if (n2 < 0) {
            JOptionPane.showMessageDialog(this, "Please select an item from the table first.", "No Selection", 2);
            return;
        }
        int n3 = (Integer)this.lineTableModel.getValueAt(n2, 1);
        int n4 = n3 + n;
        if (n4 <= 0) {
            this.lineTableModel.removeRow(n2);
        } else {
            BigDecimal bigDecimal = (BigDecimal)this.lineTableModel.getValueAt(n2, 2);
            BigDecimal bigDecimal2 = bigDecimal.multiply(BigDecimal.valueOf(n4));
            this.lineTableModel.setValueAt(n4, n2, 1);
            this.lineTableModel.setValueAt(bigDecimal2, n2, 3);
        }
        this.recalcTotals();
    }

    public void refreshMenuComboPublic() {
        this.refreshMenuItems();
    }

    private void recalcTotals() {
        BigDecimal bigDecimal = BigDecimal.ZERO;
        for (int i = 0; i < this.lineTableModel.getRowCount(); ++i) {
            bigDecimal = bigDecimal.add((BigDecimal)this.lineTableModel.getValueAt(i, 3));
        }
        BigDecimal bigDecimal2 = this.parseDiscount();
        BigDecimal bigDecimal3 = bigDecimal.subtract(bigDecimal2).max(BigDecimal.ZERO);
        BigDecimal bigDecimal4 = this.parseDeliveryCharge();
        BigDecimal bigDecimal5 = this.parseServiceChargePercent();
        BigDecimal bigDecimal6 = bigDecimal3.multiply(bigDecimal5).divide(BigDecimal.valueOf(100L));
        BigDecimal bigDecimal7 = bigDecimal3.add(this.deliveryChargeCheck.isSelected() ? bigDecimal4 : BigDecimal.ZERO).add(this.serviceChargeCheck.isSelected() ? bigDecimal6 : BigDecimal.ZERO);
        this.subtotalLabel.setText("Subtotal: Rs. " + bigDecimal.toPlainString());
        this.totalLabel.setText("TOTAL: Rs. " + bigDecimal7.toPlainString());
    }

    private BigDecimal parseDiscount() {
        try {
            return new BigDecimal(this.discountField.getText().trim());
        }
        catch (Exception exception) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal parseDeliveryCharge() {
        try {
            return new BigDecimal(this.deliveryChargeField.getText().trim()).max(BigDecimal.ZERO);
        }
        catch (Exception exception) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal parseServiceChargePercent() {
        try {
            return new BigDecimal(this.serviceChargeField.getText().trim()).max(BigDecimal.ZERO);
        }
        catch (Exception exception) {
            return BigDecimal.ZERO;
        }
    }

    private void saveOrder() {
        saveOrder(true);
    }

    private void saveOrder(boolean printBill) {
        if (this.lineTableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, "Add at least one item before saving.", "Nothing to Save", 2);
            return;
        }
        try {
            BigDecimal bigDecimal;
            BigDecimal bigDecimal2;
            Object object;
            List<MenuItem> list = this.menuDAO.getAllActiveMenuItems();
            Order order = new Order();
            order.setOrderNo(this.orderDAO.generateOrderNo());
            order.setOrderDate(LocalDate.now());
            order.setOrderTime(LocalTime.now(ZoneId.of("Asia/Karachi")));
            order.setCustomerName("");
            order.setOrderType((String)this.orderTypeCombo.getSelectedItem());
            String tableNumber = this.tableNumberField.getText().trim();
            if (this.tableNumberCheck.isSelected() && tableNumber.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Enter a table number or uncheck Table No.", "Table Number Required", 2);
                return;
            }
            order.setTableNumber(this.tableNumberCheck.isSelected() ? tableNumber : "");
            order.setPaymentType((String)this.paymentTypeCombo.getSelectedItem());
            BigDecimal bigDecimal3 = BigDecimal.ZERO;
            for (int i = 0; i < this.lineTableModel.getRowCount(); ++i) {
                String itemName = this.lineTableModel.getValueAt(i, 0).toString();
                int n = (Integer)this.lineTableModel.getValueAt(i, 1);
                bigDecimal2 = (BigDecimal)this.lineTableModel.getValueAt(i, 2);
                bigDecimal = (BigDecimal)this.lineTableModel.getValueAt(i, 3);
                MenuItem menuItem = list.stream().filter(m -> m.getDisplayName().equalsIgnoreCase(itemName) || m.getName().equalsIgnoreCase(itemName)).findFirst().orElse(null);
                int n2 = menuItem != null ? menuItem.getMenuItemId() : 0;
                OrderItem orderItem = new OrderItem(n2, itemName, n, bigDecimal2);
                orderItem.setLineTotal(bigDecimal);
                order.addItem(orderItem);
                bigDecimal3 = bigDecimal3.add(bigDecimal);
            }
            BigDecimal bigDecimal4 = this.parseDiscount();
            BigDecimal bigDecimal5 = this.parseDeliveryCharge();
            object = this.parseServiceChargePercent();
            bigDecimal2 = bigDecimal3.subtract(bigDecimal4).max(BigDecimal.ZERO);
            bigDecimal = bigDecimal2.multiply((BigDecimal)object).divide(BigDecimal.valueOf(100L));
            BigDecimal bigDecimal6 = bigDecimal2.add(this.deliveryChargeCheck.isSelected() ? bigDecimal5 : BigDecimal.ZERO).add(this.serviceChargeCheck.isSelected() ? bigDecimal : BigDecimal.ZERO);
            order.setSubtotal(bigDecimal3);
            order.setDiscount(bigDecimal4);
            order.setDeliveryCharge(this.deliveryChargeCheck.isSelected() ? bigDecimal5 : BigDecimal.ZERO);
            order.setServiceChargePercent((BigDecimal)(this.serviceChargeCheck.isSelected() ? object : BigDecimal.ZERO));
            order.setServiceCharge(this.serviceChargeCheck.isSelected() ? bigDecimal : BigDecimal.ZERO);
            order.setTotalAmount(bigDecimal6);
            this.orderDAO.saveOrder(order);
            if (printBill) {
                BillPrinter.showOrderBill(this, order);
            }
            JOptionPane.showMessageDialog(this, "Order saved successfully.", "Saved", JOptionPane.INFORMATION_MESSAGE);
            this.lineTableModel.setRowCount(0);
            this.discountField.setText("0");
            this.tableNumberCheck.setSelected(false);
            this.tableNumberField.setText("");
            this.tableNumberField.setEnabled(false);
            this.deliveryChargeCheck.setSelected(false);
            this.deliveryChargeField.setText("0");
            this.serviceChargeCheck.setSelected(false);
            this.serviceChargeField.setText("0");
            this.recalcTotals();
            this.refreshLowStockWarnings();
            if (this.onSaved != null) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        this.onSaved.run();
                    }
                    catch (Exception exception) {
                        System.err.println("Warning: post-save refresh non-critical error: " + exception.getMessage());
                    }
                });
            }
        }
        catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "Failed to save order: " + exception.getMessage(), "Database Error", 0);
        }
    }
}
