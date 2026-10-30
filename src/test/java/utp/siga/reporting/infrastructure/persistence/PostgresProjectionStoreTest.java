package utp.siga.reporting.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import utp.siga.reporting.EventFixtures;
import utp.siga.reporting.ReportingIntegrationEnvironment;
import utp.siga.reporting.application.command.MovementConfirmedCommand;
import utp.siga.reporting.application.command.ProductUpdatedCommand;
import utp.siga.reporting.application.dto.ReportingDtos.TrendBucket;
import utp.siga.reporting.application.port.in.ProjectionEventUseCase;
import utp.siga.reporting.domain.model.InventoryProjection;
import utp.siga.reporting.domain.model.ProductProjection;
import utp.siga.reporting.infrastructure.messaging.CanonicalEventValidator;

@SpringBootTest
class PostgresProjectionStoreTest extends ReportingIntegrationEnvironment {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectionEventUseCase events;
    @Autowired PostgresProjectionStore store;
    @Autowired ObjectMapper mapper;
    @Autowired CanonicalEventValidator validator;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clear() {
        jdbc.execute("DELETE FROM analytics.processed_event");
        jdbc.execute("DELETE FROM analytics.movement_projection");
        jdbc.execute("DELETE FROM analytics.inventory_projection");
        jdbc.execute("DELETE FROM analytics.product_projection");
    }

    @Test
    void movementAndAbsoluteBalanceArePersistedAtomically() {
        UUID eventId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID balanceId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var first = movement(eventId, movementId, balanceId, productId, new BigDecimal("8.000000"));
        assertTrue(events.process(first));

        var second = movement(UUID.randomUUID(), UUID.randomUUID(), balanceId, productId, new BigDecimal("3.000000"));
        assertTrue(events.process(second));
        assertEquals(new BigDecimal("3.000000"), jdbc.queryForObject(
                "SELECT quantity FROM analytics.inventory_projection WHERE stock_balance_id=?",
                BigDecimal.class, balanceId));
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM analytics.movement_projection", Long.class));
        assertEquals(2L, businessEventCount());
    }

    @Test
    void staleProductVersionCannotRegressProjection() {
        UUID productId = UUID.randomUUID();
        assertTrue(events.process(product(UUID.randomUUID(), productId, 5, "NEW")));
        assertTrue(events.process(product(UUID.randomUUID(), productId, 4, "OLD")));
        var row = jdbc.queryForMap(
                "SELECT sku,source_version FROM analytics.product_projection WHERE product_id=?", productId);
        assertEquals("NEW", row.get("sku"));
        assertEquals(5L, row.get("source_version"));
        assertEquals(2L, businessEventCount());
    }

    @Test
    void olderAbsoluteInventorySnapshotCannotRegressNewerBalance() {
        UUID balanceId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID newerEventId = UUID.randomUUID();
        var newer = movementAt(newerEventId, UUID.randomUUID(), balanceId, productId,
                new BigDecimal("3.000000"), "2026-10-16T06:00:00Z");
        var older = movementAt(UUID.randomUUID(), UUID.randomUUID(), balanceId, productId,
                new BigDecimal("9.000000"), "2026-10-16T05:00:00Z");

        assertTrue(events.process(newer));
        assertTrue(events.process(older));

        var balance = jdbc.queryForMap("""
                SELECT quantity, source_event_id, updated_at
                FROM analytics.inventory_projection WHERE stock_balance_id=?
                """, balanceId);
        assertEquals(new BigDecimal("3.000000"), balance.get("quantity"));
        assertEquals(newerEventId, balance.get("source_event_id"));
        assertEquals(Instant.parse("2026-10-16T06:00:00Z"),
                ((java.sql.Timestamp) balance.get("updated_at")).toInstant());
    }

    @Test
    void movementProjectionIsImmutableForASecondEventWithSameMovementId() {
        UUID movementId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID firstBalanceId = UUID.randomUUID();
        UUID firstEventId = UUID.randomUUID();
        var first = movementAt(firstEventId, movementId, firstBalanceId, productId,
                new BigDecimal("4.000000"), "2026-10-16T05:00:00Z");
        var conflicting = movementAt(UUID.randomUUID(), movementId, UUID.randomUUID(), productId,
                new BigDecimal("99.000000"), "2026-10-16T06:00:00Z");

        assertTrue(events.process(first));
        assertTrue(events.process(conflicting));

        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM analytics.movement_projection", Long.class));
        assertEquals(firstEventId, jdbc.queryForObject(
                "SELECT source_event_id FROM analytics.movement_projection WHERE movement_id=?",
                UUID.class, movementId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM analytics.inventory_projection", Long.class));
        assertEquals(new BigDecimal("4.000000"), jdbc.queryForObject(
                "SELECT quantity FROM analytics.inventory_projection WHERE stock_balance_id=?",
                BigDecimal.class, firstBalanceId));
        assertEquals(2L, businessEventCount());
    }

    @Test
    void concurrentDuplicateHasExactlyOneLogicalEffect() throws Exception {
        UUID eventId = UUID.randomUUID();
        var command = product(eventId, UUID.randomUUID(), 1, "ONE");
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task = () -> events.process(command);
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertEquals(1, List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM analytics.product_projection", Long.class));
        assertEquals(1L, businessEventCount());
    }

    @Test
    void concurrentProductVersionsCannotRegressTheNewestProjection() throws Exception {
        UUID productId = UUID.randomUUID();
        var older = product(UUID.randomUUID(), productId, 4, "OLD");
        var newer = product(UUID.randomUUID(), productId, 5, "NEW");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var olderResult = executor.submit(() -> {
                start.await();
                return events.process(older);
            });
            var newerResult = executor.submit(() -> {
                start.await();
                return events.process(newer);
            });
            start.countDown();
            assertTrue(olderResult.get());
            assertTrue(newerResult.get());
        }

        var row = jdbc.queryForMap(
                "SELECT sku, source_version FROM analytics.product_projection WHERE product_id=?", productId);
        assertEquals("NEW", row.get("sku"));
        assertEquals(5L, row.get("source_version"));
        assertEquals(2L, businessEventCount());
    }

    @Test
    void projectionFailureRollsBackClaimAndMovement() {
        var valid = movement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE);
        InventoryProjection invalid = new InventoryProjection(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null,
                new BigDecimal("-1.000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                valid.eventId(), valid.occurredAt());
        var command = new MovementConfirmedCommand(valid.eventId(), valid.correlationId(), valid.occurredAt(),
                valid.movement(), List.of(invalid));
        assertThrows(DataIntegrityViolationException.class, () -> events.process(command));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM analytics.processed_event", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM analytics.movement_projection", Long.class));
    }

    @Test
    void snapshotReplacementIsAtomicAndSupportsEmptyRebuild() {
        ProductProjection original = projection(UUID.randomUUID(), "ORIGINAL", 1);
        Instant snapshotStartedAt = Instant.parse("2026-10-16T10:00:00Z");
        store.replaceSnapshots(List.of(original), List.of(), snapshotStartedAt);
        ProductProjection duplicateA = projection(UUID.randomUUID(), "A", 1);
        ProductProjection duplicateB = new ProductProjection(
                duplicateA.productId(), "B", "B", "CAT", "Category", "MATERIAL", "PZA", "PZA",
                BigDecimal.ONE, true, 1, null, null);
        assertThrows(DataIntegrityViolationException.class,
                () -> store.replaceSnapshots(
                        List.of(duplicateA, duplicateB), List.of(), snapshotStartedAt.plusSeconds(1)));
        assertEquals("ORIGINAL", jdbc.queryForObject(
                "SELECT sku FROM analytics.product_projection", String.class));

        store.replaceSnapshots(List.of(), List.of(), snapshotStartedAt.plusSeconds(2));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM analytics.product_projection", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM analytics.inventory_projection", Long.class));
        assertNull(store.dashboard(
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"), TrendBucket.DAY).dataUpdatedAt());
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE c.relpersistence='t'
                  AND c.relname IN ('reporting_product_stage','reporting_inventory_stage')
                """, Long.class));
    }

    @Test
    void reconciliationWatermarkRejectsQueuedOlderSnapshotButAllowsNewerOne() {
        UUID productId = UUID.randomUUID();
        UUID balanceId = UUID.randomUUID();
        Instant snapshotStartedAt = Instant.parse("2026-10-16T06:00:00Z");
        InventoryProjection reconciled = new InventoryProjection(
                balanceId, productId, UUID.randomUUID(), null, null,
                new BigDecimal("10.000000"), BigDecimal.ONE, BigDecimal.ZERO, null, null);
        store.replaceSnapshots(List.of(projection(productId, "P", 1)), List.of(reconciled), snapshotStartedAt);

        assertTrue(events.process(movementAt(UUID.randomUUID(), UUID.randomUUID(), balanceId, productId,
                new BigDecimal("2.000000"), "2026-10-16T05:59:59Z")));
        assertEquals(new BigDecimal("10.000000"), jdbc.queryForObject(
                "SELECT quantity FROM analytics.inventory_projection WHERE stock_balance_id=?",
                BigDecimal.class, balanceId));

        assertTrue(events.process(movementAt(UUID.randomUUID(), UUID.randomUUID(), balanceId, productId,
                new BigDecimal("12.000000"), "2026-10-16T06:00:01Z")));
        assertEquals(new BigDecimal("12.000000"), jdbc.queryForObject(
                "SELECT quantity FROM analytics.inventory_projection WHERE stock_balance_id=?",
                BigDecimal.class, balanceId));
    }

    @Test
    void dashboardAndReportsUseCanonicalAggregationOrderingAndPrecision() {
        UUID productA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID productB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        store.replaceSnapshots(
                List.of(projection(productA, "A", 1), projection(productB, "B", 1)),
                List.of(new InventoryProjection(UUID.randomUUID(), productA, UUID.randomUUID(), null, null,
                        new BigDecimal("2.000000"), new BigDecimal("3.333333"), new BigDecimal("5.000000"), null, null)),
                Instant.parse("2026-10-16T00:00:00Z"));
        events.process(movement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), productA,
                new BigDecimal("2.000000")));
        MovementConfirmedCommand adjustment = movement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                productA, new BigDecimal("1.000000"));
        var adjustmentNode = EventFixtures.movement(mapper, adjustment.eventId(), adjustment.movement().movementId(),
                adjustment.stockBalances().getFirst().stockBalanceId(), productA,
                adjustment.stockBalances().getFirst().locationId(), "ADJUST_POSITIVE",
                "2026-10-16T08:00:00Z", BigDecimal.ONE, new BigDecimal("2.000000"));
        events.process((MovementConfirmedCommand) validator.validate(CanonicalEventValidator.MOVEMENT_ROUTING_KEY, adjustmentNode));

        var dashboard = store.dashboard(Instant.parse("2026-10-16T00:00:00Z"),
                Instant.parse("2026-10-17T00:00:00Z"), TrendBucket.DAY);
        assertEquals(1, dashboard.lowStockProductCount());
        assertEquals(1, dashboard.outOfStockProductCount());
        assertEquals(2, dashboard.confirmedMovementCount());
        assertEquals(1, dashboard.positiveAdjustmentCount());
        assertFalse(dashboard.movementTrend().isEmpty());
        assertEquals(1, store.adjustments(Instant.parse("2026-10-16T00:00:00Z"),
                Instant.parse("2026-10-17T00:00:00Z"), null, 0, 20).totalElements());
    }

    @Test
    void emptyProjectionsExposeNullFreshness() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");

        assertNull(store.dashboard(from, to, TrendBucket.DAY).dataUpdatedAt());
        assertNull(store.movements(from, to, null, null, 0, 20).dataUpdatedAt());
        assertNull(store.inventoryValuation(null, null, null, 0, 20).dataUpdatedAt());
        assertNull(store.adjustments(from, to, null, 0, 20).dataUpdatedAt());
    }

    @Test
    void inventoryValuationPreservesDecimalPrecisionAndStableTieOrdering() {
        UUID productA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID productB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        UUID balanceA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID balanceB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        store.replaceSnapshots(
                List.of(projection(productA, "A", 1), projection(productB, "B", 1)),
                List.of(
                        new InventoryProjection(balanceB, productB, UUID.randomUUID(), null, null,
                                new BigDecimal("2.000000"), new BigDecimal("3.333333"),
                                BigDecimal.ZERO, null, null),
                        new InventoryProjection(balanceA, productA, UUID.randomUUID(), null, null,
                                new BigDecimal("2.000000"), new BigDecimal("3.333333"),
                                BigDecimal.ZERO, null, null)),
                Instant.parse("2026-10-16T00:00:00Z"));

        var first = store.inventoryValuation(null, null, null, 0, 1);
        var second = store.inventoryValuation(null, null, null, 1, 1);
        assertEquals(balanceA, first.content().getFirst().stockBalanceId());
        assertEquals(balanceB, second.content().getFirst().stockBalanceId());
        assertEquals(new BigDecimal("6.666666000000"), first.content().getFirst().stockValuePen());
    }

    @Test
    void importantQueryPlansMatchTheFixedFiltersAndOrderingIndexes() {
        UUID productId = UUID.randomUUID();
        store.replaceSnapshots(
                List.of(projection(productId, "IDX", 1)),
                List.of(new InventoryProjection(UUID.randomUUID(), productId, UUID.randomUUID(), null, null,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, null, null)),
                Instant.parse("2026-10-16T00:00:00Z"));
        assertTrue(events.process(movement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), productId,
                BigDecimal.ONE)));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        List<String> movementPlan = transaction.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan=off");
            return jdbc.query("""
                    EXPLAIN SELECT movement_id FROM analytics.movement_projection
                    WHERE movement_type=? AND confirmed_at>=? AND confirmed_at<?
                    ORDER BY confirmed_at DESC, movement_id DESC LIMIT ? OFFSET ?
                    """, (rs, row) -> rs.getString(1), "ENTRY",
                    java.sql.Timestamp.from(Instant.parse("2026-10-16T00:00:00Z")),
                    java.sql.Timestamp.from(Instant.parse("2026-10-17T00:00:00Z")), 20, 0L);
        });
        List<String> valuationPlan = transaction.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan=off");
            return jdbc.query("""
                    EXPLAIN SELECT stock_balance_id, quantity * avg_cost_pen AS stock_value_pen
                    FROM analytics.inventory_projection
                    ORDER BY stock_value_pen DESC, stock_balance_id ASC LIMIT ? OFFSET ?
                    """, (rs, row) -> rs.getString(1), 20, 0L);
        });

        assertTrue(String.join("\n", movementPlan).contains("idx_movement_projection_type_confirmed"));
        assertTrue(String.join("\n", valuationPlan).contains("idx_inventory_projection_value"));
    }

    @Test
    void dateRangeIsHalfOpenAndDayMonthBucketsUseLimaBoundaries() {
        UUID productId = UUID.randomUUID();
        UUID atFrom = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID alsoAtFrom = UUID.fromString("00000000-0000-0000-0000-000000000015");
        UUID atTo = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID previousLimaDay = UUID.fromString("00000000-0000-0000-0000-000000000030");

        processMovement(previousLimaDay, productId, "2026-10-01T04:59:59Z");
        processMovement(atFrom, productId, "2026-10-01T05:00:00Z");
        processMovement(alsoAtFrom, productId, "2026-10-01T05:00:00Z");
        processMovement(atTo, productId, "2026-11-01T05:00:00Z");

        var firstPage = store.movements(
                Instant.parse("2026-10-01T05:00:00Z"),
                Instant.parse("2026-11-01T05:00:00Z"), null, null, 0, 1);
        var secondPage = store.movements(
                Instant.parse("2026-10-01T05:00:00Z"),
                Instant.parse("2026-11-01T05:00:00Z"), null, null, 1, 1);
        assertEquals(2, firstPage.totalElements());
        assertEquals(2, firstPage.totalPages());
        assertEquals(alsoAtFrom, firstPage.content().getFirst().movementId());
        assertEquals(atFrom, secondPage.content().getFirst().movementId());

        var days = store.dashboard(
                Instant.parse("2026-10-01T04:00:00Z"),
                Instant.parse("2026-10-02T06:00:00Z"), TrendBucket.DAY).movementTrend();
        assertEquals(List.of(
                Instant.parse("2026-09-30T05:00:00Z"),
                Instant.parse("2026-10-01T05:00:00Z")),
                days.stream().map(bucket -> bucket.periodStart()).toList());

        var months = store.dashboard(
                Instant.parse("2026-10-01T04:00:00Z"),
                Instant.parse("2026-11-02T00:00:00Z"), TrendBucket.MONTH).movementTrend();
        assertEquals(List.of(
                Instant.parse("2026-09-01T05:00:00Z"),
                Instant.parse("2026-10-01T05:00:00Z"),
                Instant.parse("2026-11-01T05:00:00Z")),
                months.stream().map(bucket -> bucket.periodStart()).toList());
    }

    private void processMovement(UUID movementId, UUID productId, String confirmedAt) {
        var event = EventFixtures.movement(mapper, UUID.randomUUID(), movementId, UUID.randomUUID(),
                productId, UUID.randomUUID(), "ENTRY", confirmedAt,
                BigDecimal.ONE, BigDecimal.ONE);
        events.process((MovementConfirmedCommand) validator.validate(
                CanonicalEventValidator.MOVEMENT_ROUTING_KEY, event));
    }

    private MovementConfirmedCommand movement(
            UUID eventId, UUID movementId, UUID balanceId, UUID productId, BigDecimal quantity) {
        return movementAt(eventId, movementId, balanceId, productId, quantity, "2026-10-16T05:00:00Z");
    }

    private MovementConfirmedCommand movementAt(
            UUID eventId, UUID movementId, UUID balanceId, UUID productId,
            BigDecimal quantity, String occurredAt) {
        var node = EventFixtures.movement(mapper, eventId, movementId, balanceId, productId, UUID.randomUUID(),
                "ENTRY", occurredAt, quantity, new BigDecimal("2.000000"));
        return (MovementConfirmedCommand) validator.validate(CanonicalEventValidator.MOVEMENT_ROUTING_KEY, node);
    }

    private ProductUpdatedCommand product(UUID eventId, UUID productId, long version, String sku) {
        return (ProductUpdatedCommand) validator.validate(CanonicalEventValidator.PRODUCT_ROUTING_KEY,
                EventFixtures.product(mapper, eventId, productId, version, sku));
    }

    private ProductProjection projection(UUID id, String sku, long version) {
        return new ProductProjection(id, sku, "Product " + sku, "CAT", "Category", "MATERIAL",
                "PZA", "PZA", new BigDecimal("5.000000"), true, version, null, null);
    }

    private long businessEventCount() {
        return jdbc.queryForObject("SELECT count(*) FROM analytics.processed_event", Long.class);
    }
}
