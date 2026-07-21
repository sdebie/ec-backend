package org.ecommerce.backend.exception;

import org.ecommerce.common.enums.QuoteRequestStatusEn;

/**
 * Thrown when a quote request status transition is invalid per the forward-only
 * transition map: NEW→IN_PROGRESS, NEW→CLOSED, IN_PROGRESS→CLOSED.
 */
public class InvalidQuoteStatusTransitionException extends RuntimeException {

    private final QuoteRequestStatusEn currentStatus;
    private final QuoteRequestStatusEn targetStatus;

    public InvalidQuoteStatusTransitionException(QuoteRequestStatusEn currentStatus, QuoteRequestStatusEn targetStatus) {
        super("Invalid status transition from " + currentStatus + " to " + targetStatus);
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
    }

    public QuoteRequestStatusEn getCurrentStatus() {
        return currentStatus;
    }

    public QuoteRequestStatusEn getTargetStatus() {
        return targetStatus;
    }
}
