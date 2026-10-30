package utp.siga.reporting.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import utp.siga.reporting.application.dto.ReportingDtos.AdjustmentReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.DashboardResponse;
import utp.siga.reporting.application.dto.ReportingDtos.InventoryValuationPage;
import utp.siga.reporting.application.dto.ReportingDtos.MovementReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.TrendBucket;
import utp.siga.reporting.domain.model.InventoryProjection;
import utp.siga.reporting.domain.model.MovementProjection;
import utp.siga.reporting.domain.model.MovementType;
import utp.siga.reporting.domain.model.ProductProjection;

public interface ProjectionStore {
    boolean claim(UUID eventId, String consumer);
    boolean insertMovement(MovementProjection movement);
    void upsertInventory(List<InventoryProjection> balances);
    boolean upsertProduct(ProductProjection product);
    DashboardResponse dashboard(Instant from, Instant to, TrendBucket bucket);
    MovementReportPage movements(Instant from, Instant to, MovementType type, String costCenterCode, int page, int size);
    InventoryValuationPage inventoryValuation(UUID productId, String categoryCode, UUID locationId, int page, int size);
    AdjustmentReportPage adjustments(Instant from, Instant to, MovementType type, int page, int size);
    Instant replaceSnapshots(
            List<ProductProjection> products, List<InventoryProjection> balances, Instant snapshotStartedAt);
}
