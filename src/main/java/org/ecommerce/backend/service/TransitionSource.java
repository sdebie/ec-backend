package org.ecommerce.backend.service;

/**
 * Who is moving an order, which decides <em>which</em> set of transitions is legal.
 * The two sets are deliberately different: the admin UI mirrors the staff set, so
 * anything reachable there is a button somebody can press.
 */
public enum TransitionSource
{
    /** A person acting in the admin UI, checked against {@code allowedTransitions()}. */
    STAFF,

    /** The platform acting on its own, checked against {@code systemTransitions()}. */
    SYSTEM
}
