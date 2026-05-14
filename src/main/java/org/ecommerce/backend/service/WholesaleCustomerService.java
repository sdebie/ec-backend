package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.util.JsonConverter;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;

import java.util.UUID;

@ApplicationScoped
public class WholesaleCustomerService {

    @Transactional
    public WholesaleCustomerDto createWholesaleCustomer(WholesaleCustomerDto customerDto) {
        if (customerDto == null) {
            throw new IllegalArgumentException("customer is required");
        }

        String email = normalizeEmail(customerDto.getEmail());
        if (email == null) {
            throw new IllegalArgumentException("email is required");
        }

        if (CustomerEntity.findByEmail(email) != null) {
            throw new IllegalArgumentException("customer already exists with email: " + email);
        }

        CustomerEntity customerEntity = new CustomerEntity();
        customerEntity.email = email;
        customerEntity.shopperType = CustomerTypeEn.WHOLESALER;
        customerEntity.status = resolveStatus(customerDto.getStatus(), CustomerStatusEn.REGISTERING);

        applyEditableFields(customerEntity, customerDto);

        CustomerEntity.persist(customerEntity);
        return toDto(customerEntity);
    }

    @Transactional
    public WholesaleCustomerDto updateWholesaleCustomer(UUID id, WholesaleCustomerDto customerDto) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (customerDto == null) {
            throw new IllegalArgumentException("customer is required");
        }

        CustomerEntity customerEntity = CustomerEntity.findById(id);
        if (customerEntity == null) {
            throw new IllegalArgumentException("customer not found: " + id);
        }
        if (customerEntity.shopperType != CustomerTypeEn.WHOLESALER) {
            throw new IllegalArgumentException("customer is not a wholesale customer: " + id);
        }

        if (customerDto.getEmail() != null) {
            String email = normalizeEmail(customerDto.getEmail());
            if (email == null) {
                throw new IllegalArgumentException("email cannot be blank");
            }

            CustomerEntity existing = CustomerEntity.findByEmail(email);
            if (existing != null && !existing.id.equals(customerEntity.id)) {
                throw new IllegalArgumentException("customer already exists with email: " + email);
            }
            customerEntity.email = email;
        }

        if (customerDto.getStatus() != null) {
            customerEntity.status = resolveStatus(customerDto.getStatus(), customerEntity.status);
        }

        applyEditableFields(customerEntity, customerDto);

        customerEntity.shopperType = CustomerTypeEn.WHOLESALER;
        customerEntity.persist();

        return toDto(customerEntity);
    }

    private void applyEditableFields(CustomerEntity customerEntity, WholesaleCustomerDto customerDto) {
        if (customerDto.getFirstName() != null) customerEntity.firstName = customerDto.getFirstName();
        if (customerDto.getLastName() != null) customerEntity.lastName = customerDto.getLastName();
        if (customerDto.getPhone() != null) customerEntity.phone = customerDto.getPhone();
        if (customerDto.getPhysicalAddressLine1() != null) customerEntity.physicalAddressLine1 = customerDto.getPhysicalAddressLine1();
        if (customerDto.getPhysicalAddressLine2() != null) customerEntity.physicalAddressLine2 = customerDto.getPhysicalAddressLine2();
        if (customerDto.getPhysicalSuburb() != null) customerEntity.physicalSuburb = customerDto.getPhysicalSuburb();
        if (customerDto.getPhysicalCity() != null) customerEntity.physicalCity = customerDto.getPhysicalCity();
        if (customerDto.getPhysicalProvince() != null) customerEntity.physicalProvince = customerDto.getPhysicalProvince();
        if (customerDto.getPhysicalPostalCode() != null) customerEntity.physicalPostalCode = customerDto.getPhysicalPostalCode();

        if (customerDto.getPostalAddressLine1() != null) customerEntity.postalAddressLine1 = customerDto.getPostalAddressLine1();
        if (customerDto.getPostalAddressLine2() != null) customerEntity.postalAddressLine2 = customerDto.getPostalAddressLine2();
        if (customerDto.getPostalSuburb() != null) customerEntity.postalSuburb = customerDto.getPostalSuburb();
        if (customerDto.getPostalCity() != null) customerEntity.postalCity = customerDto.getPostalCity();
        if (customerDto.getPostalProvince() != null) customerEntity.postalProvince = customerDto.getPostalProvince();
        if (customerDto.getPostalPostalCode() != null) customerEntity.postalPostalCode = customerDto.getPostalPostalCode();
        if (customerDto.getAdditionalInfo() != null) {
            customerEntity.additionalInfo = JsonConverter.toJsonString(customerDto.getAdditionalInfo());
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }

        String normalized = email.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized;
    }

    private CustomerStatusEn resolveStatus(String statusValue, CustomerStatusEn fallback) {
        if (statusValue == null || statusValue.isBlank()) {
            return fallback;
        }

        try {
            return CustomerStatusEn.valueOf(statusValue.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid status: " + statusValue);
        }
    }

    private WholesaleCustomerDto toDto(CustomerEntity customerEntity) {
        WholesaleCustomerDto dto = new WholesaleCustomerDto();
        dto.setId(customerEntity.id);
        dto.setEmail(customerEntity.email);
        dto.setFirstName(customerEntity.firstName);
        dto.setLastName(customerEntity.lastName);
        dto.setPhone(customerEntity.phone);
        dto.setPhysicalAddressLine1(customerEntity.physicalAddressLine1);
        dto.setPhysicalAddressLine2(customerEntity.physicalAddressLine2);
        dto.setPhysicalSuburb(customerEntity.physicalSuburb);
        dto.setPhysicalCity(customerEntity.physicalCity);
        dto.setPhysicalProvince(customerEntity.physicalProvince);
        dto.setPhysicalPostalCode(customerEntity.physicalPostalCode);

        dto.setPostalAddressLine1(customerEntity.postalAddressLine1);
        dto.setPostalAddressLine2(customerEntity.postalAddressLine2);
        dto.setPostalSuburb(customerEntity.postalSuburb);
        dto.setPostalCity(customerEntity.postalCity);
        dto.setPostalProvince(customerEntity.postalProvince);
        dto.setPostalPostalCode(customerEntity.postalPostalCode);
        dto.setAdditionalInfo(customerEntity.additionalInfo);
        if (customerEntity.status != null) {
            dto.setStatus(customerEntity.status.name());
        }
        return dto;
    }
}

