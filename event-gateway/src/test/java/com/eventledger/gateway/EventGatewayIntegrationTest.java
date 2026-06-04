package com.eventledger.gateway;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventGatewayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    static WireMockServer wireMockServer;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().port(9999));
        wireMockServer.start();
        WireMock.configureFor("localhost", 9999);
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
        eventRepository.deleteAll();
        // Default stub: Account Service responds OK
        stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"eventId\":\"test\",\"accountId\":\"acct-123\",\"type\":\"CREDIT\",\"amount\":100}")));
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", () -> "http://localhost:9999");
    }

    // ===========================
    // VALIDATION TESTS
    // ===========================

    @Test
    @Order(1)
    void submitEvent_missingEventId_returns400() throws Exception {
        EventRequest request = EventRequest.builder()
                .accountId("acct-123")
                .type("CREDIT")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .build();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @Order(2)
    void submitEvent_invalidType_returns400() throws Exception {
        EventRequest request = buildValidRequest("evt-bad-type");
        request.setType("TRANSFER");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(3)
    void submitEvent_negativeAmount_returns400() throws Exception {
        EventRequest request = buildValidRequest("evt-neg");
        request.setAmount(new BigDecimal("-50.00"));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(4)
    void submitEvent_zeroAmount_returns400() throws Exception {
        EventRequest request = buildValidRequest("evt-zero");
        request.setAmount(BigDecimal.ZERO);

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ===========================
    // CORE FUNCTIONALITY
    // ===========================

    @Test
    @Order(5)
    void submitEvent_validRequest_returns201() throws Exception {
        EventRequest request = buildValidRequest("evt-001");

        MvcResult result = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.accountId").value("acct-123"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andReturn();

        EventResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), EventResponse.class);
        assertThat(response.getEventId()).isEqualTo("evt-001");
    }

    @Test
    @Order(6)
    void submitEvent_duplicate_returns200WithOriginalEvent() throws Exception {
        // First submission
        EventRequest request = buildValidRequest("evt-dup-001");
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Duplicate submission — must return 200 (not 201), not alter balance
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-dup-001"));

        // Verify only one record in DB
        assertThat(eventRepository.findByEventId("evt-dup-001")).isPresent();
        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    @Order(7)
    void getEvent_existingEvent_returns200() throws Exception {
        // First create it
        EventRequest request = buildValidRequest("evt-get-001");
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Then retrieve it
        mockMvc.perform(get("/events/evt-get-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-get-001"));
    }

    @Test
    @Order(8)
    void getEvent_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/events/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(9)
    void getEventsByAccount_outOfOrderEvents_returnedChronologically() throws Exception {
        String accountId = "acct-order-test";

        // Submit events out of order
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T12:00:00Z");
        Instant t3 = Instant.parse("2026-01-01T14:00:00Z");

        // Submit t3 first, then t1, then t2
        submitEvent("evt-ord-3", accountId, "CREDIT", "300", t3);
        submitEvent("evt-ord-1", accountId, "CREDIT", "100", t1);
        submitEvent("evt-ord-2", accountId, "CREDIT", "200", t2);

        MvcResult result = mockMvc.perform(get("/events")
                        .param("account", accountId))
                .andExpect(status().isOk())
                .andReturn();

        EventResponse[] events = objectMapper.readValue(
                result.getResponse().getContentAsString(), EventResponse[].class);

        assertThat(events).hasSize(3);
        assertThat(events[0].getEventTimestamp()).isEqualTo(t1);
        assertThat(events[1].getEventTimestamp()).isEqualTo(t2);
        assertThat(events[2].getEventTimestamp()).isEqualTo(t3);
    }

    // ===========================
    // RESILIENCY TESTS
    // ===========================

    @Test
    @Order(10)
    void submitEvent_accountServiceDown_returns503() throws Exception {
        // Override stub: Account Service returns 500 repeatedly
        wireMockServer.resetAll();
        stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500).withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        EventRequest request = buildValidRequest("evt-svc-down");

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Service Unavailable"));
    }

    @Test
    @Order(11)
    void getEvent_accountServiceDown_stillReturnsGatewayData() throws Exception {
        // Create event while Account Service is up
        EventRequest request = buildValidRequest("evt-degrade-001");
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Now simulate Account Service down
        wireMockServer.resetAll();
        stubFor(WireMock.get(urlPathMatching("/accounts/.*/.*"))
                .willReturn(aResponse().withStatus(503)));

        // GET should still work (Gateway local data only)
        mockMvc.perform(get("/events/evt-degrade-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-degrade-001"));
    }

    @Test
    @Order(12)
    void getEventsByAccount_accountServiceDown_stillReturnsGatewayData() throws Exception {
        String accountId = "acct-degrade";
        submitEvent("evt-deg-1", accountId, "CREDIT", "50", Instant.now());

        // Account Service down — GET /events?account=... should still work
        wireMockServer.resetAll();

        mockMvc.perform(get("/events").param("account", accountId))
                .andExpect(status().isOk());
    }

    // ===========================
    // TRACE PROPAGATION TEST
    // ===========================

    @Test
    @Order(13)
    void submitEvent_tracePropagatedToAccountService() throws Exception {
        String traceId = "test-trace-abc123";

        // Stub that verifies trace header is present
        stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .withHeader("X-Trace-Id", equalTo(traceId))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"eventId\":\"trace-test\"}")));

        EventRequest request = buildValidRequest("evt-trace-001");

        MvcResult result = mockMvc.perform(post("/events")
                        .header("X-Trace-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        // Verify trace ID is returned in response header
        String returnedTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(returnedTraceId).isEqualTo(traceId);

        // Verify WireMock received the request with trace header
        verify(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))
                .withHeader("X-Trace-Id", equalTo(traceId)));
    }

    @Test
    @Order(14)
    void submitEvent_noTraceHeader_generatesTraceId() throws Exception {
        EventRequest request = buildValidRequest("evt-auto-trace");

        MvcResult result = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        // Trace ID should be auto-generated and returned
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).isNotNull().isNotBlank();
    }

    @Test
    @Order(15)
    void healthEndpoint_returns200() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("event-gateway"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    // ===========================
    // FULL INTEGRATION TEST
    // ===========================

    @Test
    @Order(16)
    void fullFlow_creditAndDebit_gatewayToAccountService() throws Exception {
        String accountId = "acct-full-flow";

        // CREDIT
        stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"eventId\":\"tx1\"}")));
        submitEvent("evt-full-1", accountId, "CREDIT", "500.00", Instant.parse("2026-01-01T10:00:00Z"));

        // DEBIT
        submitEvent("evt-full-2", accountId, "DEBIT", "200.00", Instant.parse("2026-01-01T11:00:00Z"));

        // List events
        MvcResult result = mockMvc.perform(get("/events").param("account", accountId))
                .andExpect(status().isOk())
                .andReturn();

        EventResponse[] events = objectMapper.readValue(
                result.getResponse().getContentAsString(), EventResponse[].class);
        assertThat(events).hasSize(2);
    }

    // ===========================
    // HELPERS
    // ===========================

    private EventRequest buildValidRequest(String eventId) {
        return EventRequest.builder()
                .eventId(eventId)
                .accountId("acct-123")
                .type("CREDIT")
                .amount(new BigDecimal("150.00"))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .metadata(Map.of("source", "test", "batchId", "B-001"))
                .build();
    }

    private void submitEvent(String eventId, String accountId, String type, String amount, Instant ts) throws Exception {
        EventRequest request = EventRequest.builder()
                .eventId(eventId)
                .accountId(accountId)
                .type(type)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .eventTimestamp(ts)
                .build();

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }
}
