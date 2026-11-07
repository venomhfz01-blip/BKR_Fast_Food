package ui;

import api.ApiClient;
import api.OfflineStore;
import api.OfflineSyncService;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import util.UIHelper;

public class OfflineSyncCenterDialog extends JDialog {
    private final JLabel statusLabel = new JLabel();
    private final JTextArea activity = new JTextArea();
    private JButton prepareButton;
    private JButton ordersButton;
    private JButton inventoryButton;
    private JButton syncButton;

    public OfflineSyncCenterDialog(MainFrame owner) {
        super(owner, "Offline Sync Center", true);
        setSize(700, 470);
        setMinimumSize(new Dimension(620, 400));
        setLocationRelativeTo(owner);
        getContentPane().setBackground(UIHelper.DARK_BG);
        setLayout(new BorderLayout(12, 12));
        ((javax.swing.JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        statusLabel.setForeground(UIHelper.TEXT_WHITE);
        add(statusLabel, BorderLayout.NORTH);

        activity.setEditable(false);
        activity.setLineWrap(true);
        activity.setWrapStyleWord(true);
        activity.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(activity);
        UIHelper.styleScrollPane(scrollPane);
        add(scrollPane, BorderLayout.CENTER);

        JButton closeButton = UIHelper.createButton("Close", UIHelper.PANEL_BG, UIHelper.TEXT_WHITE, 13);
        closeButton.addActionListener(event -> dispose());
        prepareButton = UIHelper.createButton("Prepare / Refresh Offline Data", UIHelper.BKR_GOLD, java.awt.Color.BLACK, 13);
        prepareButton.addActionListener(event -> runTask("prepare"));
        ordersButton = UIHelper.createButton("Update Orders", UIHelper.BKR_RED, java.awt.Color.WHITE, 13);
        ordersButton.addActionListener(event -> runTask("orders"));
        inventoryButton = UIHelper.createButton("Update Inventory", UIHelper.BKR_RED, java.awt.Color.WHITE, 13);
        inventoryButton.addActionListener(event -> runTask("inventory"));
        syncButton = UIHelper.createButton("Sync All Pending", UIHelper.BKR_RED_DARK, java.awt.Color.WHITE, 13);
        syncButton.addActionListener(event -> runTask("all"));

        javax.swing.JPanel buttons = new javax.swing.JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(prepareButton);
        buttons.add(ordersButton);
        buttons.add(inventoryButton);
        buttons.add(syncButton);
        buttons.add(closeButton);
        add(buttons, BorderLayout.SOUTH);
        refreshStatus();
        activity.setText("Prepare offline data while connected to the server. Pending orders and inventory changes stay on this computer until you sync them.");
    }

    private void runTask(String task) {
        boolean prepare = "prepare".equals(task);
        setBusy(true);
        activity.append("\n\n" + (prepare ? "Downloading server data..." : "Sending " + task + " changes...") + "\n");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                if (prepare) {
                    OfflineSyncService.prepareOfflineData();
                    return "Offline data is ready on this computer. Saved at: " + OfflineStore.getStoreLocation();
                }
                List<String> results = "orders".equals(task) ? OfflineSyncService.syncOrders()
                    : "inventory".equals(task) ? OfflineSyncService.syncInventoryChanges()
                    : OfflineSyncService.syncPending();
                return results.isEmpty() ? "There are no pending changes." : String.join("\n", results);
            }

            @Override
            protected void done() {
                try {
                    activity.append(get() + "\n");
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    activity.append("Operation failed: " + cause.getMessage() + "\n");
                } finally {
                    setBusy(false);
                    refreshStatus();
                    ((MainFrame) getOwner()).refreshOfflineStatus();
                    activity.setCaretPosition(activity.getDocument().getLength());
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        prepareButton.setEnabled(!busy);
        ordersButton.setEnabled(!busy);
        inventoryButton.setEnabled(!busy);
        syncButton.setEnabled(!busy);
    }

    private void refreshStatus() {
        String mode = ApiClient.isOfflineMode() ? "Offline mode" : "Server mode";
        String cache = OfflineStore.isReady() ? "offline data ready" : "offline setup required";
        statusLabel.setText(mode + "  |  " + cache + "  |  " + OfflineStore.getPendingCount() + " pending change(s)");
    }
}
