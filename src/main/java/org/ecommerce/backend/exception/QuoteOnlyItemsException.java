package org.ecommerce.backend.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class QuoteOnlyItemsException extends RuntimeException
{

    private final List<String> quoteOnlyVariantIds;

    public QuoteOnlyItemsException(List<String> quoteOnlyVariantIds)
    {
        super("One or more items are quote-only and cannot be purchased: " + quoteOnlyVariantIds);
        this.quoteOnlyVariantIds = quoteOnlyVariantIds;
    }

}
