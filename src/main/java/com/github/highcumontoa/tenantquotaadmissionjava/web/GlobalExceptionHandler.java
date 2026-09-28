package com.github.highcumontoa.tenantquotaadmissionjava.web;

import com.github.highcumontoa.tenantquotaadmissionjava.common.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将准入异常映射为带可区分判定码的 HTTP 响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AdmissionException.class)
    public ResponseEntity<Map<String, Object>> handleAdmission(AdmissionException ex) {
        DecisionCode code = ex.getCode();
        HttpStatus status = switch (code) {
            case TENANT_MISSING, TENANT_CREDENTIAL_MISMATCH, TENANT_SOURCE_CONFLICT -> HttpStatus.UNAUTHORIZED;
            case TENANT_UNKNOWN, TENANT_DISABLED -> HttpStatus.FORBIDDEN;
            case QUOTA_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case DEGRADED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("admitted", false);
        body.put("decision", code.name());
        body.put("reason", ex.getMessage());
        log.warn("请求被拒绝: decision={}, httpStatus={}, reason={}", code, status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", "BAD_REQUEST");
        body.put("reason", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
