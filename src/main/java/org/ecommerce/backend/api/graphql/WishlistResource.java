package org.ecommerce.backend.api.graphql;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.ecommerce.backend.service.CustomerAuthService;
import org.ecommerce.backend.service.WishlistService;
import org.ecommerce.common.dto.WishlistItemDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@GraphQLApi
public class WishlistResource
{
    private static final Logger LOG = Logger.getLogger(WishlistResource.class);

    @Inject
    WishlistService wishlistService;

    @Inject
    JsonWebToken jwt;

    @Inject
    CustomerAuthService customerAuthService;

    @Query("myWishlist")
    @Description("Saved variant IDs for the authenticated customer")
    @RolesAllowed("customer")
    public List<UUID> myWishlist() throws GraphQLException
    {
        return wishlistService.getVariantIds(requireCustomerId());
    }

    @Query("wishlistItems")
    @Description("Display data for the given variant IDs. Public; at most 50 IDs per request. Unknown IDs are omitted.")
    public List<WishlistItemDto> wishlistItems(@Name("variantIds") List<String> variantIds) throws GraphQLException
    {
        List<UUID> parsed = parseVariantIds(variantIds);
        try {
            return wishlistService.getItems(parsed);
        } catch (IllegalArgumentException ex) {
            throw new GraphQLException(ex.getMessage());
        }
    }

    @Mutation("addToWishlist")
    @Description("Save a variant for the authenticated customer. Idempotent; unknown variant returns an error.")
    @RolesAllowed("customer")
    public boolean addToWishlist(@Name("variantId") String variantId) throws GraphQLException
    {
        UUID parsedVariantId = parseVariantId(variantId);
        UUID customerId = requireCustomerId();
        WishlistService.AddResult result = wishlistService.addToWishlist(customerId, parsedVariantId);
        return switch (result) {
            case CREATED, ALREADY_EXISTS -> true;
            case VARIANT_NOT_FOUND -> throw new GraphQLException("Variant not found");
        };
    }

    @Mutation("removeFromWishlist")
    @Description("Remove a variant from the authenticated customer's saved list. Always succeeds.")
    @RolesAllowed("customer")
    public boolean removeFromWishlist(@Name("variantId") String variantId) throws GraphQLException
    {
        UUID parsedVariantId = parseVariantId(variantId);
        wishlistService.removeFromWishlist(requireCustomerId(), parsedVariantId);
        return true;
    }

    private UUID requireCustomerId() throws GraphQLException
    {
        if (jwt == null || jwt.getSubject() == null) {
            LOG.warn("Wishlist called without valid customer JWT");
            throw new GraphQLException("Unauthorized");
        }

        String email = jwt.getSubject();
        CustomerEntity customer = customerAuthService.findCustomerByEmail(email);
        if (customer == null) {
            LOG.warnf("Wishlist: customer not found for email: %s", email);
            throw new GraphQLException("Unauthorized");
        }
        return customer.getId();
    }

    private static List<UUID> parseVariantIds(List<String> variantIds) throws GraphQLException
    {
        if (variantIds == null || variantIds.isEmpty()) {
            return List.of();
        }
        List<UUID> parsed = new ArrayList<>(variantIds.size());
        for (String variantId : variantIds) {
            parsed.add(parseVariantId(variantId));
        }
        return parsed;
    }

    private static UUID parseVariantId(String variantId) throws GraphQLException
    {
        try {
            return UUID.fromString(variantId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new GraphQLException("Invalid variant ID");
        }
    }
}
