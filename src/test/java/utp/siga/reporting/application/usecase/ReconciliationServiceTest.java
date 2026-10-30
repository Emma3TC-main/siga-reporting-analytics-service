package utp.siga.reporting.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import utp.siga.reporting.application.port.out.ConsumerControlPort;
import utp.siga.reporting.application.port.out.ProjectionStore;
import utp.siga.reporting.application.port.out.SourceSnapshotPort;
import utp.siga.reporting.infrastructure.http.HttpSourceSnapshotAdapter.SourceApiException;

class ReconciliationServiceTest {

    @Test
    void pausesLoadsReplacesAndAlwaysResumes() {
        ConsumerControlPort control = Mockito.mock(ConsumerControlPort.class);
        SourceSnapshotPort sources = Mockito.mock(SourceSnapshotPort.class);
        ProjectionStore store = Mockito.mock(ProjectionStore.class);
        when(sources.load("Bearer opaque")).thenReturn(new SourceSnapshotPort.Snapshot(List.of(), List.of()));
        Instant completed = Instant.parse("2026-10-16T15:00:00Z");
        when(store.replaceSnapshots(anyList(), anyList(), any(Instant.class))).thenReturn(completed);

        var result = new ReconciliationService(control, sources, store).reconcile("Bearer opaque");
        assertEquals(completed, result.dataUpdatedAt());
        var order = inOrder(control, sources, store);
        order.verify(control).pause();
        order.verify(sources).load("Bearer opaque");
        order.verify(store).replaceSnapshots(
                org.mockito.ArgumentMatchers.eq(List.of()),
                org.mockito.ArgumentMatchers.eq(List.of()),
                any(Instant.class));
        order.verify(control).resume();
    }

    @Test
    void sourceFailurePreservesActiveProjectionAndResumesConsumer() {
        ConsumerControlPort control = Mockito.mock(ConsumerControlPort.class);
        SourceSnapshotPort sources = Mockito.mock(SourceSnapshotPort.class);
        ProjectionStore store = Mockito.mock(ProjectionStore.class);
        when(sources.load("Bearer opaque")).thenThrow(new SourceApiException("unavailable"));
        var service = new ReconciliationService(control, sources, store);

        assertThrows(SourceApiException.class, () -> service.reconcile("Bearer opaque"));
        verify(store, never()).replaceSnapshots(anyList(), anyList(), any(Instant.class));
        verify(control).resume();
    }

    @Test
    void refusesMissingBearerBeforePausing() {
        ConsumerControlPort control = Mockito.mock(ConsumerControlPort.class);
        var service = new ReconciliationService(control, Mockito.mock(SourceSnapshotPort.class),
                Mockito.mock(ProjectionStore.class));
        assertThrows(IllegalArgumentException.class, () -> service.reconcile("not-bearer"));
        verify(control, never()).pause();
    }
}
