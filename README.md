# Event Ledger System

A distributed financial transaction event processing system composed of two microservices built with Spring Boot, H2, and Resilience4j.

---

## Architecture Overview

```
Browser / Client ─────► Event Gateway API (port 8080)
                              │
                              │ REST (sync) with Circuit Breaker
                              ▼
                         Account Service (port 8081)
```

### Event Gateway API (public-facing, port 8080)
The single entry point for all client requests. Responsibilities:
- Receives and validates transaction events
- Enforces idempotency (duplicate `eventId` returns original event)
- Persists event records in its own H2 in-memory database
- Propagates trace IDs and calls the Account Service to apply transactions
- Implements **Circuit Breaker + Retry with exponential backoff** (Resilience4j) on calls to Account Service
- Degrades gracefully: GET endpoints work even when Account Service is down

### Account Service (internal, port 8081)
Manages account state exclusively. Responsibilities:
- Maintains account balances and transaction history in its own H2 in-memory database
- Handles out-of-order events: balance is always recomputed as `SUM(CREDITs) - SUM(DEBITs)` from all stored transactions
- Enforces idempotency at the transaction level
- Exposes balance and account detail endpoints

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+ |
| Maven | 3.8+ |
| Docker & Docker Compose | 20+ (optional) |

---

## Setup Instructions

### Clone / extract the project

```bash
unzip event-ledger.zip
cd event-ledger
```

---

## Running with Docker Compose (Recommended)

```bash
docker-compose up --build
```

Services will start in dependency order (Account Service first, then Gateway).

- Event Gateway: http://localhost:8080
- Account Service: http://localhost:8081

To stop:
```bash
docker-compose down
```

---

## Running Locally (Manual)

### Terminal 1 — Account Service
```bash
cd account-service
mvn spring-boot:run
```
Account Service starts on **port 8081**.

### Terminal 2 — Event Gateway
```bash
cd event-gateway
mvn spring-boot:run
```
Event Gateway starts on **port 8080**.

---

## Running the Tests

### Account Service tests
```bash
cd account-service
mvn test
```

### Event Gateway tests
```bash
cd event-gateway
mvn test
```

### Run all tests from root
```bash
cd account-service && mvn test && cd ../event-gateway && mvn test
```

---

## API Reference

### Event Gateway (port 8080)

#### Submit a transaction event
```http
POST /events
Content-Type: application/json

{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {
    "source": "mainframe-batch",
    "batchId": "B-9042"
  }
}
```
- Returns `201 Created` for new events
- Returns `200 OK` for duplicate `eventId` (idempotent)
- Returns `400 Bad Request` for validation errors
- Returns `503 Service Unavailable` if Account Service is unreachable

#### Get a single event
```http
GET /events/{eventId}
```

#### List events for an account (chronological order)
```http
GET /events?account={accountId}
```

#### Health check
```http
GET /health
```

### Account Service (port 8081)

#### Apply a transaction
```http
POST /accounts/{accountId}/transactions
```

#### Get balance
```http
GET /accounts/{accountId}/balance
```

#### Get account details + transactions
```http
GET /accounts/{accountId}
```

#### Health check
```http
GET /health
```

---

## Observability

### Structured JSON Logs
Both services emit JSON-structured logs with `traceId`, `timestamp`, `level`, `service`, and `message`.

### Trace Propagation
- Gateway generates a `X-Trace-Id` UUID for each incoming request (or inherits one from the client)
- The trace ID is propagated to Account Service via the `X-Trace-Id` HTTP header
- Both services log the trace ID in every log line via MDC
- The trace ID is returned to clients in the `X-Trace-Id` response header

### Custom Metrics (via Micrometer / Prometheus)
Both services expose metrics at `GET /actuator/prometheus`:

| Metric | Description |
|--------|-------------|
| `events.submitted.total` | Total events submitted to the Gateway |
| `events.duplicate.total` | Total duplicate events detected |
| `account_service.errors.total` | Total errors calling Account Service |
| `event.processing.duration` | End-to-end processing time histogram |
| `transactions.applied.total` | Transactions applied in Account Service |
| `transactions.duplicate.total` | Duplicate transactions in Account Service |

### H2 Console
- Gateway: http://localhost:8080/h2-console (JDBC URL: `jdbc:h2:mem:eventgatewaydb`)
- Account Service: http://localhost:8081/h2-console (JDBC URL: `jdbc:h2:mem:accountservicedb`)

---

## Resiliency Pattern: Circuit Breaker + Retry with Exponential Backoff

### Why Circuit Breaker?
The Circuit Breaker prevents the Gateway from hammering a failing Account Service. When 50% of calls fail within a sliding window of 10 requests, the circuit **opens** and immediately returns `503` to clients instead of waiting for timeouts. After 10 seconds, it transitions to **half-open** and allows 3 trial calls. If those succeed, the circuit closes.

### Why Retry with Exponential Backoff?
Transient network blips (brief connectivity issues, GC pauses) are retried automatically — up to 3 times with 500ms → 1s → 2s waits — so the client doesn't see errors for short outages. Retries are NOT applied to 4xx errors (client mistakes shouldn't be retried).

### Combined effect
- Short blips: handled transparently by retry
- Sustained failures: circuit opens quickly, fail fast to clients, Account Service gets breathing room
- Recovery: half-open state allows gradual traffic resumption

### Configuration (event-gateway/src/main/resources/application.yml)
```yaml
resilience4j:
  circuitbreaker:
    instances:
      accountService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
  retry:
    instances:
      accountService:
        maxAttempts: 3
        waitDuration: 500ms
        exponentialBackoffMultiplier: 2
```

---

## Graceful Degradation

| Scenario | Behavior |
|----------|----------|
| `POST /events` when Account Service is down | Returns `503 Service Unavailable` immediately (circuit breaker open) |
| `GET /events/{id}` when Account Service is down | ✅ Still works — reads from Gateway's local DB |
| `GET /events?account=...` when Account Service is down | ✅ Still works — reads from Gateway's local DB |
| Balance queries when Account Service is down | Returns `503` with clear message |

---

## Key Design Decisions

1. **Each service has its own H2 in-memory database** — no shared state
2. **Balance is always recomputed** from all transactions (`SUM(CREDITs) - SUM(DEBITs)`), ensuring correctness regardless of arrival order
3. **Idempotency at both layers** — Gateway checks `eventId` before calling Account Service; Account Service checks independently
4. **Events ordered by `eventTimestamp`** in all list responses, not by insertion time
5. **Auto-create accounts** — Account Service creates an account on first transaction (no separate account provisioning step required)
