package utp.siga.reporting.application.usecase;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import utp.siga.reporting.application.dto.ReportingDtos.TrendBucket;
import utp.siga.reporting.application.port.out.ProjectionStore;
import utp.siga.reporting.domain.model.MovementType;

class ReportingQueryServiceTest {

    private final ProjectionStore store = mock(ProjectionStore.class);
    private final ReportingQueryService service = new ReportingQueryService(store);
    private final Instant from = Instant.parse("2026-10-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void rejectsInvalidPeriodsPaginationAndAdjustmentTypeBeforeQuerying() {
        assertThrows(IllegalArgumentException.class,
                () -> service.dashboard(to, from, TrendBucket.DAY));
        assertThrows(IllegalArgumentException.class,
                () -> service.movements(from, to, null, null, -1, 20));
        assertThrows(IllegalArgumentException.class,
                () -> service.movements(from, to, null, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.inventoryValuation(null, null, null, 0, 101));
        assertThrows(IllegalArgumentException.class,
                () -> service.adjustments(from, to, MovementType.ENTRY, 0, 20));
        verifyNoInteractions(store);
    }

    @Test
    void acceptsCanonicalPaginationBoundsAndNormalizesBlankFilters() {
        service.movements(from, to, null, "  ", 0, 1);
        service.inventoryValuation(null, " CAT ", null, 4, 100);

        verify(store).movements(from, to, null, null, 0, 1);
        verify(store).inventoryValuation(null, "CAT", null, 4, 100);
    }
}
