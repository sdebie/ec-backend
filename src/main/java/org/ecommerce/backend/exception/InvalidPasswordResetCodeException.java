package org.ecommerce.backend.exception;

public class InvalidPasswordResetCodeException extends RuntimeException {

    public InvalidPasswordResetCodeException() {
        super("Invalid or expired reset code");
    }

}
