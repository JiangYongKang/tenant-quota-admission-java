# 多租户请求准入与配额降级（tenant-quota-admission-java）

基于 Spring Boot 3 / Java 21 的多租户请求准入示例：每个请求必须携带可识别的租户身份，
系统按租户独立进行配额占用、幂等去重、下游调用与结算，并能根据下游健康状况在
**正常（NORMAL）/ 限流（THROTTLED）/ 降级（DEGRADED）** 之间运行时切换；
租户之间完全隔离，所有判定都带可区分原因码与人类可读解释。

## 1. 租户识别

请求通过两个 HTTP 头携带身份（头名大小写不敏感）：

| 请求头 | 含义 |
| --- | --- |
| `X-Tenant-Id` | 网关注入的可信身份，值为 `tenantId` 或 `tenantId:source` |
| `X-Tenant-Token` | 调用方自带凭证，格式同上 |

判定顺序与拒绝原因（`RejectReason`，彼此互斥、可区分）：

1. 两个头都缺失 → `IDENTITY_MISSING`（HTTP 400）
2. 两个头都存在但租户 id 不一致 → `SOURCE_CONFLICT`（HTTP 400），防止伪造来源覆盖可信身份
3. 注册表中找不到该租户 → `IDENTITY_UNKNOWN`（HTTP 403）
4. 租户状态为 `DISABLED` → `TENANT_DISABLED`（HTTP 403）
5. 全部通过后才进入配额流程

租户只能看到自己的配额快照：`AdmissionResult.quotaSnapshot` 与日志中的
`tenantId` 永远是当前请求租户，任何租户都无法查询或消耗他人的配额。

内置租户：`t-active`、`t-other`（启用，容量 20）、`t-disabled`（停用）。

## 2. 配额模型与状态转换

每个租户拥有**独立**的 `QuotaAccount`，内部维护：

- `used`：已成功并计入的用量；
- `inflight`：已占用但尚未结算（下游执行中）的数量；
- 恒成立的守恒不变式：`used + inflight <= capacity`（不超卖）；
- `policy`：不可变策略快照（状态 + 限流百分比 + 版本号），`volatile` 发布，
  运行时切换对**后续准入立即生效**，已占用的在途请求仍按原承诺完成结算，
  因此切换不会让在途请求出现自相矛盾的结果。

### 状态机

```
                 手动切换 / 失败率≥50%（持续失败）
        NORMAL ───────────────────────────────► THROTTLED / DEGRADED
          ▲                                          │
          └──────────── 手动恢复 / 失败率≤25%且冷却已过 ┘
```

- **NORMAL**：有容量即放行；容量耗尽拒绝为 `QUOTA_EXHAUSTED`。
- **THROTTLED**：对 requestId 哈希取模（0–99），低于 `throttlePercent` 的请求
  拒绝为 `THROTTLED_BY_POLICY`。同一 requestId 的判定稳定一致。
- **DEGRADED**：不调用下游，直接快速失败为 `DEGRADED_BY_POLICY`。
- 闸门拒绝（耗尽/限流/降级）从未占用配额，因此不存在“返还”，结果中
  `quotaRefunded=false`。

### 自动降级与防抖（DegradationController）

按租户维护最近 10 个下游结果的滑动窗口，驱动 NORMAL ↔ DEGRADED：

- 迟滞阈值：降级需要失败率 **≥ 50%**，恢复需要失败率 **≤ 25%**；
- 最小样本：至少 4 个观测才允许判定，避免冷启动误降；
- 冷却时间：两次状态切换间隔至少 **3 秒**；窗口中超过一个冷却窗口的陈旧样本
  会被淘汰，避免“陈旧失败 + 恢复迹象”造成的反复抖动。

### 幂等与并发

- 以 `requestId` 作为幂等键。首个请求（leader）执行闸门判定、占用配额并调用下游；
  相同 requestId 的重试（无论顺序还是并发，也无论是否同一线程）只会在
  `CountDownLatch` 上等待并**回放首次终态**，不会第二次调用下游、第二次扣减或返还。
- `used/inflight` 的所有变更在账户对象锁内完成；100 并发、容量 20 的压测下
  恰好 20 个成功、80 个 `QUOTA_EXHAUSTED`，最终 `used=20, inflight=0`，无丢失无超卖。

## 3. 下游异常处理规则

| 下游结果 | 异常类型 | 原因码 | 配额处理 |
| --- | --- | --- | --- |
| 成功 | — | — | 占用转正式用量，**不返还** |
| 业务拒绝 | `BusinessRejectedException` | `DOWNSTREAM_BUSINESS_REJECTED` | 返还 |
| 超时 | `DownstreamTimeoutException` | `DOWNSTREAM_TIMEOUT` | 返还（按可重试语义） |
| 内部失败 | `DownstreamInternalFailureException` | `DOWNSTREAM_INTERNAL_FAILURE` | 返还 |
| 临时不可用 | `DownstreamUnavailableException` | `DOWNSTREAM_UNAVAILABLE` | 返还 |
| 未分类异常 | 任意其它 `RuntimeException` | `DOWNSTREAM_UNCLASSIFIED` | 返还（兜底，防止占用泄漏） |

> 说明：本示例将超时等“结果不确定”的情况统一按**可重试**处理并释放占用，
> 避免重试时配额被永久占用；真实系统可按下游是否幂等调整该策略。
> 每次失败都会喂给 `DegradationController` 参与降级评估；每次结果都带
> `decisionExplanation` 说明占用、提交、返还的具体决定。

## 4. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /admission/request?requestId=...`（头携带租户身份） | 准入 + 下游调用；成功 200，被拒 4xx，响应体含原因与配额快照 |
| `GET  /admin/quota/{tenantId}` | 查询租户配额快照 |
| `POST /admin/policy/{tenantId}?state=NORMAL|THROTTLED|DEGRADED&throttlePercent=0-100` | 运行时切换策略 |
| `POST /admin/tenant/{tenantId}/status?status=ACTIVE|DISABLED` | 运行时启停用租户 |
| `POST /admin/downstream/behavior?scope=request|tenant&key=...&outcome=...` | 注入模拟下游行为（SUCCESS/BUSINESS_REJECTED/TIMEOUT/INTERNAL_FAILURE/UNAVAILABLE） |

关键日志（输入 / 关键状态 / 判定结果）：`准入开始`、`配额占用成功`、
`准入拒绝(...)`、`配额提交`、`配额返还`、`策略切换`、`降级状态切换`、
`判定结果(成功/下游失败已返还/重复回放/闸门拒绝)`。

## 5. 本地验证

前置：JDK 21+、Maven 3.9+。

```bash
# 运行全部 28 个测试（单元 + 并发 + 集成）
mvn test

# 只跑某一类
mvn test -Dtest=ConcurrentQuotaTest        # 并发扣减：不丢失/不超卖/守恒
mvn test -Dtest=DuplicateRequestTest       # 重复请求幂等
mvn test -Dtest=PolicySwitchTest           # 运行时状态切换
mvn test -Dtest=DownstreamExceptionTest    # 四类下游异常分类与返还
mvn test -Dtest=DegradationFlapTest        # 降级防抖（含约 3s 冷却等待）
mvn test -Dtest=TenantIdentityTest         # 非法租户原因可区分
mvn test -Dtest=TenantIsolationTest        # 租户隔离
mvn test -Dtest=AdmissionWebIntegrationTest # MockMvc 端到端
```

手工冒烟（应用启动于随机端口，可加 `--server.port=8080` 固定）：

```bash
mvn spring-boot:run

# 正常请求
curl -i -X POST 'http://localhost:8080/admission/request?requestId=demo-1' \
  -H 'X-Tenant-Id: t-active'

# 降级后快速失败
curl -i -X POST 'http://localhost:8080/admin/policy/t-active?state=DEGRADED'
curl -i -X POST 'http://localhost:8080/admission/request?requestId=demo-2' \
  -H 'X-Tenant-Id: t-active'

# 查询配额
curl -s 'http://localhost:8080/admin/quota/t-active'
```

## 6. 主要代码结构

```
tenant/     身份模型、注册表、身份解析（缺失/冲突/未知/停用）
quota/      QuotaAccount（并发占用/结算/幂等）、QuotaManager（租户隔离）、
            DegradationController（防抖状态机）
downstream/ 下游客户端接口、四类异常、可配置行为的模拟实现
admission/  AdmissionService（编排）、AdmissionResult（可解释结果）
web/        准入控制器、管理端控制器、全局异常映射
```
