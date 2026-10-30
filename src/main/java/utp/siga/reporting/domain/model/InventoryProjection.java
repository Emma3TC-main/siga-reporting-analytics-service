package utp.siga.reporting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryProjection(
        UUID stockBalanceId,
        UUID productId,
        UUID locationId,
        UUID lotId,
        UUID assetId,
        BigDecimal quantity,
        BigDecimal avgCostPen,
        BigDecimal minStock,
        UUID sourceEventId,
        Instant updatedAt) {
}
