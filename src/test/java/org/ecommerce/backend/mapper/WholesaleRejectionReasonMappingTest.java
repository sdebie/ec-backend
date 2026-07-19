package org.ecommerce.backend.mapper;

// Feature: wholesale-application-review-workflow, Task 1.2
// Validates: Requirements 1.2, 3.2

import org.ecommerce.common.dto.AdminCustomerDetailDto;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Asserts that {@code rejectionReason} is surfaced by BOTH mapping paths:
 * <ol>
 *   <li>Wholesale-queue detail: {@link WholesaleMapper#toDetailsDto}</li>
 *   <li>Customer-admin detail: {@link CustomerAdminMapper#toDetailDto} (delegates to WholesaleMapper)</li>
 * </ol>
 *
 * Validates: Requirements 1.2, 3.2
 */
class WholesaleRejectionReasonMappingTest {

    private WholesaleMapper wholesaleMapper;
    private CustomerAdminMapper customerAdminMapper;

    @BeforeEach
    void setUp() {
        wholesaleMapper = new WholesaleMapperImpl();

        customerAdminMapper = new CustomerAdminMapper();
        try {
            var field = CustomerAdminMapper.class.getDeclaredField("wholesaleMapper");
            field.setAccessible(true);
            field.set(customerAdminMapper, wholesaleMapper);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to inject WholesaleMapper into CustomerAdminMapper", e);
        }
    }

    // ── Path 1: WholesaleMapper.toDetailsDto (wholesale-queue detail) ────────

    @Test
    void toDetailsDto_mapsRejectionReason_whenPresent() {
        WholesaleApplicationEntity entity = buildRejectedEntity("Quality standards not met");

        WholesaleApplicationDetailsDto dto = wholesaleMapper.toDetailsDto(entity);

        assertEquals("Quality standards not met", dto.getRejectionReason(),
                "rejectionReason should be mapped from entity to details DTO");
    }

    @Test
    void toDetailsDto_rejectionReasonIsNull_whenApproved() {
        WholesaleApplicationEntity entity = buildApprovedEntity();

        WholesaleApplicationDetailsDto dto = wholesaleMapper.toDetailsDto(entity);

        assertNull(dto.getRejectionReason(),
                "rejectionReason should be null for approved applications");
    }

    @Test
    void toDetailsDto_rejectionReasonIsNull_whenEntityFieldIsNull() {
        WholesaleApplicationEntity entity = buildPendingEntity();

        WholesaleApplicationDetailsDto dto = wholesaleMapper.toDetailsDto(entity);

        assertNull(dto.getRejectionReason(),
                "rejectionReason should be null for pending applications");
    }

    // ── Path 2: CustomerAdminMapper.toDetailDto (customer-admin detail) ──────

    @Test
    void customerAdminDetailDto_surfacesRejectionReason_whenPresent() {
        CustomerEntity customer = new CustomerEntity();
        customer.id = UUID.randomUUID();
        customer.firstName = "Jane";
        customer.lastName = "Doe";

        WholesaleApplicationEntity entity = buildRejectedEntity("Incomplete documentation");
        entity.customer = customer;

        AdminCustomerDetailDto dto = customerAdminMapper.toDetailDto(customer, entity, Collections.emptyList());

        assertNotNull(dto.wholesaleApplication,
                "wholesaleApplication should be present in customer detail DTO");
        assertEquals("Incomplete documentation", dto.wholesaleApplication.getRejectionReason(),
                "rejectionReason should be surfaced through the customer-admin detail path");
    }

    @Test
    void customerAdminDetailDto_rejectionReasonIsNull_whenApproved() {
        CustomerEntity customer = new CustomerEntity();
        customer.id = UUID.randomUUID();
        customer.firstName = "John";
        customer.lastName = "Smith";

        WholesaleApplicationEntity entity = buildApprovedEntity();
        entity.customer = customer;

        AdminCustomerDetailDto dto = customerAdminMapper.toDetailDto(customer, entity, Collections.emptyList());

        assertNotNull(dto.wholesaleApplication,
                "wholesaleApplication should be present in customer detail DTO");
        assertNull(dto.wholesaleApplication.getRejectionReason(),
                "rejectionReason should be null for approved applications via customer-admin path");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WholesaleApplicationEntity buildRejectedEntity(String reason) {
        WholesaleApplicationEntity entity = new WholesaleApplicationEntity();
        entity.id = UUID.randomUUID();
        entity.applicantEmail = "applicant@example.com";
        entity.firstName = "Test";
        entity.lastName = "User";
        entity.companyName = "Test Corp";
        entity.status = WholesaleApplicationStatusEn.REJECTED;
        entity.processedAt = OffsetDateTime.now();
        entity.rejectionReason = reason;
        return entity;
    }

    private WholesaleApplicationEntity buildApprovedEntity() {
        WholesaleApplicationEntity entity = new WholesaleApplicationEntity();
        entity.id = UUID.randomUUID();
        entity.applicantEmail = "applicant@example.com";
        entity.firstName = "Test";
        entity.lastName = "User";
        entity.companyName = "Test Corp";
        entity.status = WholesaleApplicationStatusEn.APPROVED;
        entity.processedAt = OffsetDateTime.now();
        entity.rejectionReason = null;
        return entity;
    }

    private WholesaleApplicationEntity buildPendingEntity() {
        WholesaleApplicationEntity entity = new WholesaleApplicationEntity();
        entity.id = UUID.randomUUID();
        entity.applicantEmail = "applicant@example.com";
        entity.firstName = "Test";
        entity.lastName = "User";
        entity.companyName = "Test Corp";
        entity.status = WholesaleApplicationStatusEn.PENDING;
        entity.rejectionReason = null;
        return entity;
    }
}
