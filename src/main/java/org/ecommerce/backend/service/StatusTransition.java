package org.ecommerce.backend.service;

import org.ecommerce.common.enums.OrderStatusEn;

/**
 * One requested move of an order to a new status, and everything that decides
 * what the move entails.
 * <p>
 * Built through {@link #staff} or {@link #system} rather than directly, so the
 * two kinds cannot be confused: a staff transition always carries the name of
 * the person making it and is checked against the map the admin UI mirrors, while
 * a system transition is always attributed to {@link OrderService#SYSTEM_ACTOR}
 * and checked against the map of moves the platform makes for itself.
 *
 * The transition carries no stock instruction. What happens to the goods follows
 * from the destination status alone ({@link org.ecommerce.common.enums.StockEffect}),
 * so no caller can move stock by asking.
 *
 * @param expectedFrom the status the caller believes the order is in, or null to
 *                     accept whatever it is in. Only a system writer can know this:
 *                     it acts on a specific step of the lifecycle, so finding the
 *                     order elsewhere means another writer got there first — a race
 *                     to report, not a mistake to throw over. A staff member acts on
 *                     whatever the order currently is, so they state nothing.
 */
public record StatusTransition(
        OrderStatusEn to,
        TransitionSource source,
        OrderStatusEn expectedFrom,
        String changedBy,
        String comment)
{
    /** A move a staff member chose, applied to whatever status the order is in. */
    public static StatusTransition staff(OrderStatusEn to, String changedBy)
    {
        return new StatusTransition(to, TransitionSource.STAFF, null, changedBy, null);
    }

    /**
     * A move the platform made for itself, from one specific status. The comment is
     * required because, unlike a staff move, nothing else on the timeline explains
     * why it happened.
     */
    public static StatusTransition system(OrderStatusEn expectedFrom, OrderStatusEn to, String comment)
    {
        return new StatusTransition(to, TransitionSource.SYSTEM, expectedFrom,
                OrderService.SYSTEM_ACTOR, comment);
    }
}
