package org.ecommerce.backend.api.graphql;

import org.eclipse.microprofile.graphql.GraphQLException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.CustomerAuthService;
import org.ecommerce.backend.service.WishlistService;
import org.ecommerce.common.dto.WishlistItemDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WishlistResourceTest
{
    private static final String EMAIL = "shopper@example.com";

    private WishlistService wishlistService;
    private JsonWebToken jwt;
    private CustomerAuthService customerAuthService;
    private WishlistResource resource;
    private UUID customerId;

    @BeforeEach
    void setUp()
    {
        wishlistService = mock(WishlistService.class);
        jwt = mock(JsonWebToken.class);
        customerAuthService = mock(CustomerAuthService.class);

        resource = new WishlistResource();
        resource.wishlistService = wishlistService;
        resource.jwt = jwt;
        resource.customerAuthService = customerAuthService;

        customerId = UUID.randomUUID();
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);
        when(jwt.getSubject()).thenReturn(EMAIL);
        when(customerAuthService.findCustomerByEmail(EMAIL)).thenReturn(customer);
    }

    @Test
    void myWishlistReturnsSavedVariantIds() throws GraphQLException
    {
        UUID variantId = UUID.randomUUID();
        when(wishlistService.getVariantIds(customerId)).thenReturn(List.of(variantId));

        assertEquals(List.of(variantId), resource.myWishlist());
    }

    @Test
    void myWishlistRejectsUnknownCustomer()
    {
        when(customerAuthService.findCustomerByEmail(EMAIL)).thenReturn(null);

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.myWishlist());
        assertEquals("Unauthorized", ex.getMessage());
        verifyNoInteractions(wishlistService);
    }

    @Test
    void wishlistItemsDelegatesParsedIds() throws GraphQLException
    {
        UUID variantId = UUID.randomUUID();
        WishlistItemDto item = new WishlistItemDto();
        when(wishlistService.getItems(List.of(variantId))).thenReturn(List.of(item));

        assertEquals(List.of(item), resource.wishlistItems(List.of(variantId.toString())));
    }

    @Test
    void wishlistItemsMapsTheFiftyIdCap()
    {
        List<String> ids = IntStream.range(0, 51)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toList();
        List<UUID> parsed = ids.stream().map(UUID::fromString).toList();
        when(wishlistService.getItems(parsed))
                .thenThrow(new IllegalArgumentException("Maximum 50 variant IDs per request"));

        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.wishlistItems(ids));
        assertEquals("Maximum 50 variant IDs per request", ex.getMessage());
    }

    @Test
    void addToWishlistIsTrueWhenCreatedOrAlreadySaved() throws GraphQLException
    {
        UUID variantId = UUID.randomUUID();
        when(wishlistService.addToWishlist(customerId, variantId)).thenReturn(WishlistService.AddResult.CREATED);
        assertTrue(resource.addToWishlist(variantId.toString()));

        when(wishlistService.addToWishlist(customerId, variantId)).thenReturn(WishlistService.AddResult.ALREADY_EXISTS);
        assertTrue(resource.addToWishlist(variantId.toString()));
    }

    @Test
    void addToWishlistErrorsWhenVariantIsUnknown()
    {
        UUID variantId = UUID.randomUUID();
        when(wishlistService.addToWishlist(customerId, variantId)).thenReturn(WishlistService.AddResult.VARIANT_NOT_FOUND);

        GraphQLException ex = assertThrows(
                GraphQLException.class, () -> resource.addToWishlist(variantId.toString()));
        assertEquals("Variant not found", ex.getMessage());
    }

    @Test
    void addToWishlistRejectsMalformedIds()
    {
        GraphQLException ex = assertThrows(GraphQLException.class, () -> resource.addToWishlist("not-a-uuid"));
        assertEquals("Invalid variant ID", ex.getMessage());
        verifyNoInteractions(wishlistService);
    }

    @Test
    void removeFromWishlistAlwaysReturnsTrue() throws GraphQLException
    {
        UUID variantId = UUID.randomUUID();

        assertTrue(resource.removeFromWishlist(variantId.toString()));
        verify(wishlistService).removeFromWishlist(customerId, variantId);
    }
}
