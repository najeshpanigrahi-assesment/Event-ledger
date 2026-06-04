package com.eventledger.gateway.service;

import com.eventledger.gateway.dto.ApiError;
import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.filter.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountServiceClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${account-service.base-url:http://localhost:8081}")
    private String accountServiceBaseUrl;

    /**
     * Apply a transaction to an account via Account Service.
     * Protected by Circuit Breaker + Retry + TimeLimiter.
     */
    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    @Retry(name = "accountService")
    public void applyTransaction(String accountId, String eventId, String type,
                                  BigDecimal amount, String currency, Instant eventTimestamp) {
        String url = accountServiceBaseUrl + "/accounts/" + accountId + "/transactions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TraceContext.TRACE_ID_HEADER, TraceContext.getTraceId());

        Map<String, Object> body = new HashMap<>();
        body.put("eventId", eventId);
        body.put("type", type);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("eventTimestamp", eventTimestamp.toString());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        log.info("Calling Account Service: POST {} | eventId={} | traceId={}",
                url, eventId, TraceContext.getTraceId());

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("Account Service responded: status={} | eventId={} | traceId={}",
                    response.getStatusCode(), eventId, TraceContext.getTraceId());
        } catch (HttpClientErrorException ex) {
            // 4xx errors from Account Service — don't retry, propagate
            log.error("Account Service returned client error: status={} | eventId={} | traceId={}",
                    ex.getStatusCode(), eventId, TraceContext.getTraceId());
            throw ex;
        }
    }

    /**
     * Fallback when circuit breaker is open or retries exhausted.
     */
    public void applyTransactionFallback(String accountId, String eventId, String type,
                                          BigDecimal amount, String currency, Instant eventTimestamp,
                                          Throwable ex) {
        log.error("Circuit breaker fallback triggered for Account Service | accountId={} | eventId={} | traceId={} | cause={}",
                accountId, eventId, TraceContext.getTraceId(), ex.getMessage());
        throw new AccountServiceUnavailableException(
                "Account Service is currently unavailable. Please try again later.", ex);
    }

    /**
     * Get account balance — used for health/balance checks.
     */
    @CircuitBreaker(name = "accountService", fallbackMethod = "getBalanceFallback")
    public Map<String, Object> getAccountBalance(String accountId) {
        String url = accountServiceBaseUrl + "/accounts/" + accountId + "/balance";

        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceContext.TRACE_ID_HEADER, TraceContext.getTraceId());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("Calling Account Service: GET {} | traceId={}", url, TraceContext.getTraceId());

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
        return response.getBody();
    }

    public Map<String, Object> getBalanceFallback(String accountId, Throwable ex) {
        log.error("Circuit breaker fallback for getBalance | accountId={} | cause={}", accountId, ex.getMessage());
        throw new AccountServiceUnavailableException("Account Service is currently unavailable.", ex);
    }

    /**
     * Check if Account Service is reachable.
     */
    public boolean isHealthy() {
        try {
            String url = accountServiceBaseUrl + "/health";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception ex) {
            log.warn("Account Service health check failed: {}", ex.getMessage());
            return false;
        }
    }
}
