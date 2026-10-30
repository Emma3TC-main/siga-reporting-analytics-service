package utp.siga.reporting.application.command;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import utp.siga.reporting.domain.model.InventoryProjection;
import utp.siga.reporting.domain.model.MovementProjection;

public record MovementConfirmedCommand(
        UUID eventId,
        UUID correlationId,
        Instant occurredAt,
        MovementProjection movement,
        List<InventoryProjection> stockBalances) {
}
