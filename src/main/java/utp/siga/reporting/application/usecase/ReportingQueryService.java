package utp.siga.reporting.application.usecase;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.reporting.application.dto.ReportingDtos.AdjustmentReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.DashboardResponse;
import utp.siga.reporting.application.dto.ReportingDtos.InventoryValuationPage;
import utp.siga.reporting.application.dto.ReportingDtos.MovementReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.TrendBucket;
import utp.siga.reporting.application.port.in.ReportingQueryUseCase;
import utp.siga.reporting.application.port.out.ProjectionStore;
import utp.siga.reporting.domain.model.MovementType;

@Service
@Transactional(readOnly = true)
public class ReportingQueryService implements ReportingQueryUseCase {

    private final ProjectionStore store;

    public ReportingQueryService(ProjectionStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public DashboardResponse dashboard(Instant from, Instant to, TrendBucket bucket) {
        validatePeriod(from, to);
        return store.dashboard(from, to, Objects.requireNonNull(bucket));
    }

    @Override
    public MovementReportPage movements(
            Instant from, Instant to, MovementType type, String costCenterCode, int page, int size) {
        validatePeriod(from, to);
        validatePage(page, size);
        return store.movements(from, to, type, normalize(costCenterCode), page, size);
    }

    @Override
    public InventoryValuationPage inventoryValuation(
            UUID productId, String categoryCode, UUID locationId, int page, int size) {
        validatePage(page, size);
        return store.inventoryValuation(productId, normalize(categoryCode), locationId, page, size);
    }

    @Override
    public AdjustmentReportPage adjustments(
            Instant from, Instant to, MovementType type, int page, int size) {
        validatePeriod(from, to);
        validatePage(page, size);
        if (type != null && !type.isAdjustment()) {
            throw new IllegalArgumentException("adjustmentType must be ADJUST_POSITIVE or ADJUST_NEGATIVE");
        }
        return store.adjustments(from, to, type, page, size);
    }

    static void validatePeriod(Instant from, Instant to) {
        Objects.requireNonNull(from, "from is required");
        Objects.requireNonNull(to, "to is required");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }

    static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
