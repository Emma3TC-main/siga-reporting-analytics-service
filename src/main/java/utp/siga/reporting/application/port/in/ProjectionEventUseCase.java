package utp.siga.reporting.application.port.in;

import utp.siga.reporting.application.command.MovementConfirmedCommand;
import utp.siga.reporting.application.command.ProductUpdatedCommand;

public interface ProjectionEventUseCase {
    boolean process(MovementConfirmedCommand command);
    boolean process(ProductUpdatedCommand command);
}
