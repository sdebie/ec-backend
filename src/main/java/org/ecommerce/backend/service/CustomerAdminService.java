package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.ecommerce.common.dto.AdminCustomerDetailDto;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.dto.AdminOrderRefDto;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CustomerAdminService {

    private static final Logger LOG = Logger.getLogger(CustomerAdminService.class);

    public List<AdminCustomerListItemDto> allCustomers(PageRequest pageRequest, FilterRequest filterRequest) {
        QuerySpec spec = buildQuery(filterRequest);
        int pageIndex = pageRequest != null ? pageRequest.getPageIndex() : 0;
        int pageSize  = pageRequest != null ? pageRequest.getPageSize()  : 10;

        List<CustomerEntity> customers = CustomerEntity.find(spec.query, spec.params)
                .page(pageIndex, pageSize)
                .<CustomerEntity>list();
        return customers.stream()
                .map(c -> toListItemDto(c))
                .toList();
    }

    public long customerCount(FilterRequest filterRequest) {
        QuerySpec spec = buildQuery(filterRequest);
        return CustomerEntity.count(spec.query, spec.params);
    }

    public AdminCustomerDetailDto adminCustomer(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }

        CustomerEntity customer = CustomerEntity.findById(id);
        if (customer == null) {
            throw new IllegalArgumentException("customer not found: " + id);
        }

        WholesaleApplicationEntity app = WholesaleApplicationEntity
                .find("customer.id = ?1", id)
                .firstResult();

        List<OrderEntity> orders = OrderEntity
                .find("customerEntity.id = ?1 order by createdAt desc", id)
                .page(0, 10)
                .list();

        return toDetailDto(customer, app, orders);
    }

    @Transactional
    public AdminCustomerListItemDto updateCustomerStatus(UUID id, String status) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }

        CustomerStatusEn newStatus;
        try {
            newStatus = CustomerStatusEn.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid status: " + status);
        }

        CustomerEntity customer = CustomerEntity.findById(id);
        if (customer == null) {
            throw new IllegalArgumentException("customer not found: " + id);
        }

        validateStatusTransition(customer.status, newStatus);

        customer.status = newStatus;
        customer.persist();

        return toListItemDto(customer);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static class QuerySpec {
        final String query;
        final Map<String, Object> params;

        QuerySpec(String query, Map<String, Object> params) {
            this.query = query;
            this.params = params;
        }
    }

    private QuerySpec buildQuery(FilterRequest filterRequest) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterRequest != null && filterRequest.getFilters() != null) {
            for (Filter f : filterRequest.getFilters()) {
                if (f.getKey() == null || f.getValue() == null) continue;

                switch (f.getKey()) {
                    case "shopperType" -> {
                        try {
                            params.put("shopperType", CustomerTypeEn.valueOf(f.getValue()));
                            query.append(" and shopperType = :shopperType");
                        } catch (IllegalArgumentException e) {
                            LOG.warnf("Invalid shopperType filter value: %s", f.getValue());
                        }
                    }
                    case "status" -> {
                        try {
                            params.put("status", CustomerStatusEn.valueOf(f.getValue()));
                            query.append(" and status = :status");
                        } catch (IllegalArgumentException e) {
                            LOG.warnf("Invalid status filter value: %s", f.getValue());
                        }
                    }
                    case "search" -> {
                        String term = "%" + f.getValue().toLowerCase() + "%";
                        params.put("search", term);
                        query.append(" and (lower(firstName) like :search or lower(lastName) like :search or lower(user.email) like :search)");
                    }
                }
            }
        }

        return new QuerySpec(query.toString(), params);
    }

    private void validateStatusTransition(CustomerStatusEn current, CustomerStatusEn next) {
        boolean valid = switch (current) {
            case PENDING  -> next == CustomerStatusEn.ACTIVE;
            case ACTIVE   -> next == CustomerStatusEn.DISABLED;
            case DISABLED -> next == CustomerStatusEn.ACTIVE;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "invalid status transition: " + current + " → " + next);
        }
    }

    private AdminCustomerListItemDto toListItemDto(CustomerEntity c) {
        WholesaleApplicationEntity app = WholesaleApplicationEntity
                .find("customer.id = ?1", c.id)
                .firstResult();
        return toListItemDto(c, app);
    }

    private AdminCustomerListItemDto toListItemDto(CustomerEntity c, WholesaleApplicationEntity app) {
        AdminCustomerListItemDto dto = new AdminCustomerListItemDto();
        dto.id         = c.id.toString();
        dto.firstName  = c.firstName;
        dto.lastName   = c.lastName;
        dto.email      = c.user != null ? c.user.email : null;
        dto.status     = c.status != null ? c.status.name() : null;
        dto.shopperType = c.shopperType != null ? c.shopperType.name() : null;
        dto.registeredAt = c.user != null && c.user.createdAt != null
                ? c.user.createdAt.toString()
                : null;
        dto.wholesaleApplicationStatus = app != null && app.status != null
                ? app.status.name()
                : null;
        return dto;
    }

    private AdminCustomerDetailDto toDetailDto(CustomerEntity c,
                                               WholesaleApplicationEntity app,
                                               List<OrderEntity> orders) {
        AdminCustomerDetailDto dto = new AdminCustomerDetailDto();
        dto.id          = c.id.toString();
        dto.firstName   = c.firstName;
        dto.lastName    = c.lastName;
        dto.email       = c.user != null ? c.user.email : null;
        dto.phone       = c.phone;
        dto.status      = c.status != null ? c.status.name() : null;
        dto.shopperType = c.shopperType != null ? c.shopperType.name() : null;
        dto.registeredAt = c.user != null && c.user.createdAt != null
                ? c.user.createdAt.toString()
                : null;

        dto.wholesaleApplication = app != null ? toApplicationDetailsDto(app) : null;

        dto.recentOrders = orders.stream()
                .map(this::toOrderRefDto)
                .toList();

        return dto;
    }

    private WholesaleApplicationDetailsDto toApplicationDetailsDto(WholesaleApplicationEntity app) {
        WholesaleApplicationDetailsDto dto = new WholesaleApplicationDetailsDto();
        dto.setId(app.id);
        dto.setEmail(app.accountEmail);
        dto.setFirstName(app.firstName);
        dto.setLastName(app.lastName);
        dto.setPhone(app.phone);
        dto.setCompanyName(app.companyName);
        dto.setVatNumber(app.vatNumber);
        dto.setRegNumber(app.regNumber);
        dto.setNotes(app.notes);
        dto.setStatus(app.status);
        dto.setCreatedAt(app.createdAt);
        dto.setProcessedAt(app.processedAt);
        dto.setCustomerId(app.customer != null ? app.customer.id : null);

        dto.setPhysicalAddressLine1(app.physicalAddressLine1);
        dto.setPhysicalAddressLine2(app.physicalAddressLine2);
        dto.setPhysicalSuburb(app.physicalSuburb);
        dto.setPhysicalCity(app.physicalCity);
        dto.setPhysicalProvince(app.physicalProvince);
        dto.setPhysicalPostalCode(app.physicalPostalCode);

        dto.setPostalAddressLine1(app.postalAddressLine1);
        dto.setPostalAddressLine2(app.postalAddressLine2);
        dto.setPostalSuburb(app.postalSuburb);
        dto.setPostalCity(app.postalCity);
        dto.setPostalProvince(app.postalProvince);
        dto.setPostalPostalCode(app.postalPostalCode);
        return dto;
    }

    private AdminOrderRefDto toOrderRefDto(OrderEntity o) {
        AdminOrderRefDto dto = new AdminOrderRefDto();
        dto.id        = o.id.toString();
        dto.reference = "ORD-" + o.id.toString().substring(0, 8).toUpperCase();
        dto.placedAt  = o.createdAt != null ? o.createdAt.toString() : null;
        dto.total     = o.totalAmount != null ? o.totalAmount.doubleValue() : 0.0;
        dto.status    = o.status != null ? o.status.name() : null;
        return dto;
    }
}
