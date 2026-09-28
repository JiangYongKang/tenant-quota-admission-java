package com.github.highcumontoa.tenantquotaadmissionjava.web;

import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionResult;
import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 请求准入与管理接口。 */
@RestController
public class AdmissionController {

    private final AdmissionService admissionService;

    public AdmissionController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @PostMapping("/admission/request")
    public ResponseEntity<AdmissionResult> handle(@RequestHeader Map<String, String> headers,
                                                  @RequestParam String requestId,
                                                  @RequestBody(required = false) String payload) {
        AdmissionResult result = admissionService.handle(requestId, headers, payload);
        if (result.admitted()) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
