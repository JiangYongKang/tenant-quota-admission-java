package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ProcessResult;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantConfig;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户隔离：
 * 一个租户耗尽配额/触发熔断不影响另一租户；
 * 相同 requestId 分属不同租户时彼此独立，分别扣减；
 * 任意租户只能看到自己的配额解释。
 */
@SpringBootTest
@Import(FakeDownstream.Config.class)
class TenantIsolationTest {

    @Autowired
    private AdmissionService admissionService;
    @Autowired
    private QuotaManager quotaManager;
    @Autowired
    private TenantRegistry registry;
    @Autowired
    private FakeDownstream downstream;

    @BeforeEach
    void setUp() {
        downstream.reset();
        quotaManager.reset("tenant-a");
        quotaManager.reset("tenant-b");
        registry.register(new TenantConfig("tenant-a", true, "token-a", 10, 60_000,
                ForcedState.AUTO, 1));
        registry.register(new TenantConfig("tenant-b", true, "token-b", 2, 60_000,
                ForcedState.AUTO, 1));
    }

    @Test
    void quotaOfOneTenantDoesNotAffectAnother() {
        // tenant-b 用掉其 2 个配额
        admissionService.process("tenant-b", null, "token-b", "iso-b1", null);
        admissionService.process("tenant-b", null, "token-b", "iso-b2", null);

        // tenant-a 仍可在其自己的 10 配额内成功
        ProcessResult a = admissionService.process("tenant-a", null, "token-a", "iso-a1", null);
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, a.decision());
        assertEquals(1L, quotaManager.snapshot("tenant-a").used());
        assertEquals(2L, quotaManager.snapshot("tenant-b").used());
        // 解释中只出现自己的 tenantId
        assertEquals("tenant-a", a.explanation().get("tenantId"));
        assertNotEquals(quotaManager.snapshot("tenant-a").limit(),
                quotaManager.snapshot("tenant-b").limit());
    }

    @Test
    void sameRequestIdForDifferentTenantsIsIndependent() {
        ProcessResult a = admissionService.process("tenant-a", null, "token-a", "SAME-ID", null);
        ProcessResult b = admissionService.process("tenant-b", null, "token-b", "SAME-ID", null);
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, a.decision());
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, b.decision());
        assertTrue(!a.duplicate() && !b.duplicate(), "跨租户同 requestId 不得去重");
        assertEquals(2, downstream.getInvocations(), "两个租户各扣一次、各调一次");
    }
}
