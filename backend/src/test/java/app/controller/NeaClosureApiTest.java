package app.controller;

import app.closure.HawkerCentre;
import app.closure.HawkerCentreClosure;
import app.closure.HawkerCentreClosure.Kind;
import app.closure.HawkerCentreClosureRepository;
import app.closure.HawkerCentreRepository;
import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Issue #105 over real HTTP and H2: once a stall picks its hawker centre, NEA's cleaning and works
// dates are closed days (no forecast, no missing-sales reminder), an owner's own closed day is never
// overwritten, the owner can open an NEA day anyway, and unlinking the centre keeps only their own days.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NeaClosureApiTest {

    private static final String CENTRE = "Apitown Food Centre";

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HawkerCentreRepository centres;

    @Autowired
    HawkerCentreClosureRepository closures;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Singapore"));
    // Cleaning in 3 and 4 days; works in 10 and 11 days.
    private LocalDate cleaningStart;
    private LocalDate cleaningEnd;
    private LocalDate worksStart;
    private LocalDate worksEnd;
    private long centreId;

    @BeforeEach
    void addCentre() {
        cleaningStart = today.plusDays(3);
        cleaningEnd = today.plusDays(4);
        worksStart = today.plusDays(10);
        worksEnd = today.plusDays(11);
        HawkerCentre centre = centres.save(new HawkerCentre(CENTRE, "1 Apitown Road", Instant.now()));
        closures.save(new HawkerCentreClosure(centre, cleaningStart, cleaningEnd, Kind.CLEANING, null));
        closures.save(new HawkerCentreClosure(centre, worksStart, worksEnd, Kind.WORKS, "Repairs and Redecoration"));
        closures.save(new HawkerCentreClosure(centre, today.minusDays(60), today.minusDays(59), Kind.CLEANING, null));
        centreId = centre.getId();
    }

    @AfterEach
    void deleteTestRows() {
        String owners = "SELECT id FROM app_users WHERE email LIKE '%@nea.test'";
        jdbc.update("DELETE FROM nea_open_days WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM stall_hawker_centres WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM closed_dates WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM daily_sales WHERE dish_id IN (SELECT id FROM dishes WHERE owner_id IN (" + owners + "))");
        jdbc.update("DELETE FROM dishes WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM hawker_centre_closures WHERE hawker_centre_id IN (SELECT id FROM hawker_centres WHERE name = ?)", CENTRE);
        jdbc.update("DELETE FROM hawker_centres WHERE name = ?", CENTRE);
    }

    @Test
    void neaClosuresBecomeClosedDaysOnceTheStallPicksItsCentre() throws Exception {
        Session session = signUp("pick@nea.test");
        assertTrue(session.get("/api/closures/hawker-centres").body().contains("{\"id\":" + centreId + ",\"name\":\"" + CENTRE + "\""));
        assertTrue(session.get("/api/closures").body().contains("\"closedDates\":[]"));

        String body = link(session).body();

        assertTrue(body.contains("\"hawkerCentre\":{\"id\":" + centreId + ",\"name\":\"" + CENTRE + "\"}"), body);
        for (LocalDate day : new LocalDate[]{cleaningStart, cleaningEnd}) {
            assertTrue(body.contains(closed(day, "Centre cleaning (NEA)", "NEA")), body);
        }
        for (LocalDate day : new LocalDate[]{worksStart, worksEnd}) {
            assertTrue(body.contains(closed(day, "Centre works (NEA)", "NEA")), body);
        }
        // Past NEA days still count (the dashboard looks back), but only closures that haven't ended are listed.
        assertTrue(body.contains(closed(today.minusDays(60), "Centre cleaning (NEA)", "NEA")), body);
        assertTrue(body.contains("\"neaClosures\":[{\"startDate\":\"" + cleaningStart + "\",\"endDate\":\"" + cleaningEnd
                + "\",\"reason\":\"Centre cleaning (NEA)\",\"remarks\":null,\"openDays\":[]},{\"startDate\":\"" + worksStart
                + "\",\"endDate\":\"" + worksEnd + "\",\"reason\":\"Centre works (NEA)\",\"remarks\":\"Repairs and Redecoration\",\"openDays\":[]}]"), body);
    }

    @Test
    void anOwnersOwnClosedDayIsNeverOverwrittenAndUnlinkingKeepsOnlyThem() throws Exception {
        Session session = signUp("own@nea.test");
        session.send("PUT", "/api/closures/dates/" + cleaningStart, "{\"reason\":\"Family wedding\"}");

        String linked = link(session).body();
        assertTrue(linked.contains(closed(cleaningStart, "Family wedding", "OWNER")), linked);
        assertFalse(linked.contains(closed(cleaningStart, "Centre cleaning (NEA)", "NEA")), linked);

        assertEquals("{\"restDays\":[],\"closedDates\":[" + closed(cleaningStart, "Family wedding", "OWNER")
                        + "],\"hours\":null,\"hawkerCentre\":null,\"neaClosures\":[]}",
                session.send("PUT", "/api/closures/hawker-centre", "{\"hawkerCentreId\":null}").body());
    }

    @Test
    void anNeaDayIsNotForecastUnlessTheOwnerOpensAnyway() throws Exception {
        Session session = signUp("forecast@nea.test");
        String dishId = session.send("POST", "/api/dishes", "{\"name\":\"Chicken rice\"}").body().replaceAll(".*\"id\":(\\d+).*", "$1");
        link(session);
        String forecastRequest = "{\"dishId\":" + dishId + ",\"forecastDate\":\"" + cleaningStart + "\"}";

        HttpResponse<String> refused = session.send("POST", "/api/forecast", forecastRequest);
        assertEquals(400, refused.statusCode());
        assertTrue(refused.body().contains("STALL_CLOSED"), refused.body());
        assertTrue(refused.body().contains(CENTRE + " is closed for cleaning on"), refused.body());

        String opened = session.send("DELETE", "/api/closures/dates/" + cleaningStart, null).body();
        assertFalse(opened.contains("\"date\":\"" + cleaningStart + "\""), opened);
        assertTrue(opened.contains("\"openDays\":[\"" + cleaningStart + "\"]"), opened);
        assertFalse(session.send("POST", "/api/forecast", forecastRequest).body().contains("STALL_CLOSED"));

        // Closing it again brings back NEA's closure, without turning it into one of the owner's own days.
        String closedAgain = session.send("PUT", "/api/closures/dates/" + cleaningStart, null).body();
        assertTrue(closedAgain.contains(closed(cleaningStart, "Centre cleaning (NEA)", "NEA")), closedAgain);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM closed_dates WHERE owner_id IN "
                + "(SELECT id FROM app_users WHERE email = 'forecast@nea.test')", Integer.class));
    }

    @Test
    void aWholeClosureCanBeOpenedAnywayAndClosedAgain() throws Exception {
        Session session = signUp("range@nea.test");
        link(session);
        String range = "\"from\":\"" + worksStart + "\",\"to\":\"" + worksEnd + "\"";

        String open = session.send("PUT", "/api/closures/nea-days", "{" + range + ",\"open\":true}").body();
        assertFalse(open.contains("\"date\":\"" + worksStart + "\""), open);
        assertFalse(open.contains("\"date\":\"" + worksEnd + "\""), open);
        assertTrue(open.contains("\"openDays\":[\"" + worksStart + "\",\"" + worksEnd + "\"]"), open);

        String closedAgain = session.send("PUT", "/api/closures/nea-days", "{" + range + ",\"open\":false}").body();
        assertTrue(closedAgain.contains(closed(worksStart, "Centre works (NEA)", "NEA")), closedAgain);
        assertTrue(closedAgain.contains(closed(worksEnd, "Centre works (NEA)", "NEA")), closedAgain);
    }

    @Test
    void anUnknownCentreIsRefused() throws Exception {
        HttpResponse<String> response = signUp("unknown@nea.test")
                .send("PUT", "/api/closures/hawker-centre", "{\"hawkerCentreId\":999999}");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("isn't on NEA's list"), response.body());
    }

    private HttpResponse<String> link(Session session) throws Exception {
        HttpResponse<String> response = session.send("PUT", "/api/closures/hawker-centre", "{\"hawkerCentreId\":" + centreId + "}");
        assertEquals(200, response.statusCode(), response.body());
        return response;
    }

    private static String closed(LocalDate day, String reason, String source) {
        return "{\"date\":\"" + day + "\",\"reason\":\"" + reason + "\",\"source\":\"" + source + "\"}";
    }

    private Session signUp(String email) throws Exception {
        Session session = new Session();
        HttpResponse<String> signup = session.send("POST", "/api/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"a-long-unique-password\",\"name\":\"Stall owner\"}");
        assertEquals(201, signup.statusCode(), signup.body());
        return session;
    }

    private final class Session {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path, String json) throws Exception {
            String body = get("/api/csrf").body();
            Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            assertTrue(matcher.find(), body);
            return http.send(HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", matcher.group(1))
                            .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
