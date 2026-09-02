package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.ProductVariantEntity;
import org.ecommerce.common.entity.WishlistItemEntity;
import org.ecommerce.common.repository.WishlistItemRepository;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class WishlistService
{
    private static final Logger LOG = Logger.getLogger(WishlistService.class);

    @Inject
    WishlistItemRepository wishlistItemRepository;

    public enum AddResult
    {
        CREATED,
        ALREADY_EXISTS,
        VARIANT_NOT_FOUND
    }

    public List<UUID> getWishlistVariantIds(UUID customerId)
    {
        List<WishlistItemEntity> items = wishlistItemRepository.findByCustomerId(customerId);
        return items
                .stream()
                .map(item -> item.getVariant().getId())
                .collect(Collectors.toList());
    }

    @Transactional
    public AddResult addToWishlist(UUID customerId, UUID variantId)
    {
        // Check variant existence
        ProductVariantEntity variant = ProductVariantEntity.findById(variantId);
        if (variant == null) {
            LOG.warn("Attempt to add non-existent variant to wishlist: variantId=" + variantId);
            return AddResult.VARIANT_NOT_FOUND;
        }

        // Check for existing entry (idempotent)
        WishlistItemEntity existing = wishlistItemRepository.findByCustomerAndVariant(customerId, variantId);
        if (existing != null) {
            return AddResult.ALREADY_EXISTS;
        }

        // Persist new entry
        CustomerEntity customer = CustomerEntity.findById(customerId);
        WishlistItemEntity newItem = new WishlistItemEntity();
        newItem.setCustomer(customer);
        newItem.setVariant(variant);
        newItem.persist();

        return AddResult.CREATED;
    }

    @Transactional
    public void removeFromWishlist(UUID customerId, UUID variantId)
    {
        wishlistItemRepository.deleteByCustomerAndVariant(customerId, variantId);
    }
}
