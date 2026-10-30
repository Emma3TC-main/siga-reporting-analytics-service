package utp.siga.reporting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MovementProjection(
        UUID movementId,
        String movementCode,
        MovementType movementType,
        UUID registeredByUserId,
        UUID confirmedByUserId,
        UUID requestedByUserId,
        UUID authorizedByUserId,
        String costCenterCode,
        String costCenterNameSnapshot,
        UUID supplierId,
        String supplierNameSnapshot,
        String supplierTaxIdSnapshot,
        String referenceDocType,
        String referenceDocNumber,
        String reason,
        boolean sensitive,
        String sensitivityReason,
        BigDecimal totalBaseCostPen,
        Instant confirmedAt,
        UUID sourceEventId,
        Instant updatedAt) {
}
