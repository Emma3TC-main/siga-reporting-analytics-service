package utp.siga.reporting.presentation.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import utp.siga.reporting.application.usecase.ReconciliationService.ReconciliationAlreadyRunningException;
import utp.siga.reporting.infrastructure.http.HttpSourceSnapshotAdapter.SourceApiException;

@RestControllerAdvice
public class ReportingHttpExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportingHttpExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception ex, HttpServletRequest request) {
        log.warn("Invalid Reporting request: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .contentType(org.springframework.http.MediaType.parseMediaType("application/problem+json"))
                .body(Problems.body(request, 400, "VALIDATION_ERROR", "La solicitud no es válida"));
    }

    @ExceptionHandler(ReconciliationAlreadyRunningException.class)
    ResponseEntity<Map<String, Object>> conflict(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(org.springframework.http.MediaType.parseMediaType("application/problem+json"))
                .body(Problems.body(request, 409, "RECONCILIATION_IN_PROGRESS", ex.getMessage()));
    }

    @ExceptionHandler(SourceApiException.class)
    ResponseEntity<Map<String, Object>> dependency(Exception ex, HttpServletRequest request) {
        log.error("Reporting reconciliation source failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(org.springframework.http.MediaType.parseMediaType("application/problem+json"))
                .body(Problems.body(request, 502, "SOURCE_UNAVAILABLE",
                        "No se pudo reconciliar desde las fuentes canónicas"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> internal(Exception ex, HttpServletRequest request) {
        log.error("Unhandled Reporting error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(org.springframework.http.MediaType.parseMediaType("application/problem+json"))
                .body(Problems.body(request, 500, "INTERNAL_ERROR", "Error interno del servidor"));
    }
}
