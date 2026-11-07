/*
 * Decompiled with CFR 0.152.
 */
import java.awt.Color;
import java.awt.Font;
import java.util.Enumeration;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import ui.MainFrame;
import util.SeedBKRMenu;
import dao.MenuDAO;
import api.ApiClient;
import api.OfflineStore;
import api.OfflineSyncService;

public class Main {
    public static void main(String[] stringArray) {
        // Auto-seed menu if empty
        if (!ApiClient.isOfflineMode()) new Thread(() -> {
            try {
                MenuDAO dao = new MenuDAO();
                if (dao.getAllActiveMenuItems().isEmpty()) {
                    System.out.println("Menu is empty. Auto-seeding BKR flyer menu items...");
                    SeedBKRMenu.main(new String[0]);
                }
                if (!OfflineStore.isReady()) OfflineSyncService.prepareOfflineData();
            } catch (Exception e) {
                System.err.println("Offline cache refresh skipped: " + e.getMessage());
            }
        }).start();

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                // Global polish: Segoe UI everywhere + softer widget defaults
                FontUIResource uiFont = new FontUIResource(new Font("Segoe UI", Font.PLAIN, 14));
                Enumeration<Object> keys = UIManager.getDefaults().keys();
                while (keys.hasMoreElements()) {
                    Object key = keys.nextElement();
                    if (UIManager.get(key) instanceof FontUIResource) {
                        UIManager.put(key, uiFont);
                    }
                }
                UIManager.put("OptionPane.messageFont", new Font("Segoe UI", Font.PLAIN, 13));
                UIManager.put("OptionPane.buttonFont", new Font("Segoe UI", Font.BOLD, 12));
                UIManager.put("TabbedPane.selected", new Color(255, 252, 247));
                UIManager.put("TabbedPane.contentAreaColor", new Color(255, 252, 247));
            }
            catch (Exception exception) {
                // empty catch block
            }
            new MainFrame().setVisible(true);
        });
    }
}
