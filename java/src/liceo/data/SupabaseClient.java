package liceo.data;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Talks to Supabase over HTTPS: log in, register, and read or change buildings and rooms. */
public class SupabaseClient {
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // who is logged in (all null for a guest)
    private String accessToken;
    private String refreshToken;
    private Instant expiresAt;
    private String userId;
    private String email;
    private String fullName;
    private String role;

    public SupabaseClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isLoggedIn() { return accessToken != null; }
    public boolean isAdmin() { return "admin".equals(role); }
    public String getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getRole() { return isLoggedIn() ? (role == null ? "student" : role) : "guest"; }

    // ---------- accounts ----------

    public void logIn(String email, String password) throws IOException, ApiException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        useSession(Json.parseObject(send("POST", "/auth/v1/token?grant_type=password", body, false, null)));
        loadProfile();
    }

    /** @return true if logged in straight away, false if the account must first be confirmed by email. */
    public boolean register(String fullName, String email, String password) throws IOException, ApiException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("data", Map.of("full_name", fullName));
        Map<String, Object> answer = Json.parseObject(send("POST", "/auth/v1/signup", body, false, null));
        if (answer.get("access_token") == null) return false;
        useSession(answer);
        loadProfile();
        return true;
    }

    /** Turns the logged-in account into an admin if the code is right. */
    public boolean claimAdmin(String code) throws IOException, ApiException {
        String answer = send("POST", "/rest/v1/rpc/claim_admin", Map.of("code", code), true, null);
        boolean ok = "true".equals(answer.trim());
        if (ok) role = "admin";
        return ok;
    }

    public void logOut() {
        accessToken = refreshToken = userId = email = fullName = role = null;
        expiresAt = null;
    }

    @SuppressWarnings("unchecked")
    private void useSession(Map<String, Object> session) {
        accessToken = (String) session.get("access_token");
        refreshToken = (String) session.get("refresh_token");
        long seconds = session.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        expiresAt = Instant.now().plusSeconds(seconds - 60);
        if (session.get("user") instanceof Map<?, ?> user) {
            userId = (String) ((Map<String, Object>) user).get("id");
            email = (String) ((Map<String, Object>) user).get("email");
        }
    }

    private void loadProfile() throws IOException, ApiException {
        List<Map<String, Object>> rows = Json.parseRows(send("GET", "/rest/v1/profiles?select=full_name,role&id=eq." + userId, null, true, null));
        if (!rows.isEmpty()) {
            fullName = (String) rows.get(0).get("full_name");
            role = (String) rows.get(0).get("role");
        }
    }

    /** Logins last about an hour; quietly get a new one when it is about to run out. */
    private void refreshIfNeeded() throws IOException, ApiException {
        if (accessToken == null || expiresAt == null || Instant.now().isBefore(expiresAt)) return;
        useSession(Json.parseObject(send("POST", "/auth/v1/token?grant_type=refresh_token", Map.of("refresh_token", refreshToken), false, null)));
    }

    // ---------- data ----------

    public List<Map<String, Object>> list(String table) throws IOException, ApiException {
        return Json.parseRows(send("GET", "/rest/v1/" + table + "?select=*&order=name", null, true, null));
    }

    /** Adds a row and returns it as saved (with its new id). */
    public Map<String, Object> insert(String table, Map<String, Object> row) throws IOException, ApiException {
        Map<String, Object> body = new LinkedHashMap<>(row);
        body.put("created_by", userId);
        return firstRow(send("POST", "/rest/v1/" + table, body, true, "return=representation"));
    }

    public Map<String, Object> update(String table, String id, Map<String, Object> changes) throws IOException, ApiException {
        return firstRow(send("PATCH", "/rest/v1/" + table + "?id=eq." + encode(id), changes, true, "return=representation"));
    }

    public void delete(String table, String id) throws IOException, ApiException {
        firstRow(send("DELETE", "/rest/v1/" + table + "?id=eq." + encode(id), null, true, "return=representation"));
    }

    private static Map<String, Object> firstRow(String json) throws ApiException {
        List<Map<String, Object>> rows = Json.parseRows(json);
        // the database rules silently skip rows you may not change, so an empty answer means "not allowed"
        if (rows.isEmpty()) throw new ApiException(403, "You do not have permission to change this.");
        return rows.get(0);
    }

    // ---------- photos ----------

    /** Uploads a JPEG or PNG and returns its storage path (saved in photo_path). */
    public String uploadPhoto(String placeId, byte[] bytes, String contentType) throws IOException, ApiException {
        refreshIfNeeded();
        String ext = contentType.endsWith("png") ? "png" : "jpg";
        String path = userId + "/" + placeId + "-" + System.currentTimeMillis() + "." + ext;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/storage/v1/object/photos/" + path))
                .timeout(Duration.ofSeconds(60))
                .header("apikey", apiKey)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        check(exchange(request));
        return path;
    }

    public String photoUrl(String photoPath) {
        return baseUrl + "/storage/v1/object/public/photos/" + photoPath;
    }

    // ---------- plumbing ----------

    private String send(String method, String path, Object body, boolean asUser, String prefer) throws IOException, ApiException {
        if (asUser) refreshIfNeeded();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("apikey", apiKey)
                .header("Content-Type", "application/json");
        if (asUser && accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        if (prefer != null) request.header("Prefer", prefer);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8);
        return check(exchange(request.method(method, publisher).build()));
    }

    private HttpResponse<String> exchange(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static String check(HttpResponse<String> response) throws ApiException {
        if (response.statusCode() < 400) return response.body();
        String message = "Request failed (" + response.statusCode() + ")";
        try {
            Map<String, Object> error = Json.parseObject(response.body());
            for (String key : new String[]{"msg", "message", "error_description", "error"}) {
                if (error.get(key) instanceof String text && !text.isBlank()) { message = text; break; }
            }
        } catch (RuntimeException ignored) {
            // not JSON: keep the generic message
        }
        throw new ApiException(response.statusCode(), message);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
