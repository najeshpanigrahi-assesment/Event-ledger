package com.eventledger.gateway.exception;

import com.eventledger.gateway.dto.EventResponse;
import lombok.Getter;

@Getter
public class DuplicateEventException extends RuntimeException {

    private final EventResponse existingEvent;

    public DuplicateEventException(String eventId, EventResponse existingEvent) {
        super("Event with ID '" + eventId + "' already exists");
        this.existingEvent = existingEvent;
    }
}
