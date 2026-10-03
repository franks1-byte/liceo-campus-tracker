package liceo;

import liceo.data.CampusStore;
import liceo.data.SupabaseClient;
import liceo.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Starts the desktop app. */
public final class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // the default look is fine
            }
            // -Dliceo.url=... points the app at another server (used for testing)
            SupabaseClient client = new SupabaseClient(System.getProperty("liceo.url", Config.SUPABASE_URL), Config.SUPABASE_KEY);
            MainFrame frame = new MainFrame(client, new CampusStore(client));
            frame.setVisible(true);
            frame.start();
        });
    }
}
