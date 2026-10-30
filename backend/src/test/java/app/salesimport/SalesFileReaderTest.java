package app.salesimport;

import app.salesimport.SalesFileReader.SalesFile;
import app.salesimport.SalesFileReader.SalesRow;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SalesFileReaderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    @TempDir Path directory;

    private SalesFile csv(String text) throws Exception {
        Path file = directory.resolve("sales.csv");
        Files.writeString(file, text);
        return SalesFileReader.read(file, "sales.csv", TODAY);
    }

    @Test
    void readsTheDemoCsvFormat() throws Exception {
        SalesFile file = csv("date,dish,quantity_sold\n2026-07-01,Char siew rice,31\r\n2026-07-01,Roasted chicken rice,56\n\n");

        assertNull(file.sheet());
        assertEquals(0, file.errorCount());
        assertEquals(List.of(
                new SalesRow(2, LocalDate.of(2026, 7, 1), "Char siew rice", 31),
                new SalesRow(3, LocalDate.of(2026, 7, 1), "Roasted chicken rice", 56)), file.rows());
    }

    @Test
    void acceptsOtherColumnNamesATitleRowQuotesAndExcelsByteOrderMark() throws Exception {
        SalesFile file = csv("﻿Ah Huat Chicken Rice, export\n"
                + "Item,Qty Sold,Unit Price,Date\n"
                + "\"Chicken rice, large\",\"1,200\",5.5,1/7/2026\n"
                + "Char siew rice,30.0,4.5,2026/07/02 00:00\n");

        assertEquals(0, file.errorCount());
        assertEquals(new SalesRow(3, LocalDate.of(2026, 7, 1), "Chicken rice, large", 1200), file.rows().get(0));
        assertEquals(new SalesRow(4, LocalDate.of(2026, 7, 2), "Char siew rice", 30), file.rows().get(1));
    }

    @Test
    void readsEachDateFormatAndRejectsDaysThatDoNotExist() {
        assertEquals(LocalDate.of(2026, 7, 1), SalesFileReader.parseDate("2026-07-01"));
        assertEquals(LocalDate.of(2026, 7, 1), SalesFileReader.parseDate("2026/7/1"));
        assertEquals(LocalDate.of(2026, 7, 1), SalesFileReader.parseDate("1/7/2026"));
        assertEquals(LocalDate.of(2026, 7, 1), SalesFileReader.parseDate("01-07-2026"));
        assertEquals(LocalDate.of(2026, 7, 1), SalesFileReader.parseDate("1 Jul 2026"));
        assertEquals(LocalDate.of(2026, 7, 1), SalesFileReader.parseDate("20260701"));
        assertNull(SalesFileReader.parseDate("31/6/2026"));
        assertNull(SalesFileReader.parseDate("2026-02-29"));
        assertNull(SalesFileReader.parseDate("13/13/2026"));
    }

    @Test
    void readsSemicolonSeparatedFiles() throws Exception {
        SalesFile file = csv("Date;Dish;Quantity\n02.07.2026;Wanton mee;12\n");

        assertEquals(List.of(new SalesRow(2, LocalDate.of(2026, 7, 2), "Wanton mee", 12)), file.rows());
    }

    @Test
    void reportsEachBadRowAndKeepsTheGoodOnes() throws Exception {
        SalesFile file = csv("date,dish,quantity_sold\n"
                + "2026-07-01,Chicken rice,40\n"
                + "July first,Chicken rice,40\n"
                + "2026-07-02,,40\n"
                + "2026-07-03,Chicken rice,forty\n"
                + "2026-07-04,Chicken rice,4.5\n"
                + "2026-07-05,Chicken rice,-1\n"
                + "2026-11-01,Chicken rice,40\n"
                + "2026-07-06,Chicken rice,\n");

        assertEquals(1, file.rows().size());
        assertEquals(7, file.errorCount());
        assertEquals(List.of(3, 4, 5, 6, 7, 8, 9), file.errors().stream().map(SalesFileReader.RowError::row).toList());
        assertEquals("date", file.errors().get(0).column());
        assertTrue(file.errors().get(0).message().contains("July first"));
        assertEquals("dish", file.errors().get(1).column());
        assertTrue(file.errors().get(4).message().contains("between 0 and"));
        assertTrue(file.errors().get(5).message().contains("in the future"));
        assertEquals("The quantity is missing.", file.errors().get(6).message());
    }

    @Test
    void keepsTheFirstFiftyErrorsAndCountsTheRest() throws Exception {
        StringBuilder text = new StringBuilder("date,dish,quantity_sold\n");
        for (int i = 0; i < 80; i++) text.append("not a date,Chicken rice,1\n");

        SalesFile file = csv(text.toString());

        assertEquals(80, file.errorCount());
        assertEquals(SalesFileReader.MAX_ERRORS, file.errors().size());
    }

    @Test
    void saysWhichColumnsAreNeededWhenTheyAreMissing() throws Exception {
        SalesFile file = csv("day,revenue\nMon,1964\n");

        assertTrue(file.rows().isEmpty());
        assertEquals(1, file.errorCount());
        assertNull(file.errors().get(0).row());
        assertTrue(file.errors().get(0).message().contains("date column"));
    }

    @Test
    void readsTheFirstWorksheetWithSalesColumnsLikeHawkerSalesXlsx() throws Exception {
        Path path = directory.resolve("hawker_sales.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet summary = workbook.createSheet("Daily Summary");
            summary.createRow(0).createCell(0).setCellValue("Date");
            summary.getRow(0).createCell(1).setCellValue("Revenue (S$)");

            Sheet items = workbook.createSheet("Daily Item Sales");
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            String[] headers = {"Date", "Day", "Month", "Item", "Qty Sold", "Unit Price (S$)"};
            Row header = items.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);

            Row first = items.createRow(1);
            first.createCell(0).setCellValue(LocalDate.of(2026, 3, 1));
            first.getCell(0).setCellStyle(dateStyle);
            first.createCell(3).setCellValue("Roasted Chicken Rice");
            first.createCell(4).setCellValue(75.0);

            Row second = items.createRow(2);
            second.createCell(0).setCellValue(46083); // a date serial without a date format: 2 March 2026
            second.createCell(3).setCellValue("Char Siew Rice");
            second.createCell(4).setCellFormula("40+8");

            try (OutputStream out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }

        SalesFile file = SalesFileReader.read(path, "hawker_sales.xlsx", TODAY);

        assertEquals("Daily Item Sales", file.sheet());
        assertEquals(0, file.errorCount(), () -> file.errors().toString());
        assertEquals(new SalesRow(2, LocalDate.of(2026, 3, 1), "Roasted Chicken Rice", 75), file.rows().get(0));
        assertEquals(new SalesRow(3, LocalDate.of(2026, 3, 2), "Char Siew Rice", 48), file.rows().get(1));
    }

    @Test
    void aFileThatIsNotAWorkbookIsReportedNotThrown() throws Exception {
        Path path = directory.resolve("sales.xlsx");
        Files.writeString(path, "date,dish,quantity_sold\n");

        SalesFile file = SalesFileReader.read(path, "sales.xlsx", TODAY);

        assertEquals(1, file.errorCount());
        assertTrue(file.errors().get(0).message().contains(".xlsx"));
    }
}
