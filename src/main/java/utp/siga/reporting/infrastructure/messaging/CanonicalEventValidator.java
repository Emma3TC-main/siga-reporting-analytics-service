package utp.siga.reporting.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import utp.siga.reporting.application.command.MovementConfirmedCommand;
import utp.siga.reporting.application.command.ProductUpdatedCommand;
import utp.siga.reporting.domain.model.InventoryProjection;
import utp.siga.reporting.domain.model.MovementProjection;
import utp.siga.reporting.domain.model.MovementType;
import utp.siga.reporting.domain.model.ProductProjection;

@Component
public class CanonicalEventValidator {

    public static final String MOVEMENT_ROUTING_KEY = "inventory.movement.confirmed.v1";
    public static final String PRODUCT_ROUTING_KEY = "catalog.product.updated.v1";
    private static final BigDecimal QUANTUM = new BigDecimal("0.000001");
    private static final Set<String> PRODUCT_TYPES = Set.of("MATERIAL", "INSUMO", "REPUESTO", "MAQUINARIA");

    public Object validate(String routingKey, JsonNode envelope) {
        return switch (routingKey) {
            case MOVEMENT_ROUTING_KEY -> movement(envelope);
            case PRODUCT_ROUTING_KEY -> product(envelope);
            default -> throw new IllegalArgumentException("Unsupported routing key: " + routingKey);
        };
    }

    private MovementConfirmedCommand movement(JsonNode envelope) {
        Envelope common = envelope(envelope, "MovementConfirmed", "Movement", "inventory-service");
        JsonNode payload = object(envelope.path("payload"), "payload");
        JsonNode movement = object(payload.path("movement"), "payload.movement");
        UUID movementId = uuid(movement, "id");
        if (!movementId.equals(common.aggregateId())) {
            throw new IllegalArgumentException("aggregateId must equal payload.movement.id");
        }
        MovementType type = enumValue(MovementType.class, text(movement, "movementType"), "movementType");
        if (!"CONFIRMED".equals(text(movement, "status"))) {
            throw new IllegalArgumentException("movement.status must be CONFIRMED");
        }
        boolean sensitive = bool(movement, "isSensitive");
        MovementProjection projection = new MovementProjection(
                movementId, text(movement, "movementCode"), type,
                uuid(movement, "registeredByUserId"), uuid(movement, "confirmedByUserId"),
                optionalUuid(movement, "requestedByUserId"), optionalUuid(movement, "authorizedByUserId"),
                optionalText(movement, "costCenterCode"), optionalText(movement, "costCenterNameSnapshot"),
                optionalUuid(movement, "supplierId"), optionalText(movement, "supplierNameSnapshot"),
                optionalText(movement, "supplierTaxIdSnapshot"), optionalText(movement, "referenceDocType"),
                optionalText(movement, "referenceDocNumber"), optionalText(movement, "reason"), sensitive,
                optionalText(movement, "sensitivityReason"),
                decimal(movement, "totalBaseCostPen", false), instant(movement, "confirmedAt"),
                common.eventId(), common.occurredAt());

        validateDetails(payload.path("details"));
        JsonNode stock = array(payload.path("stockBalances"), "payload.stockBalances", true);
        List<InventoryProjection> balances = new ArrayList<>();
        for (JsonNode node : stock) {
            balances.add(new InventoryProjection(
                    uuid(node, "stockBalanceId"), uuid(node, "productId"), uuid(node, "locationId"),
                    optionalUuid(node, "lotId"), optionalUuid(node, "assetId"),
                    decimal(node, "quantity", false), decimal(node, "avgCostPen", false),
                    decimal(node, "minStock", false), common.eventId(), common.occurredAt()));
        }
        return new MovementConfirmedCommand(
                common.eventId(), common.correlationId(), common.occurredAt(), projection, List.copyOf(balances));
    }

    private ProductUpdatedCommand product(JsonNode envelope) {
        Envelope common = envelope(envelope, "ProductUpdated", "Product", "catalog-service");
        JsonNode payload = object(envelope.path("payload"), "payload");
        JsonNode product = object(payload.path("product"), "payload.product");
        UUID productId = uuid(product, "id");
        if (!productId.equals(common.aggregateId())) {
            throw new IllegalArgumentException("aggregateId must equal payload.product.id");
        }
        String productType = text(product, "productType");
        if (!PRODUCT_TYPES.contains(productType)) {
            throw new IllegalArgumentException("Unsupported productType: " + productType);
        }
        long version = nonNegativeLong(product, "version");
        ProductProjection projection = new ProductProjection(
                productId, boundedText(product, "sku", 80), boundedText(product, "name", 200),
                boundedText(product, "categoryCode", 50), boundedText(product, "categoryName", 120),
                productType, boundedText(product, "baseUnitCode", 20),
                boundedText(product, "storageUnitCode", 20), decimal(product, "minStock", false),
                bool(product, "active"), version, common.eventId(), common.occurredAt());

        JsonNode conversions = array(payload.path("conversions"), "payload.conversions", false);
        for (JsonNode conversion : conversions) {
            boundedText(conversion, "fromUnitCode", 20);
            boundedText(conversion, "toUnitCode", 20);
            decimal(conversion, "factor", true);
            bool(conversion, "active");
        }
        requiredBoolean(product, "requiresLot");
        requiredBoolean(product, "requiresHeatNumber");
        requiredBoolean(product, "requiresExpiry");
        requiredBoolean(product, "requiresSerial");
        uuid(product, "categoryId");
        return new ProductUpdatedCommand(common.eventId(), common.correlationId(), common.occurredAt(), projection);
    }

    private static Envelope envelope(
            JsonNode envelope, String eventType, String aggregateType, String producer) {
        object(envelope, "event envelope");
        UUID eventId = uuid(envelope, "eventId");
        if (!eventType.equals(text(envelope, "eventType"))) {
            throw new IllegalArgumentException("Unsupported eventType");
        }
        if (nonNegativeLong(envelope, "schemaVersion") != 1) {
            throw new IllegalArgumentException("Unsupported schemaVersion");
        }
        if (!aggregateType.equals(text(envelope, "aggregateType"))) {
            throw new IllegalArgumentException("Unsupported aggregateType");
        }
        UUID aggregateId = uuid(envelope, "aggregateId");
        Instant occurredAt = instant(envelope, "occurredAt");
        UUID correlationId = uuid(envelope, "correlationId");
        if (!producer.equals(text(envelope, "producer"))) {
            throw new IllegalArgumentException("Unsupported producer");
        }
        if (envelope.has("causationId") && !envelope.path("causationId").isNull()) {
            uuid(envelope, "causationId");
        }
        return new Envelope(eventId, aggregateId, correlationId, occurredAt);
    }

    private static void validateDetails(JsonNode node) {
        JsonNode details = array(node, "payload.details", true);
        for (JsonNode detail : details) {
            uuid(detail, "id");
            if (nonNegativeLong(detail, "lineNumber") < 1) {
                throw new IllegalArgumentException("lineNumber must be at least 1");
            }
            uuid(detail, "productId");
            decimal(detail, "enteredQuantity", true);
            text(detail, "unitCode");
            decimal(detail, "conversionFactor", true);
            decimal(detail, "stockQuantity", true);
            optionalUuid(detail, "originLocationId");
            optionalUuid(detail, "destinationLocationId");
            optionalUuid(detail, "lotId");
            optionalUuid(detail, "assetId");
            optionalDecimal(detail, "baseUnitCostPen");
            optionalDecimal(detail, "appliedAvgCostPen");
        }
    }

    private static JsonNode object(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return node;
    }

    private static JsonNode array(JsonNode node, String field, boolean nonEmpty) {
        if (node == null || !node.isArray() || (nonEmpty && node.isEmpty())) {
            throw new IllegalArgumentException(field + " must be " + (nonEmpty ? "a non-empty" : "an") + " array");
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.asText();
    }

    private static String boundedText(JsonNode node, String field, int max) {
        String value = text(node, field);
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " exceeds maximum length " + max);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        return !node.has(field) || node.path(field).isNull() ? null : text(node, field);
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " must be a UUID", ex);
        }
    }

    private static UUID optionalUuid(JsonNode node, String field) {
        return !node.has(field) || node.path(field).isNull() ? null : uuid(node, field);
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " must be an RFC3339 UTC instant", ex);
        }
    }

    private static BigDecimal decimal(JsonNode node, String field, boolean positive) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        BigDecimal number = value.decimalValue();
        if (positive ? number.signum() <= 0 : number.signum() < 0) {
            throw new IllegalArgumentException(field + (positive ? " must be positive" : " must be non-negative"));
        }
        if (number.remainder(QUANTUM).compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException(field + " exceeds six decimal places");
        }
        return number;
    }

    private static void optionalDecimal(JsonNode node, String field) {
        if (node.has(field) && !node.path(field).isNull()) {
            decimal(node, field, false);
        }
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.booleanValue();
    }

    private static void requiredBoolean(JsonNode node, String field) {
        bool(node, field);
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported " + field + ": " + value, ex);
        }
    }

    private record Envelope(UUID eventId, UUID aggregateId, UUID correlationId, Instant occurredAt) { }
}
