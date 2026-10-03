package liceo.model;

/** Distance and direction between two GPS points, plus web-map (Mercator) maths. */
public final class Geo {
    private Geo() {}

    private static final double EARTH_RADIUS_M = 6_371_000;
    private static final String[] DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** Straight-line distance in metres (haversine formula). */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }

    /** Compass bearing in degrees from point 1 to point 2 (0 = north, 90 = east). */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dLng = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLng) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    public static String compass(double bearing) {
        return DIRECTIONS[(int) Math.round(bearing / 45) % 8];
    }

    public static String formatDistance(double metres) {
        return metres < 1000 ? Math.round(metres) + " m" : String.format("%.1f km", metres / 1000);
    }

    /** Longitude to a pixel x position on the world map at this zoom (256 px tiles). */
    public static double lngToX(double lng, int zoom) {
        return (lng + 180) / 360 * 256 * (1 << zoom);
    }

    public static double latToY(double lat, int zoom) {
        double rad = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * 256 * (1 << zoom);
    }

    public static double xToLng(double x, int zoom) {
        return x / (256.0 * (1 << zoom)) * 360 - 180;
    }

    public static double yToLat(double y, int zoom) {
        double n = Math.PI - 2 * Math.PI * y / (256.0 * (1 << zoom));
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }
}
