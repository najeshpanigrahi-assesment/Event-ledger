package com.eventledger.gateway.service;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.entity.EventRecord;
import com.eventledger.gateway.exception.DuplicateEventException;
import com.eventledger.gateway.exception.EventNotFoundException;
import com.eventledger.gateway.filter.TraceContext;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;

    // Metrics
    private final Counter eventsSubmittedCounter;
    private final Counter duplicateEventsCounter;
    private final Counter accountServiceErrorCounter;
    private final Timer eventProcessingTimer;

    public EventService(EventRepository eventRepository,
                        AccountServiceClient accountServiceClient,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;

        this.eventsSubmittedCounter = Counter.builder("events.submitted.total")
                .description("Total events submitted to the gateway")
                .register(meterRegistry);
        this.duplicateEventsCounter = Counter.builder("events.duplicate.total")
                .description("Total duplicate events detected")
                .register(meterRegistry);
        this.accountServiceErrorCounter = Counter.builder("account_service.errors.total")
                .description("Total errors calling Account Service")
                .register(meterRegistry);
        this.eventProcessingTimer = Timer.builder("event.processing.duration")
                .description("Time to process an event end to end")
                .register(meterRegistry);
    }

    /**
     * Submit a new event. Idempotent — duplicate eventId returns original event.
     */
    @Transactional
    public EventResponse submitEvent(EventRequest request) {
        String traceId = TraceContext.getTraceId();
        log.info("Processing event submission | eventId={} | accountId={} | type={} | traceId={}",
                request.getEventId(), request.getAccountId(), request.getType(), traceId);

        eventsSubmittedCounter.increment();

        // Idempotency check
        return (EventResponse) eventRepository.findByEventId(request.getEventId())
                .map(existing -> {
                    duplicateEventsCounter.increment();
                    log.info("Duplicate event detected | eventId={} | traceId={}", request.getEventId(), traceId);
                    throw new DuplicateEventException(request.getEventId(), toResponse(existing));
                })
                .orElseGet(() -> processNewEvents(request, traceId));
    }

    private EventResponse processNewEvent(EventRequest request, String traceId) {
        // Persist event record
        EventRecord record = buildEventRecord(request);
        EventRecord saved = eventRepository.save(record);
        log.info("Event persisted | eventId={} | traceId={}", saved.getEventId(), traceId);

        // Call Account Service to apply transaction
        try {
            accountServiceClient.applyTransaction(
                    request.getAccountId(),
                    request.getEventId(),
                    request.getType(),
                    request.getAmount(),
                    request.getCurrency(),
                    request.getEventTimestamp()
            );
            log.info("Transaction applied to Account Service | eventId={} | traceId={}", saved.getEventId(), traceId);
        } catch (Exception ex) {
            accountServiceErrorCounter.increment();
            // Update status to FAILED but still surface the event
            saved.setStatus("FAILED");
            eventRepository.save(saved);
            log.error("Failed to apply transaction to Account Service | eventId={} | traceId={} | error={}",
                    saved.getEventId(), traceId, ex.getMessage());
            throw ex;
        }

        saved.setStatus("PROCESSED");
        eventRepository.save(saved);
        return toResponse(saved);
    }

    /**
     * Retrieve a single event by ID. Works even if Account Service is down.
     */
    @Transactional(readOnly = true)
    public EventResponse getEvent(String eventId) {
        log.info("Retrieving event | eventId={} | traceId={}", eventId, TraceContext.getTraceId());
        return eventRepository.findByEventId(eventId)
                .map(this::toResponse)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    /**
     * List all events for an account, ordered by eventTimestamp (chronological).
     * Works even if Account Service is down.
     */
    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByAccount(String accountId) {
        log.info("Listing events for account | accountId={} | traceId={}", accountId, TraceContext.getTraceId());
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private EventRecord buildEventRecord(EventRequest request) {
        String metadataJson = null;
        if (request.getMetadata() != null) {
            try {
                metadataJson = objectMapper.writeValueAsString(request.getMetadata());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize metadata for eventId={}", request.getEventId());
            }
        }

        return EventRecord.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(request.getType())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .metadataJson(metadataJson)
                .receivedAt(Instant.now())
                .status("PROCESSED")
                .build();
    }

    @SuppressWarnings("unchecked")
    private EventResponse toResponse(EventRecord record) {
        Map<String, Object> metadata = null;
        if (record.getMetadataJson() != null) {
            try {
                metadata = objectMapper.readValue(record.getMetadataJson(), Map.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize metadata for eventId={}", record.getEventId());
            }
        }

        return EventResponse.builder()
                .eventId(record.getEventId())
                .accountId(record.getAccountId())
                .type(record.getType())
                .amount(record.getAmount())
                .currency(record.getCurrency())
                .eventTimestamp(record.getEventTimestamp())
                .receivedAt(record.getReceivedAt())
                .status(record.getStatus())
                .metadata(metadata)
                .build();
    }

    public EventResponse processNewEvents(EventRequest request, String traceId) {

        return eventProcessingTimer.record(() -> {

            EventRecord record = buildEventRecord(request);

            EventRecord saved = eventRepository.save(record);

            accountServiceClient.applyTransaction(
                    request.getAccountId(),
                    request.getEventId(),
                    request.getType(),
                    request.getAmount(),
                    request.getCurrency(),
                    request.getEventTimestamp()
            );

            return toResponse(saved);

        });
    }
}
