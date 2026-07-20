package org.ecommerce.backend.exception;

public class RateLimitExceededException extends RuntimeException
{
    public RateLimitExceededException()
    {
        super("Too many requests \u2014 please try again later.");
    }
}
