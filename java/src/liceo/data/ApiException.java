package liceo.data;

/** The server answered, but refused the request (wrong password, no permission, ...). */
public class ApiException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
