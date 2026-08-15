package org.ecommerce.backend.mapper;

import org.ecommerce.common.dto.QuoteRequestDetailsDto;
import org.ecommerce.common.dto.QuoteRequestItemDto;
import org.ecommerce.common.dto.QuoteRequestListItemDto;
import org.ecommerce.common.entity.QuoteRequestEntity;
import org.ecommerce.common.entity.QuoteRequestItemEntity;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.ArrayList;
import java.util.List;

import static org.mapstruct.NullValueCheckStrategy.ALWAYS;
import static org.mapstruct.NullValueMappingStrategy.RETURN_NULL;
import static org.mapstruct.NullValuePropertyMappingStrategy.SET_TO_NULL;

/**
 * Single owner of the quote-request entity → DTO mapping, for both the admin list and
 * the detail view.
 * <p>
 * The detail shape is read by two callers (the service and the admin GraphQL resolver);
 * they map through here so the two cannot disagree about item ordering, snapshot fields,
 * or null handling.
 */
@Mapper(componentModel = "cdi", nullValueMappingStrategy = RETURN_NULL,
        nullValuePropertyMappingStrategy = SET_TO_NULL,
        nullValueCheckStrategy = ALWAYS)
public interface QuoteRequestMapper
{
    QuoteRequestDetailsDto mapEntityToDetailsDto(QuoteRequestEntity entity);

    @Mapping(target = "itemCount", expression = "java(entity.getItems() == null ? 0 : entity.getItems().size())")
    QuoteRequestListItemDto mapEntityToListItemDto(QuoteRequestEntity entity);

    List<QuoteRequestListItemDto> mapEntityToListItemDto(List<QuoteRequestEntity> entities);

    @Mapping(target = "variantId", source = "variant.id")
    QuoteRequestItemDto mapItemEntityToDto(QuoteRequestItemEntity item);

    /**
     * An absent item collection reads as "no items", never as a null the caller has to
     * guard. The entity initialises its list, so this only covers a detached or
     * hand-built instance.
     */
    @AfterMapping
    default void defaultItemsToEmpty(@MappingTarget QuoteRequestDetailsDto dto)
    {
        if (dto.getItems() == null) {
            dto.setItems(new ArrayList<>());
        }
    }
}
