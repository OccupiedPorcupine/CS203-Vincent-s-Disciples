package app.service;

import app.closure.HawkerCentre;
import app.closure.HawkerCentreClosure;
import app.closure.HawkerCentreClosure.Kind;
import app.closure.HawkerCentreClosureRepository;
import app.closure.HawkerCentreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// Reads data.gov.sg's "Dates of Hawker Centres Closure" as NEA publishes it: d/M/yyyy dates, "NA" and
// "TBC" for no date, "nil" for no remark, one row per centre. Runs against a fake server and H2.
@SpringBootTest
class HawkerCentreSyncServiceTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @Autowired
    HawkerCentreRepository centres;

    @Autowired
    HawkerCentreClosureRepository closures;

    @Autowired
    JdbcTemplate jdbc;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T02:00:00Z"), ZoneId.of("Asia/Singapore"));

    // Shaped like data.gov.sg's response, with the fields the sync doesn't read left out.
    private static final String RESPONSE = """
            {"success": true, "result": {"total": 3, "records": [
              {"_id": 1, "name": "Testville Food Centre", "address_myenv": "2, Testville Road, Singapore 289876",
               "q1_cleaningstartdate": "30/3/2026", "q1_cleaningenddate": "31/3/2026", "remarks_q1": "nil",
               "q2_cleaningstartdate": "8/6/2026", "q2_cleaningenddate": "8/6/2026", "remarks_q2": "nil",
               "q3_cleaningstartdate": "TBC", "q3_cleaningenddate": "TBC", "remarks_q3": "nil",
               "q4_cleaningstartdate": "7/12/2026", "q4_cleaningenddate": "8/12/2026", "remarks_q4": "  Only Cooked Food Section is closed. ",
               "other_works_startdate": "NA", "other_works_enddate": "NA", "remarks_other_works": "nil"},
              {"_id": 2, "name": "Testville Market", "address_myenv": "Blk 1, Testville Ave",
               "q1_cleaningstartdate": "TBC", "q1_cleaningenddate": "TBC", "remarks_q1": "nil",
               "q2_cleaningstartdate": "NA", "q2_cleaningenddate": "NA", "remarks_q2": "nil",
               "q3_cleaningstartdate": "17/9/2026", "q3_cleaningenddate": "14/9/2026", "remarks_q3": "nil",
               "q4_cleaningstartdate": "NA", "q4_cleaningenddate": "NA", "remarks_q4": "nil",
               "other_works_startdate": "1/10/2026", "other_works_enddate": "31/12/2029", "remarks_other_works": "Closed for redevelopment till 2029 (tentative)"},
              {"_id": 3, "name": "   ", "address_myenv": "Nowhere"}
            ]}}
            """;

    @AfterEach
    void deleteTestCentres() {
        jdbc.update("DELETE FROM hawker_centre_closures WHERE hawker_centre_id IN (SELECT id FROM hawker_centres WHERE name LIKE 'Testville%')");
        jdbc.update("DELETE FROM hawker_centres WHERE name LIKE 'Testville%'");
    }

    @Test
    void storesEachCentreWithItsDatedClosuresAndSkipsTheRest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://data.gov.sg");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("resource_id=" + HawkerCentreSyncService.RESOURCE_ID)))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        var result = new HawkerCentreSyncService(centres, closures, builder.build(), CLOCK).syncCentres();

        assertEquals(new HawkerCentreSyncService.SyncResult(2, 4), result);
        HawkerCentre foodCentre = centres.findByName("Testville Food Centre").orElseThrow();
        assertEquals("2, Testville Road, Singapore 289876", foodCentre.getAddress());

        List<HawkerCentreClosure> saved = closures.findByCentreId(foodCentre.getId()).stream()
                .sorted(Comparator.comparing(HawkerCentreClosure::getStartDate)).toList();
        assertEquals(List.of(LocalDate.of(2026, 3, 30), LocalDate.of(2026, 6, 8), LocalDate.of(2026, 12, 7)),
                saved.stream().map(HawkerCentreClosure::getStartDate).toList());
        assertEquals(LocalDate.of(2026, 3, 31), saved.get(0).getEndDate());
        assertNull(saved.get(0).getRemarks(), "\"nil\" isn't a remark");
        assertEquals("Only Cooked Food Section is closed.", saved.get(2).getRemarks());

        // "TBC", "NA" and an end before the start are left out; the renovation is kept whole.
        HawkerCentre market = centres.findByName("Testville Market").orElseThrow();
        List<HawkerCentreClosure> works = closures.findByCentreId(market.getId());
        assertEquals(1, works.size());
        assertEquals(Kind.WORKS, works.get(0).getKind());
        assertEquals(LocalDate.of(2029, 12, 31), works.get(0).getEndDate());
        server.verify();
    }

    @Test
    void syncingAgainReplacesClosuresInsteadOfAddingThem() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://data.gov.sg");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.twice(), requestTo(containsString("datastore_search")))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        HawkerCentreSyncService sync = new HawkerCentreSyncService(centres, closures, builder.build(), CLOCK);

        sync.syncCentres();
        Long id = centres.findByName("Testville Food Centre").orElseThrow().getId();
        sync.syncCentres();

        assertEquals(id, centres.findByName("Testville Food Centre").orElseThrow().getId(), "the centre keeps its id");
        assertEquals(3, closures.findByCentreId(id).size());
        assertEquals(CLOCK.instant(), sync.lastSyncedAt());
    }

    @Test
    void anEmptyResponseIsAnErrorAndChangesNothing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://data.gov.sg");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("datastore_search")))
                .andRespond(withSuccess("{\"success\": true, \"result\": {\"records\": []}}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class,
                () -> new HawkerCentreSyncService(centres, closures, builder.build(), CLOCK).syncCentres());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM hawker_centres WHERE name LIKE 'Testville%'", Integer.class));
    }

    @Test
    void neaDatesAreDayMonthYear() {
        assertEquals(LocalDate.of(2026, 3, 9), HawkerCentreSyncService.date("9/3/2026"));
        assertEquals(LocalDate.of(2026, 12, 14), HawkerCentreSyncService.date(" 14/12/2026 "));
        assertNull(HawkerCentreSyncService.date("NA"));
        assertNull(HawkerCentreSyncService.date("TBC"));
        assertNull(HawkerCentreSyncService.date("31/2/2026"));
        assertNull(HawkerCentreSyncService.date(null));
    }
}
