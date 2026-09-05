package org.ecommerce.backend.exception;

import lombok.Getter;

import java.time.Instant;

@Getter
public class PasswordResetLockedException extends RuntimeException {

    private final Instant lockedUntil;

    public PasswordResetLockedException(Instant lockedUntil) {
        super("Too many invalid code attempts. Try again later.");
        this.lockedUntil = lockedUntil;
    }

}
