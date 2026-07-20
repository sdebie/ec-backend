package org.ecommerce.backend.exception;

import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
public class PasswordResetLockedException extends RuntimeException {

    private final OffsetDateTime lockedUntil;

    public PasswordResetLockedException(OffsetDateTime lockedUntil) {
        super("Too many invalid code attempts. Try again later.");
        this.lockedUntil = lockedUntil;
    }

}
