package liceo;

/** App settings: the Supabase project and where the campus is. */
public final class Config {
    private Config() {}

    public static final String APP_NAME = "Liceo Campus Tracker";
    public static final String SUPABASE_URL = "https://iljibsddirnnnkhnpqjb.supabase.co";
    /** Public (publishable) key. What each user may do is enforced by the database rules, not by this key. */
    public static final String SUPABASE_KEY = "sb_publishable_X-LYjE0XrYp1KevQAu8HaQ_jRGZmx24";

    /** Liceo de Cagayan University, main campus. */
    public static final double CAMPUS_LAT = 8.48597;
    public static final double CAMPUS_LNG = 124.63940;
    public static final int CAMPUS_ZOOM = 18;
}
