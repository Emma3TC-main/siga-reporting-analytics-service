package utp.siga.reporting.presentation.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import utp.siga.reporting.application.dto.ReportingDtos.DashboardResponse;
import utp.siga.reporting.application.dto.ReportingDtos.Period;
import utp.siga.reporting.application.dto.ReportingDtos.ReconciliationResponse;
import utp.siga.reporting.application.port.in.ReconciliationUseCase;
import utp.siga.reporting.application.port.in.ReportingQueryUseCase;
import utp.siga.reporting.infrastructure.security.SecurityConfiguration;
import utp.siga.reporting.presentation.error.ReportingHttpExceptionHandler;

@WebMvcTest(ReportingController.class)
@Import({SecurityConfiguration.class, ReportingHttpExceptionHandler.class})
class ReportingControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean ReportingQueryUseCase queries;
    @MockBean ReconciliationUseCase reconciliation;
    @MockBean JwtDecoder decoder;

    @Test
    void protectedReadRequiresAuthenticationAndReportRead() throws Exception {
        String url = "/api/v1/dashboard?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).with(jwt().authorities(new SimpleGrantedAuthority("INVENTORY_READ"))))
                .andExpect(status().isForbidden());
        when(queries.dashboard(any(), any(), any())).thenReturn(emptyDashboard());
        mvc.perform(get(url).with(jwt().authorities(new SimpleGrantedAuthority("REPORT_READ"))))
                .andExpect(status().isOk());
    }

    @Test
    void reconciliationRequiresAllThreeCanonicalPermissions() throws Exception {
        var reportAndCatalog = jwt().authorities(
                new SimpleGrantedAuthority("REPORT_READ"), new SimpleGrantedAuthority("CATALOG_READ"));
        mvc.perform(post("/api/v1/admin/reconciliation")
                        .with(reportAndCatalog))
                .andExpect(status().isForbidden());

        var all = jwt().authorities(
                new SimpleGrantedAuthority("REPORT_READ"), new SimpleGrantedAuthority("CATALOG_READ"),
                new SimpleGrantedAuthority("INVENTORY_READ"));
        when(reconciliation.reconcile(anyString()))
                .thenReturn(new ReconciliationResponse(0, 0, Instant.parse("2026-10-16T00:00:00Z")));
        mvc.perform(post("/api/v1/admin/reconciliation")
                        .with(all))
                .andExpect(status().isOk());
    }

    @Test
    void reconciliationMissingCatalogOrReportPermissionIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/admin/reconciliation")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("REPORT_READ"),
                                new SimpleGrantedAuthority("INVENTORY_READ"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reconciliation")
                        .with(jwt().authorities(
                                new SimpleGrantedAuthority("CATALOG_READ"),
                                new SimpleGrantedAuthority("INVENTORY_READ"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownEndpointIsDeniedAndInvalidDateIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/not-real").with(jwt().authorities(new SimpleGrantedAuthority("REPORT_READ"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dashboard?from=nope&to=2026-11-01T00:00:00Z")
                        .with(jwt().authorities(new SimpleGrantedAuthority("REPORT_READ"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analyticsReadDoesNotGrantOperationalReports() throws Exception {
        mvc.perform(get("/api/v1/reports/inventory-valuation")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ANALYTICS_READ"))))
                .andExpect(status().isForbidden());
    }

    private static DashboardResponse emptyDashboard() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");
        return new DashboardResponse(new Period(from, to, "America/Lima"), null,
                java.math.BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }
}
