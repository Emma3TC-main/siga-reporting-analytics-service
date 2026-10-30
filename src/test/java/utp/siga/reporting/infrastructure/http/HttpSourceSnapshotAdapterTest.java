package utp.siga.reporting.infrastructure.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import utp.siga.reporting.infrastructure.http.HttpSourceSnapshotAdapter.SourceApiException;

class HttpSourceSnapshotAdapterTest {

    private HttpServer server;
    private final List<String> authorizationHeaders = new ArrayList<>();
    private UUID productOne;
    private UUID productTwo;
    private UUID category;
    private UUID unit;

    @BeforeEach
    void start() throws IOException {
        productOne = UUID.randomUUID();
        productTwo = UUID.randomUUID();
        category = UUID.randomUUID();
        unit = UUID.randomUUID();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/categories", exchange -> json(exchange, 200,
                "[{\"id\":\"" + category + "\",\"code\":\"CAT\",\"name\":\"Category\"}]"));
        server.createContext("/api/v1/units", exchange -> json(exchange, 200,
                "[{\"id\":\"" + unit + "\",\"code\":\"PZA\"}]"));
        server.createContext("/api/v1/products", exchange -> {
            int page = queryPage(exchange);
            UUID id = page == 0 ? productOne : productTwo;
            String content = page < 2 ? "[" + product(id, "SKU-" + page) + "]" : "[]";
            json(exchange, 200, "{\"content\":" + content + ",\"totalElements\":2}");
        });
        server.createContext("/api/v1/inventory", exchange -> {
            int page = queryPage(exchange);
            UUID product = page == 0 ? productOne : productTwo;
            String content = page < 2 ? "[{\"id\":\"" + UUID.randomUUID()
                    + "\",\"productId\":\"" + product + "\",\"locationId\":\"" + UUID.randomUUID()
                    + "\",\"quantity\":2.000000,\"avgCostPen\":3.000000}]" : "[]";
            json(exchange, 200, "{\"content\":" + content + ",\"totalElements\":2}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void loadsEveryCatalogAndInventoryPageAndForwardsBearer() {
        var adapter = adapter(1);
        var snapshot = adapter.load("Bearer opaque-token");
        assertEquals(2, snapshot.products().size());
        assertEquals(2, snapshot.balances().size());
        assertTrue(authorizationHeaders.size() >= 6);
        assertTrue(authorizationHeaders.stream().allMatch("Bearer opaque-token"::equals));
        assertEquals("CAT", snapshot.products().getFirst().categoryCode());
        assertEquals("PZA", snapshot.products().getFirst().baseUnitCode());
    }

    @Test
    void malformedOrUnavailableDependencyFailsClosed() throws IOException {
        server.removeContext("/api/v1/products");
        server.createContext("/api/v1/products", exchange -> json(exchange, 200, "{\"content\":{}}"));
        assertThrows(SourceApiException.class, () -> adapter(1).load("Bearer opaque-token"));

        server.removeContext("/api/v1/products");
        server.createContext("/api/v1/products", exchange -> json(exchange, 503, "{}"));
        assertThrows(SourceApiException.class, () -> adapter(1).load("Bearer opaque-token"));
    }

    @Test
    void invalidPageSizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> adapter(101));
    }

    private HttpSourceSnapshotAdapter adapter(int pageSize) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
        return new HttpSourceSnapshotAdapter(RestClient.builder(), base, base, pageSize);
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String product(UUID id, String sku) {
        return "{\"id\":\"" + id + "\",\"sku\":\"" + sku
                + "\",\"name\":\"Product\",\"categoryId\":\"" + category
                + "\",\"productType\":\"MATERIAL\",\"storageUnitId\":\"" + unit
                + "\",\"baseUnitId\":\"" + unit
                + "\",\"minStock\":5.000000,\"active\":true,\"version\":1}";
    }

    private static int queryPage(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        for (String pair : query.split("&")) {
            if (pair.startsWith("page=")) {
                return Integer.parseInt(pair.substring(5));
            }
        }
        return 0;
    }
}
