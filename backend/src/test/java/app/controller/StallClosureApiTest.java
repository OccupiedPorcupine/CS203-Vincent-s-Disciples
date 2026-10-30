package app.controller;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Rest days and closed dates over real HTTP and a real (H2) database, including changing rest
// days back and forth (the unique key would clash if all rows were deleted and re-inserted) and
// refusing to forecast a day the stall is closed.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StallClosureApiTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    // Other tests delete every user, which the rows these tests create would block.
    @AfterEach
    void deleteTestRows() {
        String owners = "SELECT id FROM app_users WHERE email LIKE '%@closures.test'";
        jdbc.update("DELETE FROM closed_dates WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM rest_days WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM dishes WHERE owner_id IN (" + owners + ")");
    }

    @Test
    void restDaysCanBeChangedBackAndForth() throws Exception {
        Session session = signUp("rest-days@closures.test");

        assertEquals("{\"restDays\":[],\"closedDates\":[]}", session.get("/api/closures").body());
        assertEquals("{\"restDays\":[\"TUESDAY\"],\"closedDates\":[]}",
                session.send("PUT", "/api/closures/rest-days", "{\"restDays\":[\"TUESDAY\"]}").body());
        assertEquals("{\"restDays\":[\"MONDAY\",\"TUESDAY\"],\"closedDates\":[]}",
                session.send("PUT", "/api/closures/rest-days", "{\"restDays\":[\"TUESDAY\",\"MONDAY\"]}").body());
        assertEquals("{\"restDays\":[\"MONDAY\"],\"closedDates\":[]}",
                session.send("PUT", "/api/closures/rest-days", "{\"restDays\":[\"MONDAY\"]}").body());
        assertEquals("{\"restDays\":[],\"closedDates\":[]}",
                session.send("PUT", "/api/closures/rest-days", "{\"restDays\":[]}").body());
    }

    @Test
    void everyDayCannotBeARestDay() throws Exception {
        Session session = signUp("always-closed@closures.test");

        HttpResponse<String> response = session.send("PUT", "/api/closures/rest-days",
                "{\"restDays\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("At least one day of the week must be open."), response.body());
    }

    @Test
    void closedDatesCanBeAddedRenamedAndRemoved() throws Exception {
        Session session = signUp("closed-dates@closures.test");

        session.send("PUT", "/api/closures/dates/2026-11-05", "{\"reason\":\"  Centre cleaning \"}");
        assertEquals("{\"restDays\":[],\"closedDates\":[{\"date\":\"2026-11-05\",\"reason\":\"Centre cleaning\"}]}",
                session.send("PUT", "/api/closures/dates/2026-11-05", "{\"reason\":\"Centre cleaning\"}").body());
        assertEquals("{\"restDays\":[],\"closedDates\":[{\"date\":\"2026-11-05\",\"reason\":null}]}",
                session.send("PUT", "/api/closures/dates/2026-11-05", null).body());
        assertEquals("{\"restDays\":[],\"closedDates\":[]}",
                session.send("DELETE", "/api/closures/dates/2026-11-05", null).body());
    }

    @Test
    void closuresBelongToTheirOwner() throws Exception {
        signUp("owner-a@closures.test").send("PUT", "/api/closures/dates/2026-11-05", "{\"reason\":\"Holiday\"}");

        assertEquals("{\"restDays\":[],\"closedDates\":[]}", signUp("owner-b@closures.test").get("/api/closures").body());
    }

    @Test
    void aClosedDayIsNotForecast() throws Exception {
        Session session = signUp("closed-forecast@closures.test");
        String dishId = session.send("POST", "/api/dishes", "{\"name\":\"Chicken rice\"}").body().replaceAll(".*\"id\":(\\d+).*", "$1");
        LocalDate nextTuesday = LocalDate.now(ZoneId.of("Asia/Singapore")).with(TemporalAdjusters.next(DayOfWeek.TUESDAY));
        session.send("PUT", "/api/closures/rest-days", "{\"restDays\":[\"TUESDAY\"]}");

        HttpResponse<String> forecast = session.send("POST", "/api/forecast",
                "{\"dishId\":" + dishId + ",\"forecastDate\":\"" + nextTuesday + "\"}");

        assertEquals(400, forecast.statusCode());
        assertTrue(forecast.body().contains("STALL_CLOSED"), forecast.body());
        assertTrue(forecast.body().contains("Your stall is closed on Tuesdays."), forecast.body());
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
