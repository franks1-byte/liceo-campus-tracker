package liceo.ui;

import liceo.data.ApiException;
import liceo.data.SupabaseClient;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;

/** The welcome window: log in or register as a student or an admin, or carry on as a guest. */
public class LoginDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final transient SupabaseClient client;

    public LoginDialog(Frame owner, SupabaseClient client) {
        super(owner, "Welcome", true);
        this.client = client;

        JLabel title = new JLabel("Who is using the app?");
        title.setFont(Theme.HEADING);
        title.setBorder(BorderFactory.createEmptyBorder(14, 16, 6, 16));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Student", new AuthPanel(false));
        tabs.addTab("Admin", new AuthPanel(true));

        JButton guest = new JButton("Continue as guest");
        guest.addActionListener(e -> dispose());
        JLabel guestNote = new JLabel("Guests can look around and search, but not add or edit.");
        guestNote.setFont(Theme.SMALL);
        guestNote.setForeground(Theme.MUTED);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 10));
        bottom.add(guest);
        bottom.add(guestNote);

        setLayout(new BorderLayout());
        add(title, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** The form inside each tab. The admin one also asks for the admin code. */
    private final class AuthPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final boolean admin;
        private final JRadioButton logIn = new JRadioButton("Log in", true);
        private final JRadioButton register = new JRadioButton("Register");
        private final JLabel nameLabel = new JLabel("Full name");
        private final JTextField name = new JTextField(22);
        private final JTextField email = new JTextField(22);
        private final JPasswordField password = new JPasswordField(22);
        private final JLabel codeLabel = new JLabel("Admin code (first time only)");
        private final JTextField code = new JTextField(22);
        private final JLabel message = new JLabel(" ");
        private final JButton submit = new JButton();

        AuthPanel(boolean admin) {
            super(new GridBagLayout());
            this.admin = admin;
            setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
            ButtonGroup group = new ButtonGroup();
            group.add(logIn);
            group.add(register);
            logIn.addActionListener(e -> modeChanged());
            register.addActionListener(e -> modeChanged());
            JPanel mode = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            mode.add(logIn);
            mode.add(register);

            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(3, 0, 3, 0);
            add(mode, c);
            add(nameLabel, c);
            add(name, c);
            add(new JLabel("Email"), c);
            add(email, c);
            add(new JLabel("Password (at least 6 characters)"), c);
            add(password, c);
            if (admin) {
                add(codeLabel, c);
                add(code, c);
            }
            message.setForeground(new Color(0xB3261E));
            add(message, c);
            add(submit, c);
            submit.addActionListener(e -> submit());
            password.addActionListener(e -> submit());
            modeChanged();
        }

        private void modeChanged() {
            boolean registering = register.isSelected();
            nameLabel.setVisible(registering);
            name.setVisible(registering);
            codeLabel.setText(registering ? "Admin code" : "Admin code (first time only)");
            submit.setText((registering ? "Register" : "Log in") + " as " + (admin ? "admin" : "student"));
            message.setText(" ");
            LoginDialog.this.pack();
        }

        private void submit() {
            boolean registering = register.isSelected();
            String fullName = name.getText().trim(), mail = email.getText().trim(), pass = new String(password.getPassword());
            String adminCode = code.getText().trim().toUpperCase();
            if (mail.isEmpty() || pass.length() < 6) { message.setText("Enter your email and a password of at least 6 characters."); return; }
            if (registering && fullName.isEmpty()) { message.setText("Enter your full name."); return; }
            if (registering && admin && adminCode.isEmpty()) { message.setText("Enter the admin code."); return; }
            submit.setEnabled(false);
            message.setText("Please wait…");

            // talk to the server in the background so the window does not freeze
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    try {
                        if (registering) {
                            if (!client.register(fullName, mail, pass)) {
                                return "CONFIRM";
                            }
                        } else {
                            client.logIn(mail, pass);
                        }
                        if (admin && !client.isAdmin()) {
                            if (adminCode.isEmpty()) return "This account is not an admin yet. Enter the admin code.";
                            if (!client.claimAdmin(adminCode)) return "That admin code is not correct. You are logged in as a student.";
                        }
                        return null;
                    } catch (IOException e) {
                        return "No internet connection. Try again when you are online.";
                    } catch (ApiException e) {
                        return e.getMessage();
                    }
                }

                @Override
                protected void done() {
                    submit.setEnabled(true);
                    String problem;
                    try { problem = get(); } catch (Exception e) { problem = "Something went wrong. Try again."; }
                    if (problem == null) { dispose(); return; }
                    if ("CONFIRM".equals(problem)) {
                        message.setForeground(Theme.MAROON);
                        problem = "Check your email to confirm your account, then log in here.";
                        logIn.setSelected(true);
                        modeChanged();
                    } else {
                        message.setForeground(new Color(0xB3261E));
                        if (client.isLoggedIn()) { logIn.setSelected(true); modeChanged(); } // the account exists now
                    }
                    message.setText(problem);
                    LoginDialog.this.pack();
                }
            }.execute();
        }
    }
}
