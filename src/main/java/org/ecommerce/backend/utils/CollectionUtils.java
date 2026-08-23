package org.ecommerce.backend.utils;

import java.util.ArrayList;
import java.util.List;

public class CollectionUtils
{
    /**
     * Returns {@code list} unchanged, or a fresh mutable {@link ArrayList} when it is
     * {@code null} — never {@link java.util.Collections#emptyList()}, so callers can still
     * mutate the result.
     */
    public static <T> List<T> emptyIfNull(List<T> list)
    {
        return list == null ? new ArrayList<>() : list;
    }
}
