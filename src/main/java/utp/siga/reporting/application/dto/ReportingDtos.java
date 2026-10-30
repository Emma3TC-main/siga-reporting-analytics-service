package utp.siga.reporting.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import utp.siga.reporting.domain.model.MovementType;

public final class ReportingDtos {

    private ReportingDtos() {
    }

    public enum TrendBucket { DAY, MONTH }

    public record Period(Instant from, Instant to, String timeZone) { }

    public record MovementTrendBucket(
            Instant periodStart,
            long entries,
            long exits,
            long transfers,
            long positiveAdjustments,
            long negativeAdjustments) { }

    public record CriticalProduct(
            UUID productId,
            String sku,
            String name,
            String categoryCode,
            BigDecimal quantity,
            BigDecimal minStock,
            BigDecimal deficit) { }

    public record DashboardResponse(
            Period period,
            Instant dataUpdatedAt,
            BigDecimal stockValuePen,
            long lowStockProductCount,
            long outOfStockProductCount,
            long confirmedMovementCount,
            long entryCount,
            long exitCount,
            long transferCount,
            long positiveAdjustmentCount,
            long negativeAdjustmentCount,
            List<MovementTrendBucket> movementTrend,
            List<CriticalProduct> topCriticalProducts) { }

    public record MovementReportItem(
            UUID movementId,
            String movementCode,
            MovementType movementType,
            String status,
            UUID registeredByUserId,
            UUID confirmedByUserId,
            UUID authorizedByUserId,
            UUID requestedByUserId,
            String costCenterCode,
            String costCenterNameSnapshot,
            UUID supplierId,
            String supplierNameSnapshot,
            String referenceDocType,
            String referenceDocNumber,
            String reason,
            boolean sensitive,
            BigDecimal totalBaseCostPen,
            Instant confirmedAt) { }

    public record InventoryValuationItem(
            UUID stockBalanceId,
            UUID productId,
            String sku,
            String name,
            String categoryCode,
            String categoryName,
            String productType,
            UUID locationId,
            UUID lotId,
            UUID assetId,
            BigDecimal quantity,
            BigDecimal avgCostPen,
            BigDecimal stockValuePen,
            BigDecimal minStock) { }

    public record MovementReportPage(
            List<MovementReportItem> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            Period period,
            Instant dataUpdatedAt) { }

    public record AdjustmentReportPage(
            List<MovementReportItem> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            Period period,
            Instant dataUpdatedAt) { }

    public record InventoryValuationPage(
            List<InventoryValuationItem> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            Instant dataUpdatedAt) { }

    public record ReconciliationResponse(
            long productsReconciled,
            long stockBalancesReconciled,
            Instant dataUpdatedAt) { }
}
