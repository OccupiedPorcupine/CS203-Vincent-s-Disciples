package app.recipe;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Recipes and the prep list over real HTTP and a real (H2) database (issue #45). The ML service
// isn't running in tests, so every dish is forecast from its own past sales. Each dish sells the
// same amount every day, so its forecast and likely range are exactly that amount.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrepListApiTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Singapore"));
    private final LocalDate tomorrow = today.plusDays(1);

    // Other tests delete every user, which the rows these tests create would block.
    @AfterEach
    void deleteTestRows() {
        String owners = "SELECT id FROM app_users WHERE email LIKE '%@prep.test'";
        jdbc.update("DELETE FROM dish_ingredients WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM ingredients WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM daily_sales WHERE dish_id IN (SELECT id FROM dishes WHERE owner_id IN (" + owners + "))");
        jdbc.update("DELETE FROM closed_dates WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM dishes WHERE owner_id IN (" + owners + ")");
    }

    @Test
    void recipesForThreeDishesGiveTomorrowsPortionsAndGramsOfEachIngredient() throws Exception {
        Session session = signUp("three-dishes@prep.test");
        long chickenRice = dishSelling(session, "Chicken rice", 40);
        long charSiewRice = dishSelling(session, "Char siew rice", 30);
        long laksa = dishSelling(session, "Laksa", 20);
        dishSelling(session, "Wanton mee", 10);

        setRecipe(session, chickenRice, "[" + item("Chicken", "150", "g") + "," + item("Rice", "120", "g") + "]");
        setRecipe(session, charSiewRice, "[" + item("Char siew", "100", "g") + "," + item(" rice ", "160", "g") + "]");
        setRecipe(session, laksa, "[" + item("Noodles", "200", "g") + "," + item("Egg", "0.5", "pcs") + "]");

        JsonNode prep = json.readTree(session.get("/api/prep-list?date=" + tomorrow).body());

        assertEquals(tomorrow.toString(), prep.path("date").asString());
        assertTrue(prep.path("closed").isNull());
        assertEquals(List.of("Char siew rice 30 true", "Chicken rice 40 true", "Laksa 20 true", "Wanton mee 10 false"),
                rows(prep.path("dishes"), "dish", "portions", "hasRecipe"));
        assertEquals(List.of("Char siew 3000 g", "Chicken 6000 g", "Egg 10 pcs", "Noodles 4000 g", "Rice 9600 g"),
                rows(prep.path("ingredients"), "ingredient", "quantity", "unit"));
        assertEquals("[\"Char siew rice\",\"Chicken rice\"]", prep.path("ingredients").get(4).path("dishes").toString());
    }

    @Test
    void aBufferAddsPercentToTheForecast() throws Exception {
        Session session = signUp("buffer@prep.test");
        long chickenRice = dishSelling(session, "Chicken rice", 40);
        setRecipe(session, chickenRice, "[" + item("Chicken", "150", "g") + "]");

        JsonNode prep = json.readTree(session.get("/api/prep-list?date=" + tomorrow + "&bufferPercent=15").body());

        assertEquals(46, prep.path("dishes").get(0).path("portions").asInt());
        assertEquals("6900", prep.path("ingredients").get(0).path("quantity").asString());
        assertEquals(400, session.get("/api/prep-list?date=" + tomorrow + "&bufferPercent=101").statusCode());
    }

    @Test
    void aClosedDayHasNothingToPrepare() throws Exception {
        Session session = signUp("closed@prep.test");
        dishSelling(session, "Chicken rice", 40);
        session.send("PUT", "/api/closures/dates/" + tomorrow, "{\"reason\":\"Centre cleaning\"}");

        JsonNode prep = json.readTree(session.get("/api/prep-list?date=" + tomorrow).body());

        assertTrue(prep.path("closed").asString().contains("Centre cleaning"), prep.toString());
        assertTrue(prep.path("dishes").isEmpty());
    }

    @Test
    void onlyTheComingWeekCanBePrepared() throws Exception {
        Session session = signUp("dates@prep.test");

        assertEquals(400, session.get("/api/prep-list?date=" + today.minusDays(1)).statusCode());
        assertEquals(400, session.get("/api/prep-list?date=" + today.plusDays(8)).statusCode());
        assertEquals(200, session.get("/api/prep-list?date=" + today.plusDays(7)).statusCode());
    }

    @Test
    void anIngredientKeepsOneUnitAcrossDishes() throws Exception {
        Session session = signUp("units@prep.test");
        long chickenRice = dishSelling(session, "Chicken rice", 40);
        long chickenNoodles = dishSelling(session, "Chicken noodles", 20);
        setRecipe(session, chickenRice, "[" + item("Chicken", "150", "g") + "]");

        HttpResponse<String> clash = session.send("PUT", "/api/dishes/" + chickenNoodles + "/recipe",
                "{\"items\":[" + item("chicken", "0.1", "kg") + "]}");
        assertEquals(400, clash.statusCode());
        assertTrue(clash.body().contains("Chicken is measured in g in Chicken rice. Use g here too."), clash.body());

        // With no other dish using it, the unit can change.
        setRecipe(session, chickenRice, "[" + item("Chicken", "0.15", "kg") + "]");
        assertEquals("[{\"name\":\"Chicken\",\"unit\":\"kg\"}]", session.get("/api/ingredients").body());
    }

    @Test
    void anIngredientNoRecipeUsesIsRemoved() throws Exception {
        Session session = signUp("removed@prep.test");
        long chickenRice = dishSelling(session, "Chicken rice", 40);
        setRecipe(session, chickenRice, "[" + item("Chicken", "150", "g") + "," + item("Rice", "120", "g") + "]");

        String saved = setRecipe(session, chickenRice, "[" + item("Chicken", "160", "g") + "]");

        assertEquals("{\"dishId\":" + chickenRice + ",\"dish\":\"Chicken rice\",\"items\":[{\"ingredient\":\"Chicken\",\"unit\":\"g\",\"quantity\":160}]}", saved);
        assertEquals("[{\"name\":\"Chicken\",\"unit\":\"g\"}]", session.get("/api/ingredients").body());
        setRecipe(session, chickenRice, "[]");
        assertEquals("[]", session.get("/api/ingredients").body());
    }

    @Test
    void anIngredientCannotBeListedTwice() throws Exception {
        Session session = signUp("twice@prep.test");
        long chickenRice = dishSelling(session, "Chicken rice", 40);

        HttpResponse<String> response = session.send("PUT", "/api/dishes/" + chickenRice + "/recipe",
                "{\"items\":[" + item("Rice", "100", "g") + "," + item("rice", "20", "g") + "]}");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("rice is listed twice"), response.body());
    }

    @Test
    void recipesBelongToTheirOwner() throws Exception {
        Session owner = signUp("owner-a@prep.test");
        long chickenRice = dishSelling(owner, "Chicken rice", 40);
        setRecipe(owner, chickenRice, "[" + item("Chicken", "150", "g") + "]");
        Session other = signUp("owner-b@prep.test");

        assertEquals(400, other.send("PUT", "/api/dishes/" + chickenRice + "/recipe", "{\"items\":[]}").statusCode());
        assertEquals("[]", other.get("/api/recipes").body());
        assertEquals("[]", other.get("/api/ingredients").body());
        assertFalse(other.get("/api/prep-list?date=" + tomorrow).body().contains("Chicken"));
    }

    // A dish that sold the same number every day for the last 14 days.
    private long dishSelling(Session session, String name, int perDay) throws Exception {
        long dishId = json.readTree(session.send("POST", "/api/dishes", "{\"name\":\"" + name + "\"}").body()).path("id").asLong();
        List<String> sales = new ArrayList<>();
        for (int daysAgo = 14; daysAgo >= 1; daysAgo--) {
            sales.add("{\"date\":\"" + today.minusDays(daysAgo) + "\",\"quantitySold\":" + perDay + "}");
        }
        HttpResponse<String> response = session.send("POST", "/api/dishes/" + dishId + "/sales", "{\"sales\":[" + String.join(",", sales) + "]}");
        assertTrue(response.statusCode() < 300, response.body());
        return dishId;
    }

    private String setRecipe(Session session, long dishId, String items) throws Exception {
        HttpResponse<String> response = session.send("PUT", "/api/dishes/" + dishId + "/recipe", "{\"items\":" + items + "}");
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static String item(String ingredient, String quantity, String unit) {
        return "{\"ingredient\":\"" + ingredient + "\",\"quantity\":" + quantity + ",\"unit\":\"" + unit + "\"}";
    }

    private static List<String> rows(JsonNode array, String... fields) {
        List<String> rows = new ArrayList<>();
        for (JsonNode node : array) {
            List<String> values = new ArrayList<>();
            for (String field : fields) values.add(node.path(field).asString());
            rows.add(String.join(" ", values));
        }
        return rows;
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

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            String csrf = get("/api/csrf").body();
            Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(csrf);
            assertTrue(matcher.find(), csrf);
            return http.send(HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", matcher.group(1))
                            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
