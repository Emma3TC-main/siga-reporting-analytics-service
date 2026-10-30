package utp.siga.reporting.application.usecase;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utp.siga.reporting.application.command.MovementConfirmedCommand;
import utp.siga.reporting.application.command.ProductUpdatedCommand;
import utp.siga.reporting.application.port.in.ProjectionEventUseCase;
import utp.siga.reporting.application.port.out.ProjectionStore;

@Service
public class ProjectionEventService implements ProjectionEventUseCase {

    static final String MOVEMENT_CONSUMER = "reporting-movement-confirmed-v1";
    static final String PRODUCT_CONSUMER = "reporting-product-updated-v1";

    private final ProjectionStore store;

    public ProjectionEventService(ProjectionStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    @Transactional
    public boolean process(MovementConfirmedCommand command) {
        Objects.requireNonNull(command);
        if (!store.claim(command.eventId(), MOVEMENT_CONSUMER)) {
            return false;
        }
        if (!store.insertMovement(command.movement())) {
            return true;
        }
        store.upsertInventory(command.stockBalances());
        return true;
    }

    @Override
    @Transactional
    public boolean process(ProductUpdatedCommand command) {
        Objects.requireNonNull(command);
        if (!store.claim(command.eventId(), PRODUCT_CONSUMER)) {
            return false;
        }
        store.upsertProduct(command.product());
        return true;
    }
}
