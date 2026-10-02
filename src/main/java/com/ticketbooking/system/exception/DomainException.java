package com.ticketbooking.system.exception;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    public final String code;
    public final HttpStatus status;

    public DomainException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
