package com.github.highcumontoa.tenantquotaadmissionjava.web;

import com.github.highcumontoa.tenantquotaadmissionjava.tenant.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.RejectReason;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将准入异常映射为携带可区分原因码的 HTTP 响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AdmissionException.class)
    public ResponseEntity<Map<String, Object>> handleAdmission(AdmissionException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case IDENTITY_MISSING, SOURCE_CONFLICT -> HttpStatus.BAD_REQUEST;
            case IDENTITY_UNKNOWN, TENANT_DISABLED,
                 QUOTA_EXHAUSTED, THROTTLED_BY_POLICY, DEGRADED_BY_POLICY -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        log.warn("准入异常返回: tenantId={}, reason={}, status={}",
                ex.getTenantId(), ex.getReason(), status);
        return ResponseEntity.status(status).body(Map.of(
                "rejected", true,
                "tenantId", ex.getTenantId() == null ? "" : ex.getTenantId(),
                "reason", ex.getReason().name(),
                "message", ex.getMessage()));
    }
}
