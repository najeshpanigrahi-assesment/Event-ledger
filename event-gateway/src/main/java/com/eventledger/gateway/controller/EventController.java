package com.eventledger.gateway.controller;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.exception.DuplicateEventException;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventService eventService;

    /**
     * POST /events — Submit a transaction event.
     * Returns 201 on creation, 200 on duplicate.
     */
    @PostMapping
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        try {
            EventResponse response = eventService.submitEvent(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (DuplicateEventException ex) {
            // Idempotent: return original event with 200
            return ResponseEntity.ok(ex.getExistingEvent());
        }
    }

    /**
     * GET /events/{id} — Retrieve a single event by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable("id") String eventId) {
        return ResponseEntity.ok(eventService.getEvent(eventId));
    }

    /**
     * GET /events?account={accountId} — List events for an account.
     */
    @GetMapping
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam("account") String accountId) {
        return ResponseEntity.ok(eventService.getEventsByAccount(accountId));
    }
}
