package org.ecommerce.backend.service;

/**
 * The courier reference for a dispatched order, as staff supply it.
 * <p>
 * Both fields are optional even together: a courier may issue no reference at all, and an
 * order still has to be able to leave. The in-transit email says so plainly rather than
 * showing an empty tracking box.
 */
public record OrderTracking(String number, String carrier)
{
    /** Whitespace is not a tracking number; a caller sending only blanks supplied nothing. */
    public boolean isEmpty()
    {
        return isBlank(number) && isBlank(carrier);
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }
}
