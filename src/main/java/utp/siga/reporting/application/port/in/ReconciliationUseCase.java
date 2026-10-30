package utp.siga.reporting.application.port.in;

import utp.siga.reporting.application.dto.ReportingDtos.ReconciliationResponse;

public interface ReconciliationUseCase {
    ReconciliationResponse reconcile(String bearerToken);
}
