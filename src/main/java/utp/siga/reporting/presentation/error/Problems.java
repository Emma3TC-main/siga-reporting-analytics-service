package utp.siga.reporting.presentation.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;

public final class Problems {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Problems() {
    }

    public static Map<String, Object> body(
            HttpServletRequest request, int status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title(status));
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code);
        String correlation = request.getHeader("X-Correlation-ID");
        if (isUuid(correlation)) {
            body.put("correlationId", correlation);
        }
        return body;
    }

    public static void write(
            HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        MAPPER.writeValue(response.getOutputStream(), body(request, status, code,
                status == 401 ? "Autenticación requerida" : "Permiso insuficiente"));
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Solicitud inválida";
            case 401 -> "No autenticado";
            case 403 -> "Acceso denegado";
            case 409 -> "Conflicto";
            case 502 -> "Dependencia no disponible";
            default -> "Error interno";
        };
    }
}
