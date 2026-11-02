package app.dto;

import app.salesimport.SalesFileReader.RowError;

import java.util.List;

// 400 for a file that can't be imported: the problems, by row where there is one.
public record SalesImportErrorResponse(String code, String message, int errorCount, List<RowError> errors) {
}
