package app.salesimport;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.IntFunction;

// Reads a sales file in long format, one row per dish per day: a date, a dish and the quantity
// sold. Column names vary (date / dish or item / quantity_sold or qty sold), the header can
// sit below a title row, and in a workbook the first sheet with those columns is used, e.g.
// "Daily Item Sales" in hawker_sales.xlsx. Other columns are ignored.
public final class SalesFileReader {

    public record SalesRow(int row, LocalDate date, String dish, int quantity) {}

    public record RowError(Integer row, String column, String message) {}

    // sheet is null for CSV. errors holds the first MAX_ERRORS; errorCount is the total.
    public record SalesFile(String sheet, List<SalesRow> rows, List<RowError> errors, int errorCount) {}

    static final int MAX_ERRORS = 50;
    static final int MAX_QUANTITY = 100_000;
    private static final int HEADER_SEARCH_ROWS = 10;

    private static final Set<String> DATE_HEADERS = Set.of("date", "sale date", "sales date", "business date");
    private static final Set<String> DISH_HEADERS = Set.of("dish", "dish name", "item", "item name", "menu item", "product");
    private static final Set<String> QUANTITY_HEADERS = Set.of(
            "quantity sold", "quantity", "qty sold", "qty", "portions", "portions sold", "units sold", "sold");

    // Day first, as written in Singapore: 1/7/2026 is 1 July.
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.BASIC_ISO_DATE);

    private SalesFileReader() {
    }

    public static SalesFile read(Path path, String filename, LocalDate today) throws IOException {
        if (filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return readWorkbook(path, today);
        }
        return readCsv(path, today);
    }

    // ---- Formats ----

    private static SalesFile readCsv(Path path, LocalDate today) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1); // Excel's "CSV UTF-8" adds a byte order mark
        List<List<String>> lines = parseCsv(text);
        return readTable(null, lines.size(), row -> new CsvRow(lines.get(row)), today);
    }

    private static SalesFile readWorkbook(Path path, LocalDate today) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(path.toFile(), PackageAccess.READ);
             Workbook workbook = new XSSFWorkbook(pkg)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : workbook) {
                if (findHeader(sheet.getLastRowNum() + 1, row -> new SheetRow(sheet.getRow(row), evaluator)) != null) {
                    return readTable(sheet.getSheetName(), sheet.getLastRowNum() + 1,
                            row -> new SheetRow(sheet.getRow(row), evaluator), today);
                }
            }
            return missingColumns();
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException | org.apache.poi.ooxml.POIXMLException
                 | org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException exception) {
            return fileError("This isn't a readable .xlsx workbook. Save it from Excel as .xlsx or CSV and upload it again.");
        }
    }

    // ---- Table reading, shared by both formats ----

    private interface Row {
        String text(int column);

        // A date cell in a workbook, if the cell holds one; CSV has text only.
        default LocalDate date(int column) {
            return null;
        }

        // A numeric cell in a workbook; CSV has text only.
        default Double number(int column) {
            return null;
        }
    }

    private record Header(int row, int date, int dish, int quantity) {}

    // Keeps the first MAX_ERRORS errors and counts the rest.
    private static final class Errors {
        final List<RowError> kept = new ArrayList<>();
        int count;

        void add(Integer row, String column, String message) {
            count++;
            if (kept.size() < MAX_ERRORS) kept.add(new RowError(row, column, message));
        }
    }

    private static SalesFile readTable(String sheet, int rowCount, IntFunction<Row> rows, LocalDate today) {
        Header header = findHeader(rowCount, rows);
        if (header == null) return missingColumns();

        List<SalesRow> sales = new ArrayList<>();
        Errors errors = new Errors();

        for (int index = header.row() + 1; index < rowCount; index++) {
            Row row = rows.apply(index);
            int rowNumber = index + 1; // as shown in Excel
            String dateText = row.text(header.date());
            String dish = row.text(header.dish()).strip();
            String quantityText = row.text(header.quantity());
            if (dateText.isBlank() && dish.isEmpty() && quantityText.isBlank()) continue;

            LocalDate date = row.date(header.date());
            if (date == null) date = parseDate(dateText);
            Integer quantity = parseQuantity(row.number(header.quantity()), quantityText);
            int errorsBefore = errors.count;

            if (date == null) {
                errors.add(rowNumber, "date", dateText.isBlank()
                        ? "The date is missing."
                        : "\"" + dateText + "\" isn't a date. Use 2026-07-01 or 1/7/2026 (day first).");
            } else if (date.isAfter(today)) {
                errors.add(rowNumber, "date", date + " is in the future.");
            }
            if (dish.isEmpty()) {
                errors.add(rowNumber, "dish", "The dish name is missing.");
            } else if (dish.length() > 100) {
                errors.add(rowNumber, "dish", "The dish name is longer than 100 characters.");
            }
            if (quantity == null) {
                errors.add(rowNumber, "quantity", quantityText.isBlank()
                        ? "The quantity is missing."
                        : "\"" + quantityText + "\" isn't a whole number of portions.");
            } else if (quantity < 0 || quantity > MAX_QUANTITY) {
                errors.add(rowNumber, "quantity",
                        "The quantity must be between 0 and " + MAX_QUANTITY + ".");
            }

            if (errors.count == errorsBefore) {
                sales.add(new SalesRow(rowNumber, date, dish, quantity));
            }
        }
        return new SalesFile(sheet, sales, errors.kept, errors.count);
    }

    private static Header findHeader(int rowCount, IntFunction<Row> rows) {
        for (int index = 0; index < Math.min(rowCount, HEADER_SEARCH_ROWS); index++) {
            Row row = rows.apply(index);
            int date = -1, dish = -1, quantity = -1;
            for (int column = 0; column < 50; column++) {
                String name = headerName(row.text(column));
                if (date < 0 && DATE_HEADERS.contains(name)) date = column;
                else if (dish < 0 && DISH_HEADERS.contains(name)) dish = column;
                else if (quantity < 0 && QUANTITY_HEADERS.contains(name)) quantity = column;
            }
            if (date >= 0 && dish >= 0 && quantity >= 0) return new Header(index, date, dish, quantity);
        }
        return null;
    }

    private static SalesFile missingColumns() {
        return fileError("Couldn't find the columns. The file needs a header row with a date column, a dish "
                + "(or item) column and a quantity sold (or qty sold) column, one row per dish per day.");
    }

    private static SalesFile fileError(String message) {
        return new SalesFile(null, List.of(), List.of(new RowError(null, null, message)), 1);
    }

    // "Qty Sold", "quantity_sold" and "QUANTITY-SOLD" are all "qty sold" / "quantity sold".
    static String headerName(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    static LocalDate parseDate(String text) {
        String value = text.strip();
        if (value.isEmpty()) return null;
        // "2026-07-01 00:00:00" and "2026-07-01T00:00" from exports that add a time.
        value = value.split("[ T]\\d{1,2}:\\d{2}")[0].strip();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
        }
        return null;
    }

    static Integer parseQuantity(Double number, String text) {
        double value;
        if (number != null) {
            value = number;
        } else {
            String cleaned = text.strip().replace(",", "");
            if (cleaned.isEmpty()) return null;
            try {
                value = Double.parseDouble(cleaned);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        if (Double.isNaN(value) || Math.abs(value - Math.rint(value)) > 1e-9 || Math.abs(value) > Integer.MAX_VALUE) {
            return null;
        }
        return (int) Math.rint(value);
    }

    // ---- CSV ----

    private record CsvRow(List<String> cells) implements Row {
        @Override
        public String text(int column) {
            return column < cells.size() ? cells.get(column) : "";
        }
    }

    // RFC 4180: quoted fields may contain commas, quotes ("") and line breaks. Semicolons are
    // used instead of commas when the first line has semicolons but no commas (European Excel).
    static List<List<String>> parseCsv(String text) {
        int firstLineEnd = text.indexOf('\n');
        String firstLine = firstLineEnd < 0 ? text : text.substring(0, firstLineEnd);
        char separator = firstLine.contains(";") && !firstLine.contains(",") ? ';' : ',';

        List<List<String>> lines = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                cells.add(cell.toString());
                cell.setLength(0);
                lines.add(cells);
                cells = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !cells.isEmpty()) {
            cells.add(cell.toString());
            lines.add(cells);
        }
        return lines;
    }

    // ---- XLSX ----

    // Formulas are read from the value Excel saved with the file. Recalculating them can take tens
    // of seconds in workbooks whose summary sheets add up thousands of rows (hawker_sales.xlsx
    // took 27 s), so a formula is only calculated when the file has no saved value, as in files
    // written by some libraries.
    private record SheetRow(org.apache.poi.ss.usermodel.Row row, FormulaEvaluator evaluator) implements Row {
        private static final DataFormatter FORMATTER = new DataFormatter(Locale.ENGLISH);

        static {
            FORMATTER.setUseCachedValuesForFormulaCells(true);
        }

        @Override
        public String text(int column) {
            Cell cell = cell(column);
            return cell == null ? "" : FORMATTER.formatCellValue(cell);
        }

        @Override
        public LocalDate date(int column) {
            Cell cell = cell(column);
            if (cell == null || type(cell) != CellType.NUMERIC) return null;
            double value = cell.getNumericCellValue();
            // Date-formatted cells, and plain serial numbers in the range Excel uses for dates.
            if (DateUtil.isCellDateFormatted(cell) || (value >= 20_000 && value <= 80_000 && value == Math.rint(value))) {
                return DateUtil.getLocalDateTime(value).toLocalDate();
            }
            return null;
        }

        @Override
        public Double number(int column) {
            Cell cell = cell(column);
            return cell != null && type(cell) == CellType.NUMERIC ? cell.getNumericCellValue() : null;
        }

        private Cell cell(int column) {
            Cell cell = row == null ? null : row.getCell(column);
            if (cell instanceof XSSFCell xssfCell && cell.getCellType() == CellType.FORMULA && !xssfCell.getCTCell().isSetV()) {
                evaluator.evaluateInCell(cell); // no saved value: calculate this one cell
            }
            return cell;
        }

        private static CellType type(Cell cell) {
            return cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        }
    }
}
