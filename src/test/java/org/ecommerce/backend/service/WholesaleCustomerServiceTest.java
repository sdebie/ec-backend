package org.ecommerce.backend.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.dto.WholesaleApplicationListItemDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.enums.FilterOperator;
import org.ecommerce.common.repository.WholesaleApplicationRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class WholesaleCustomerServiceTest
{
    @Inject
    WholesaleCustomerService wholesaleCustomerService;

    @InjectMock
    WholesaleApplicationRepository wholesaleApplicationRepository;

    @Test
    void getWholesaleApplications_shouldReturnMinimalDtos()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();

        WholesaleApplicationEntity first = new WholesaleApplicationEntity();
        first.setId(UUID.randomUUID());
        first.setCreatedAt(OffsetDateTime.parse("2026-05-14T08:00:00Z"));
        first.setStatus(WholesaleApplicationStatusEn.PENDING);

        WholesaleApplicationEntity second = new WholesaleApplicationEntity();
        second.setId(UUID.randomUUID());
        second.setCreatedAt(OffsetDateTime.parse("2026-05-14T09:00:00Z"));
        second.setStatus(WholesaleApplicationStatusEn.APPROVED);

        when(wholesaleApplicationRepository.findForAdmin(isNull(), isNull(), isNull(), isNull(), eq(pageRequest)))
                .thenReturn(List.of(first, second));

        List<WholesaleApplicationListItemDto> result = wholesaleCustomerService.getWholesaleApplications(pageRequest, filterRequest, null, null);

        assertEquals(2, result.size());
        assertEquals(first.getId(), result.getFirst().getId());
        assertEquals(first.getCreatedAt(), result.getFirst().getCreatedAt());
        assertEquals(first.getStatus(), result.getFirst().getStatus());
    }

    @Test
    void wholesaleApplicationCount_shouldReturnRepositoryCount()
    {
        FilterRequest filterRequest = new FilterRequest();
        when(wholesaleApplicationRepository.countForAdmin(isNull(), isNull(), isNull())).thenReturn(7L);

        long count = wholesaleCustomerService.wholesaleApplicationCount(filterRequest, null, null);

        assertEquals(7L, count);
    }

    @Test
    void getWholesaleApplications_extractsStatusFilterFromFilterRequest()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();
        filterRequest.setFilters(List.of(new Filter("status", FilterOperator.EQUALS, "PENDING")));

        when(wholesaleApplicationRepository.findForAdmin(eq(WholesaleApplicationStatusEn.PENDING), isNull(), isNull(), isNull(), eq(pageRequest)))
                .thenReturn(List.of());

        wholesaleCustomerService.getWholesaleApplications(pageRequest, filterRequest, null, null);

        verify(wholesaleApplicationRepository).findForAdmin(eq(WholesaleApplicationStatusEn.PENDING), isNull(), isNull(), isNull(), eq(pageRequest));
    }

    @Test
    void getWholesaleApplications_rejectsUnparsableFromDate()
    {
        PageRequest pageRequest = new PageRequest();
        FilterRequest filterRequest = new FilterRequest();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> wholesaleCustomerService.getWholesaleApplications(pageRequest, filterRequest, "not-a-date", null));

        assertTrue(ex.getMessage().contains("fromDate"));
    }

    @Test
    void getWholesaleApplicationById_shouldThrowWhenIdIsNull()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> wholesaleCustomerService.getWholesaleApplicationById(null));

        assertEquals("id is required", ex.getMessage());
    }

    @Test
    void getWholesaleApplicationById_shouldThrowWhenNotFound()
    {
        UUID id = UUID.randomUUID();
        when(wholesaleApplicationRepository.findById(id)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> wholesaleCustomerService.getWholesaleApplicationById(id));

        assertEquals("wholesale application not found: " + id, ex.getMessage());
    }

    @Test
    void getWholesaleApplicationById_shouldReturnFullDetails()
    {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);

        WholesaleApplicationEntity application = new WholesaleApplicationEntity();
        application.setId(id);
        application.setAccountEmail("buyer@example.com");
        application.setFirstName("Ana");
        application.setLastName("Smith");
        application.setCompanyName("ACME");
        application.setStatus(WholesaleApplicationStatusEn.CONVERTED);
        application.setCreatedAt(OffsetDateTime.parse("2026-05-14T10:00:00Z"));
        application.setProcessedAt(OffsetDateTime.parse("2026-05-14T12:00:00Z"));
        application.setCustomer(customer);

        when(wholesaleApplicationRepository.findById(id)).thenReturn(application);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(id);

        assertEquals(id, result.getId());
        assertEquals("buyer@example.com", result.getEmail());
        assertEquals("Ana", result.getFirstName());
        assertEquals("Smith", result.getLastName());
        assertEquals("ACME", result.getCompanyName());
        assertEquals(WholesaleApplicationStatusEn.CONVERTED, result.getStatus());
        assertEquals(application.getCreatedAt(), result.getCreatedAt());
        assertEquals(application.getProcessedAt(), result.getProcessedAt());
        assertEquals(customerId, result.getCustomerId());
        assertNull(result.getNotes());
    }

    @Test
    void toDetailsDto_shouldMapAllNewFieldsFromEntity()
    {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);

        WholesaleApplicationEntity application = new WholesaleApplicationEntity();
        application.setId(id);
        application.setApplicantEmail("applicant@example.com");
        application.setAccountEmail("account@example.com");
        application.setFirstName("John");
        application.setLastName("Doe");
        application.setPhone("0821234567");
        application.setCompanyName("Wholesale Corp");
        application.setTradingName("WC Trading");
        application.setCompanyPhone("0111234567");
        application.setCompanyEmail("info@wholesalecorp.co.za");
        application.setVatNumber("VAT123456");
        application.setRegNumber("REG789012");
        application.setFinanceContactName("Jane Finance");
        application.setFinanceContactEmail("jane@wholesalecorp.co.za");
        application.setFinanceContactPhone("0119876543");
        application.setPurchaseOrderRequired(true);
        application.setNotes("We order in bulk monthly");
        application.setStatus(WholesaleApplicationStatusEn.PENDING);
        application.setCreatedAt(OffsetDateTime.parse("2026-06-01T09:00:00Z"));
        application.setProcessedAt(null);
        application.setPhysicalAddressLine1("10 Main Road");
        application.setPhysicalAddressLine2("Unit 5");
        application.setPhysicalSuburb("Sandton");
        application.setPhysicalCity("Johannesburg");
        application.setPhysicalProvince("Gauteng");
        application.setPhysicalPostalCode("2196");
        application.setPostalAddressLine1("PO Box 100");
        application.setPostalAddressLine2(null);
        application.setPostalSuburb("Braamfontein");
        application.setPostalCity("Johannesburg");
        application.setPostalProvince("Gauteng");
        application.setPostalPostalCode("2001");
        application.setCustomer(customer);

        when(wholesaleApplicationRepository.findById(id)).thenReturn(application);

        WholesaleApplicationDetailsDto result = wholesaleCustomerService.getWholesaleApplicationById(id);

        // New fields
        assertEquals("applicant@example.com", result.getApplicantEmail());
        assertEquals("WC Trading", result.getTradingName());
        assertEquals("0111234567", result.getCompanyPhone());
        assertEquals("info@wholesalecorp.co.za", result.getCompanyEmail());
        assertEquals("Jane Finance", result.getFinanceContactName());
        assertEquals("jane@wholesalecorp.co.za", result.getFinanceContactEmail());
        assertEquals("0119876543", result.getFinanceContactPhone());
        assertEquals(true, result.getPurchaseOrderRequired());

        // Existing fields still mapped correctly
        assertEquals(id, result.getId());
        assertEquals("account@example.com", result.getEmail());
        assertEquals("John", result.getFirstName());
        assertEquals("Doe", result.getLastName());
        assertEquals("0821234567", result.getPhone());
        assertEquals("Wholesale Corp", result.getCompanyName());
        assertEquals("VAT123456", result.getVatNumber());
        assertEquals("REG789012", result.getRegNumber());
        assertEquals("We order in bulk monthly", result.getNotes());
        assertEquals(WholesaleApplicationStatusEn.PENDING, result.getStatus());
        assertEquals(OffsetDateTime.parse("2026-06-01T09:00:00Z"), result.getCreatedAt());
        assertNull(result.getProcessedAt());
        assertEquals(customerId, result.getCustomerId());

        // Address fields
        assertEquals("10 Main Road", result.getPhysicalAddress().getLine1());
        assertEquals("Unit 5", result.getPhysicalAddress().getLine2());
        assertEquals("Sandton", result.getPhysicalAddress().getSuburb());
        assertEquals("Johannesburg", result.getPhysicalAddress().getCity());
        assertEquals("Gauteng", result.getPhysicalAddress().getProvince());
        assertEquals("2196", result.getPhysicalAddress().getPostalCode());
        assertEquals("PO Box 100", result.getPostalAddress().getLine1());
        assertNull(result.getPostalAddress().getLine2());
        assertEquals("Braamfontein", result.getPostalAddress().getSuburb());
        assertEquals("Johannesburg", result.getPostalAddress().getCity());
        assertEquals("Gauteng", result.getPostalAddress().getProvince());
        assertEquals("2001", result.getPostalAddress().getPostalCode());
    }
}

