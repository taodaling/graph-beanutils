package beans;

public class CastException extends RuntimeException {
    public CastException(String message) {
        super(message);
    }

    public CastException(Throwable cause) {
        super(cause);
    }
}
