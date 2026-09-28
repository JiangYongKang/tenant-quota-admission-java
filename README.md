# 多租户请求准入与配额降级（tenant-quota-admission-java）

在 Spring Boot 3.4 / Java 21 上实现的多租户请求准入控制：每个请求先做**租户身份识别**，
再做**配额占用与状态判定**，然后调用下游，最后按下游结果**返还或保留配额**。
目标是：并发请求、策略运行时切换、下游异常三种情形下结果稳定、可解释，且租户之间完全隔离。

---

## 1. 租户识别

身份来自两个 HTTP 头，可信度不同：

| 请求头 | 含义 | 是否可信 |
| --- | --- | --- |
| `X-Tenant-Id` | 应用自报身份 | 否，必须配合 `X-Tenant-Token` 校验 |
| `X-Auth-Tenant` | 鉴权网关注入的身份 | 是，无需 token |
| `X-Tenant-Token` | 自报身份的凭证 | — |
| `X-Request-Id` | 请求幂等键（缺失时服务端自动生成，此时不去重） | — |

校验顺序与可区分拒绝码（HTTP 状态码）：

1. 两个身份头都缺失 → `TENANT_MISSING`（401）
2. 两个身份头都存在但值不一致 → `TENANT_SOURCE_CONFLICT`（401，来源冲突）
3. 租户不存在 → `TENANT_UNKNOWN`（403）
4. 租户已停用 → `TENANT_DISABLED`（403）
5. 走自报头但 token 缺失/错误 → `TENANT_CREDENTIAL_MISMATCH`（401，使用常量时间比较）

两个身份头一致、或仅出现可信头时视为同一身份。**所有配额、熔断、去重状态都以租户为 key
分别持有，任何租户都无法消耗或窥探其他租户的状态**；去重键为 `tenantId + "#" + requestId`，
因此两个租户使用相同 requestId 也互不影响。

---

## 2. 配额模型与状态转换

### 2.1 固定窗口配额（`TenantQuotaState`）

每个租户独立维护一个固定窗口，计数由原子变量 + CAS 更新：

- `used`：本窗口内已被“消耗/保留”的单位；
- `inFlight`：已占用但下游调用尚未结束的单位；
- 准入条件：`used + inFlight < limit`，CAS 失败者自旋重试，**绝不超卖、不丢失更新**；
- 窗口到期（默认 60s，由 `QuotaWindowScheduler` 每秒 tick）重置 `used`，在途计数自然携带；
- 管理面调小上限立即对新准入生效（开窗重置，避免新旧窗口语义混淆）。

### 2.2 三种有效状态

对每个请求按以下**优先级**判定有效状态（基于读取到的不可变策略快照，判定过程一致）：

1. **管理面强制状态**（`TenantConfig.forcedState`）
   - `DEGRADED` → 直接拒绝（`DEGRADED`，503），不调下游、不占配额；
   - `RATE_LIMITED` → 直接限流（`QUOTA_EXHAUSTED`，429），不调下游、不占配额；
   - `AUTO` / `NORMAL` → 继续向下判定。
2. **熔断器**（自动降级，见下节）：打开 → `DEGRADED`（503）。
3. **配额窗口**：占用失败 → `QUOTA_EXHAUSTED`（429）。
4. 以上都通过即 **NORMAL**：占用 1 个单位并调用下游。

### 2.3 运行时切换

`POST /admin/tenants/{tenantId}/state`，body：`{"state":"NORMAL|RATE_LIMITED|DEGRADED|AUTO"}`。
切换通过“不可变快照替换 + 版本号 `version` 递增”发布，**对切换后到达的新准入请求立即、一致生效**；
已持有旧快照的在途请求按其进入时的快照执行，不会读到半更新状态（每个请求的解释里带
`policyVersion`，可据此核对）。切换状态不触碰其他租户。

`POST /admin/tenants/{tenantId}/quota`，body：`{"limit":5,"windowMillis":60000}` 可热调配额。

---

## 3. 下游异常处理规则

下游被抽象为 `DownstreamClient`，生产实现 `HttpDownstreamClient` 基于 JDK HttpClient，
把结果映射为四类可区分异常：

| 下游情形 | 判定码 | 配额动作 | 是否计入熔断失败 |
| --- | --- | --- | --- |
| 成功（2xx） | `DOWNSTREAM_SUCCESS` | `CONSUME`（消耗） | 否（并清零连续失败） |
| 业务拒绝（4xx） | `DOWNSTREAM_BIZ_REJECTED` | `REFUND`（返还） | 否（业务拒绝不代表下游不健康） |
| 超时 | `DOWNSTREAM_TIMEOUT` | `RETAIN`（保留） | 是 |
| 内部失败（500/502） | `DOWNSTREAM_INTERNAL_ERROR` | `REFUND`（返还） | 是 |
| 临时不可用（503/504/连接失败） | `DOWNSTREAM_UNAVAILABLE` | `REFUND`（返还） | 是 |

- **返还（REFUND）**：释放占用，配额立即可被后续请求使用；
- **保留（RETAIN）**：超时往往意味着下游可能已实际处理，保守地保留配额，避免放大重试造成超卖；
- **消耗（CONSUME）**：成功请求正常计入窗口用量。

### 熔断器（防抖动）

每租户独立一个 `CircuitBreaker`，状态机 `CLOSED → OPEN → HALF_OPEN → CLOSED`：

- CLOSED 下**连续**失败达到 `tenant.breaker.failure-threshold`（默认 3）才打开；
  单次失败后出现成功会清零计数，短暂波动不会触发降级；
- OPEN 后在 `tenant.breaker.cooldown-millis`（默认 5000ms）内直接降级、不调用下游，
  冷却期保证不会因零星成功反复抖动；
- 冷却结束只放行**一个**探测请求（HALF_OPEN）：成功 → CLOSED，失败 → 重新 OPEN 并重新计时。

### 幂等去重

`AdmissionService` 以 `(tenantId, requestId)` 去重：

- 同一 requestId 在途并发到达：后续请求阻塞等待并**共享首次结果**，下游只调用一次、配额只扣一次；
- 首次完成后在 `tenant.dedup.millis`（默认 60s）内重放：直接返回首次结果（响应中 `duplicate=true`）；
- 身份非法的请求不进入去重表，客户端修正身份后可正常重试。

每个响应都带可解释字段 `explanation`：租户、requestId、策略版本、配额前/后快照、
熔断状态、配额动作、下游判定等；日志同样打印输入、关键状态与判定结果。

---

## 4. HTTP 接口

- `POST /process`
  - 请求头：`X-Tenant-Id` / `X-Auth-Tenant` / `X-Tenant-Token` / `X-Request-Id`，body 为透传文本；
  - 成功/业务失败返回 200，body 为 `ProcessResult`（含 `decision`、`explanation`）；
  - 准入拒绝由 `GlobalExceptionHandler` 映射为 401/403/429/503，body 含可区分 `decision` 与 `reason`。
- `POST /admin/tenants/{tenantId}/state`、`POST /admin/tenants/{tenantId}/quota`：见上节。

预置租户（`TenantBootstrap`，本地验证用）：

| 租户 | token | 启用 | 窗口配额 |
| --- | --- | --- | --- |
| `tenant-a` | `token-a` | 是 | 10 / 60s |
| `tenant-b` | `token-b` | 是 | 2 / 60s（便于验证限流与隔离） |
| `tenant-disabled` | `token-disabled` | 否 | 10 / 60s |

---

## 5. 本地验证方法

### 5.1 运行测试

```bash
mvn test
```

覆盖内容（共 25 个用例）：

- `ConcurrencyQuotaTest`：200 线程 CAS 压测不超卖；30 并发打 tenant-b 恰好成功 2 个、
  其余全部 `QUOTA_EXHAUSTED`；
- `DuplicateRequestTest`：完成后重放幂等、在途并发重复共享一次执行；
- `StateSwitchTest`：AUTO→限流→降级→恢复立即生效、版本递增、强制限流不占配额不调下游；
- `DownstreamExceptionTest`：四类下游结果的判定/配额动作、熔断阈值触发、冷却探测恢复、
  单次波动不熔断、解释字段完整；
- `IllegalTenantTest`：五类身份拒绝原因可区分、可信头免 token；
- `TenantIsolationTest`：租户间配额与 requestId 隔离；
- `QuotaConservationTest`：成功消耗、失败返还、超时保留的计数守恒，100 并发无丢失。

### 5.2 手工 curl 验证

```bash
mvn spring-boot:run            # 注意：application.properties 使用 server.port=0（随机端口），
                               # 手工验证可改成固定端口，如 server.port=8080

# 正常请求
curl -s -X POST localhost:8080/process \
  -H 'X-Tenant-Id: tenant-a' -H 'X-Tenant-Token: token-a' -H 'X-Request-Id: r1' -d 'hello'

# 缺失身份 -> 401 TENANT_MISSING
curl -i -X POST localhost:8080/process

# token 错误 -> 401 TENANT_CREDENTIAL_MISMATCH
curl -i -X POST localhost:8080/process -H 'X-Tenant-Id: tenant-a' -H 'X-Tenant-Token: nope'

# 来源冲突 -> 401 TENANT_SOURCE_CONFLICT
curl -i -X POST localhost:8080/process \
  -H 'X-Tenant-Id: tenant-a' -H 'X-Auth-Tenant: tenant-b' -H 'X-Tenant-Token: token-a'

# 小配额租户连发 3 次 -> 第 3 次 429 QUOTA_EXHAUSTED
for i in 1 2 3; do curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/process \
  -H 'X-Tenant-Id: tenant-b' -H 'X-Tenant-Token: token-b' -H "X-Request-Id: q$i"; done

# 切换降级 -> 503 DEGRADED；再切回 AUTO 恢复
curl -s -X POST localhost:8080/admin/tenants/tenant-a/state -H 'Content-Type: application/json' -d '{"state":"DEGRADED"}'
curl -i -X POST localhost:8080/process -H 'X-Tenant-Id: tenant-a' -H 'X-Tenant-Token: token-a' -H 'X-Request-Id: r2'
curl -s -X POST localhost:8080/admin/tenants/tenant-a/state -H 'Content-Type: application/json' -d '{"state":"AUTO"}'
```

下游默认指向 `http://127.0.0.1:9999/downstream`，可通过 `tenant.downstream.base-url`
配置到真实/Mock 服务（2xx 成功、4xx 业务拒绝、500/502 内部失败、503/504 不可用、超时即触发超时语义）。
