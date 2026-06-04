package com.eventledger.account;

import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void cleanup() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    // ===========================
    // BALANCE COMPUTATION
    // ===========================

    @Test
    @Order(1)
    void applyTransaction_credit_createsAccountAndSetsBalance() throws Exception {
        TransactionRequest request = buildRequest("evt-bal-001", "CREDIT", "500.00", "2026-01-01T10:00:00Z");

        mockMvc.perform(post("/accounts/acct-bal/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-bal-001"))
                .andExpect(jsonPath("$.type").value("CREDIT"));

        // Verify balance
        mockMvc.perform(get("/accounts/acct-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(500.0));
    }

    @Test
    @Order(2)
    void applyTransaction_creditThenDebit_correctNetBalance() throws Exception {
        String accountId = "acct-net";

        applyTx(accountId, "evt-net-1", "CREDIT", "1000.00", "2026-01-01T10:00:00Z");
        applyTx(accountId, "evt-net-2", "DEBIT", "300.00", "2026-01-01T11:00:00Z");

        mockMvc.perform(get("/accounts/" + accountId + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(700.0));
    }

    // ===========================
    // OUT-OF-ORDER EVENTS
    // ===========================

    @Test
    @Order(3)
    void applyTransaction_outOfOrder_balanceAlwaysCorrect() throws Exception {
        String accountId = "acct-ooo";

        // Apply t3 first
        applyTx(accountId, "evt-ooo-3", "CREDIT", "300.00", "2026-01-01T14:00:00Z");
        // Then t1
        applyTx(accountId, "evt-ooo-1", "CREDIT", "100.00", "2026-01-01T10:00:00Z");
        // Then t2
        applyTx(accountId, "evt-ooo-2", "DEBIT", "50.00", "2026-01-01T12:00:00Z");

        // Balance = 100 + 300 - 50 = 350 regardless of insertion order
        mockMvc.perform(get("/accounts/" + accountId + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(350.0));

        // Transactions must be returned in chronological order
        MvcResult result = mockMvc.perform(get("/accounts/" + accountId))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body.indexOf("evt-ooo-1")).isLessThan(body.indexOf("evt-ooo-2"));
        assertThat(body.indexOf("evt-ooo-2")).isLessThan(body.indexOf("evt-ooo-3"));
    }

    // ===========================
    // IDEMPOTENCY
    // ===========================

    @Test
    @Order(4)
    void applyTransaction_duplicate_doesNotAlterBalance() throws Exception {
        String accountId = "acct-idem";

        applyTx(accountId, "evt-idem-1", "CREDIT", "200.00", "2026-01-01T10:00:00Z");

        // Submit same transaction again
        TransactionRequest request = buildRequest("evt-idem-1", "CREDIT", "200.00", "2026-01-01T10:00:00Z");
        mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()); // Still 201 idempotently

        // Balance must still be 200, not 400
        mockMvc.perform(get("/accounts/" + accountId + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(200.0));

        // Only one transaction record
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // ===========================
    // VALIDATION
    // ===========================

    @Test
    @Order(5)
    void applyTransaction_invalidType_returns400() throws Exception {
        TransactionRequest request = buildRequest("evt-invalid", "WIRE", "100.00", "2026-01-01T10:00:00Z");

        mockMvc.perform(post("/accounts/acct-test/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(6)
    void getBalance_nonExistentAccount_returns404() throws Exception {
        mockMvc.perform(get("/accounts/does-not-exist/balance"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(7)
    void getAccount_nonExistentAccount_returns404() throws Exception {
        mockMvc.perform(get("/accounts/ghost-account"))
                .andExpect(status().isNotFound());
    }

    // ===========================
    // TRACE PROPAGATION
    // ===========================

    @Test
    @Order(8)
    void traceId_propagatedFromHeader_returnedInResponse() throws Exception {
        String traceId = "trace-acct-xyz789";
        TransactionRequest request = buildRequest("evt-trace-as", "CREDIT", "100.00", "2026-01-01T10:00:00Z");

        MvcResult result = mockMvc.perform(post("/accounts/acct-trace/transactions")
                        .header("X-Trace-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String returnedTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(returnedTraceId).isEqualTo(traceId);
    }

    @Test
    @Order(9)
    void healthEndpoint_returns200() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("account-service"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    @Order(10)
    void getAccount_returnsTransactionsInChronologicalOrder() throws Exception {
        String accountId = "acct-chrono";

        applyTx(accountId, "evt-c-3", "CREDIT", "30.00", "2026-03-01T00:00:00Z");
        applyTx(accountId, "evt-c-1", "CREDIT", "10.00", "2026-01-01T00:00:00Z");
        applyTx(accountId, "evt-c-2", "DEBIT", "20.00", "2026-02-01T00:00:00Z");

        MvcResult result = mockMvc.perform(get("/accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(20.0))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // evt-c-1 (Jan) must appear before evt-c-2 (Feb) before evt-c-3 (Mar)
        assertThat(body.indexOf("evt-c-1")).isLessThan(body.indexOf("evt-c-2"));
        assertThat(body.indexOf("evt-c-2")).isLessThan(body.indexOf("evt-c-3"));
    }

    // ===========================
    // HELPERS
    // ===========================

    private TransactionRequest buildRequest(String eventId, String type, String amount, String ts) {
        return TransactionRequest.builder()
                .eventId(eventId)
                .type(type)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .eventTimestamp(ts)
                .build();
    }

    private void applyTx(String accountId, String eventId, String type, String amount, String ts) throws Exception {
        TransactionRequest request = buildRequest(eventId, type, amount, ts);
        mockMvc.perform(post("/accounts/" + accountId + "/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }
}
