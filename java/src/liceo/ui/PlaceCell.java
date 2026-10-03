package liceo.ui;

import liceo.model.Building;
import liceo.model.Place;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;

/** How one building or room looks in the list: a badge, the name, and a line of detail. */
final class PlaceCell extends JPanel implements ListCellRenderer<Place> {
    private static final long serialVersionUID = 1L;
    private final JLabel badge = new JLabel("", SwingConstants.CENTER);
    private final JLabel name = new JLabel();
    private final JLabel summary = new JLabel();

    PlaceCell() {
        super(new BorderLayout(10, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        badge.setPreferredSize(new Dimension(42, 38));
        badge.setOpaque(true);
        badge.setFont(Theme.BOLD.deriveFont(11f));
        name.putClientProperty("html.disable", Boolean.TRUE);
        summary.putClientProperty("html.disable", Boolean.TRUE);
        badge.putClientProperty("html.disable", Boolean.TRUE);
        name.setFont(Theme.BOLD);
        summary.setFont(Theme.SMALL);
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(name);
        text.add(summary);
        add(badge, BorderLayout.WEST);
        add(text, BorderLayout.CENTER);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends Place> list, Place place, int index, boolean isSelected, boolean hasFocus) {
        boolean building = place instanceof Building;
        badge.setText(place.badge());
        badge.setBackground(building ? Theme.MAROON : Theme.CARD);
        badge.setForeground(building ? Theme.GOLD : Theme.MAROON);
        badge.setBorder(BorderFactory.createLineBorder(Theme.MAROON, 2));
        name.setText(place.getName());
        summary.setText(place.summary());
        name.setForeground(Theme.INK);
        summary.setForeground(Theme.MUTED);
        setBackground(isSelected ? new java.awt.Color(0xF3E3BC) : Theme.CARD);
        return this;
    }
}
