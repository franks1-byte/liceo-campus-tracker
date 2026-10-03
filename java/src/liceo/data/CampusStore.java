package liceo.data;

import liceo.model.Building;
import liceo.model.Place;
import liceo.model.Room;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the buildings and rooms the app is showing.
 * Online it loads them from Supabase and saves a copy on this computer;
 * offline it shows that saved copy (read only).
 */
public class CampusStore {
    public enum Filter { ALL, BUILDINGS, ROOMS }

    private final SupabaseClient client;
    private final Path cacheFile;
    private List<Building> buildings = new ArrayList<>();
    private List<Room> rooms = new ArrayList<>();
    private boolean online;

    public CampusStore(SupabaseClient client) {
        this(client, Path.of(System.getProperty("user.home"), ".liceo-campus-tracker", "cache.json"));
    }

    public CampusStore(SupabaseClient client, Path cacheFile) {
        this.client = client;
        this.cacheFile = cacheFile;
    }

    public boolean isOnline() { return online; }
    public List<Building> getBuildings() { return buildings; }
    public List<Room> getRooms() { return rooms; }

    /** Loads the latest data; falls back to the saved copy when there is no connection. */
    public void refresh() throws ApiException {
        try {
            List<Map<String, Object>> buildingRows = client.list("buildings");
            List<Map<String, Object>> roomRows = client.list("rooms");
            use(buildingRows, roomRows);
            online = true;
            saveCache(buildingRows, roomRows);
        } catch (IOException noConnection) {
            online = false;
            loadCache();
        }
    }

    private void use(List<Map<String, Object>> buildingRows, List<Map<String, Object>> roomRows) {
        List<Building> newBuildings = new ArrayList<>();
        for (Map<String, Object> row : buildingRows) newBuildings.add(Building.fromJson(row));
        List<Room> newRooms = new ArrayList<>();
        for (Map<String, Object> row : roomRows) newRooms.add(Room.fromJson(row));
        buildings = newBuildings;
        rooms = newRooms;
        link();
    }

    /** Fills in each room's building name and each building's room count. */
    private void link() {
        Map<String, Building> byId = new LinkedHashMap<>();
        for (Building b : buildings) { byId.put(b.getId(), b); b.setRoomCount(0); }
        for (Room r : rooms) {
            Building b = byId.get(r.getBuildingId());
            if (b != null) { r.setBuildingName(b.getName()); b.setRoomCount(b.getRoomCount() + 1); }
        }
    }

    public Building buildingOf(Room room) {
        for (Building b : buildings) if (b.getId().equals(room.getBuildingId())) return b;
        return null;
    }

    public List<Room> roomsIn(Building building) {
        List<Room> result = new ArrayList<>();
        for (Room r : rooms) if (building.getId().equals(r.getBuildingId())) result.add(r);
        return result;
    }

    /** Buildings and rooms matching the search words, sorted by name. */
    public List<Place> search(String query, Filter filter) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Place> result = new ArrayList<>();
        if (filter != Filter.ROOMS) for (Building b : buildings) if (b.searchText().contains(q)) result.add(b);
        if (filter != Filter.BUILDINGS) for (Room r : rooms) if (r.searchText().contains(q)) result.add(r);
        result.sort(Comparator.comparing(Place::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** May the logged-in user edit or delete this place? Admins: anything. Students: only their own. */
    public boolean canEdit(Place place) {
        return online && client.isLoggedIn() && (client.isAdmin() || client.getUserId().equals(place.getCreatedBy()));
    }

    public boolean canAdd() {
        return online && client.isLoggedIn();
    }

    // ---------- changes (online only) ----------

    public Place add(Place place) throws IOException, ApiException {
        Map<String, Object> saved = client.insert(place.table(), place.toJson());
        Place result = place instanceof Building ? Building.fromJson(saved) : Room.fromJson(saved);
        if (result instanceof Building b) buildings.add(b); else rooms.add((Room) result);
        link();
        return result;
    }

    public void save(Place place) throws IOException, ApiException {
        client.update(place.table(), place.getId(), place.toJson());
        link();
    }

    public void remove(Place place) throws IOException, ApiException {
        client.delete(place.table(), place.getId());
        if (place instanceof Building b) {
            buildings.remove(b);
            rooms.removeIf(r -> b.getId().equals(r.getBuildingId())); // the database deletes its rooms too
        } else {
            rooms.remove(place);
        }
        link();
    }

    // ---------- saved copy for offline use ----------

    private void saveCache(List<Map<String, Object>> buildingRows, List<Map<String, Object>> roomRows) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Map<String, Object> all = new LinkedHashMap<>();
            all.put("buildings", buildingRows);
            all.put("rooms", roomRows);
            Files.writeString(cacheFile, Json.write(all), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // the app still works without a saved copy
        }
    }

    @SuppressWarnings("unchecked")
    private void loadCache() {
        try {
            Map<String, Object> all = Json.parseObject(Files.readString(cacheFile, StandardCharsets.UTF_8));
            use((List<Map<String, Object>>) all.get("buildings"), (List<Map<String, Object>>) all.get("rooms"));
        } catch (IOException | RuntimeException noCache) {
            // nothing saved yet: keep whatever is in memory
        }
    }
}
