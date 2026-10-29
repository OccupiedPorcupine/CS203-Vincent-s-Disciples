package app.exception;

// The user already uses this name for a different dish, so it can't also point to this one.
public class AliasInUseException extends RuntimeException {

    public AliasInUseException(String alias, String dishName) {
        super("\"" + alias + "\" is already a name for " + dishName);
    }
}
