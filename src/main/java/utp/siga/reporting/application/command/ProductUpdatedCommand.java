package utp.siga.reporting.application.command;

import java.time.Instant;
import java.util.UUID;
import utp.siga.reporting.domain.model.ProductProjection;

public record ProductUpdatedCommand(
        UUID eventId,
        UUID correlationId,
        Instant occurredAt,
        ProductProjection product) {
}
