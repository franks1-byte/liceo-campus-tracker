package liceo.ui;

import liceo.Config;
import liceo.data.ApiException;
import liceo.data.CampusStore;
import liceo.data.SupabaseClient;
import liceo.model.Building;
import liceo.model.Geo;
import liceo.model.Place;
import liceo.model.Room;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** The main window: search list on the left, campus map in the middle, details on the right. */
public class MainFrame extends JFrame implements MapPanel.Listener {
    private static final long serialVersionUID = 1L;

    private final transient SupabaseClient client;
    private final transient CampusStore store;

    private final JLabel status = new JLabel("Loading…");
    private final JLabel user = new JLabel("Guest");
    private final JButton account = new JButton("Log in");
    private final JTextField search = new JTextField();
    private final JComboBox<String> filter = new JComboBox<>(new String[]{"All", "Buildings", "Rooms"});
    private final DefaultListModel<Place> listModel = new DefaultListModel<>();
    private final JList<Place> list = new JList<>(listModel);
    private final MapPanel map = new MapPanel();

    private final JLabel detailTitle = new JLabel("Select a place");
    private final JLabel detailKind = new JLabel(" ");
    private final JTextArea detailText = new JTextArea();
    private final JLabel photo = new JLabel();
    private final JButton addBuilding = new JButton("Add building");
    private final JButton addRoom = new JButton("Add room");
    private final JButton edit = new JButton("Edit");
    private final JButton delete = new JButton("Delete");
    private final JButton addPhoto = new JButton("Add photo…");

    private transient Place selected;
    private boolean fillingList;

    public MainFrame(SupabaseClient client, CampusStore store) {
        super(Config.APP_NAME);
        this.client = client;
        this.store = store;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1180, 720);
        setMinimumSize(new Dimension(900, 560));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        add(header(), BorderLayout.NORTH);
        add(listPanel(), BorderLayout.WEST);
        add(map, BorderLayout.CENTER);
        add(detailPanel(), BorderLayout.EAST);
        map.setListener(this);
        updateButtons();
    }

    /** Loads the data, then asks who is using the app. */
    public void start() {
        refresh(() -> {
            new LoginDialog(this, client).setVisible(true);
            accountChanged();
        });
    }

    // ---------- building the window ----------

    private JPanel header() {
        JLabel title = new JLabel(Config.APP_NAME);
        title.setFont(Theme.TITLE);
        title.setForeground(Theme.PAPER);
        status.setForeground(Theme.GOLD);
        status.setFont(Theme.BOLD);
        user.setForeground(Theme.PAPER);
        user.putClientProperty("html.disable", Boolean.TRUE);

        JButton reload = new JButton("Refresh");
        reload.addActionListener(e -> refresh(null));
        JButton campus = new JButton("Show campus");
        campus.addActionListener(e -> map.showCampus());
        account.addActionListener(e -> {
            if (client.isLoggedIn()) client.logOut(); else new LoginDialog(this, client).setVisible(true);
            accountChanged();
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(status);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(user);
        right.add(campus);
        right.add(reload);
        right.add(account);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.MAROON);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 3, 0, Theme.GOLD), BorderFactory.createEmptyBorder(10, 6, 10, 6)));
        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel listPanel() {
        search.setToolTipText("Search rooms, buildings and codes");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { fillList(); }
            @Override public void removeUpdate(DocumentEvent e) { fillList(); }
            @Override public void changedUpdate(DocumentEvent e) { fillList(); }
        });
        filter.addActionListener(e -> fillList());

        list.setCellRenderer(new PlaceCell());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.CARD);
        list.setFixedCellWidth(280);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !fillingList && list.getSelectedValue() != null) select(list.getSelectedValue(), true);
        });

        JPanel top = new JPanel(new BorderLayout(6, 4));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
        JLabel label = new JLabel("Search");
        label.setFont(Theme.BOLD);
        top.add(label, BorderLayout.NORTH);
        top.add(search, BorderLayout.CENTER);
        top.add(filter, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.PAPER);
        panel.setPreferredSize(new Dimension(310, 0));
        panel.add(top, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel detailPanel() {
        detailTitle.setFont(Theme.HEADING);
        detailTitle.setForeground(Theme.INK);
        detailTitle.putClientProperty("html.disable", Boolean.TRUE);
        detailKind.setFont(Theme.BOLD);
        detailKind.setForeground(Theme.MAROON);
        detailText.setEditable(false);
        detailText.setLineWrap(true);
        detailText.setWrapStyleWord(true);
        detailText.setFont(Theme.BODY);
        detailText.setBackground(Theme.PAPER);
        detailText.setText("Click a pin on the map or a name in the list.\n\nTo add a building: log in, click the spot on the map, then press Add building.");

        addBuilding.addActionListener(e -> addBuilding());
        addRoom.addActionListener(e -> addRoom());
        edit.addActionListener(e -> editSelected());
        delete.addActionListener(e -> deleteSelected());
        addPhoto.addActionListener(e -> choosePhoto());

        JPanel titles = new JPanel(new GridLayout(2, 1));
        titles.setOpaque(false);
        titles.add(detailTitle);
        titles.add(detailKind);

        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.setOpaque(false);
        middle.add(photo, BorderLayout.NORTH);
        JScrollPane textScroll = new JScrollPane(detailText);
        textScroll.setBorder(null);
        middle.add(textScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 2, 6, 6));
        buttons.setOpaque(false);
        buttons.add(addBuilding);
        buttons.add(addRoom);
        buttons.add(edit);
        buttons.add(delete);
        buttons.add(addPhoto);
        buttons.add(Box.createGlue());

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(Theme.PAPER);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.LINE), BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        panel.setPreferredSize(new Dimension(300, 0));
        panel.add(titles, BorderLayout.NORTH);
        panel.add(middle, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    // ---------- showing data ----------

    private void refresh(Runnable afterwards) {
        status.setText("Loading…");
        background(() -> { store.refresh(); return null; }, done -> {
            map.setBuildings(store.getBuildings());
            fillList();
            if (selected != null) select(findAgain(selected), false);
            updateStatus();
            if (afterwards != null) afterwards.run();
        });
    }

    private void updateStatus() {
        int b = store.getBuildings().size(), r = store.getRooms().size();
        status.setText((store.isOnline() ? "Online" : "Offline (saved copy, read only)") + "  ·  " + b + (b == 1 ? " building" : " buildings")
                + "  ·  " + r + (r == 1 ? " room" : " rooms"));
        updateButtons();
    }

    private void accountChanged() {
        user.setText(client.isLoggedIn()
                ? (client.getFullName() != null ? client.getFullName() : client.getEmail()) + " (" + client.getRole() + ")"
                : "Guest");
        account.setText(client.isLoggedIn() ? "Log out" : "Log in");
        updateButtons();
    }

    private void updateButtons() {
        addBuilding.setEnabled(store.canAdd());
        addRoom.setEnabled(store.canAdd() && !store.getBuildings().isEmpty());
        boolean editable = selected != null && store.canEdit(selected);
        edit.setEnabled(editable);
        delete.setEnabled(editable);
        addPhoto.setEnabled(editable);
    }

    private void fillList() {
        CampusStore.Filter chosen = CampusStore.Filter.values()[filter.getSelectedIndex()];
        fillingList = true;
        listModel.clear();
        for (Place place : store.search(search.getText(), chosen)) listModel.addElement(place);
        if (selected != null) list.setSelectedValue(findAgain(selected), false);
        fillingList = false;
    }

    /** After a reload the objects are new; find the same place again by its id. */
    private Place findAgain(Place old) {
        for (Building b : store.getBuildings()) if (b.getId().equals(old.getId())) return b;
        for (Room r : store.getRooms()) if (r.getId().equals(old.getId())) return r;
        return null;
    }

    private void select(Place place, boolean centerMap) {
        selected = place;
        updateButtons();
        photo.setIcon(null);
        if (place == null) {
            detailTitle.setText("Select a place");
            detailKind.setText(" ");
            detailText.setText("");
            map.setSelected(null, false);
            return;
        }
        detailTitle.setText(place.getName());
        detailKind.setText(place.getKind());
        StringBuilder text = new StringBuilder();
        Building building;
        if (place instanceof Building b) {
            building = b;
            line(text, "Code", b.getCode());
            line(text, "Floors", b.getFloors());
            line(text, "GPS", String.format("%.5f, %.5f", b.getLat(), b.getLng()));
            if (b.getDescription() != null) text.append('\n').append(b.getDescription()).append('\n');
            List<Room> rooms = store.roomsIn(b);
            text.append('\n').append(rooms.isEmpty() ? "No rooms listed yet." : "Rooms (" + rooms.size() + "):").append('\n');
            for (Room r : rooms) text.append("  • ").append(r.getName()).append(r.floorLabel() == null ? "" : " — " + r.floorLabel()).append('\n');
        } else {
            Room r = (Room) place;
            building = store.buildingOf(r);
            line(text, "Building", r.getBuildingName());
            line(text, "Floor", r.floorLabel());
            line(text, "Capacity", r.getCapacity());
            if (r.getNotes() != null) text.append('\n').append(r.getNotes()).append('\n');
        }
        if (building != null && map.hasPickedPoint()) {
            double metres = Geo.distance(map.getPickedLat(), map.getPickedLng(), building.getLat(), building.getLng());
            String direction = Geo.compass(Geo.bearing(map.getPickedLat(), map.getPickedLng(), building.getLat(), building.getLng()));
            text.append("\nFrom the point you clicked: ").append(Geo.formatDistance(metres)).append(" to the ").append(direction).append('\n');
        }
        detailText.setText(text.toString());
        detailText.setCaretPosition(0);
        map.setSelected(building, centerMap);
        loadPhoto(place);
    }

    private static void line(StringBuilder text, String label, Object value) {
        if (value != null && !value.toString().isBlank()) text.append(label).append(": ").append(value).append('\n');
    }

    private void loadPhoto(Place place) {
        if (place.getPhotoPath() == null) return;
        String url = client.photoUrl(place.getPhotoPath());
        new SwingWorker<ImageIcon, Void>() {
            @Override
            protected ImageIcon doInBackground() throws Exception {
                BufferedImage image = ImageIO.read(URI.create(url).toURL());
                if (image == null) return null;
                int width = 274, height = Math.min(200, image.getHeight() * width / image.getWidth());
                return new ImageIcon(image.getScaledInstance(width, height, Image.SCALE_SMOOTH));
            }

            @Override
            protected void done() {
                try { if (selected == place) photo.setIcon(get()); } catch (Exception offline) { /* no photo while offline */ }
            }
        }.execute();
    }

    // ---------- map clicks ----------

    @Override
    public void buildingClicked(Building building) {
        list.setSelectedValue(building, true);
        select(building, false);
    }

    @Override
    public void pointPicked(double lat, double lng) {
        if (selected != null) select(selected, false); // refresh the "distance from the point you clicked" line
    }

    // ---------- adding, editing, deleting ----------

    private void addBuilding() {
        if (!map.hasPickedPoint()) {
            JOptionPane.showMessageDialog(this, "First click the spot on the map where the building is, then press Add building.");
            return;
        }
        Building building = PlaceForms.building(this, null, map.getPickedLat(), map.getPickedLng());
        if (building != null) background(() -> store.add(building), this::afterChange);
    }

    private void addRoom() {
        Building preselected = selected instanceof Building b ? b : selected instanceof Room r ? store.buildingOf(r) : null;
        Room room = PlaceForms.room(this, null, store.getBuildings(), preselected);
        if (room != null) background(() -> store.add(room), this::afterChange);
    }

    private void editSelected() {
        Place place = selected;
        Place edited = place instanceof Building b ? PlaceForms.building(this, b, b.getLat(), b.getLng())
                : PlaceForms.room(this, (Room) place, store.getBuildings(), null);
        if (edited != null) background(() -> { store.save(edited); return edited; }, this::afterChange);
    }

    private void deleteSelected() {
        Place place = selected;
        String extra = place instanceof Building b && b.getRoomCount() > 0 ? " and its " + b.getRoomCount() + " room(s)" : "";
        int answer = JOptionPane.showConfirmDialog(this, "Delete \"" + place.getName() + "\"" + extra + "? This cannot be undone.",
                "Delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        background(() -> { store.remove(place); return null; }, this::afterChange);
    }

    private void choosePhoto() {
        Place place = selected;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Photos (JPG, PNG)", "jpg", "jpeg", "png"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file.length() > 5 * 1024 * 1024) {
            JOptionPane.showMessageDialog(this, "That photo is larger than 5 MB. Choose a smaller one.");
            return;
        }
        String type = file.getName().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        background(() -> {
            place.setPhotoPath(client.uploadPhoto(place.getId(), Files.readAllBytes(file.toPath()), type));
            store.save(place);
            return place;
        }, this::afterChange);
    }

    private void afterChange(Place changed) {
        map.setBuildings(store.getBuildings());
        selected = changed;
        fillList();
        select(changed, false);
        updateStatus();
    }

    /** Runs slow work (network) off the screen thread, then hands the result back to the screen. */
    private <T> void background(Callable<T> work, Consumer<T> whenDone) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception { return work.call(); }

            @Override
            protected void done() {
                try {
                    whenDone.accept(get());
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String message = cause instanceof IOException ? "No internet connection. Changes need you to be online."
                            : cause instanceof ApiException ? cause.getMessage() : "Something went wrong: " + cause;
                    JOptionPane.showMessageDialog(MainFrame.this, message, Config.APP_NAME, JOptionPane.WARNING_MESSAGE);
                    refresh(null); // put the screen back in step with the server
                }
            }
        }.execute();
    }
}
