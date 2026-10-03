package liceo.model;

import java.util.Map;

/** A classroom, lab, office or other room inside a building. */
public final class Room extends Place {
    public static final String[] KINDS = {"Classroom", "Laboratory", "Office", "Library", "Auditorium", "Comfort room", "Other"};

    private String buildingId;
    private String buildingName = "";
    private Integer floor;
    private Integer capacity;
    private String notes;

    public Room(String id, String name, String kind, String buildingId, Integer floor, Integer capacity, String notes,
                String photoPath, String createdBy) {
        super(id, name, kind, photoPath, createdBy);
        this.buildingId = buildingId;
        this.floor = floor;
        this.capacity = capacity;
        this.notes = notes;
    }

    public static Room fromJson(Map<String, Object> row) {
        return new Room(str(row, "id"), str(row, "name"), str(row, "kind"), str(row, "building_id"), integer(row, "floor"),
                integer(row, "capacity"), str(row, "notes"), str(row, "photo_path"), str(row, "created_by"));
    }

    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String buildingId) { this.buildingId = buildingId; }
    public String getBuildingName() { return buildingName; }
    public void setBuildingName(String buildingName) { this.buildingName = buildingName == null ? "" : buildingName; }
    public Integer getFloor() { return floor; }
    public void setFloor(Integer floor) { this.floor = floor; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String floorLabel() {
        if (floor == null) return null;
        return floor == 0 ? "Ground floor" : "Floor " + floor;
    }

    @Override
    public String table() { return "rooms"; }

    @Override
    public String badge() { return "RM"; }

    @Override
    public String summary() {
        StringBuilder text = new StringBuilder(getKind());
        if (!buildingName.isEmpty()) text.append(" · ").append(buildingName);
        if (floor != null) text.append(" · ").append(floorLabel());
        return text.toString();
    }

    @Override
    public String searchText() {
        return (super.searchText() + " " + buildingName + " " + (notes == null ? "" : notes)).toLowerCase();
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> json = super.toJson();
        json.put("building_id", buildingId);
        json.put("floor", floor);
        json.put("capacity", capacity);
        json.put("notes", notes);
        return json;
    }
}
