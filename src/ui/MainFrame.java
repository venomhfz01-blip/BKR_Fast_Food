/*
 * Decompiled with CFR 0.152.
 */
package ui;

import api.ApiClient;
import api.OfflineStore;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import util.BKRLogoPanel;
import util.UIHelper;

public class MainFrame
extends JFrame {
    private ViewInventoryPanel viewInventoryPanel;
    private StockInPanel stockInPanel;
    private BillHistoryPanel billHistoryPanel;
    private NewOrderPanel newOrderPanel;
    private OrderHistoryPanel orderHistoryPanel;
    private SalesReportPanel salesReportPanel;
    private MenuManagementPanel menuManagementPanel;
    private ItemPurchaseHistoryPanel itemPurchaseHistoryPanel;
    private DailyClosingReportPanel dailyClosingReportPanel;
    private ClosingMonthlySummaryPanel closingMonthlySummaryPanel;
    private JLabel serverStatusLabel;
    private JButton modeButton;
    private JTabbedPane tabs;

    public MainFrame() {
        this.setTitle("BKR BACHA KHAN RESTAURANT & FAST FOOD");
        this.setDefaultCloseOperation(3);
        this.setSize(1280, 830);
        this.setMinimumSize(new Dimension(1080, 700));
        this.setLocationRelativeTo(null);
        this.getContentPane().setBackground(UIHelper.DARK_BG);

        JTabbedPane jTabbedPane = new JTabbedPane();
        this.tabs = jTabbedPane;
        jTabbedPane.setFont(new Font("Segoe UI", Font.BOLD, 14));
        jTabbedPane.setBackground(UIHelper.DARK_BG);
        jTabbedPane.setForeground(UIHelper.BKR_RED);
        jTabbedPane.setUI(new BasicTabbedPaneUI() {
            @Override
            protected void installDefaults() {
                super.installDefaults();
                this.tabAreaInsets = new Insets(8, 14, 2, 14);
                this.contentBorderInsets = new Insets(4, 4, 4, 4);
                this.tabInsets = new Insets(10, 18, 10, 18);
            }

            @Override
            protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (isSelected) {
                    g2.setPaint(new GradientPaint(x, y, UIHelper.BKR_RED, x, y + h, UIHelper.BKR_RED_DARK));
                    g2.fillRoundRect(x, y, w, h + 5, 12, 12);
                } else {
                    g2.setColor(UIHelper.PANEL_BG);
                    g2.fillRoundRect(x, y + 3, w, h + 2, 10, 10);
                    g2.setColor(UIHelper.BORDER_DARK);
                    g2.drawRoundRect(x, y + 3, w, h + 1, 10, 10);
                }
                g2.dispose();
            }

            @Override
            protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics, int tabIndex, String title, Rectangle textRect, boolean isSelected) {
                g.setFont(font);
                g.setColor(isSelected ? Color.WHITE : UIHelper.TEXT_MUTED);
                g.drawString(title, textRect.x, textRect.y + metrics.getAscent());
            }

            @Override
            protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex, Rectangle iconRect, Rectangle textRect, boolean isSelected) {
                // no focus ring
            }

            @Override
            protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
                // clean flat content area, panels draw their own borders
            }
        });

        this.viewInventoryPanel = new ViewInventoryPanel();
        this.billHistoryPanel = new BillHistoryPanel();
        this.stockInPanel = new StockInPanel(() -> {
            this.viewInventoryPanel.refresh();
            this.billHistoryPanel.refresh();
        });
        this.orderHistoryPanel = new OrderHistoryPanel();
        this.salesReportPanel = new SalesReportPanel();
        this.newOrderPanel = new NewOrderPanel(() -> {
            this.orderHistoryPanel.refresh();
            this.salesReportPanel.refresh();
            this.viewInventoryPanel.refresh();
        });
        this.menuManagementPanel = new MenuManagementPanel(() -> this.newOrderPanel.refreshMenuComboPublic());
        this.itemPurchaseHistoryPanel = new ItemPurchaseHistoryPanel();
        this.dailyClosingReportPanel = new DailyClosingReportPanel();
        this.closingMonthlySummaryPanel = new ClosingMonthlySummaryPanel();
        
        jTabbedPane.addTab("New Order", this.newOrderPanel);
        jTabbedPane.addTab("Order History", this.orderHistoryPanel);
        jTabbedPane.addTab("Sales Report", this.salesReportPanel);
        jTabbedPane.addTab("Menu Management", this.menuManagementPanel);
        jTabbedPane.addTab("Inventory", this.viewInventoryPanel);
        jTabbedPane.addTab("Add Stock", this.stockInPanel);
        jTabbedPane.addTab("Inventory History", this.billHistoryPanel);
        jTabbedPane.addTab("Item Purchase History", this.itemPurchaseHistoryPanel);
        jTabbedPane.addTab("Daily Closing Report", this.dailyClosingReportPanel);
        jTabbedPane.addTab("Closing Monthly Summary", this.closingMonthlySummaryPanel);
        
        jTabbedPane.addChangeListener(changeEvent -> {
            this.refreshTab(jTabbedPane.getSelectedIndex());
        });
        this.getContentPane().setLayout(new BorderLayout());
        this.getContentPane().add((Component)this.buildHeaderPanel(), "North");
        this.getContentPane().add((Component)jTabbedPane, "Center");
    }

    private JPanel buildHeaderPanel() {
        JPanel jPanel = new JPanel(new BorderLayout(15, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, UIHelper.BKR_RED_DARK, getWidth(), getHeight(), UIHelper.BKR_RED));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(0, 0, 0, 45));
                g2.fillRect(0, getHeight() - 4, getWidth(), 2);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        jPanel.setOpaque(false);
        jPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 4, 0, UIHelper.BKR_GOLD),
            BorderFactory.createEmptyBorder(10, 20, 10, 20)
        ));

        // Left Brand Box
        JPanel brandBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        brandBox.setOpaque(false);

        BKRLogoPanel logo = new BKRLogoPanel();
        brandBox.add(logo);

        JPanel titleBox = new JPanel();
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.setOpaque(false);

        JLabel titleLabel = new JLabel("BKR BACHA KHAN RESTAURANT");
        titleLabel.setFont(new Font("Impact", Font.PLAIN, 24));
        titleLabel.setForeground(Color.WHITE);

        JLabel subtitleLabel = new JLabel("AND FAST FOOD");
        subtitleLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
        subtitleLabel.setForeground(new Color(255, 221, 140));

        JLabel infoLabel = new JLabel("MAIN GT ROAD, ESSORI STOP, NEAR ARMY PUBLIC SCHOOL   \u2022   Tel: 0333-6250944");
        infoLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        infoLabel.setForeground(new Color(250, 236, 215));

        titleBox.add(titleLabel);
        titleBox.add(subtitleLabel);
        titleBox.add(Box.createVerticalStrut(2));
        titleBox.add(infoLabel);

        brandBox.add(titleBox);

        // Right Status Box
        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
        rightBox.setOpaque(false);

        this.serverStatusLabel = new JLabel("●  Server: " + ApiClient.getServerHost() + ":" + ApiClient.getServerPort());
        this.serverStatusLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        this.serverStatusLabel.setForeground(new Color(60, 130, 70));
        this.serverStatusLabel.setOpaque(true);
        this.serverStatusLabel.setBackground(new Color(234, 244, 233));
        this.serverStatusLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(198, 224, 197), 1),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));

        JButton serverBtn = UIHelper.createButton("Server Connection", UIHelper.BKR_RED_DARK, Color.WHITE, 12);
        serverBtn.addActionListener(actionEvent -> this.showServerSettingsDialog());
        this.modeButton = UIHelper.createButton("", UIHelper.BKR_GOLD, Color.BLACK, 12);
        this.modeButton.addActionListener(actionEvent -> this.toggleMode());
        JButton syncButton = UIHelper.createButton("Sync Center", UIHelper.BKR_RED_DARK, Color.WHITE, 12);
        syncButton.addActionListener(actionEvent -> new OfflineSyncCenterDialog(this).setVisible(true));

        rightBox.add(this.serverStatusLabel);
        rightBox.add(this.modeButton);
        rightBox.add(syncButton);
        rightBox.add(serverBtn);
        this.updateModeDisplay();

        jPanel.add(brandBox, BorderLayout.WEST);
        jPanel.add(rightBox, BorderLayout.EAST);
        return jPanel;
    }

    private void toggleMode() {
        boolean offline = !ApiClient.isOfflineMode();
        String message = offline
            ? "Switch to Offline mode? Orders and inventory changes will be saved on this computer until you sync."
            : "Switch to Server mode? New changes will be sent directly to the server.";
        if (JOptionPane.showConfirmDialog(this, message, "Change Working Mode",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return;
        try {
            ApiClient.setOfflineMode(offline);
            this.updateModeDisplay();
            this.refreshTab(this.tabs.getSelectedIndex());
            if (!offline && OfflineStore.getPendingCount() > 0) {
                JOptionPane.showMessageDialog(this,
                    OfflineStore.getPendingCount() + " offline change(s) are still waiting. Open Sync Center to send them.",
                    "Changes Still Pending", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Mode Change Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateModeDisplay() {
        boolean offline = ApiClient.isOfflineMode();
        this.modeButton.setText(offline ? "Offline Mode" : "Server Mode");
        this.modeButton.setBackground(offline ? new Color(255, 220, 125) : new Color(221, 240, 222));
        this.modeButton.setForeground(Color.BLACK);
        this.serverStatusLabel.setText((offline ? "\u25cf  Offline  |  " : "\u25cf  Server: ")
            + (offline ? OfflineStore.getPendingCount() + " pending"
                : ApiClient.getServerHost() + ":" + ApiClient.getServerPort()
                    + "  |  " + OfflineStore.getPendingCount() + " pending"));
        this.serverStatusLabel.setForeground(offline ? new Color(140, 90, 0) : new Color(60, 130, 70));
    }

    public void refreshOfflineStatus() {
        this.updateModeDisplay();
    }

    private void refreshTab(int index) {
        switch (index) {
            case 0: this.newOrderPanel.refreshMenuComboPublic(); break;
            case 1: this.orderHistoryPanel.refresh(); break;
            case 2: this.salesReportPanel.refresh(); break;
            case 3: this.menuManagementPanel.refresh(); break;
            case 4: this.viewInventoryPanel.refresh(); break;
            case 6: this.billHistoryPanel.refresh(); break;
            case 7:
                this.itemPurchaseHistoryPanel.refreshDates();
                this.itemPurchaseHistoryPanel.refresh();
                break;
            case 8: this.dailyClosingReportPanel.refresh(); break;
            case 9: this.closingMonthlySummaryPanel.refresh(); break;
            default: break;
        }
    }

    private void showServerSettingsDialog() {
        JPanel jPanel = new JPanel(new BorderLayout(10, 10));
        jPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        JLabel jLabel = new JLabel("<html><b>Server Address (Local IP or Live Cloud URL):</b><br><font color='#555555'>Examples: <code>http://192.168.1.100:8080</code> or <code>https://api.bkrfastfood.com</code></font></html>");
        JTextField jTextField = new JTextField(ApiClient.getBaseUrl(), 28);
        jTextField.setFont(new Font("SansSerif", 0, 13));
        jPanel.add((Component)jLabel, "North");
        jPanel.add((Component)jTextField, "Center");
        int n = JOptionPane.showConfirmDialog(this, jPanel, "Server Connection Settings", 2, -1);
        if (n == 0) {
            String string = jTextField.getText().trim();
            if (string.isEmpty()) {
                string = "http://localhost:8080/api";
            }
            ApiClient.saveConfig(string, 0);
            this.updateModeDisplay();
            if (ApiClient.testConnection()) {
                JOptionPane.showMessageDialog(this, "Successfully connected to BKR Backend Server!\n" + ApiClient.getBaseUrl(), "Connected", 1);
            } else {
                JOptionPane.showMessageDialog(this, "Could not connect to server at:\n" + ApiClient.getBaseUrl() + "\n\nPlease check if backend server is online and URL is correct.", "Connection Warning", 2);
            }
            this.newOrderPanel.refreshMenuComboPublic();
            this.viewInventoryPanel.refresh();
        }
    }
}
