package app.salesimport;

import app.datasource.TrainingDataSourceRepository;
import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.dto.SalesImportPreviewResponse;
import app.dto.SalesImportRequest;
import app.dto.SalesImportRequest.Action;
import app.dto.SalesImportRequest.DishChoice;
import app.dto.SalesImportRequest.ExistingSales;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import app.service.DailySaleService;
import app.service.DishService;
import app.service.PublicHolidaySyncService;
import app.service.TrainingDataSourceService;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// Upload → preview → import on H2, with the demo data file from scripts/sample-data.
@SpringBootTest
class SalesImportServiceTest {

    // Stubbed so the test does not call data.gov.sg on startup.
    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @Autowired SalesImportService importService;
    @Autowired TrainingDataSourceService sourceService;
    @Autowired TrainingDataSourceRepository sourceRepository;
    @Autowired AppUserRepository users;
    @Autowired DishService dishService;
    @Autowired DishRepository dishRepository;
    @Autowired DishAliasRepository aliasRepository;
    @Autowired DailySaleRepository saleRepository;
    @Autowired DailySaleService dailySaleService;

    Long owner;

    @BeforeEach
    void setUp() {
        owner = newUser();
    }

    // Other tests share this database and delete users, so remove everything that points at them.
    @AfterEach
    void cleanUp() {
        sourceRepository.deleteAll();
        aliasRepository.deleteAll();
        saleRepository.deleteAll();
        dishRepository.deleteAll();
    }

    private Long newUser() {
        return users.save(AppUser.localUser(UUID.randomUUID() + "@example.com", "{pbkdf2}hash", "Hawker")).getId();
    }

    private Long upload(Long user, String filename, byte[] content) {
        return sourceService.upload(user, new MockMultipartFile("file", filename, "text/csv", content)).id();
    }

    private Long upload(String csv) {
        return upload(owner, "sales-" + UUID.randomUUID() + ".csv", csv.getBytes());
    }

    private static SalesImportRequest request(ExistingSales existingSales, DishChoice... choices) {
        return new SalesImportRequest(existingSales, List.of(choices));
    }

    private List<DailySale> sales(Dish dish) {
        return saleRepository.findByDishIdOrderBySaleDateAsc(dish.getId());
    }

    @Test
    void theDemoFileFillsAnEmptyAccountAndImportingItAgainWithSkipWritesNothing() throws Exception {
        byte[] demo = Files.readAllBytes(Path.of("../scripts/sample-data/demo_sales.csv"));
        Long source = upload(owner, "demo_sales.csv", demo);

        SalesImportPreviewResponse preview = importService.preview(owner, source);
        assertEquals(600, preview.rows());
        assertEquals(0, preview.errorCount());
        assertEquals(5, preview.dishes().size());
        assertTrue(preview.dishes().stream().allMatch(dish -> dish.match() == null && dish.rows() == 120));
        assertNull(preview.lastImport());

        DishChoice[] addAll = preview.dishes().stream()
                .map(dish -> new DishChoice(dish.name(), Action.NEW, null, null))
                .toArray(DishChoice[]::new);
        var result = importService.importSales(owner, source, request(ExistingSales.SKIP, addAll));

        assertEquals(600, result.rowsAdded());
        assertEquals(5, result.createdDishes().size());
        assertEquals(600, saleRepository.findAll().size());
        assertEquals(600, result.source().lastImport().rowsAdded());

        // Now every name matches a dish, so no choices are needed.
        SalesImportPreviewResponse again = importService.preview(owner, source);
        assertTrue(again.dishes().stream().allMatch(dish -> dish.match() != null && "name".equals(dish.match().by())));
        assertEquals(600, again.rowsWithExistingSales());

        var second = importService.importSales(owner, source, request(ExistingSales.SKIP));
        assertEquals(0, second.rowsAdded());
        assertEquals(600, second.rowsSkipped());
        assertEquals(600, saleRepository.findAll().size());
    }

    @Test
    void namesMatchDishesAndNicknamesIgnoringCaseAndSpacing() {
        Dish chickenRice = dishService.createDish("Roasted chicken rice", owner);
        Dish charSiew = dishService.createDish("Char siew rice", owner);
        aliasRepository.save(new DishAlias(charSiew, "叉烧饭"));
        Long source = upload("date,dish,quantity_sold\n"
                + "2026-07-01,ROASTED  Chicken Rice,40\n"
                + "2026-07-01,叉烧饭,30\n");

        var preview = importService.preview(owner, source);

        assertEquals(new SalesImportPreviewResponse.Match(chickenRice.getId(), "Roasted chicken rice", "name"),
                preview.dishes().get(0).match());
        assertEquals(new SalesImportPreviewResponse.Match(charSiew.getId(), "Char siew rice", "nickname"),
                preview.dishes().get(1).match());

        var result = importService.importSales(owner, source, request(ExistingSales.SKIP));
        assertEquals(2, result.rowsAdded());
        assertEquals(30, sales(charSiew).get(0).getQuantitySold());
    }

    @Test
    void existingDatesAreSkippedOrOverwrittenAsChosen() {
        Dish dish = dishService.createDish("Chicken rice", owner);
        dailySaleService.setSale(dish.getId(), owner, LocalDate.of(2026, 7, 1), 10);
        dailySaleService.setSale(dish.getId(), owner, LocalDate.of(2026, 7, 2), 20);
        Long source = upload("date,dish,quantity_sold\n2026-07-01,Chicken rice,11\n2026-07-02,Chicken rice,20\n2026-07-03,Chicken rice,30\n");

        assertEquals(2, importService.preview(owner, source).rowsWithExistingSales());

        var skipped = importService.importSales(owner, source, request(ExistingSales.SKIP));
        assertEquals(1, skipped.rowsAdded());
        assertEquals(2, skipped.rowsSkipped());
        assertEquals(List.of(10, 20, 30), sales(dish).stream().map(DailySale::getQuantitySold).toList());

        var overwritten = importService.importSales(owner, source, request(ExistingSales.OVERWRITE));
        assertEquals(0, overwritten.rowsAdded());
        assertEquals(1, overwritten.rowsOverwritten()); // 1 July; 2 and 3 July are already the same
        assertEquals(2, overwritten.rowsSkipped());
        assertEquals(List.of(11, 20, 30), sales(dish).stream().map(DailySale::getQuantitySold).toList());
    }

    @Test
    void unknownNamesNeedAChoiceAndAMappedNameIsRememberedAsANickname() {
        Dish chickenRice = dishService.createDish("Roasted chicken rice", owner);
        Long source = upload("date,dish,quantity_sold\n"
                + "2026-07-01,Roast chix rice,40\n"
                + "2026-07-01,Barley drink,25\n"
                + "2026-07-01,Wanton mee,12\n");

        var error = assertThrows(SalesImportException.class,
                () -> importService.importSales(owner, source, request(ExistingSales.SKIP)));
        assertEquals("UNMATCHED_DISHES", error.getCode());
        assertEquals(3, error.getErrorCount());
        assertTrue(saleRepository.findAll().isEmpty());

        var result = importService.importSales(owner, source, request(ExistingSales.SKIP,
                new DishChoice("Roast chix rice", Action.EXISTING, chickenRice.getId(), null),
                new DishChoice("Barley drink", Action.LEAVE_OUT, null, null),
                new DishChoice("wanton mee", Action.NEW, null, "Wanton noodles")));

        assertEquals(2, result.rowsAdded());
        assertEquals(1, result.rowsLeftOut());
        assertEquals("Wanton noodles", result.createdDishes().get(0).name());
        assertEquals(40, sales(chickenRice).get(0).getQuantitySold());
        assertTrue(aliasRepository.findByOwnerIdAndNormalizedAlias(owner, "roast chix rice").isPresent());
        assertTrue(aliasRepository.findByOwnerIdAndNormalizedAlias(owner, "wanton mee").isPresent());
    }

    @Test
    void rowsForTheSameDishAndDayAreAddedTogether() {
        Dish dish = dishService.createDish("Chicken rice", owner);
        aliasRepository.save(new DishAlias(dish, "Chicken rice (takeaway)"));
        Long source = upload("date,dish,quantity_sold\n"
                + "2026-07-01,Chicken rice,40\n"
                + "2026-07-01,Chicken rice (takeaway),15\n"
                + "2026-07-01,chicken rice,5\n");

        assertEquals(2, importService.preview(owner, source).rowsCombined());

        importService.importSales(owner, source, request(ExistingSales.SKIP));
        assertEquals(60, sales(dish).get(0).getQuantitySold());
    }

    @Test
    void aFileWithBadRowsIsRefusedWithTheRowsListedAndNothingIsSaved() {
        dishService.createDish("Chicken rice", owner);
        Long source = upload("date,dish,quantity_sold\n2026-07-01,Chicken rice,40\n2026-07-02,Chicken rice,lots\n");

        var preview = importService.preview(owner, source);
        assertEquals(1, preview.errorCount());
        assertEquals(3, preview.errors().get(0).row());

        var error = assertThrows(SalesImportException.class,
                () -> importService.importSales(owner, source, request(ExistingSales.SKIP)));
        assertEquals("IMPORT_INVALID", error.getCode());
        assertTrue(saleRepository.findAll().isEmpty());
    }

    @Test
    void addingANewDishThatAlreadyExistsIsRefused() {
        dishService.createDish("Chicken rice", owner);
        Long source = upload("date,dish,quantity_sold\n2026-07-01,Chix rice,40\n");

        var error = assertThrows(IllegalArgumentException.class, () -> importService.importSales(owner, source,
                request(ExistingSales.SKIP, new DishChoice("Chix rice", Action.NEW, null, "chicken RICE"))));
        assertTrue(error.getMessage().contains("already a dish called Chicken rice"));
        assertEquals(1, dishRepository.findByOwnerId(owner).size());
    }

    @Test
    void anotherUsersFileOrDishCannotBeUsed() {
        Long otherUser = newUser();
        Dish theirDish = dishService.createDish("Chicken rice", otherUser);
        Long theirSource = upload(otherUser, "theirs.csv", "date,dish,quantity_sold\n2026-07-01,Chicken rice,40\n".getBytes());
        Long mySource = upload("date,dish,quantity_sold\n2026-07-01,Chicken rice,40\n");

        assertThrows(IllegalArgumentException.class, () -> importService.preview(owner, theirSource));
        assertThrows(IllegalArgumentException.class, () -> importService.importSales(owner, mySource,
                request(ExistingSales.SKIP, new DishChoice("Chicken rice", Action.EXISTING, theirDish.getId(), null))));
        assertTrue(sales(theirDish).isEmpty());
    }
}
