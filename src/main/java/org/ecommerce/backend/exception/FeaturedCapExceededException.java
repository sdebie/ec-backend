package org.ecommerce.backend.exception;

public class FeaturedCapExceededException extends RuntimeException
{
    public FeaturedCapExceededException()
    {
        super("Featured limit of 50 reached. Remove a product before adding another.");
    }
}
