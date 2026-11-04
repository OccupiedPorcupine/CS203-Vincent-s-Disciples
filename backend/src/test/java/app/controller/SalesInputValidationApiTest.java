package app.controller;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Input validation on sales and dishes (week 9 security): POST /api/dishes/{id}/sales was missing
// @Valid, so its constraints never ran. Over real HTTP and H2, so the whole request path is checked.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesInputValidationApiTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    // Other tests delete every user, which the rows these tests create would block.
    @AfterEach
    void deleteTestRows() {
        String owners = "SELECT id FROM app_users WHERE email LIKE '%@validation.test'";
        jdbc.update("DELETE FROM daily_sales WHERE dish_id IN (SELECT id FROM dishes WHERE owner_id IN (" + owners + "))");
        jdbc.update("DELETE FROM dishes WHERE owner_id IN (" + owners + ")");
    }

    @Test
    void salesWithoutADateAreRejectedAsInvalid() throws Exception {
        Session session = signUp("no-date@validation.test");
        String dishId = createDish(session);

        HttpResponse<String> response = session.send("POST", "/api/dishes/" + dishId + "/sales",
                "{\"sales\":[{\"quantitySold\":42}]}");

        // Before @Valid was added this reached the database and failed with a 500.
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("VALIDATION_FAILED"), response.body());
    }

    @Test
    void anEmptyListOfSalesIsRejected() throws Exception {
        Session session = signUp("empty@validation.test");
        String dishId = createDish(session);

        HttpResponse<String> response = session.send("POST", "/api/dishes/" + dishId + "/sales", "{\"sales\":[]}");

        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    void absurdQuantitiesAreRejected() throws Exception {
        Session session = signUp("huge-quantity@validation.test");
        String dishId = createDish(session);

        HttpResponse<String> response = session.send("POST", "/api/dishes/" + dishId + "/sales",
                "{\"sales\":[{\"date\":\"2026-09-01\",\"quantitySold\":100000000}]}");

        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    void absurdQuantitiesAreRejectedWhenCorrectingADay() throws Exception {
        Session session = signUp("huge-correction@validation.test");
        String dishId = createDish(session);

        HttpResponse<String> response = session.send("PUT", "/api/dishes/" + dishId + "/sales/2026-09-01",
                "{\"quantitySold\":100000000}");

        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    void validSalesAreStillAccepted() throws Exception {
        Session session = signUp("valid-sales@validation.test");
        String dishId = createDish(session);

        HttpResponse<String> response = session.send("POST", "/api/dishes/" + dishId + "/sales",
                "{\"sales\":[{\"date\":\"2026-09-01\",\"quantitySold\":42}]}");

        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void overlongDishNamesAreRejected() throws Exception {
        Session session = signUp("long-name@validation.test");

        HttpResponse<String> response = session.send("POST", "/api/dishes", "{\"name\":\"" + "x".repeat(101) + "\"}");

        assertEquals(400, response.statusCode(), response.body());
    }

    private String createDish(Session session) throws Exception {
        HttpResponse<String> dish = session.send("POST", "/api/dishes", "{\"name\":\"Chicken rice\"}");
        assertTrue(dish.statusCode() / 100 == 2, dish.body());
        return dish.body().replaceAll(".*\"id\":(\\d+).*", "$1");
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
