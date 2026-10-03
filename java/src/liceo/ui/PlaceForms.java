package liceo.ui;

import liceo.model.Building;
import liceo.model.Room;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

/** The pop-up forms for adding or editing a building or a room. */
final class PlaceForms {
    private PlaceForms() {}

    /** @return the filled-in building, or null if the user cancelled. Pass existing = null to add a new one. */
    static Building building(Component parent, Building existing, double lat, double lng) {
        JTextField name = new JTextField(existing == null ? "" : existing.getName(), 24);
        JTextField code = new JTextField(existing == null ? "" : text(existing.getCode()), 24);
        JComboBox<String> kind = new JComboBox<>(Building.KINDS);
        if (existing != null) kind.setSelectedItem(existing.getKind());
        JTextField floors = new JTextField(existing == null || existing.getFloors() == null ? "" : existing.getFloors().toString(), 24);
        JTextArea description = new JTextArea(existing == null ? "" : text(existing.getDescription()), 3, 24);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        JTextField latField = new JTextField(String.valueOf(lat), 24);
        JTextField lngField = new JTextField(String.valueOf(lng), 24);

        JPanel form = form("Building name", name, "Short code (e.g. ENG)", code, "Type", kind, "Number of floors", floors,
                "Description", new JScrollPane(description), "Latitude", latField, "Longitude", lngField);
        while (true) {
            int answer = JOptionPane.showConfirmDialog(parent, form, existing == null ? "Add a building" : "Edit building",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return null;
            try {
                if (name.getText().isBlank()) throw new IllegalArgumentException("Give the building a name.");
                Integer floorCount = number(floors.getText(), "Number of floors", 1, 60);
                double newLat = Double.parseDouble(latField.getText().trim()), newLng = Double.parseDouble(lngField.getText().trim());
                if (Math.abs(newLat) > 90 || Math.abs(newLng) > 180) throw new IllegalArgumentException("Those coordinates are not on Earth.");
                Building result = existing != null ? existing
                        : new Building(null, "", "", null, null, null, newLat, newLng, null, null);
                result.setName(name.getText().trim());
                result.setCode(blankToNull(code.getText()));
                result.setKind((String) kind.getSelectedItem());
                result.setFloors(floorCount);
                result.setDescription(blankToNull(description.getText()));
                result.setLocation(newLat, newLng);
                return result;
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(parent, "Latitude and longitude must be numbers, like 8.48597 and 124.6394.");
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(parent, e.getMessage());
            }
        }
    }

    /** @return the filled-in room, or null if the user cancelled. */
    static Room room(Component parent, Room existing, List<Building> buildings, Building preselected) {
        JTextField name = new JTextField(existing == null ? "" : existing.getName(), 24);
        JComboBox<Building> building = new JComboBox<>(buildings.toArray(new Building[0]));
        String currentId = existing != null ? existing.getBuildingId() : preselected != null ? preselected.getId() : null;
        for (Building b : buildings) if (b.getId().equals(currentId)) building.setSelectedItem(b);
        JComboBox<String> kind = new JComboBox<>(Room.KINDS);
        if (existing != null) kind.setSelectedItem(existing.getKind());
        JTextField floor = new JTextField(existing == null || existing.getFloor() == null ? "" : existing.getFloor().toString(), 24);
        JTextField capacity = new JTextField(existing == null || existing.getCapacity() == null ? "" : existing.getCapacity().toString(), 24);
        JTextArea notes = new JTextArea(existing == null ? "" : text(existing.getNotes()), 3, 24);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);

        JPanel form = form("Room name or number", name, "Building", building, "Type", kind, "Floor (0 = ground)", floor,
                "Capacity", capacity, "Notes", new JScrollPane(notes));
        while (true) {
            int answer = JOptionPane.showConfirmDialog(parent, form, existing == null ? "Add a room" : "Edit room",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return null;
            try {
                if (name.getText().isBlank()) throw new IllegalArgumentException("Give the room a name or number.");
                Building chosen = (Building) building.getSelectedItem();
                if (chosen == null) throw new IllegalArgumentException("Choose the building this room is in.");
                Integer floorNumber = number(floor.getText(), "Floor", -5, 60);
                Integer seats = number(capacity.getText(), "Capacity", 0, 100000);
                Room result = existing != null ? existing : new Room(null, "", "", chosen.getId(), null, null, null, null, null);
                result.setName(name.getText().trim());
                result.setBuildingId(chosen.getId());
                result.setKind((String) kind.getSelectedItem());
                result.setFloor(floorNumber);
                result.setCapacity(seats);
                result.setNotes(blankToNull(notes.getText()));
                return result;
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(parent, e.getMessage());
            }
        }
    }

    /** Lays out label / field pairs one under another. */
    private static JPanel form(Object... labelsAndFields) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < labelsAndFields.length; i += 2) {
            c.insets = new Insets(6, 0, 2, 0);
            panel.add(new JLabel((String) labelsAndFields[i]), c);
            c.insets = new Insets(0, 0, 0, 0);
            panel.add((Component) labelsAndFields[i + 1], c);
        }
        return panel;
    }

    private static Integer number(String text, String label, int min, int max) {
        if (text.isBlank()) return null;
        try {
            int value = Integer.parseInt(text.trim());
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a whole number from " + min + " to " + max + ".");
        }
    }

    private static String blankToNull(String text) { return text == null || text.isBlank() ? null : text.trim(); }

    private static String text(String value) { return value == null ? "" : value; }
}
