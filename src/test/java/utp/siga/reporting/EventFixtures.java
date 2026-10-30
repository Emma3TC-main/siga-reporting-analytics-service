package utp.siga.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class EventFixtures {

    private EventFixtures() {
    }

    public static ObjectNode movement(
            ObjectMapper mapper, UUID eventId, UUID movementId, UUID balanceId, UUID productId,
            UUID locationId, String type, String confirmedAt, BigDecimal quantity, BigDecimal avgCost) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", "MovementConfirmed");
        root.put("schemaVersion", 1);
        root.put("aggregateType", "Movement");
        root.put("aggregateId", movementId.toString());
        root.put("occurredAt", confirmedAt);
        root.put("correlationId", UUID.randomUUID().toString());
        root.put("producer", "inventory-service");
        ObjectNode payload = root.putObject("payload");
        ObjectNode movement = payload.putObject("movement");
        movement.put("id", movementId.toString());
        movement.put("movementCode", "MOV-2026-00001");
        movement.put("movementType", type);
        movement.put("status", "CONFIRMED");
        movement.put("registeredByUserId", UUID.randomUUID().toString());
        movement.put("confirmedByUserId", UUID.randomUUID().toString());
        movement.put("isSensitive", type.startsWith("ADJUST"));
        movement.put("reason", type.startsWith("ADJUST") ? "Conteo físico" : "Recepción");
        movement.put("confirmedAt", confirmedAt);
        movement.put("totalBaseCostPen", quantity.multiply(avgCost));
        ArrayNode details = payload.putArray("details");
        ObjectNode detail = details.addObject();
        detail.put("id", UUID.randomUUID().toString());
        detail.put("lineNumber", 1);
        detail.put("productId", productId.toString());
        detail.put("enteredQuantity", quantity);
        detail.put("unitCode", "PZA");
        detail.put("conversionFactor", new BigDecimal("1.000000"));
        detail.put("stockQuantity", quantity);
        ArrayNode balances = payload.putArray("stockBalances");
        ObjectNode balance = balances.addObject();
        balance.put("stockBalanceId", balanceId.toString());
        balance.put("productId", productId.toString());
        balance.put("locationId", locationId.toString());
        balance.put("quantity", quantity);
        balance.put("avgCostPen", avgCost);
        balance.put("minStock", new BigDecimal("5.000000"));
        return root;
    }

    public static ObjectNode product(
            ObjectMapper mapper, UUID eventId, UUID productId, long version, String sku) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", eventId.toString());
        root.put("eventType", "ProductUpdated");
        root.put("schemaVersion", 1);
        root.put("aggregateType", "Product");
        root.put("aggregateId", productId.toString());
        root.put("occurredAt", Instant.parse("2026-10-16T12:00:00Z").toString());
        root.put("correlationId", UUID.randomUUID().toString());
        root.put("producer", "catalog-service");
        ObjectNode payload = root.putObject("payload");
        ObjectNode product = payload.putObject("product");
        product.put("id", productId.toString());
        product.put("sku", sku);
        product.put("name", "Producto " + sku);
        product.put("categoryId", UUID.randomUUID().toString());
        product.put("categoryCode", "CAT-01");
        product.put("categoryName", "Categoría");
        product.put("productType", "MATERIAL");
        product.put("storageUnitCode", "PZA");
        product.put("baseUnitCode", "PZA");
        product.put("minStock", new BigDecimal("5.000000"));
        product.put("requiresLot", false);
        product.put("requiresHeatNumber", false);
        product.put("requiresExpiry", false);
        product.put("requiresSerial", false);
        product.put("active", true);
        product.put("version", version);
        payload.putArray("conversions");
        return root;
    }
}
