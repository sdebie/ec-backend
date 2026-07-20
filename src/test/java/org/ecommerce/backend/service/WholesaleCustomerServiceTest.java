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

    @Test
    void toDetailsDto_shouldMapAllNewFieldsFromEntity() {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        CustomerEntity customer = new CustomerEntity();
        customer.id = customerId;

        WholesaleApplicationEntity application = new WholesaleApplicationEntity();
        application.id = id;
        application.applicantEmail = "applicant@example.com";
        application.accountEmail = "account@example.com";
        application.firstName = "John";
        application.lastName = "Doe";
        application.phone = "0821234567";
        application.companyName = "Wholesale Corp";
        application.tradingName = "WC Trading";
        application.companyPhone = "0111234567";
        application.companyEmail = "info@wholesalecorp.co.za";
        application.vatNumber = "VAT123456";
        application.regNumber = "REG789012";
        application.financeContactName = "Jane Finance";
        application.financeContactEmail = "jane@wholesalecorp.co.za";
        application.financeContactPhone = "0119876543";
        application.purchaseOrderRequired = true;
        application.notes = "We order in bulk monthly";
        application.status = WholesaleApplicationStatusEn.PENDING;
        application.createdAt = OffsetDateTime.parse("2026-06-01T09:00:00Z");
        application.processedAt = null;
        application.physicalAddressLine1 = "10 Main Road";
        application.physicalAddressLine2 = "Unit 5";
        application.physicalSuburb = "Sandton";
        application.physicalCity = "Johannesburg";
        application.physicalProvince = "Gauteng";
        application.physicalPostalCode = "2196";
        application.postalAddressLine1 = "PO Box 100";
        application.postalAddressLine2 = null;
        application.postalSuburb = "Braamfontein";
        application.postalCity = "Johannesburg";
        application.postalProvince = "Gauteng";
        application.postalPostalCode = "2001";
        application.customer = customer;

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
        assertEquals("10 Main Road", result.getPhysicalAddressLine1());
        assertEquals("Unit 5", result.getPhysicalAddressLine2());
        assertEquals("Sandton", result.getPhysicalSuburb());
        assertEquals("Johannesburg", result.getPhysicalCity());
        assertEquals("Gauteng", result.getPhysicalProvince());
        assertEquals("2196", result.getPhysicalPostalCode());
        assertEquals("PO Box 100", result.getPostalAddressLine1());
        assertNull(result.getPostalAddressLine2());
        assertEquals("Braamfontein", result.getPostalSuburb());
        assertEquals("Johannesburg", result.getPostalCity());
        assertEquals("Gauteng", result.getPostalProvince());
        assertEquals("2001", result.getPostalPostalCode());
    }
}

