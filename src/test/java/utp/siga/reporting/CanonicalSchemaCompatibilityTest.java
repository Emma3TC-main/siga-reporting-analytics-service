package utp.siga.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import utp.siga.reporting.infrastructure.messaging.CanonicalEventValidator;

class CanonicalSchemaCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalEventValidator validator = new CanonicalEventValidator();

    @Test
    void realFixturesMatchCanonicalEnvelopeConstantsAndRequiredFields() throws Exception {
        assertContract(
                "movement-confirmed-v1.schema.json", "MovementConfirmed", "Movement", "inventory-service",
                EventFixtures.movement(mapper, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), "ENTRY", "2026-10-16T00:00:00Z",
                        java.math.BigDecimal.ONE, java.math.BigDecimal.ONE),
                CanonicalEventValidator.MOVEMENT_ROUTING_KEY);
        assertContract(
                "product-updated-v1.schema.json", "ProductUpdated", "Product", "catalog-service",
                EventFixtures.product(mapper, UUID.randomUUID(), UUID.randomUUID(), 1, "SKU"),
                CanonicalEventValidator.PRODUCT_ROUTING_KEY);
    }

    private void assertContract(
            String file, String eventType, String aggregateType, String producer,
            JsonNode actual, String routingKey) throws Exception {
        Path path = Path.of("..", "siga-documentation", "SIGA_Documentacion_Tecnica_Final_v1.2", "api", file);
        assertTrue(Files.isRegularFile(path), "Canonical schema must be available: " + path);
        JsonNode schema = mapper.readTree(path.toFile());
        assertEquals(eventType, schema.path("properties").path("eventType").path("const").asText());
        assertEquals(aggregateType, schema.path("properties").path("aggregateType").path("const").asText());
        assertEquals(producer, schema.path("properties").path("producer").path("const").asText());
        Set<String> actualFields = new java.util.HashSet<>();
        actual.fieldNames().forEachRemaining(actualFields::add);
        for (JsonNode required : schema.path("required")) {
            assertTrue(actualFields.contains(required.asText()), "Missing canonical field " + required.asText());
        }
        validator.validate(routingKey, actual);
    }
}
