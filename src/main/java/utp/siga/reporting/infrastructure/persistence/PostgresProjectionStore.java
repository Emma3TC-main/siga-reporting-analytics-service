package utp.siga.reporting.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.reporting.application.dto.ReportingDtos.AdjustmentReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.CriticalProduct;
import utp.siga.reporting.application.dto.ReportingDtos.DashboardResponse;
import utp.siga.reporting.application.dto.ReportingDtos.InventoryValuationItem;
import utp.siga.reporting.application.dto.ReportingDtos.InventoryValuationPage;
import utp.siga.reporting.application.dto.ReportingDtos.MovementReportItem;
import utp.siga.reporting.application.dto.ReportingDtos.MovementReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.MovementTrendBucket;
import utp.siga.reporting.application.dto.ReportingDtos.Period;
import utp.siga.reporting.application.dto.ReportingDtos.TrendBucket;
import utp.siga.reporting.application.port.out.ProjectionStore;
import utp.siga.reporting.domain.model.InventoryProjection;
import utp.siga.reporting.domain.model.MovementProjection;
import utp.siga.reporting.domain.model.MovementType;
import utp.siga.reporting.domain.model.ProductProjection;

@Repository
public class PostgresProjectionStore implements ProjectionStore {

    private static final String TIME_ZONE = "America/Lima";
    private static final String MOVEMENT_SELECT = """
            SELECT movement_id, movement_code, movement_type, registered_by_user_id,
                   confirmed_by_user_id, authorized_by_user_id, requested_by_user_id,
                   cost_center_code, cost_center_name_snapshot, supplier_id,
                   supplier_name_snapshot, reference_doc_type, reference_doc_number,
                   reason, sensitive, total_base_cost_pen, confirmed_at
            FROM analytics.movement_projection
            """;

    private final JdbcTemplate jdbc;

    public PostgresProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public boolean claim(UUID eventId, String consumer) {
        return jdbc.update("""
                INSERT INTO analytics.processed_event(event_id, consumer)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, eventId, consumer) == 1;
    }

    @Override
    public boolean insertMovement(MovementProjection movement) {
        return jdbc.update("""
                INSERT INTO analytics.movement_projection(
                    movement_id, movement_code, movement_type, status,
                    registered_by_user_id, confirmed_by_user_id, requested_by_user_id,
                    authorized_by_user_id, cost_center_code, cost_center_name_snapshot,
                    supplier_id, supplier_name_snapshot, supplier_tax_id_snapshot,
                    reference_doc_type, reference_doc_number, reason, sensitive,
                    sensitivity_reason, total_base_cost_pen, confirmed_at, source_event_id, updated_at)
                VALUES (?,?,?,'CONFIRMED',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
                ON CONFLICT (movement_id) DO NOTHING
                """,
                movement.movementId(), movement.movementCode(), movement.movementType().name(),
                movement.registeredByUserId(), movement.confirmedByUserId(), movement.requestedByUserId(),
                movement.authorizedByUserId(), movement.costCenterCode(), movement.costCenterNameSnapshot(),
                movement.supplierId(), movement.supplierNameSnapshot(), movement.supplierTaxIdSnapshot(),
                movement.referenceDocType(), movement.referenceDocNumber(), movement.reason(), movement.sensitive(),
                movement.sensitivityReason(), movement.totalBaseCostPen(), Timestamp.from(movement.confirmedAt()),
                movement.sourceEventId()) == 1;
    }

    @Override
    public void upsertInventory(List<InventoryProjection> balances) {
        jdbc.batchUpdate("""
                INSERT INTO analytics.inventory_projection(
                    stock_balance_id, product_id, location_id, lot_id, asset_id,
                    quantity, avg_cost_pen, min_stock, source_event_id, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (stock_balance_id) DO UPDATE SET
                    product_id=EXCLUDED.product_id,
                    location_id=EXCLUDED.location_id,
                    lot_id=EXCLUDED.lot_id,
                    asset_id=EXCLUDED.asset_id,
                    quantity=EXCLUDED.quantity,
                    avg_cost_pen=EXCLUDED.avg_cost_pen,
                    min_stock=EXCLUDED.min_stock,
                    source_event_id=EXCLUDED.source_event_id,
                    updated_at=EXCLUDED.updated_at
                WHERE EXCLUDED.updated_at >= analytics.inventory_projection.updated_at
                """, batch(balances, this::setInventory));
    }

    @Override
    public boolean upsertProduct(ProductProjection product) {
        return jdbc.update("""
                INSERT INTO analytics.product_projection(
                    product_id, sku, name, category_code, category_name, product_type,
                    base_unit_code, storage_unit_code, min_stock, active, source_version,
                    source_event_id, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,now())
                ON CONFLICT (product_id) DO UPDATE SET
                    sku=EXCLUDED.sku,
                    name=EXCLUDED.name,
                    category_code=EXCLUDED.category_code,
                    category_name=EXCLUDED.category_name,
                    product_type=EXCLUDED.product_type,
                    base_unit_code=EXCLUDED.base_unit_code,
                    storage_unit_code=EXCLUDED.storage_unit_code,
                    min_stock=EXCLUDED.min_stock,
                    active=EXCLUDED.active,
                    source_version=EXCLUDED.source_version,
                    source_event_id=EXCLUDED.source_event_id,
                    updated_at=now()
                WHERE EXCLUDED.source_version > analytics.product_projection.source_version
                """,
                product.productId(), product.sku(), product.name(), product.categoryCode(), product.categoryName(),
                product.productType(), product.baseUnitCode(), product.storageUnitCode(), product.minStock(),
                product.active(), product.sourceVersion(), product.sourceEventId()) == 1;
    }

    @Override
    public DashboardResponse dashboard(Instant from, Instant to, TrendBucket bucket) {
        BigDecimal valuation = jdbc.queryForObject(
                "SELECT COALESCE(sum(quantity * avg_cost_pen), 0) FROM analytics.inventory_projection",
                BigDecimal.class);

        Map<String, Long> stockCounts = jdbc.queryForObject("""
                WITH totals AS (
                    SELECT p.product_id, p.min_stock, COALESCE(sum(i.quantity), 0) AS quantity
                    FROM analytics.product_projection p
                    LEFT JOIN analytics.inventory_projection i ON i.product_id = p.product_id
                    WHERE p.active = true
                    GROUP BY p.product_id, p.min_stock
                )
                SELECT count(*) FILTER (WHERE quantity > 0 AND quantity <= min_stock) AS low_count,
                       count(*) FILTER (WHERE quantity = 0) AS out_count
                FROM totals
                """, (rs, row) -> Map.of("low", rs.getLong("low_count"), "out", rs.getLong("out_count")));

        Map<MovementType, Long> counts = new HashMap<>();
        jdbc.query("""
                SELECT movement_type, count(*) AS total
                FROM analytics.movement_projection
                WHERE confirmed_at >= ? AND confirmed_at < ?
                GROUP BY movement_type
                """, (RowCallbackHandler) rs -> counts.put(
                        MovementType.valueOf(rs.getString("movement_type")), rs.getLong("total")),
                Timestamp.from(from), Timestamp.from(to));

        String unit = bucket == TrendBucket.DAY ? "day" : "month";
        List<MovementTrendBucket> trend = jdbc.query("""
                SELECT date_trunc(?, confirmed_at AT TIME ZONE 'America/Lima')
                           AT TIME ZONE 'America/Lima' AS period_start,
                       count(*) FILTER (WHERE movement_type='ENTRY') AS entries,
                       count(*) FILTER (WHERE movement_type='EXIT') AS exits,
                       count(*) FILTER (WHERE movement_type='TRANSFER') AS transfers,
                       count(*) FILTER (WHERE movement_type='ADJUST_POSITIVE') AS positive_adjustments,
                       count(*) FILTER (WHERE movement_type='ADJUST_NEGATIVE') AS negative_adjustments
                FROM analytics.movement_projection
                WHERE confirmed_at >= ? AND confirmed_at < ?
                GROUP BY period_start
                ORDER BY period_start ASC
                """, (rs, row) -> new MovementTrendBucket(
                        instant(rs, "period_start"), rs.getLong("entries"), rs.getLong("exits"),
                        rs.getLong("transfers"), rs.getLong("positive_adjustments"),
                        rs.getLong("negative_adjustments")),
                unit, Timestamp.from(from), Timestamp.from(to));

        List<CriticalProduct> critical = jdbc.query("""
                SELECT p.product_id, p.sku, p.name, p.category_code,
                       COALESCE(sum(i.quantity), 0) AS quantity, p.min_stock,
                       GREATEST(p.min_stock - COALESCE(sum(i.quantity), 0), 0) AS deficit
                FROM analytics.product_projection p
                LEFT JOIN analytics.inventory_projection i ON i.product_id = p.product_id
                WHERE p.active = true
                GROUP BY p.product_id, p.sku, p.name, p.category_code, p.min_stock
                HAVING COALESCE(sum(i.quantity), 0) <= p.min_stock
                ORDER BY deficit DESC, p.product_id ASC
                LIMIT 10
                """, (rs, row) -> new CriticalProduct(
                        uuid(rs, "product_id"), rs.getString("sku"), rs.getString("name"),
                        rs.getString("category_code"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("min_stock"), rs.getBigDecimal("deficit")));

        long entry = counts.getOrDefault(MovementType.ENTRY, 0L);
        long exit = counts.getOrDefault(MovementType.EXIT, 0L);
        long transfer = counts.getOrDefault(MovementType.TRANSFER, 0L);
        long positive = counts.getOrDefault(MovementType.ADJUST_POSITIVE, 0L);
        long negative = counts.getOrDefault(MovementType.ADJUST_NEGATIVE, 0L);
        return new DashboardResponse(
                new Period(from, to, TIME_ZONE), freshness(), zero(valuation),
                stockCounts.get("low"), stockCounts.get("out"),
                entry + exit + transfer + positive + negative,
                entry, exit, transfer, positive, negative, trend, critical);
    }

    @Override
    public MovementReportPage movements(
            Instant from, Instant to, MovementType type, String costCenterCode, int page, int size) {
        Query query = movementQuery(from, to, type, costCenterCode, false);
        long total = count(query);
        List<Object> args = new ArrayList<>(query.args());
        args.add(size);
        args.add(offset(page, size));
        List<MovementReportItem> content = jdbc.query(
                MOVEMENT_SELECT + query.where() + " ORDER BY confirmed_at DESC, movement_id DESC LIMIT ? OFFSET ?",
                this::movementRow, args.toArray());
        return new MovementReportPage(content, page, size, total, pages(total, size),
                new Period(from, to, TIME_ZONE), freshness());
    }

    @Override
    public InventoryValuationPage inventoryValuation(
            UUID productId, String categoryCode, UUID locationId, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        add(where, args, " AND i.product_id = ?", productId);
        add(where, args, " AND p.category_code = ?", categoryCode);
        add(where, args, " AND i.location_id = ?", locationId);
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM analytics.inventory_projection i
                JOIN analytics.product_projection p ON p.product_id=i.product_id
                """ + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset(page, size));
        List<InventoryValuationItem> content = jdbc.query("""
                SELECT i.stock_balance_id, i.product_id, p.sku, p.name, p.category_code,
                       p.category_name, p.product_type, i.location_id, i.lot_id, i.asset_id,
                       i.quantity, i.avg_cost_pen, i.quantity * i.avg_cost_pen AS stock_value_pen,
                       p.min_stock
                FROM analytics.inventory_projection i
                JOIN analytics.product_projection p ON p.product_id=i.product_id
                """ + where + " ORDER BY stock_value_pen DESC, i.stock_balance_id ASC LIMIT ? OFFSET ?",
                this::inventoryRow, pageArgs.toArray());
        return new InventoryValuationPage(content, page, size, total, pages(total, size), freshness());
    }

    @Override
    public AdjustmentReportPage adjustments(
            Instant from, Instant to, MovementType type, int page, int size) {
        Query query = movementQuery(from, to, type, null, true);
        long total = count(query);
        List<Object> args = new ArrayList<>(query.args());
        args.add(size);
        args.add(offset(page, size));
        List<MovementReportItem> content = jdbc.query(
                MOVEMENT_SELECT + query.where() + " ORDER BY confirmed_at DESC, movement_id DESC LIMIT ? OFFSET ?",
                this::movementRow, args.toArray());
        return new AdjustmentReportPage(content, page, size, total, pages(total, size),
                new Period(from, to, TIME_ZONE), freshness());
    }

    @Override
    @Transactional
    public Instant replaceSnapshots(
            List<ProductProjection> products, List<InventoryProjection> balances, Instant snapshotStartedAt) {
        Objects.requireNonNull(snapshotStartedAt, "snapshotStartedAt");
        jdbc.execute("CREATE TEMP TABLE reporting_product_stage "
                + "(LIKE analytics.product_projection INCLUDING DEFAULTS) ON COMMIT DROP");
        jdbc.execute("CREATE TEMP TABLE reporting_inventory_stage "
                + "(LIKE analytics.inventory_projection INCLUDING DEFAULTS) ON COMMIT DROP");

        jdbc.batchUpdate("""
                INSERT INTO reporting_product_stage(product_id,sku,name,category_code,category_name,
                    product_type,base_unit_code,storage_unit_code,min_stock,active,source_version,
                    source_event_id,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, batch(products, (statement, product) -> setProduct(statement, product, snapshotStartedAt)));
        jdbc.batchUpdate("""
                INSERT INTO reporting_inventory_stage(stock_balance_id,product_id,location_id,lot_id,
                    asset_id,quantity,avg_cost_pen,min_stock,source_event_id,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, batch(balances,
                (statement, balance) -> setInventory(statement, withTimestamp(balance, snapshotStartedAt))));

        jdbc.execute("DELETE FROM analytics.inventory_projection");
        jdbc.execute("DELETE FROM analytics.product_projection");
        jdbc.execute("""
                INSERT INTO analytics.product_projection(
                    product_id,sku,name,category_code,category_name,product_type,base_unit_code,
                    storage_unit_code,min_stock,active,source_version,source_event_id,updated_at)
                SELECT product_id,sku,name,category_code,category_name,product_type,base_unit_code,
                    storage_unit_code,min_stock,active,source_version,source_event_id,updated_at
                FROM reporting_product_stage
                """);
        jdbc.execute("""
                INSERT INTO analytics.inventory_projection(
                    stock_balance_id,product_id,location_id,lot_id,asset_id,quantity,avg_cost_pen,
                    min_stock,source_event_id,updated_at)
                SELECT stock_balance_id,product_id,location_id,lot_id,asset_id,quantity,avg_cost_pen,
                    min_stock,source_event_id,updated_at
                FROM reporting_inventory_stage
                """);
        Timestamp completedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        return Objects.requireNonNull(completedAt).toInstant();
    }

    private Query movementQuery(
            Instant from, Instant to, MovementType type, String costCenterCode, boolean adjustmentsOnly) {
        StringBuilder where = new StringBuilder(" WHERE confirmed_at >= ? AND confirmed_at < ?");
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(to)));
        if (adjustmentsOnly) {
            where.append(" AND movement_type IN ('ADJUST_POSITIVE','ADJUST_NEGATIVE')");
        }
        if (type != null) {
            where.append(" AND movement_type = ?");
            args.add(type.name());
        }
        add(where, args, " AND cost_center_code = ?", costCenterCode);
        return new Query(where.toString(), args);
    }

    private long count(Query query) {
        return jdbc.queryForObject("SELECT count(*) FROM analytics.movement_projection" + query.where(),
                Long.class, query.args().toArray());
    }

    private MovementReportItem movementRow(ResultSet rs, int row) throws SQLException {
        return new MovementReportItem(
                uuid(rs, "movement_id"), rs.getString("movement_code"),
                MovementType.valueOf(rs.getString("movement_type")), "CONFIRMED",
                uuid(rs, "registered_by_user_id"), uuid(rs, "confirmed_by_user_id"),
                uuid(rs, "authorized_by_user_id"), uuid(rs, "requested_by_user_id"),
                rs.getString("cost_center_code"), rs.getString("cost_center_name_snapshot"),
                uuid(rs, "supplier_id"), rs.getString("supplier_name_snapshot"),
                rs.getString("reference_doc_type"), rs.getString("reference_doc_number"),
                rs.getString("reason"), rs.getBoolean("sensitive"),
                rs.getBigDecimal("total_base_cost_pen"), instant(rs, "confirmed_at"));
    }

    private InventoryValuationItem inventoryRow(ResultSet rs, int row) throws SQLException {
        return new InventoryValuationItem(
                uuid(rs, "stock_balance_id"), uuid(rs, "product_id"), rs.getString("sku"),
                rs.getString("name"), rs.getString("category_code"), rs.getString("category_name"),
                rs.getString("product_type"), uuid(rs, "location_id"), uuid(rs, "lot_id"),
                uuid(rs, "asset_id"), rs.getBigDecimal("quantity"), rs.getBigDecimal("avg_cost_pen"),
                rs.getBigDecimal("stock_value_pen"), rs.getBigDecimal("min_stock"));
    }

    private Instant freshness() {
        Timestamp value = jdbc.queryForObject("""
                SELECT max(updated_at) FROM (
                    SELECT max(updated_at) AS updated_at FROM analytics.product_projection
                    UNION ALL
                    SELECT max(updated_at) FROM analytics.inventory_projection
                    UNION ALL
                    SELECT max(updated_at) FROM analytics.movement_projection
                ) projection_updates
                """, Timestamp.class);
        return value == null ? null : value.toInstant();
    }

    private static void add(StringBuilder where, List<Object> args, String condition, Object value) {
        if (value != null) {
            where.append(condition);
            args.add(value);
        }
    }

    private static int pages(long total, int size) {
        return total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
    }

    private static long offset(int page, int size) {
        return Math.multiplyExact((long) page, size);
    }

    private void setInventory(PreparedStatement statement, InventoryProjection balance) throws SQLException {
        statement.setObject(1, balance.stockBalanceId());
        statement.setObject(2, balance.productId());
        statement.setObject(3, balance.locationId());
        statement.setObject(4, balance.lotId());
        statement.setObject(5, balance.assetId());
        statement.setBigDecimal(6, balance.quantity());
        statement.setBigDecimal(7, balance.avgCostPen());
        statement.setBigDecimal(8, balance.minStock());
        statement.setObject(9, balance.sourceEventId());
        statement.setTimestamp(10, Timestamp.from(Objects.requireNonNull(balance.updatedAt(), "updatedAt")));
    }

    private static void setProduct(
            PreparedStatement statement, ProductProjection product, Instant snapshotStartedAt) throws SQLException {
        statement.setObject(1, product.productId());
        statement.setString(2, product.sku());
        statement.setString(3, product.name());
        statement.setString(4, product.categoryCode());
        statement.setString(5, product.categoryName());
        statement.setString(6, product.productType());
        statement.setString(7, product.baseUnitCode());
        statement.setString(8, product.storageUnitCode());
        statement.setBigDecimal(9, product.minStock());
        statement.setBoolean(10, product.active());
        statement.setLong(11, product.sourceVersion());
        statement.setObject(12, product.sourceEventId());
        statement.setTimestamp(13, Timestamp.from(snapshotStartedAt));
    }

    private static InventoryProjection withTimestamp(InventoryProjection balance, Instant fallback) {
        return new InventoryProjection(
                balance.stockBalanceId(), balance.productId(), balance.locationId(), balance.lotId(),
                balance.assetId(), balance.quantity(), balance.avgCostPen(), balance.minStock(),
                balance.sourceEventId(), balance.updatedAt() == null ? fallback : balance.updatedAt());
    }

    private static <T> BatchPreparedStatementSetter batch(List<T> values, StatementBinder<T> binder) {
        return new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                binder.bind(statement, values.get(index));
            }

            @Override
            public int getBatchSize() {
                return values.size();
            }
        };
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private record Query(String where, List<Object> args) { }

    @FunctionalInterface
    private interface StatementBinder<T> {
        void bind(PreparedStatement statement, T value) throws SQLException;
    }
}
