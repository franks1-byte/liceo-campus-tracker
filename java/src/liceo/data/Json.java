package liceo.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the app needs no outside libraries.
 * Objects become Map, arrays become List, numbers become Long or Double.
 */
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) { this.text = text; }

    public static Object parse(String text) {
        Json reader = new Json(text);
        reader.skipSpace();
        Object value = reader.readValue();
        reader.skipSpace();
        if (reader.pos != text.length()) throw reader.error("Unexpected text after the JSON value");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("Expected a JSON object");
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseRows(String text) {
        Object value = parse(text);
        if (value instanceof List<?> list) return (List<Map<String, Object>>) list;
        throw new IllegalArgumentException("Expected a JSON array");
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            writeString(value.toString(), out);
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private Object readValue() {
        if (pos >= text.length()) throw error("Unexpected end of JSON");
        char c = text.charAt(pos);
        if (c == '{') return readObject();
        if (c == '[') return readArray();
        if (c == '"') return readString();
        if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        if (text.startsWith("null", pos)) { pos += 4; return null; }
        return readNumber();
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipSpace();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipSpace();
            if (peek() != '"') throw error("Expected a key");
            String key = readString();
            skipSpace();
            expect(':');
            skipSpace();
            map.put(key, readValue());
            skipSpace();
            if (peek() == ',') { pos++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipSpace();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            skipSpace();
            list.add(readValue());
            skipSpace();
            if (peek() == ',') { pos++; continue; }
            expect(']');
            return list;
        }
    }

    private String readString() {
        StringBuilder out = new StringBuilder();
        pos++; // opening quote
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (pos >= text.length()) break;
            char esc = text.charAt(pos++);
            switch (esc) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (pos + 4 > text.length()) throw error("Bad unicode escape");
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> out.append(esc); // \" \\ \/
            }
        }
        throw error("Unfinished string");
    }

    private Number readNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        String number = text.substring(start, pos);
        if (number.isEmpty()) throw error("Unexpected character '" + text.charAt(pos) + "'");
        try {
            if (number.contains(".") || number.contains("e") || number.contains("E")) return Double.valueOf(number);
            return Long.valueOf(number);
        } catch (NumberFormatException e) {
            throw error("Bad number " + number);
        }
    }

    private char peek() { return pos < text.length() ? text.charAt(pos) : '\0'; }

    private void expect(char c) {
        if (peek() != c) throw error("Expected '" + c + "'");
        pos++;
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + pos);
    }
}
