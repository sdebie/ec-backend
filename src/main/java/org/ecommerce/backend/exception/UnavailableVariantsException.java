package org.ecommerce.backend.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class UnavailableVariantsException extends RuntimeException
{

    private final List<String> unavailableVariantIds;

    public UnavailableVariantsException(List<String> unavailableVariantIds)
    {
        super("One or more variants are unavailable: " + unavailableVariantIds);
        this.unavailableVariantIds = unavailableVariantIds;
    }

}
