package com.github.highcumontoa.tenantquotaadmissionjava.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性驱动所有租户的配额窗口到期重置。
 */
@Component
public class QuotaWindowScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuotaWindowScheduler.class);

    private final QuotaManager quotaManager;

    public QuotaWindowScheduler(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
    }

    @Scheduled(fixedDelayString = "${tenant.quota.tick-millis:1000}")
    public void tick() {
        quotaManager.tickAll();
    }
}
