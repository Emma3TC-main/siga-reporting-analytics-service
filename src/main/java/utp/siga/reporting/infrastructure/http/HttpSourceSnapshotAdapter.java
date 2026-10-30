package utp.siga.reporting.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import utp.siga.reporting.application.port.out.SourceSnapshotPort;
import utp.siga.reporting.domain.model.InventoryProjection;
import utp.siga.reporting.domain.model.ProductProjection;

@Component
public class HttpSourceSnapshotAdapter implements SourceSnapshotPort {

    private final RestClient catalog;
    private final RestClient inventory;
    private final int pageSize;

    public HttpSourceSnapshotAdapter(
            RestClient.Builder builder,
            @Value("${reporting.sources.catalog-base-url}") String catalogBaseUrl,
            @Value("${reporting.sources.inventory-base-url}") String inventoryBaseUrl,
            @Value("${reporting.sources.page-size:100}") int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("reporting.sources.page-size must be between 1 and 100");
        }
        this.catalog = builder.clone().baseUrl(catalogBaseUrl).build();
        this.inventory = builder.clone().baseUrl(inventoryBaseUrl).build();
        this.pageSize = pageSize;
    }

    @Override
    public Snapshot load(String bearerToken) {
        try {
            Map<UUID, Category> categories = categories(bearerToken);
            Map<UUID, String> units = units(bearerToken);
            List<ProductProjection> products = products(bearerToken, categories, units);
            Map<UUID, ProductProjection> productById = new HashMap<>();
            products.forEach(product -> productById.put(product.productId(), product));
            List<InventoryProjection> balances = balances(bearerToken, productById);
            return new Snapshot(List.copyOf(products), List.copyOf(balances));
        } catch (SourceApiException ex) {
            throw ex;
        } catch (RestClientException | IllegalArgumentException ex) {
            throw new SourceApiException("Canonical source API reconciliation failed", ex);
        }
    }

    private Map<UUID, Category> categories(String bearer) {
        JsonNode response = get(catalog, "/categories", bearer);
        requireArray(response, "Catalog categories");
        Map<UUID, Category> result = new HashMap<>();
        response.forEach(node -> {
            UUID id = uuid(node, "id");
            result.put(id, new Category(text(node, "code"), text(node, "name")));
        });
        return result;
    }

    private Map<UUID, String> units(String bearer) {
        JsonNode response = get(catalog, "/units", bearer);
        requireArray(response, "Catalog units");
        Map<UUID, String> result = new HashMap<>();
        response.forEach(node -> result.put(uuid(node, "id"), text(node, "code")));
        return result;
    }

    private List<ProductProjection> products(
            String bearer, Map<UUID, Category> categories, Map<UUID, String> units) {
        List<ProductProjection> result = new ArrayList<>();
        int page = 0;
        long total;
        do {
            JsonNode response = get(catalog, "/products?page=" + page + "&size=" + pageSize + "&sort=sku,asc", bearer);
            JsonNode content = pageContent(response, "Catalog products");
            total = longValue(response, "totalElements");
            for (JsonNode node : content) {
                UUID categoryId = uuid(node, "categoryId");
                Category category = categories.get(categoryId);
                if (category == null) {
                    throw new SourceApiException("Catalog product references an unknown category");
                }
                String baseUnit = units.get(uuid(node, "baseUnitId"));
                String storageUnit = units.get(uuid(node, "storageUnitId"));
                if (baseUnit == null || storageUnit == null) {
                    throw new SourceApiException("Catalog product references an unknown unit");
                }
                result.add(new ProductProjection(
                        uuid(node, "id"), text(node, "sku"), text(node, "name"),
                        category.code(), category.name(), text(node, "productType"), baseUnit, storageUnit,
                        decimal(node, "minStock"), bool(node, "active"), longValue(node, "version"),
                        null, null));
            }
            page++;
            ensureProgress(page, pageSize, total, content.size(), "Catalog products");
        } while ((long) page * pageSize < total);
        return result;
    }

    private List<InventoryProjection> balances(
            String bearer, Map<UUID, ProductProjection> products) {
        List<InventoryProjection> result = new ArrayList<>();
        int page = 0;
        long total;
        do {
            JsonNode response = get(inventory, "/inventory?page=" + page + "&size=" + pageSize, bearer);
            JsonNode content = pageContent(response, "Inventory balances");
            total = longValue(response, "totalElements");
            for (JsonNode node : content) {
                UUID productId = uuid(node, "productId");
                ProductProjection product = products.get(productId);
                if (product == null) {
                    throw new SourceApiException("Inventory balance references an unknown product");
                }
                result.add(new InventoryProjection(
                        uuid(node, "id"), productId, uuid(node, "locationId"),
                        optionalUuid(node, "lotId"), optionalUuid(node, "assetId"),
                        decimal(node, "quantity"), decimal(node, "avgCostPen"), product.minStock(),
                        null, null));
            }
            page++;
            ensureProgress(page, pageSize, total, content.size(), "Inventory balances");
        } while ((long) page * pageSize < total);
        return result;
    }

    private static JsonNode get(RestClient client, String uri, String bearer) {
        JsonNode response = client.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new SourceApiException("Canonical source returned an empty response");
        }
        return response;
    }

    private static JsonNode pageContent(JsonNode response, String source) {
        if (!response.isObject() || !response.path("content").isArray()
                || !response.path("totalElements").canConvertToLong()) {
            throw new SourceApiException(source + " returned a malformed page");
        }
        return response.path("content");
    }

    private static void ensureProgress(int page, int pageSize, long total, int contentSize, String source) {
        if ((long) page * pageSize < total && contentSize == 0) {
            throw new SourceApiException(source + " returned an empty page before totalElements");
        }
    }

    private static void requireArray(JsonNode node, String source) {
        if (!node.isArray()) {
            throw new SourceApiException(source + " response must be an array");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new SourceApiException(field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException ex) {
            throw new SourceApiException(field + " must be a UUID", ex);
        }
    }

    private static UUID optionalUuid(JsonNode node, String field) {
        return !node.has(field) || node.path(field).isNull() ? null : uuid(node, field);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw new SourceApiException(field + " must be numeric");
        }
        return value.decimalValue();
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.canConvertToLong() || value.longValue() < 0) {
            throw new SourceApiException(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw new SourceApiException(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private record Category(String code, String name) { }

    public static final class SourceApiException extends RuntimeException {
        public SourceApiException(String message) {
            super(message);
        }

        public SourceApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
