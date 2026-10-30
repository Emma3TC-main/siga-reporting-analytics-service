package utp.siga.reporting.application.port.in;

import java.time.Instant;
import java.util.UUID;
import utp.siga.reporting.application.dto.ReportingDtos.AdjustmentReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.DashboardResponse;
import utp.siga.reporting.application.dto.ReportingDtos.InventoryValuationPage;
import utp.siga.reporting.application.dto.ReportingDtos.MovementReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.TrendBucket;
import utp.siga.reporting.domain.model.MovementType;

public interface ReportingQueryUseCase {
    DashboardResponse dashboard(Instant from, Instant to, TrendBucket bucket);
    MovementReportPage movements(Instant from, Instant to, MovementType type, String costCenterCode, int page, int size);
    InventoryValuationPage inventoryValuation(UUID productId, String categoryCode, UUID locationId, int page, int size);
    AdjustmentReportPage adjustments(Instant from, Instant to, MovementType type, int page, int size);
}
