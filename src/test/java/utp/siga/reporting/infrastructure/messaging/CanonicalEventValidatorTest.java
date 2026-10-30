package utp.siga.reporting.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import utp.siga.reporting.EventFixtures;
import utp.siga.reporting.application.command.MovementConfirmedCommand;
import utp.siga.reporting.application.command.ProductUpdatedCommand;

class CanonicalEventValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CanonicalEventValidator validator = new CanonicalEventValidator();

    @Test
    void validatesCanonicalMovementConfirmed() {
        var event = EventFixtures.movement(mapper, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ENTRY",
                "2026-10-16T05:00:00Z", new BigDecimal("10.000000"), new BigDecimal("2.500000"));

        var command = assertInstanceOf(MovementConfirmedCommand.class,
                validator.validate(CanonicalEventValidator.MOVEMENT_ROUTING_KEY, event));
        assertEquals(1, command.stockBalances().size());
        assertEquals(new BigDecimal("10.000000"), command.stockBalances().getFirst().quantity());
    }

    @Test
    void validatesCanonicalProductUpdated() {
        var event = EventFixtures.product(mapper, UUID.randomUUID(), UUID.randomUUID(), 4, "SKU-4");
        var command = assertInstanceOf(ProductUpdatedCommand.class,
                validator.validate(CanonicalEventValidator.PRODUCT_ROUTING_KEY, event));
        assertEquals(4, command.product().sourceVersion());
    }

    @Test
    void rejectsUnknownRoutingAndMismatchedAggregate() {
        var event = EventFixtures.product(mapper, UUID.randomUUID(), UUID.randomUUID(), 1, "SKU");
        assertThrows(IllegalArgumentException.class, () -> validator.validate("unknown.route", event));
        event.put("aggregateId", UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(CanonicalEventValidator.PRODUCT_ROUTING_KEY, event));
    }

    @Test
    void rejectsIncompleteOrInvalidCanonicalPayloads() {
        var movement = EventFixtures.movement(mapper, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ENTRY",
                "2026-10-16T05:00:00Z", BigDecimal.ONE, BigDecimal.ONE);
        ((com.fasterxml.jackson.databind.node.ObjectNode) movement.path("payload").path("movement"))
                .remove("confirmedByUserId");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(CanonicalEventValidator.MOVEMENT_ROUTING_KEY, movement));

        var product = EventFixtures.product(mapper, UUID.randomUUID(), UUID.randomUUID(), 1, "SKU");
        ((com.fasterxml.jackson.databind.node.ObjectNode) product.path("payload").path("product"))
                .put("minStock", -1);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(CanonicalEventValidator.PRODUCT_ROUTING_KEY, product));
    }
}
