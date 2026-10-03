package liceo.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Anything on campus that can be listed and searched: a building or a room. */
public abstract class Place {
    private final String id;
    private String name;
    private String kind;
    private String photoPath;
    private final String createdBy;

    protected Place(String id, String name, String kind, String photoPath, String createdBy) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.photoPath = photoPath;
        this.createdBy = createdBy;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }
    public String getCreatedBy() { return createdBy; }

    /** Database table this place is stored in. */
    public abstract String table();

    /** Short text for the icon in the list, e.g. "ENG" or "RM". */
    public abstract String badge();

    /** One line shown under the name in the list. */
    public abstract String summary();

    /** Text that the search box looks through. */
    public String searchText() {
        return (name + " " + kind).toLowerCase();
    }

    /** The fields a user can edit, as they are sent to the database. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", name);
        json.put("kind", kind);
        if (photoPath != null) json.put("photo_path", photoPath);
        return json;
    }

    @Override
    public String toString() { return name; }

    // helpers for reading database rows
    protected static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    protected static Integer integer(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? Integer.valueOf(n.intValue()) : null;
    }

    protected static double number(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
