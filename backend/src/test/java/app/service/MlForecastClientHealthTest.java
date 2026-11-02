package app.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

// MlForecastClient.isUp, behind the "ml" part of GET /api/health (issue #48), against a stand-in
// ML service.
class MlForecastClientHealthTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void isUpWhenHealthAnswers() throws Exception {
        assertTrue(new MlForecastClient(serve(200)).isUp());
    }

    @Test
    void isDownWhenHealthFails() throws Exception {
        assertFalse(new MlForecastClient(serve(500)).isUp());
    }

    @Test
    void isDownWhenNothingIsListening() {
        assertFalse(new MlForecastClient("http://127.0.0.1:9").isUp());
    }
}
