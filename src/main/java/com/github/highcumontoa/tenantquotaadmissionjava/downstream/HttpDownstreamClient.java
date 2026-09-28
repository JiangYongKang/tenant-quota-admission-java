package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * 基于 JDK HttpClient 的生产下游客户端。
 * 结果映射：
 * 2xx            -> 成功，返回响应体；
 * 4xx            -> BizRejectedException（业务拒绝）；
 * 500/502        -> InternalErrorException（内部失败）；
 * 503/504/连接失败 -> ServiceUnavailableException（临时不可用）；
 * 读超时          -> TimeoutException。
 */
@Component
public class HttpDownstreamClient implements DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDownstreamClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final int timeoutMillis;

    public HttpDownstreamClient(@Value("${tenant.downstream.base-url:http://127.0.0.1:9999}") String baseUrl,
                                @Value("${tenant.downstream.timeout-millis:2000}") int timeoutMillis) {
        this.baseUrl = baseUrl;
        this.timeoutMillis = timeoutMillis;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
    }

    @Override
    public String invoke(String tenantId, String requestId, String payload) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/downstream"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "text/plain")
                .header("X-Tenant-Id", tenantId)
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "" : payload))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                log.info("下游调用成功: tenant={}, requestId={}, status={}", tenantId, requestId, status);
                return response.body();
            }
            if (status >= 400 && status < 500) {
                throw new BizRejectedException("下游业务拒绝 status=" + status + ", body=" + response.body());
            }
            if (status == 503 || status == 504) {
                throw new ServiceUnavailableException("下游临时不可用 status=" + status);
            }
            throw new InternalErrorException("下游内部失败 status=" + status);
        } catch (HttpTimeoutException e) {
            throw new DownstreamTimeoutException("下游调用超时 requestId=" + requestId);
        } catch (IOException e) {
            throw new ServiceUnavailableException("下游连接失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("下游调用被中断");
        }
    }
}
