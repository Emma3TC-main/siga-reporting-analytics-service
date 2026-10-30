package utp.siga.reporting.presentation.rest;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utp.siga.reporting.application.dto.ReportingDtos.AdjustmentReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.DashboardResponse;
import utp.siga.reporting.application.dto.ReportingDtos.InventoryValuationPage;
import utp.siga.reporting.application.dto.ReportingDtos.MovementReportPage;
import utp.siga.reporting.application.dto.ReportingDtos.ReconciliationResponse;
import utp.siga.reporting.application.dto.ReportingDtos.TrendBucket;
import utp.siga.reporting.application.port.in.ReconciliationUseCase;
import utp.siga.reporting.application.port.in.ReportingQueryUseCase;
import utp.siga.reporting.domain.model.MovementType;

@RestController
@RequestMapping("/api/v1")
public class ReportingController {

    private final ReportingQueryUseCase queries;
    private final ReconciliationUseCase reconciliation;

    public ReportingController(ReportingQueryUseCase queries, ReconciliationUseCase reconciliation) {
        this.queries = queries;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/dashboard")
    DashboardResponse dashboard(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "DAY") TrendBucket bucket) {
        return queries.dashboard(from, to, bucket);
    }

    @GetMapping("/reports/movements")
    MovementReportPage movements(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) MovementType movementType,
            @RequestParam(required = false) String costCenterCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.movements(from, to, movementType, costCenterCode, page, size);
    }

    @GetMapping("/reports/inventory-valuation")
    InventoryValuationPage inventoryValuation(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.inventoryValuation(productId, categoryCode, locationId, page, size);
    }

    @GetMapping("/reports/adjustments")
    AdjustmentReportPage adjustments(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) MovementType adjustmentType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.adjustments(from, to, adjustmentType, page, size);
    }

    @PostMapping("/admin/reconciliation")
    ResponseEntity<ReconciliationResponse> reconcile(JwtAuthenticationToken authentication) {
        return ResponseEntity.ok(
                reconciliation.reconcile("Bearer " + authentication.getToken().getTokenValue()));
    }
}
