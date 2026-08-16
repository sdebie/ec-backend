package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.mapper.CustomerAdminMapper;
import org.ecommerce.common.dto.AdminCustomerDetailDto;
import org.ecommerce.common.dto.AdminCustomerListItemDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.OrderEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.CustomerRepository;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CustomerAdminService
{

    private static final Logger LOG = Logger.getLogger(CustomerAdminService.class);

    @Inject
    CustomerAdminMapper customerAdminMapper;

    @Inject
    CustomerRepository customerRepository;

    public List<AdminCustomerListItemDto> allCustomers(PageRequest pageRequest, FilterRequest filterRequest)
    {
        return customerRepository.findForAdmin(filterRequest, pageRequest)
                .stream()
                .map(c -> customerAdminMapper.toListItemDto(c, wholesaleApplicationFor(c)))
                .toList();
    }

    public long customerCount(FilterRequest filterRequest)
    {
        return customerRepository.countForAdmin(filterRequest);
    }

    public AdminCustomerDetailDto adminCustomer(UUID id)
    {
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

        return customerAdminMapper.toDetailDto(customer, app, orders);
    }

    @Transactional
    public AdminCustomerListItemDto updateCustomerStatus(UUID id, String status)
    {
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

        validateStatusTransition(customer.getStatus(), newStatus);

        customer.setStatus(newStatus);
        customer.persist();

        return customerAdminMapper.toListItemDto(customer, wholesaleApplicationFor(customer));
    }

    private void validateStatusTransition(CustomerStatusEn current, CustomerStatusEn next)
    {
        boolean valid = switch (current) {
            case PENDING, DISABLED -> next == CustomerStatusEn.ACTIVE;
            case ACTIVE -> next == CustomerStatusEn.DISABLED;
        };

        if (!valid) {
            throw new IllegalArgumentException("invalid status transition: " + current + " → " + next);
        }
    }

    /**
     * Loaded here rather than inside the mapper: mappers do not open queries.
     * <p>
     * ⚠️ Called per row by {@link #allCustomers}, so a page of N customers costs N queries.
     * Left as-is to keep this change behaviour-preserving — the previous mapper did exactly
     * the same thing per row — but it is a genuine N+1 worth batching.
     */
    private WholesaleApplicationEntity wholesaleApplicationFor(CustomerEntity customer)
    {
        return WholesaleApplicationEntity.find("customer.id = ?1", customer.getId()).firstResult();
    }
}
