package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.dto.WholesaleApplicationListItemDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.WholesaleApplicationRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@QuarkusTest
class WholesaleCustomerServiceTest {

    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @InjectMock
    WholesaleApplicationRepository wholesaleApplicationRepository;

    @Test
    void getWholesaleApplications_shouldReturnMinimalDtos() {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();

        WholesaleApplicationEntity first = new WholesaleApplicationEntity();
        first.id = UUID.randomUUID();
        first.createdAt = OffsetDateTime.parse("2026-05-14T08:00:00Z");
        first.status = WholesaleApplicationStatusEn.PENDING;

        WholesaleApplicationEntity second = new WholesaleApplicationEntity();
        second.id = UUID.randomUUID();
        second.createdAt = OffsetDateTime.parse("2026-05-14T09:00:00Z");
        second.status = WholesaleApplicationStatusEn.APPROVED;

        when(wholesaleApplicationRepository.findAll(pageRequest, filterRequest)).thenReturn(List.of(first, second));

        List<WholesaleApplicationListItemDto> result = wholesaleCustomerService.getWholesaleApplications(pageRequest, filterRequest);

        assertEquals(2, result.size());
        assertEquals(first.id, result.getFirst().getId());
        assertEquals(first.createdAt, result.getFirst().getCreatedAt());
        assertEquals(first.status, result.getFirst().getStatus());
    }

    @Test
    void wholesaleApplicationCount_shouldReturnRepositoryCount() {
        FilterRequest filterRequest = new FilterRequest();
        when(wholesaleApplicationRepository.count(filterRequest)).thenReturn(7L);

        long count = wholesaleCustomerService.wholesaleApplicationCount(filterRequest);

        assertEquals(7L, count);
    }

    @Test
    void getWholesaleApplicationById_shouldThrowWhenIdIsNull() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> wholesaleCustomerService.getWholesaleApplicationById(null)
        );

        assertEquals("id is required", ex.getMessage());
    }

    @Test
    void getWholesaleApplicationById_shouldThrowWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(wholesaleApplicationRepository.findById(id)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> wholesaleCustomerService.getWholesaleApplicationById(id)
        );

        assertEquals("wholesale application not found: " + id, ex.getMessage());
    }

    @Test
    void getWholesaleApplicationById_shouldReturnFullDetails() {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        CustomerEntity customer = new CustomerEntity();
        customer.id = customerId;

        WholesaleApplicationEntity application = new WholesaleApplicationEntity();
        application.id = id;
        application.accountEmail = "buyer@example.com";
        application.firstName = "Ana";
        application.lastName = "Smith";
        application.companyName = "ACME";
        application.status = WholesaleApplicationStatusEn.CONVERTED;
        application.createdAt = OffsetDateTime.parse("2026-05-14T10:00:00Z");
        application.processedAt = OffsetDateTime.parse("2026-05-14T12:00:00Z");
        application.customer = customer;

        when(wholesaleApplicationRepository.findById(id)).thenReturn(application);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(id);

        assertEquals(id, result.getId());
        assertEquals("buyer@example.com", result.getEmail());
        assertEquals("Ana", result.getFirstName());
        assertEquals("Smith", result.getLastName());
        assertEquals("ACME", result.getCompanyName());
        assertEquals(WholesaleApplicationStatusEn.CONVERTED, result.getStatus());
        assertEquals(application.createdAt, result.getCreatedAt());
        assertEquals(application.processedAt, result.getProcessedAt());
        assertEquals(customerId, result.getCustomerId());
        assertNull(result.getNotes());
    }
}

