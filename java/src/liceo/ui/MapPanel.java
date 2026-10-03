package liceo.ui;

import liceo.Config;
import liceo.model.Building;
import liceo.model.Geo;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The campus map: OpenStreetMap tiles with a pin for every building. Drag to move, scroll to zoom. */
public class MapPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    /** Tells the main window what the user did on the map. */
    public interface Listener {
        void buildingClicked(Building building);
        void pointPicked(double lat, double lng);
    }

    private static final int TILE = 256, MIN_ZOOM = 15, MAX_ZOOM = 19, PIN_RADIUS = 14;

    private double centerLat = Config.CAMPUS_LAT, centerLng = Config.CAMPUS_LNG;
    private int zoom = Config.CAMPUS_ZOOM;
    private transient List<Building> buildings = List.of();
    private transient Building selected;
    private Double pickedLat, pickedLng;
    private transient Listener listener;

    // tiles are kept in memory and on disk, so the map still draws without a connection
    private final transient Map<String, BufferedImage> tiles = new ConcurrentHashMap<>();
    private final transient Set<String> loading = ConcurrentHashMap.newKeySet();
    private final transient ExecutorService loader = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "map-tiles");
        t.setDaemon(true);
        return t;
    });
    private final transient Path tileDir = Path.of(System.getProperty("user.home"), ".liceo-campus-tracker", "tiles");

    public MapPanel() {
        setBackground(new Color(0xE9E2D3));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            private Point last;
            private boolean dragged;

            @Override
            public void mousePressed(MouseEvent e) { last = e.getPoint(); dragged = false; }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (last == null) return;
                dragged = true;
                double x = Geo.lngToX(centerLng, zoom) - (e.getX() - last.x);
                double y = Geo.latToY(centerLat, zoom) - (e.getY() - last.y);
                centerLng = Geo.xToLng(x, zoom);
                centerLat = Geo.yToLat(y, zoom);
                last = e.getPoint();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!dragged) clicked(e.getPoint());
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom - e.getWheelRotation()));
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setBuildings(List<Building> buildings) {
        this.buildings = List.copyOf(buildings);
        repaint();
    }

    public void setSelected(Building building, boolean centerOnIt) {
        selected = building;
        if (building != null && centerOnIt) { centerLat = building.getLat(); centerLng = building.getLng(); }
        repaint();
    }

    public void showCampus() {
        centerLat = Config.CAMPUS_LAT;
        centerLng = Config.CAMPUS_LNG;
        zoom = Config.CAMPUS_ZOOM;
        repaint();
    }

    public boolean hasPickedPoint() { return pickedLat != null; }
    public double getPickedLat() { return pickedLat != null ? pickedLat : centerLat; }
    public double getPickedLng() { return pickedLng != null ? pickedLng : centerLng; }

    private double left() { return Geo.lngToX(centerLng, zoom) - getWidth() / 2.0; }
    private double top() { return Geo.latToY(centerLat, zoom) - getHeight() / 2.0; }

    private void clicked(Point p) {
        double left = left(), top = top();
        for (Building b : buildings) {
            double x = Geo.lngToX(b.getLng(), zoom) - left, y = Geo.latToY(b.getLat(), zoom) - top;
            if (p.distance(x, y) <= PIN_RADIUS + 2) {
                if (listener != null) listener.buildingClicked(b);
                return;
            }
        }
        pickedLat = Geo.yToLat(top + p.y, zoom);
        pickedLng = Geo.xToLng(left + p.x, zoom);
        repaint();
        if (listener != null) listener.pointPicked(pickedLat, pickedLng);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double left = left(), top = top();

        int max = 1 << zoom;
        for (int tx = (int) Math.floor(left / TILE); tx * TILE < left + getWidth(); tx++) {
            for (int ty = (int) Math.floor(top / TILE); ty * TILE < top + getHeight(); ty++) {
                if (tx < 0 || ty < 0 || tx >= max || ty >= max) continue;
                BufferedImage image = tile(zoom, tx, ty);
                int x = (int) Math.round(tx * TILE - left), y = (int) Math.round(ty * TILE - top);
                if (image != null) g.drawImage(image, x, y, TILE, TILE, null);
                else { g.setColor(new Color(0xDDD4C2)); g.drawRect(x, y, TILE, TILE); }
            }
        }

        if (pickedLat != null) {
            int x = (int) Math.round(Geo.lngToX(pickedLng, zoom) - left), y = (int) Math.round(Geo.latToY(pickedLat, zoom) - top);
            g.setColor(Theme.MAROON);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(x - 9, y - 9, 18, 18);
            g.drawLine(x - 14, y, x + 14, y);
            g.drawLine(x, y - 14, x, y + 14);
        }

        g.setFont(Theme.BOLD.deriveFont(10f));
        FontMetrics fm = g.getFontMetrics();
        for (Building b : buildings) {
            if (b != selected) drawPin(g, fm, b, left, top, false);
        }
        if (selected != null) drawPin(g, fm, selected, left, top, true);

        String credit = "© OpenStreetMap contributors";
        g.setFont(Theme.SMALL);
        int w = g.getFontMetrics().stringWidth(credit);
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRect(getWidth() - w - 12, getHeight() - 20, w + 12, 20);
        g.setColor(Theme.INK);
        g.drawString(credit, getWidth() - w - 6, getHeight() - 6);
        g.dispose();
    }

    private void drawPin(Graphics2D g, FontMetrics fm, Building b, double left, double top, boolean isSelected) {
        int x = (int) Math.round(Geo.lngToX(b.getLng(), zoom) - left), y = (int) Math.round(Geo.latToY(b.getLat(), zoom) - top);
        int r = isSelected ? PIN_RADIUS + 3 : PIN_RADIUS;
        g.setColor(new Color(0, 0, 0, 60));
        g.fillOval(x - r + 1, y - r + 3, r * 2, r * 2);
        g.setColor(isSelected ? Theme.GOLD : Theme.MAROON);
        g.fillOval(x - r, y - r, r * 2, r * 2);
        g.setColor(isSelected ? Theme.MAROON : Theme.GOLD);
        g.setStroke(new BasicStroke(2.2f));
        g.drawOval(x - r, y - r, r * 2, r * 2);
        String label = b.badge();
        if (label.length() > 3) label = label.substring(0, 3);
        g.setColor(isSelected ? Theme.MAROON_DEEP : Color.WHITE);
        g.drawString(label, x - fm.stringWidth(label) / 2, y + fm.getAscent() / 2 - 1);
    }

    /** Returns the tile if it is ready; otherwise starts loading it and returns null. */
    private BufferedImage tile(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        BufferedImage image = tiles.get(key);
        if (image == null && loading.add(key)) {
            loader.submit(() -> {
                try {
                    BufferedImage loaded = loadTile(key);
                    if (loaded != null) { tiles.put(key, loaded); repaint(); }
                } finally {
                    loading.remove(key);
                }
            });
        }
        return image;
    }

    private BufferedImage loadTile(String key) {
        Path file = tileDir.resolve(key + ".png");
        try {
            if (Files.exists(file)) return ImageIO.read(file.toFile());
            HttpURLConnection connection = (HttpURLConnection) URI.create("https://tile.openstreetmap.org/" + key + ".png").toURL().openConnection();
            connection.setRequestProperty("User-Agent", "LiceoCampusTracker/1.0 (student project)");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != 200) return null;
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) { bytes = in.readAllBytes(); }
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
            return ImageIO.read(file.toFile());
        } catch (IOException offline) {
            return null; // no connection and not saved yet: leave the square empty
        }
    }
}
