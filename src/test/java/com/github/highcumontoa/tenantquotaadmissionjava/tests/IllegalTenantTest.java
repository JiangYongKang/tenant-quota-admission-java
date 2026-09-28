package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.common.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreakerManager;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ProcessResult;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非法租户身份的五类可区分拒绝：缺失 / 未知 / 已停用 / 来源冲突 / 凭证不符。
 */
@SpringBootTest
@Import(FakeDownstream.Config.class)
class IllegalTenantTest {

    @Autowired
    private AdmissionService admissionService;
    @Autowired
    private QuotaManager quotaManager;
    @Autowired
    private FakeDownstream downstream;
    @Autowired
    private CircuitBreakerManager breakerManager;

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        downstream.reset();
        quotaManager.reset("tenant-a");
        breakerManager.reset("tenant-a");
    }

    @Test
    void missingIdentityIsRejected() {
        AdmissionException ex = assertThrows(AdmissionException.class,
                () -> admissionService.process(null, null, null, "id-missing", null));
        assertEquals(DecisionCode.TENANT_MISSING, ex.getCode());
    }

    @Test
    void unknownTenantIsRejected() {
        AdmissionException ex = assertThrows(AdmissionException.class,
                () -> admissionService.process("tenant-ghost", null, "whatever", "id-unknown", null));
        assertEquals(DecisionCode.TENANT_UNKNOWN, ex.getCode());
    }

    @Test
    void disabledTenantIsRejected() {
        AdmissionException ex = assertThrows(AdmissionException.class,
                () -> admissionService.process("tenant-disabled", null, "token-disabled",
                        "id-disabled", null));
        assertEquals(DecisionCode.TENANT_DISABLED, ex.getCode());
    }

    @Test
    void conflictingSourcesAreRejected() {
        AdmissionException ex = assertThrows(AdmissionException.class,
                () -> admissionService.process("tenant-a", "tenant-b", "token-a",
                        "id-conflict", null));
        assertEquals(DecisionCode.TENANT_SOURCE_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("冲突"));
    }

    @Test
    void wrongCredentialIsRejected() {
        AdmissionException ex = assertThrows(AdmissionException.class,
                () -> admissionService.process("tenant-a", null, "wrong-token",
                        "id-cred", null));
        assertEquals(DecisionCode.TENANT_CREDENTIAL_MISMATCH, ex.getCode());
    }

    @Test
    void missingCredentialIsRejected() {
        AdmissionException ex = assertThrows(AdmissionException.class,
                () -> admissionService.process("tenant-a", null, null, "id-no-cred", null));
        assertEquals(DecisionCode.TENANT_CREDENTIAL_MISMATCH, ex.getCode());
    }

    @Test
    void trustedAuthHeaderBypassesToken() {
        quotaManager.reset("tenant-a");
        ProcessResult r = admissionService.process(null, "tenant-a", null, "id-trusted", null);
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, r.decision(),
                "仅可信 X-Auth-Tenant 头时无需 token，应放行");
        assertEquals(1, downstream.getInvocations());
    }
}
