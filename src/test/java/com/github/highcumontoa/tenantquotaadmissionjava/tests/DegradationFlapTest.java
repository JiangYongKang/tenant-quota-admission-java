package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamOutcome;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.DegradationController;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 降级状态机防抖：迟滞阈值 + 冷却时间 + 最小样本，避免短暂波动反复切换。 */
class DegradationFlapTest {

    private static final Logger log = LoggerFactory.getLogger(DegradationFlapTest.class);

    private static final long COOLDOWN_MS = 3_000L;

    @Test
    void briefFluctuationDoesNotToggle() {
        QuotaManager qm = new QuotaManager();
        qm.initTenant("t-flap", 10);
        DegradationController c = new DegradationController(qm);

        // 失败被大量成功稀释，比例始终低于降级阈值
        c.evaluate("t-flap", DownstreamOutcome.TIMEOUT);
        c.evaluate("t-flap", DownstreamOutcome.SUCCESS);
        c.evaluate("t-flap", DownstreamOutcome.SUCCESS);
        c.evaluate("t-flap", DownstreamOutcome.SUCCESS);
        c.evaluate("t-flap", DownstreamOutcome.UNAVAILABLE);
        c.evaluate("t-flap", DownstreamOutcome.SUCCESS);
        c.evaluate("t-flap", DownstreamOutcome.SUCCESS);
        c.evaluate("t-flap", DownstreamOutcome.SUCCESS);
        c.evaluate("t-flap", DownstreamOutcome.SUCCESS);
        c.evaluate("t-flap", DownstreamOutcome.SUCCESS);
        log.info("短暂波动后状态: {}", c.currentState("t-flap"));
        assertEquals(QuotaState.NORMAL, c.currentState("t-flap"),
                "短暂波动不应触发降级");
        assertEquals(QuotaState.NORMAL, qm.snapshot("t-flap").state());
    }

    @Test
    void sustainedFailureDegradesOnceAndRecoveryWaitsForCooldown() throws Exception {
        QuotaManager qm = new QuotaManager();
        qm.initTenant("t-sus", 10);
        DegradationController c = new DegradationController(qm);

        int toggles = 0;
        QuotaState previous = QuotaState.NORMAL;
        // 连续 6 次失败（窗口 10，比例 0.6 >= 0.5，样本 >= 4）：只应切换一次
        for (int i = 0; i < 6; i++) {
            QuotaState now = c.evaluate("t-sus", DownstreamOutcome.UNAVAILABLE);
            if (now != previous) {
                toggles++;
                previous = now;
            }
        }
        log.info("持续失败后: state={}, toggles={}", c.currentState("t-sus"), toggles);
        assertEquals(QuotaState.DEGRADED, c.currentState("t-sus"));
        assertEquals(1, toggles, "持续失败期间只允许降级一次，不得抖动");

        // 立即大量成功也不能在冷却期内恢复
        for (int i = 0; i < 8; i++) {
            c.evaluate("t-sus", DownstreamOutcome.SUCCESS);
        }
        log.info("冷却期内持续成功后: state={}", c.currentState("t-sus"));
        assertEquals(QuotaState.DEGRADED, c.currentState("t-sus"),
                "冷却时间未到不得恢复");

        // 等待冷却结束，再给稳定成功
        Thread.sleep(COOLDOWN_MS + 200);
        for (int i = 0; i < 8; i++) {
            QuotaState now = c.evaluate("t-sus", DownstreamOutcome.SUCCESS);
            if (now != previous) {
                toggles++;
                previous = now;
            }
        }
        log.info("冷却后持续成功: state={}, 全程切换次数={}",
                c.currentState("t-sus"), toggles);
        assertEquals(QuotaState.NORMAL, c.currentState("t-sus"),
                "冷却后持续健康必须恢复");
        assertEquals(2, toggles, "全程恰好：降级一次、恢复一次");
    }

    @Test
    void isolationPreventsCrossTenantDegradation() {
        QuotaManager qm = new QuotaManager();
        qm.initTenant("t-a", 10);
        qm.initTenant("t-b", 10);
        DegradationController c = new DegradationController(qm);
        for (int i = 0; i < 8; i++) {
            c.evaluate("t-a", DownstreamOutcome.UNAVAILABLE);
        }
        assertEquals(QuotaState.DEGRADED, c.currentState("t-a"));
        assertEquals(QuotaState.NORMAL, c.currentState("t-b"),
                "一个租户降级不得影响其他租户");
        assertEquals(QuotaState.NORMAL, qm.snapshot("t-b").state());
        log.info("租户隔离: t-a={}, t-b={}", c.currentState("t-a"), c.currentState("t-b"));
    }
}
