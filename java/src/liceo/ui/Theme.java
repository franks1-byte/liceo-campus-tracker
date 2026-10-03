package liceo.ui;

import java.awt.Color;
import java.awt.Font;

/** School colours and fonts used across the screens. */
final class Theme {
    private Theme() {}

    static final Color MAROON = new Color(0x7A1420);
    static final Color MAROON_DEEP = new Color(0x560C16);
    static final Color GOLD = new Color(0xE8B030);
    static final Color PAPER = new Color(0xF7F1E6);
    static final Color CARD = new Color(0xFFFDF8);
    static final Color INK = new Color(0x2A1A17);
    static final Color MUTED = new Color(0x74625C);
    static final Color LINE = new Color(0xE4D8C4);

    static final Font TITLE = new Font(Font.SERIF, Font.BOLD, 20);
    static final Font HEADING = new Font(Font.SERIF, Font.BOLD, 18);
    static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    static final Font BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    static final Font SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
}
