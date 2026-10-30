package app.salesimport;

import app.salesimport.SalesFileReader.RowError;

import java.util.List;

// A file that can't be imported as it stands; returned as 400 with the problems listed.
public class SalesImportException extends RuntimeException {

    private final String code;
    private final int errorCount;
    private final List<RowError> errors;

    public SalesImportException(String code, String message, int errorCount, List<RowError> errors) {
        super(message);
        this.code = code;
        this.errorCount = errorCount;
        this.errors = errors;
    }

    public String getCode() {
        return code;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public List<RowError> getErrors() {
        return errors;
    }
}
