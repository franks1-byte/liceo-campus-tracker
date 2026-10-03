package liceo.model;

import java.util.Map;

/** A building pinned on the campus map. */
public final class Building extends Place {
    public static final String[] KINDS = {"Academic", "Office", "Library", "Laboratory", "Gym / Sports", "Canteen", "Chapel", "Clinic", "Gate", "Parking", "Other"};

    private String code;
    private String description;
    private Integer floors;
    private double lat;
    private double lng;
    private int roomCount;

    public Building(String id, String name, String kind, String code, String description, Integer floors,
                    double lat, double lng, String photoPath, String createdBy) {
        super(id, name, kind, photoPath, createdBy);
        this.code = code;
        this.description = description;
        this.floors = floors;
        this.lat = lat;
        this.lng = lng;
    }

    public static Building fromJson(Map<String, Object> row) {
        return new Building(str(row, "id"), str(row, "name"), str(row, "kind"), str(row, "code"), str(row, "description"),
                integer(row, "floors"), number(row, "lat"), number(row, "lng"), str(row, "photo_path"), str(row, "created_by"));
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getFloors() { return floors; }
    public void setFloors(Integer floors) { this.floors = floors; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public void setLocation(double lat, double lng) { this.lat = lat; this.lng = lng; }
    public int getRoomCount() { return roomCount; }
    public void setRoomCount(int roomCount) { this.roomCount = roomCount; }

    @Override
    public String table() { return "buildings"; }

    @Override
    public String badge() {
        if (code != null && !code.isBlank()) return code.length() > 4 ? code.substring(0, 4) : code;
        StringBuilder initials = new StringBuilder();
        for (String word : getName().split("\\s+")) {
            if (!word.isEmpty() && initials.length() < 3) initials.append(Character.toUpperCase(word.charAt(0)));
        }
        return initials.toString();
    }

    @Override
    public String summary() {
        return getKind() + " · " + roomCount + (roomCount == 1 ? " room" : " rooms");
    }

    @Override
    public String searchText() {
        return (super.searchText() + " " + (code == null ? "" : code) + " " + (description == null ? "" : description)).toLowerCase();
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> json = super.toJson();
        json.put("code", code);
        json.put("description", description);
        json.put("floors", floors);
        json.put("lat", lat);
        json.put("lng", lng);
        return json;
    }
}
