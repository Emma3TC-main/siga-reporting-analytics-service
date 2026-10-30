package utp.siga.reporting.application.usecase;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;
import utp.siga.reporting.application.dto.ReportingDtos.ReconciliationResponse;
import utp.siga.reporting.application.port.in.ReconciliationUseCase;
import utp.siga.reporting.application.port.out.ConsumerControlPort;
import utp.siga.reporting.application.port.out.ProjectionStore;
import utp.siga.reporting.application.port.out.SourceSnapshotPort;

@Service
public class ReconciliationService implements ReconciliationUseCase {

    private final ReentrantLock reconciliationLock = new ReentrantLock();
    private final ConsumerControlPort consumerControl;
    private final SourceSnapshotPort sources;
    private final ProjectionStore store;

    public ReconciliationService(
            ConsumerControlPort consumerControl,
            SourceSnapshotPort sources,
            ProjectionStore store) {
        this.consumerControl = Objects.requireNonNull(consumerControl);
        this.sources = Objects.requireNonNull(sources);
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public ReconciliationResponse reconcile(String bearerToken) {
        if (bearerToken == null || !bearerToken.startsWith("Bearer ") || bearerToken.length() <= 7) {
            throw new IllegalArgumentException("A bearer token is required for reconciliation");
        }
        if (!reconciliationLock.tryLock()) {
            throw new ReconciliationAlreadyRunningException();
        }
        boolean paused = false;
        try {
            consumerControl.pause();
            paused = true;
            Instant snapshotStartedAt = Instant.now();
            var snapshot = sources.load(bearerToken);
            Instant updatedAt = store.replaceSnapshots(
                    snapshot.products(), snapshot.balances(), snapshotStartedAt);
            return new ReconciliationResponse(snapshot.products().size(), snapshot.balances().size(), updatedAt);
        } finally {
            try {
                if (paused) {
                    consumerControl.resume();
                }
            } finally {
                reconciliationLock.unlock();
            }
        }
    }

    public static final class ReconciliationAlreadyRunningException extends RuntimeException {
        public ReconciliationAlreadyRunningException() {
            super("Another reconciliation is already running");
        }
    }
}
