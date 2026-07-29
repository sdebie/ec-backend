package org.ecommerce.backend.mapper;

// Feature: entity-boundary-cleanup, Property 2: CategoryDto parent field round-trip
// Feature: entity-boundary-cleanup, Property 3: Forward mapping populates parent scalar fields
// Validates: Requirements 1.1, 1.2, 1.4

import net.jqwik.api.*;
import org.ecommerce.common.dto.CategoryDto;
import org.ecommerce.common.entity.CategoryEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link CategoryMapper} verifying:
 * <ul>
 *   <li>Property 2: Round-trip (entity → dto → entity) preserves the parent's {@code id} at every ancestor level</li>
 *   <li>Property 3: Forward mapping ({@code mapEntityToDto}) populates all five parent scalar fields</li>
 * </ul>
 * <p>
 * Uses the MapStruct-generated {@link CategoryMapperImpl} directly (no CDI container needed).
 */
public class CategoryMapperPropertyTest
{
    private final CategoryMapper mapper = new CategoryMapperImpl();

    // ══════════════════════════════════════════════════════════════════════════
    // Property 2: CategoryDto parent field round-trip
    // Validates: Requirements 1.1
    // Tag: Feature: entity-boundary-cleanup, Property 2: CategoryDto parent field round-trip
    // ══════════════════════════════════════════════════════════════════════════

    @Property(tries = 100)
    void roundTrip_preservesParentIdAtEveryLevel(@ForAll("categoryEntitiesWithParents") CategoryEntity entity)
    {
        // Forward: entity → dto
        CategoryDto dto = mapper.mapEntityToDto(entity);
        // Reverse: dto → entity
        CategoryEntity roundTripped = mapper.mapDtoToEntity(dto);

        // Walk the entire ancestor chain and verify id is preserved at every depth
        CategoryEntity originalCursor = entity;
        CategoryEntity roundTrippedCursor = roundTripped;
        int depth = 0;

        while (originalCursor != null) {
            assertNotNull(roundTrippedCursor, "Round-tripped entity is null at depth " + depth + " but original is not");
            assertEquals(originalCursor.getId(), roundTrippedCursor.getId(), "id mismatch at depth " + depth);

            originalCursor = originalCursor.getParent();
            roundTrippedCursor = roundTrippedCursor.getParent();
            depth++;
        }

        // Verify the round-tripped chain doesn't have extra levels
        assertNull(roundTrippedCursor,
                "Round-tripped entity has extra parent levels beyond depth " + depth);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Property 3: Forward mapping populates parent scalar fields
    // Validates: Requirements 1.2, 1.4
    // Tag: Feature: entity-boundary-cleanup, Property 3: Forward mapping populates parent scalar fields
    // ══════════════════════════════════════════════════════════════════════════

    @Property(tries = 100)
    void forwardMapping_populatesAllParentScalarFields(
            @ForAll("categoryEntitiesWithPopulatedParent") CategoryEntity entity
    )
    {
        CategoryDto dto = mapper.mapEntityToDto(entity);

        // The entity has a non-null parent with all five scalar fields populated
        assertNotNull(dto.getParent(), "Mapped DTO parent should not be null");
        assertNotNull(dto.getParent().getId(), "parent.id should not be null");
        assertNotNull(dto.getParent().getName(), "parent.name should not be null");
        assertNotNull(dto.getParent().getSlug(), "parent.slug should not be null");
        assertNotNull(dto.getParent().getDescription(), "parent.description should not be null");
        assertNotNull(dto.getParent().getImageUrl(), "parent.imageUrl should not be null");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Providers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Generates CategoryEntity instances with non-null parents at depth 0–3.
     * All scalar fields are random; products set is left as default (empty).
     */
    @Provide
    Arbitrary<CategoryEntity> categoryEntitiesWithParents()
    {
        // Depth 0 means just the entity itself (no parent), up to depth 3
        return Arbitraries.integers().between(1, 3).flatMap(this::buildCategoryChain);
    }

    /**
     * Generates CategoryEntity instances with a non-null parent whose scalar fields
     * (id, name, slug, description, imageUrl) are ALL populated (non-null).
     */
    @Provide
    Arbitrary<CategoryEntity> categoryEntitiesWithPopulatedParent()
    {
        Arbitrary<CategoryEntity> populatedParent = buildPopulatedCategoryEntity();
        Arbitrary<CategoryEntity> child = buildCategoryEntityArbitrary();

        return Combinators.combine(child, populatedParent)
                .as((childEntity, parentEntity) -> {
                    childEntity.setParent(parentEntity);
                    return childEntity;
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helper builders
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Builds a chain of CategoryEntity instances with the specified depth.
     * depth=1 means entity has one parent, depth=2 means entity → parent → grandparent, etc.
     */
    private Arbitrary<CategoryEntity> buildCategoryChain(int depth)
    {
        if (depth <= 0) {
            return buildCategoryEntityArbitrary();
        }

        return buildCategoryEntityArbitrary().flatMap(entity ->
                buildCategoryChain(depth - 1).map(parent -> {
                    entity.setParent(parent);
                    return entity;
                })
        );
    }

    /**
     * Generates a single CategoryEntity with random scalar fields and no parent.
     */
    private Arbitrary<CategoryEntity> buildCategoryEntityArbitrary()
    {
        Arbitrary<UUID> ids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> slugs = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                .map(String::toLowerCase);
        Arbitrary<String> nullableDescriptions = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50)
        );
        Arbitrary<String> nullableImageUrls = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(30)
                        .map(s -> "/images/" + s + ".png")
        );

        return Combinators.combine(ids, names, slugs, nullableDescriptions, nullableImageUrls)
                .as((id, name, slug, description, imageUrl) -> {
                    CategoryEntity e = new CategoryEntity();
                    e.setId(id);
                    e.setName(name);
                    e.setSlug(slug);
                    e.setDescription(description);
                    e.setImageUrl(imageUrl);
                    // parent left as null — set by caller
                    // products left as default empty set
                    return e;
                });
    }

    /**
     * Generates a CategoryEntity with ALL scalar fields non-null (for Property 3).
     */
    private Arbitrary<CategoryEntity> buildPopulatedCategoryEntity()
    {
        Arbitrary<UUID> ids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> slugs = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20).map(String::toLowerCase);
        Arbitrary<String> descriptions = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);
        Arbitrary<String> imageUrls = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(30).map(s -> "/images/" + s + ".png");

        return Combinators.combine(ids, names, slugs, descriptions, imageUrls)
                .as((id, name, slug, description, imageUrl) -> {
                    CategoryEntity e = new CategoryEntity();
                    e.setId(id);
                    e.setName(name);
                    e.setSlug(slug);
                    e.setDescription(description);
                    e.setImageUrl(imageUrl);
                    return e;
                });
    }
}
