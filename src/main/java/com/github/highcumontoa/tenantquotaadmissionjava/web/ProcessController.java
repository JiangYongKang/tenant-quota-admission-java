package com.github.highcumontoa.tenantquotaadmissionjava.web;

import com.github.highcumontoa.tenantquotaadmissionjava.common.ProcessResult;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务请求入口：执行多租户准入、配额扣减与下游调用。
 */
@RestController
public class ProcessController {

    private final AdmissionService admissionService;

    public ProcessController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @PostMapping("/process")
    public ProcessResult process(@RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) String headerTenant,
                                 @RequestHeader(value = "X-Auth-Tenant", required = false) String authTenant,
                                 @RequestHeader(value = "X-Tenant-Token", required = false) String tokenHeader,
                                 String payload) {
        return admissionService.process(headerTenant, authTenant, tokenHeader, requestId, payload);
    }
}
