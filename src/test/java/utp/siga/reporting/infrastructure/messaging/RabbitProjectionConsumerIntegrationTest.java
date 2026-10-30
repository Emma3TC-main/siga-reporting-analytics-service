package utp.siga.reporting.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import utp.siga.reporting.EventFixtures;
import utp.siga.reporting.ReportingIntegrationEnvironment;
import utp.siga.reporting.application.usecase.ProjectionEventService;

@SpringBootTest(properties = "reporting.consumer.enabled=true")
class RabbitProjectionConsumerIntegrationTest extends ReportingIntegrationEnvironment {

    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired RabbitConsumerControl consumerControl;
    @SpyBean ProjectionEventService service;

    @BeforeEach
    void clear() {
        jdbc.execute("DELETE FROM analytics.processed_event");
        jdbc.execute("DELETE FROM analytics.movement_projection");
        jdbc.execute("DELETE FROM analytics.inventory_projection");
        jdbc.execute("DELETE FROM analytics.product_projection");
        while (rabbit.receive(QUEUE, 50) != null) { }
        while (rabbit.receive(QUEUE + ".dlq", 50) != null) { }
    }

    @Test
    void consumesCanonicalMovementAndAcknowledgesAfterCommit() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        publish(MOVEMENT_KEY, EventFixtures.movement(mapper, eventId, movementId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "ENTRY", "2026-10-16T12:00:00Z",
                new BigDecimal("4.000000"), new BigDecimal("5.000000")));
        await("SELECT count(*) FROM analytics.processed_event WHERE event_id=?", 1, eventId);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM analytics.movement_projection WHERE movement_id=?", Long.class, movementId));
        assertNull(rabbit.receive(QUEUE, 100));
    }

    @Test
    void consumesProductAndIgnoresDuplicateAndStaleVersions() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID firstEvent = UUID.randomUUID();
        ObjectNode versionTwo = EventFixtures.product(mapper, firstEvent, productId, 2, "V2");
        publish(PRODUCT_KEY, versionTwo);
        await("SELECT count(*) FROM analytics.processed_event WHERE event_id=?", 1, firstEvent);
        publish(PRODUCT_KEY, versionTwo);
        Thread.sleep(250);
        UUID staleEvent = UUID.randomUUID();
        publish(PRODUCT_KEY, EventFixtures.product(mapper, staleEvent, productId, 1, "V1"));
        await("SELECT count(*) FROM analytics.processed_event WHERE event_id=?", 1, staleEvent);
        assertEquals("V2", jdbc.queryForObject(
                "SELECT sku FROM analytics.product_projection WHERE product_id=?", String.class, productId));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT count(*) FROM analytics.processed_event", Long.class));
    }

    @Test
    void transientFailureRetriesSameEventThenCommitsAndAcks() throws Exception {
        UUID eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("transient test failure"))
                .doCallRealMethod()
                .when(service).process(any(utp.siga.reporting.application.command.ProductUpdatedCommand.class));
        publish(PRODUCT_KEY, EventFixtures.product(mapper, eventId, UUID.randomUUID(), 1, "RECOVER"));
        await("SELECT count(*) FROM analytics.processed_event WHERE event_id=?", 1, eventId);
        verify(service, timeout(5000).times(2))
                .process(any(utp.siga.reporting.application.command.ProductUpdatedCommand.class));
        assertNull(rabbit.receive(QUEUE, 100));
        assertNull(rabbit.receive(QUEUE + ".dlq", 100));
    }

    @Test
    void exhaustedRetryIsBoundedAndDeadLetteredWithoutFalseSuccess() throws Exception {
        UUID eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("persistent test failure"))
                .when(service).process(any(utp.siga.reporting.application.command.ProductUpdatedCommand.class));

        publish(PRODUCT_KEY, EventFixtures.product(mapper, eventId, UUID.randomUUID(), 1, "FAIL"));

        Message dead = receiveDeadLetter();
        assertNotNull(dead);
        verify(service, timeout(5000).times(3))
                .process(any(utp.siga.reporting.application.command.ProductUpdatedCommand.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM analytics.processed_event WHERE event_id=?", Long.class, eventId));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM analytics.product_projection", Long.class));
    }

    @Test
    void invalidMessageIsDeadLetteredWithoutProjection() throws Exception {
        ObjectNode invalid = EventFixtures.product(mapper, UUID.randomUUID(), UUID.randomUUID(), 1, "BAD");
        invalid.put("schemaVersion", 2);
        publish(PRODUCT_KEY, invalid);
        Message dead = receiveDeadLetter();
        assertNotNull(dead);
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM analytics.processed_event", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM analytics.product_projection", Long.class));
    }

    @Test
    void messagePublishedWhileConsumerIsPausedIsProcessedAfterResume() throws Exception {
        UUID eventId = UUID.randomUUID();
        consumerControl.pause();
        try {
            publish(PRODUCT_KEY, EventFixtures.product(mapper, eventId, UUID.randomUUID(), 1, "QUEUED"));
            Thread.sleep(250);
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT count(*) FROM analytics.processed_event WHERE event_id=?", Long.class, eventId));
        } finally {
            consumerControl.resume();
        }
        await("SELECT count(*) FROM analytics.processed_event WHERE event_id=?", 1, eventId);
    }

    private void publish(String routingKey, ObjectNode body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(EXCHANGE, routingKey,
                new Message(body.toString().getBytes(StandardCharsets.UTF_8), properties));
    }

    private Message receiveDeadLetter() {
        Message dead = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (dead == null && System.currentTimeMillis() < deadline) {
            dead = rabbit.receive(QUEUE + ".dlq", 100);
        }
        return dead;
    }

    private void await(String sql, long expected, Object... args) throws Exception {
        long deadline = System.currentTimeMillis() + 7000;
        long actual = -1;
        while (System.currentTimeMillis() < deadline) {
            actual = jdbc.queryForObject(sql, Long.class, args);
            if (actual == expected) {
                return;
            }
            Thread.sleep(40);
        }
        assertEquals(expected, actual);
    }
}
