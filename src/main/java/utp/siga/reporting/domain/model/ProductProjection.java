package utp.siga.reporting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductProjection(
        UUID productId,
        String sku,
        String name,
        String categoryCode,
        String categoryName,
        String productType,
        String baseUnitCode,
        String storageUnitCode,
        BigDecimal minStock,
        boolean active,
        long sourceVersion,
        UUID sourceEventId,
        Instant updatedAt) {
}
