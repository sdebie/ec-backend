package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.ecommerce.backend.exception.UnavailableVariantsException;
import org.ecommerce.common.dto.CustomerDetailDto;
import org.ecommerce.common.dto.CustomerDto;
import org.ecommerce.common.dto.ImageDetailDto;
import org.ecommerce.common.dto.OrderCheckoutLineDto;
import org.ecommerce.common.dto.OrderCheckoutResponseDto;
import org.ecommerce.common.dto.OrderCreationItemDto;
import org.ecommerce.common.dto.OrderCreationRequestDto;
import org.ecommerce.common.dto.OrderDetailRespDto;
import org.ecommerce.common.dto.OrderDto;
import org.ecommerce.common.dto.OrderItemDetailDto;
import org.ecommerce.common.dto.OrderItemDto;
import org.ecommerce.common.dto.OrderResponseDto;
import org.ecommerce.common.dto.ProductDetailDto;
import org.ecommerce.common.dto.ProductVariantDetailDto;
import org.ecommerce.common.entity.*;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.OrderStatusEn;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.ProductStatusEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.OrderRepository;
import org.ecommerce.backend.mapper.OrderMapper;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import org.ecommerce.common.dto.OrderSummaryDto;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OrderService
{
    @Inject
    MailTemplate order_confirmation;

    @Inject
    OrderRepository orderRepository;

    @Inject
    OrderMapper orderMapper;

    @Inject
    PricingService pricingService;

    @Inject
    TaxService taxService;

    @Inject
    ShippingService shippingService;

    private static final Logger LOG = Logger.getLogger(OrderService.class);

    @Transactional
    public OrderCheckoutResponseDto createOrderFromCart(OrderCreationRequestDto request, CustomerTypeEn customerTier)
    {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Order request must contain at least one item");
        }

        List<String> unavailableVariantIds = new ArrayList<>();
        List<OrderCreationItemDto> validItems = new ArrayList<>();
        List<ProductVariantEntity> validVariants = new ArrayList<>();

        // 1. Validate each item: existence, active status, and stock
        for (OrderCreationItemDto item : request.getItems()) {
            if (item == null || item.getVariantId() == null) {
                continue;
            }
            UUID variantUuid;
            try {
                variantUuid = UUID.fromString(item.getVariantId());
            } catch (IllegalArgumentException e) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            ProductVariantEntity variant = ProductVariantEntity.findByIdWithProduct(variantUuid);
            if (variant == null || variant.status != ProductStatusEn.ACTIVE) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            int requested = item.getQuantity() != null ? item.getQuantity() : 0;
            if (requested <= 0 || variant.stockQuantity == null || variant.stockQuantity < requested) {
                unavailableVariantIds.add(item.getVariantId());
                continue;
            }

            validItems.add(item);
            validVariants.add(variant);
        }

        // 2. Bail out if any variants are unavailable
        if (!unavailableVariantIds.isEmpty()) {
            throw new UnavailableVariantsException(unavailableVariantIds);
        }

        // 3. Build checkout lines from server-side pricing
        List<OrderCheckoutLineDto> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (int i = 0; i < validItems.size(); i++) {
            OrderCreationItemDto item = validItems.get(i);
            ProductVariantEntity variant = validVariants.get(i);

            BigDecimal unitPrice = pricingService.getActivePrice(variant.id, customerTier);
            int quantity = item.getQuantity();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

            OrderCheckoutLineDto line = new OrderCheckoutLineDto();
            line.setVariantId(variant.id.toString());
            line.setName(variant.product != null ? variant.product.name : null);
            line.setUnitPrice(unitPrice);
            line.setQuantity(quantity);
            line.setLineTotal(lineTotal);
            lines.add(line);

            subtotal = subtotal.add(lineTotal);
        }

        // 4 & 5. Calculate tax and shipping
        BigDecimal vatAmount = taxService.calculateVat(subtotal);
        BigDecimal shippingEstimate = shippingService.estimateShipping();
        BigDecimal grandTotal = subtotal.add(vatAmount).add(shippingEstimate);

        // 6. Persist the order
        UUID sessionId = UUID.randomUUID();

        OrderEntity order = new OrderEntity();
        order.sessionId = sessionId;
        order.totalAmount = grandTotal;
        order.status = OrderStatusEn.CREATED;

        for (int i = 0; i < validItems.size(); i++) {
            OrderCreationItemDto item = validItems.get(i);
            ProductVariantEntity variant = validVariants.get(i);
            BigDecimal unitPrice = lines.get(i).getUnitPrice();

            OrderItemEntity orderItem = new OrderItemEntity();
            orderItem.orderEntity = order;
            orderItem.variant = variant;
            orderItem.quantity = item.getQuantity();
            orderItem.unitPrice = unitPrice;
            order.items.add(orderItem);
        }

        OrderEntity.persist(order);

        // 7. Build and return response
        OrderCheckoutResponseDto response = new OrderCheckoutResponseDto();
        response.setOrderId(order.id.toString());
        response.setSessionId(sessionId.toString());
        response.setLines(lines);
        response.setSubtotal(subtotal);
        response.setVatAmount(vatAmount);
        response.setShippingEstimate(shippingEstimate);
        response.setGrandTotal(grandTotal);
        return response;
    }

    @Transactional
    public OrderResponseDto createOrderFromDto(OrderDto orderDto) throws GraphQLException
    {

        if (orderDto == null || orderDto.getSessionId() == null) {
            throw new GraphQLException("Invalid Order Session info");
        }
        UUID session = UUID.fromString(orderDto.getSessionId());
        OrderEntity order = orderRepository.findLatestOrderInfoBySessionId(session);
        boolean isNew = false;
        if (order == null) {
            System.out.println("DEBUG: Creating new Order for sessionId=" + session);
            order = new OrderEntity();
            order.sessionId = session;
            isNew = true;
        } else {
            // Prepare existing items for reconciliation (no bulk clear)
            if (order.items == null) {
                order.items = new java.util.ArrayList<>();
            }
        }

        // Map minimal fields from DTO
        BigDecimal dtoTotal = orderDto.getTotalAmount();
        if (isNew) {
            order.status = OrderStatusEn.CREATED;
        }

        // Reconcile items (cascade + orphanRemoval on OrderEntity will handle DB writes)
        List<OrderItemDto> dtoItems = orderDto.getItems();
        BigDecimal computedTotal = BigDecimal.ZERO;
        if (order.items == null) {
            order.items = new java.util.ArrayList<>();
        }

        // Build lookup of existing items by variant id (only for items that have a variant)
        Map<UUID, OrderItemEntity> existingByVariant = new HashMap<>();
        for (OrderItemEntity it : order.items) {
            if (it != null && it.variant != null && it.variant.id != null) {
                existingByVariant.put(it.variant.id, it);
            }
        }

        Set<UUID> seenVariantIds = new HashSet<>();

        if (dtoItems != null) {
            for (OrderItemDto dtoItem : dtoItems) {
                if (dtoItem == null) continue;

                ProductVariantEntity variant = null;
                String variantId = dtoItem.getVariant();
                if (variantId != null) {
                    variant = ProductVariantEntity.findByIdWithProduct(UUID.fromString(variantId));
                }

                OrderItemEntity target = null;
                if (variant != null && variant.id != null) {
                    seenVariantIds.add(variant.id);
                    target = existingByVariant.get(variant.id);
                }

                if (target == null) {
                    // Create a new item (either no matching variant or variant is null)
                    target = new OrderItemEntity();
                    target.id = null;
                    target.orderEntity = order;
                    target.variant = variant; // may be null
                    order.items.add(target);
                }

                // Update mutable fields
                target.unitPrice = dtoItem.getUnitPrice();
                target.quantity = dtoItem.getQuantity();

                BigDecimal unit = target.unitPrice != null ? target.unitPrice : BigDecimal.ZERO;
                int qty = target.quantity != null ? target.quantity : 0;
                computedTotal = computedTotal.add(unit.multiply(BigDecimal.valueOf(qty)));
            }
        }

        // Remove orphan items that have a variant not present in the DTO
        if (!existingByVariant.isEmpty()) {
            Iterator<OrderItemEntity> iter = order.items.iterator();
            while (iter.hasNext()) {
                OrderItemEntity it = iter.next();
                if (it != null && it.variant != null && it.variant.id != null) {
                    if (!seenVariantIds.contains(it.variant.id)) {
                        iter.remove(); // triggers orphanRemoval
                    }
                }
            }
        }

        // If total not provided, use computed
        order.totalAmount = dtoTotal != null ? dtoTotal : computedTotal;

        if (order.id == null) {
            OrderEntity.persist(order);
        } // else: entity already managed; no explicit persist needed

        return orderMapper.toResponseDto(order);
    }

    public OrderResponseDto getOrderById(UUID orderId)
    {
        if (orderId == null) {
            return null;
        }
        return orderMapper.toResponseDto(orderRepository.findOrderInfoById(orderId));
    }

    public OrderResponseDto getLatestOrderBySessionId(String sessionId)
    {
        OrderEntity order = findLatestOrderEntityBySessionId(sessionId);
        return orderMapper.toResponseDto(order);
    }

    private OrderEntity findLatestOrderEntityBySessionId(String sessionId)
    {
        try {
            UUID sid = UUID.fromString(sessionId);
            System.out.println("DEBUG: getLatestOrderBySessionId for sessionId=" + sid);
            return orderRepository.findLatestOrderInfoBySessionId(sid);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public CustomerDto updateCustomerInformation(String sessionId, CustomerDto customerDto) throws GraphQLException
    {
        if (sessionId == null || sessionId.isBlank()) {
            throw new GraphQLException("sessionId is required");
        }
        if (customerDto == null || customerDto.getEmail() == null || customerDto.getEmail().isBlank()) {
            throw new GraphQLException("customer email is required");
        }

        System.out.println("DEBUG: Updating customer info for sessionId=" + sessionId + " email=" + customerDto.getEmail());
        OrderEntity order = findLatestOrderEntityBySessionId(sessionId);
        if (order == null) {
            throw new GraphQLException("Order not found for sessionId");
        }

        System.out.println("DEBUG: Found Order with Items=" + order.items);

        String email = customerDto.getEmail().trim();
        CustomerEntity customer = CustomerEntity.findByEmail(email);
        if (customer == null) {
            // Create a linked user + customer pair for guest checkout
            org.ecommerce.common.entity.UserEntity user = org.ecommerce.common.entity.UserEntity.findByEmail(email);
            if (user == null) {
                user = new org.ecommerce.common.entity.UserEntity();
                user.email = email;
                user.passwordHash = "";
                org.ecommerce.common.entity.UserEntity.persist(user);
            }
            customer = new CustomerEntity();
            customer.user = user;
            customer.status = CustomerStatusEn.PENDING;
            CustomerEntity.persist(customer);
        }

        order.customerEntity = customer;
        System.out.println("DEBUG: Updating Order with customer info=" + order.customerEntity.id);
        // no explicit persist needed; managed entity will be updated on commit

        // Return only customer information (currently email)
        CustomerDto result = new CustomerDto();
        result.setEmail(customer.user != null ? customer.user.email : null);
        return result;
    }

    @Transactional
    public OrderResponseDto updateOrderStatus(String sessionId, String newStatus) throws GraphQLException
    {
        if (sessionId == null || sessionId.isBlank()) {
            throw new GraphQLException("sessionId is required");
        }
        if (newStatus == null || newStatus.isBlank()) {
            throw new GraphQLException("status is required");
        }
        System.out.println("DEBUG: Updating order status for sessionId=" + sessionId + " to status=" + newStatus);
        OrderEntity order = findLatestOrderEntityBySessionId(sessionId);
        if (order == null) {
            throw new GraphQLException("Order not found for sessionId");
        }
        try {
            order.status = OrderStatusEn.valueOf(newStatus);
        } catch (IllegalArgumentException e) {
            throw new GraphQLException("Invalid status: " + newStatus);
        }
        order.persist(); // ensure status update is saved before creating history record


        // 2. Create history record
        OrderStatusHistoryEntity history = new OrderStatusHistoryEntity();
        history.order = order;
        history.status = OrderStatusEn.valueOf(newStatus);
        history.comment = "Order Update";
        //history.changedBy = staffId;

        history.persist();


        //Order Created In store Payment
        if (order.status.equals(OrderStatusEn.IN_STORE_PAYMENT)) {
            sendConfirmationEmail(order);
        }
        return orderMapper.toResponseDto(order);
    }

    public void sendConfirmationEmail(OrderEntity order)
    {
        String firstName = (order.customerEntity.firstName != null && !order.customerEntity.firstName.isBlank()) ? order.customerEntity.firstName : "Guest";
        String customerEmail = order.customerEntity.user != null ? order.customerEntity.user.email : null;
        order_confirmation.to(customerEmail)
                .from("shawn.debie@gmail.com")
                .subject("Your Order #" + order.id)
                .data("order", order)
                .data("orderItems", order.items)
                .data("customerName", firstName)
                .send()
                .subscribe().with(
                        success -> LOG.info("Order email sent!"),
                        failure -> LOG.error("Order email failed", failure)
                );
    }

    public List<OrderResponseDto> getAllOrders(PageRequest pageRequest, FilterRequest filterRequest)
    {
        List<OrderEntity> orderEntities = orderRepository.findAllOrderInfo(pageRequest, filterRequest);
        List<OrderResponseDto> orders = new ArrayList<>(orderEntities.size());
        for (OrderEntity orderEntity : orderEntities) {
            orders.add(orderMapper.toResponseDto(orderEntity));
        }
        return orders;
    }

    public OrderDetailRespDto getOrderDetail(UUID orderId)
    {
        if (orderId == null) {
            return null;
        }

        OrderEntity order = orderRepository.findOrderInfoById(orderId);
        if (order == null) {
            return null;
        }

        OrderDetailRespDto detail = new OrderDetailRespDto();

        // Map all OrderEntity fields
        detail.id = order.id;
        // Populate the CustomerDetailDto
        if (order.customerEntity != null && order.customerEntity.user != null) {
            CustomerDetailDto customerDetail = new CustomerDetailDto();
            customerDetail.email = order.customerEntity.user.email;
            detail.customerEntity = customerDetail;
        }
        detail.totalAmount = order.totalAmount;
        detail.sessionId = order.sessionId;
        detail.status = order.status;
        detail.shippingPhone = null; // Legacy field — no longer on OrderEntity
        detail.shippingAddressLine1 = order.streetAddress;
        detail.shippingAddressLine2 = null; // Legacy field — merged into streetAddress
        detail.shippingCity = order.city;
        detail.shippingProvince = order.province;
        detail.shippingPostalCode = order.postalCode;
        detail.createdAt = order.createdAt;

        // Populate items
        if (order.items != null) {
            detail.items = new ArrayList<>();
            for (OrderItemEntity orderItemEntity : order.items) {
                OrderItemDetailDto itemDetailDto = new OrderItemDetailDto();
                itemDetailDto.id = orderItemEntity.id;
                itemDetailDto.unitPrice = orderItemEntity.unitPrice;
                itemDetailDto.quantity = orderItemEntity.quantity;

                if (orderItemEntity.variant != null) {
                    ProductVariantDetailDto variantDetailDto = new ProductVariantDetailDto();
                    variantDetailDto.id = orderItemEntity.variant.id;
                    variantDetailDto.stockQuantity = orderItemEntity.variant.stockQuantity;
                    variantDetailDto.attributesJson = orderItemEntity.variant.attributesJson;
                    variantDetailDto.weightKg = orderItemEntity.variant.weightKg;

                    if (orderItemEntity.variant.product != null) {
                        ProductDetailDto productDetailDto = new ProductDetailDto();
                        productDetailDto.name = orderItemEntity.variant.product.name;
                        variantDetailDto.product = productDetailDto;
                    }

                    if (orderItemEntity.variant.images != null) {
                        List<ImageDetailDto> imageDetailDtos = new ArrayList<>();
                        for (ProductImageEntity imageEntity : orderItemEntity.variant.images) {
                            ImageDetailDto imageDetailDto = new ImageDetailDto();
                            imageDetailDto.id = imageEntity.id;
                            imageDetailDto.imageUrl = imageEntity.imageUrl;
                            imageDetailDto.sortOrder = imageEntity.sortOrder;
                            imageDetailDtos.add(imageDetailDto);
                        }
                        variantDetailDto.images = imageDetailDtos;
                    }
                    itemDetailDto.variant = variantDetailDto;
                }
                detail.items.add(itemDetailDto);
            }
        }

        // Map all OrderStatusHistoryEntity fields
        List<OrderStatusHistoryEntity> histories = OrderStatusHistoryEntity
                .find("select h from OrderStatusHistoryEntity h where h.order.id = ?1 order by h.createdAt desc", orderId)
                .list();

        if (histories != null) {
            for (OrderStatusHistoryEntity history : histories) {
                if (history == null) {
                    continue;
                }
                OrderDetailRespDto.OrderStatusHistoryDetailRespDto historyDto =
                        new OrderDetailRespDto.OrderStatusHistoryDetailRespDto();
                historyDto.id = history.id;
                historyDto.order = history.order;
                historyDto.status = history.status;
                historyDto.comment = history.comment;
                historyDto.changedBy = history.changedBy;
                historyDto.createdAt = history.createdAt;
                detail.statusHistory.add(historyDto);
            }
        }

        return detail;
    }

    public List<OrderSummaryDto> getMyOrders(UUID customerId) {
        List<OrderEntity> orders = OrderEntity
                .find("customerEntity.id = ?1 order by createdAt desc", customerId)
                .list();

        return orders.stream().map(order -> {
            OrderSummaryDto dto = new OrderSummaryDto();
            dto.id = order.id.toString();
            dto.orderDate = order.createdAt != null
                    ? order.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    : null;
            dto.status = order.status != null ? order.status.name() : null;
            dto.itemCount = order.items != null
                    ? order.items.stream()
                        .mapToInt(item -> item.quantity != null ? item.quantity : 0)
                        .sum()
                    : 0;
            dto.totalAmount = order.totalAmount != null
                    ? order.totalAmount.doubleValue()
                    : 0.0;
            return dto;
        }).collect(Collectors.toList());
    }

}