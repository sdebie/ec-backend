package org.ecommerce.backend.mapper;

// Feature: service-layer-refactor, Property 2: Mapper output preservation (wholesale application)

import net.jqwik.api.*;
import org.ecommerce.common.dto.AddressDto;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Property-based test verifying that the MapStruct {@link WholesaleMapper#toDetailsDto}
 * produces output identical to the old inline mapping logic that previously lived in
 * both {@code WholesaleCustomerService.toDetailsDto} and
 * {@code CustomerAdminService.toApplicationDetailsDto}.
 * <p>
 * The reference implementation below is a direct transcription of the deleted inline
 * methods — a simple field-by-field copy with {@code accountEmail → email} and
 * null-safe {@code customer.id → customerId}.
 * <p>
 */
public class WholesaleMapperOutputPreservationPropertyTest
{
    private final WholesaleMapper mapper = new WholesaleMapperImpl();

    // ── Reference implementation (old inline logic) ──────────────────────────────

    /**
     * Replicates the old hand-written toDetailsDto that existed in both
     * WholesaleCustomerService and CustomerAdminService before consolidation.
     * This is a straightforward field-by-field copy.
     */
    private WholesaleApplicationDetailsDto referenceToDetailsDto(WholesaleApplicationEntity application)
    {
        if (application == null) {
            return null;
        }

        WholesaleApplicationDetailsDto dto = new WholesaleApplicationDetailsDto();
        dto.setId(application.getId());
        dto.setEmail(application.getAccountEmail());
        dto.setFirstName(application.getFirstName());
        dto.setLastName(application.getLastName());
        dto.setPhone(application.getPhone());

        AddressDto physical = new AddressDto();
        physical.setLine1(application.getPhysicalAddressLine1());
        physical.setLine2(application.getPhysicalAddressLine2());
        physical.setSuburb(application.getPhysicalSuburb());
        physical.setCity(application.getPhysicalCity());
        physical.setProvince(application.getPhysicalProvince());
        physical.setPostalCode(application.getPhysicalPostalCode());
        dto.setPhysicalAddress(physical);

        AddressDto postal = new AddressDto();
        postal.setLine1(application.getPostalAddressLine1());
        postal.setLine2(application.getPostalAddressLine2());
        postal.setSuburb(application.getPostalSuburb());
        postal.setCity(application.getPostalCity());
        postal.setProvince(application.getPostalProvince());
        postal.setPostalCode(application.getPostalPostalCode());
        dto.setPostalAddress(postal);

        dto.setCompanyName(application.getCompanyName());
        dto.setTradingName(application.getTradingName());
        dto.setCompanyPhone(application.getCompanyPhone());
        dto.setCompanyEmail(application.getCompanyEmail());
        dto.setVatNumber(application.getVatNumber());
        dto.setRegNumber(application.getRegNumber());
        dto.setFinanceContactName(application.getFinanceContactName());
        dto.setFinanceContactEmail(application.getFinanceContactEmail());
        dto.setFinanceContactPhone(application.getFinanceContactPhone());
        dto.setPurchaseOrderRequired(application.getPurchaseOrderRequired());
        dto.setNotes(application.getNotes());
        dto.setApplicantEmail(application.getApplicantEmail());
        dto.setRejectionReason(application.getRejectionReason());

        dto.setStatus(application.getStatus());
        dto.setCreatedAt(application.getCreatedAt());
        dto.setProcessedAt(application.getProcessedAt());

        // Null-safe customer.id navigation
        dto.setCustomerId(application.getCustomer() != null ? application.getCustomer().getId() : null);

        return dto;
    }

    // ── Property test ────────────────────────────────────────────────────────────

    @Property(tries = 200)
    void mapperOutputMatchesOldInlineLogic(
            @ForAll("wholesaleApplicationEntities") WholesaleApplicationEntity entity
    )
    {
        // Act
        WholesaleApplicationDetailsDto mapperResult = mapper.toDetailsDto(entity);
        WholesaleApplicationDetailsDto referenceResult = referenceToDetailsDto(entity);

        // Assert all fields match
        assertEquals(referenceResult.getId(), mapperResult.getId(), "id mismatch");
        assertEquals(referenceResult.getEmail(), mapperResult.getEmail(), "email (accountEmail) mismatch");
        assertEquals(referenceResult.getFirstName(), mapperResult.getFirstName(), "firstName mismatch");
        assertEquals(referenceResult.getLastName(), mapperResult.getLastName(), "lastName mismatch");
        assertEquals(referenceResult.getPhone(), mapperResult.getPhone(), "phone mismatch");

        assertEquals(referenceResult.getPhysicalAddress().getLine1(), mapperResult.getPhysicalAddress().getLine1(), "physicalAddress.line1 mismatch");
        assertEquals(referenceResult.getPhysicalAddress().getLine2(), mapperResult.getPhysicalAddress().getLine2(), "physicalAddress.line2 mismatch");
        assertEquals(referenceResult.getPhysicalAddress().getSuburb(), mapperResult.getPhysicalAddress().getSuburb(), "physicalAddress.suburb mismatch");
        assertEquals(referenceResult.getPhysicalAddress().getCity(), mapperResult.getPhysicalAddress().getCity(), "physicalAddress.city mismatch");
        assertEquals(referenceResult.getPhysicalAddress().getProvince(), mapperResult.getPhysicalAddress().getProvince(), "physicalAddress.province mismatch");
        assertEquals(referenceResult.getPhysicalAddress().getPostalCode(), mapperResult.getPhysicalAddress().getPostalCode(), "physicalAddress.postalCode mismatch");

        assertEquals(referenceResult.getPostalAddress().getLine1(), mapperResult.getPostalAddress().getLine1(), "postalAddress.line1 mismatch");
        assertEquals(referenceResult.getPostalAddress().getLine2(), mapperResult.getPostalAddress().getLine2(), "postalAddress.line2 mismatch");
        assertEquals(referenceResult.getPostalAddress().getSuburb(), mapperResult.getPostalAddress().getSuburb(), "postalAddress.suburb mismatch");
        assertEquals(referenceResult.getPostalAddress().getCity(), mapperResult.getPostalAddress().getCity(), "postalAddress.city mismatch");
        assertEquals(referenceResult.getPostalAddress().getProvince(), mapperResult.getPostalAddress().getProvince(), "postalAddress.province mismatch");
        assertEquals(referenceResult.getPostalAddress().getPostalCode(), mapperResult.getPostalAddress().getPostalCode(), "postalAddress.postalCode mismatch");

        assertEquals(referenceResult.getCompanyName(), mapperResult.getCompanyName(), "companyName mismatch");
        assertEquals(referenceResult.getTradingName(), mapperResult.getTradingName(), "tradingName mismatch");
        assertEquals(referenceResult.getCompanyPhone(), mapperResult.getCompanyPhone(), "companyPhone mismatch");
        assertEquals(referenceResult.getCompanyEmail(), mapperResult.getCompanyEmail(), "companyEmail mismatch");
        assertEquals(referenceResult.getVatNumber(), mapperResult.getVatNumber(), "vatNumber mismatch");
        assertEquals(referenceResult.getRegNumber(), mapperResult.getRegNumber(), "regNumber mismatch");
        assertEquals(referenceResult.getFinanceContactName(), mapperResult.getFinanceContactName(), "financeContactName mismatch");
        assertEquals(referenceResult.getFinanceContactEmail(), mapperResult.getFinanceContactEmail(), "financeContactEmail mismatch");
        assertEquals(referenceResult.getFinanceContactPhone(), mapperResult.getFinanceContactPhone(), "financeContactPhone mismatch");
        assertEquals(referenceResult.getPurchaseOrderRequired(), mapperResult.getPurchaseOrderRequired(), "purchaseOrderRequired mismatch");
        assertEquals(referenceResult.getNotes(), mapperResult.getNotes(), "notes mismatch");
        assertEquals(referenceResult.getApplicantEmail(), mapperResult.getApplicantEmail(), "applicantEmail mismatch");
        assertEquals(referenceResult.getRejectionReason(), mapperResult.getRejectionReason(), "rejectionReason mismatch");

        assertEquals(referenceResult.getStatus(), mapperResult.getStatus(), "status mismatch");
        assertEquals(referenceResult.getCreatedAt(), mapperResult.getCreatedAt(), "createdAt mismatch");
        assertEquals(referenceResult.getProcessedAt(), mapperResult.getProcessedAt(), "processedAt mismatch");
        assertEquals(referenceResult.getCustomerId(), mapperResult.getCustomerId(), "customerId mismatch");
    }

    @Property(tries = 100)
    void mapperReturnsNullForNullInput()
    {
        WholesaleApplicationDetailsDto mapperResult = mapper.toDetailsDto(null);
        WholesaleApplicationDetailsDto referenceResult = referenceToDetailsDto(null);
        assertNull(mapperResult);
        assertNull(referenceResult);
    }

    // ── Providers ────────────────────────────────────────────────────────────────

    @Provide
    Arbitrary<WholesaleApplicationEntity> wholesaleApplicationEntities()
    {
        return buildBaseEntity().flatMap(this::addFinanceAndMeta).flatMap(this::addAddresses);
    }

    /**
     * Stage 1: Core identity + company fields (8 combined arbitraries → entity skeleton)
     */
    private Arbitrary<WholesaleApplicationEntity> buildBaseEntity()
    {
        Arbitrary<UUID> ids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> requiredStrings = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
        Arbitrary<String> nullableStrings = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30)
        );
        Arbitrary<String> nullableEmails = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(8)
                        .map(s -> s.toLowerCase() + "@example.com")
        );

        return Combinators.combine(ids, requiredStrings, nullableEmails, requiredStrings,
                        nullableStrings, nullableStrings, requiredStrings, nullableStrings)
                .as((id, applicantEmail, accountEmail, firstName, lastName, phone, companyName, tradingName) -> {
                    WholesaleApplicationEntity e = new WholesaleApplicationEntity();
                    e.setId(id);
                    e.setApplicantEmail(applicantEmail);
                    e.setAccountEmail(accountEmail);
                    e.setFirstName(firstName);
                    e.setLastName(lastName);
                    e.setPhone(phone);
                    e.setCompanyName(companyName);
                    e.setTradingName(tradingName);
                    return e;
                });
    }

    /**
     * Stage 2: Finance contacts, purchase order, status, notes, dates, customer (8 combined)
     */
    private Arbitrary<WholesaleApplicationEntity> addFinanceAndMeta(WholesaleApplicationEntity entity)
    {
        Arbitrary<String> nullableStrings = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30)
        );
        Arbitrary<Boolean> nullableBooleans = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(true, false)
        );
        Arbitrary<WholesaleApplicationStatusEn> statuses = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(WholesaleApplicationStatusEn.values())
        );
        Arbitrary<OffsetDateTime> nullableDateTimes = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.longs().between(0L, 2000000000L)
                        .map(epoch -> OffsetDateTime.ofInstant(
                                java.time.Instant.ofEpochSecond(epoch), ZoneOffset.UTC))
        );
        Arbitrary<CustomerEntity> nullableCustomers = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.create(UUID::randomUUID).map(id -> {
                    CustomerEntity c = new CustomerEntity();
                    c.setId(id);
                    return c;
                })
        );

        return Combinators.combine(nullableStrings, nullableStrings, nullableStrings,
                        nullableBooleans, statuses, nullableStrings, nullableDateTimes, nullableCustomers)
                .as((finName, finEmail, finPhone, poRequired, status, notes, createdAt, customer) -> {
                    entity.setFinanceContactName(finName);
                    entity.setFinanceContactEmail(finEmail);
                    entity.setFinanceContactPhone(finPhone);
                    entity.setPurchaseOrderRequired(poRequired);
                    entity.setStatus(status);
                    entity.setNotes(notes);
                    entity.setCreatedAt(createdAt);
                    entity.setCustomer(customer);
                    return entity;
                }).flatMap(e -> {
                    // Also set processedAt + companyPhone/companyEmail/vatNumber/regNumber
                    Arbitrary<OffsetDateTime> processedAts = Arbitraries.oneOf(
                            Arbitraries.just(null),
                            Arbitraries.longs().between(0L, 2000000000L)
                                    .map(epoch -> OffsetDateTime.ofInstant(
                                            java.time.Instant.ofEpochSecond(epoch), ZoneOffset.UTC))
                    );
                    Arbitrary<String> nullableStrs = Arbitraries.oneOf(
                            Arbitraries.just(null),
                            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                    );
                    return Combinators.combine(processedAts, nullableStrs, nullableStrs, nullableStrs, nullableStrs, nullableStrs)
                            .as((processedAt, companyPhone, companyEmail, vatNumber, regNumber, rejectionReason) -> {
                                e.setProcessedAt(processedAt);
                                e.setCompanyPhone(companyPhone);
                                e.setCompanyEmail(companyEmail);
                                e.setVatNumber(vatNumber);
                                e.setRegNumber(regNumber);
                                e.setRejectionReason(rejectionReason);
                                return e;
                            });
                });
    }

    /**
     * Stage 3: Physical + postal address fields (8 combined: 6 physical + first 2 postal,
     * then flatMap for remaining 4 postal)
     */
    private Arbitrary<WholesaleApplicationEntity> addAddresses(WholesaleApplicationEntity entity)
    {
        Arbitrary<String> nullableStrings = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30)
        );

        return Combinators.combine(nullableStrings, nullableStrings, nullableStrings,
                        nullableStrings, nullableStrings, nullableStrings, nullableStrings, nullableStrings)
                .as((physLine1, physLine2, physSuburb, physCity, physProvince, physPostal,
                     postLine1, postLine2) -> {
                    entity.setPhysicalAddressLine1(physLine1);
                    entity.setPhysicalAddressLine2(physLine2);
                    entity.setPhysicalSuburb(physSuburb);
                    entity.setPhysicalCity(physCity);
                    entity.setPhysicalProvince(physProvince);
                    entity.setPhysicalPostalCode(physPostal);
                    entity.setPostalAddressLine1(postLine1);
                    entity.setPostalAddressLine2(postLine2);
                    return entity;
                }).flatMap(e -> {
                    Arbitrary<String> ns = Arbitraries.oneOf(
                            Arbitraries.just(null),
                            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30)
                    );
                    return Combinators.combine(ns, ns, ns, ns)
                            .as((postSuburb, postCity, postProvince, postPostal) -> {
                                e.setPostalSuburb(postSuburb);
                                e.setPostalCity(postCity);
                                e.setPostalProvince(postProvince);
                                e.setPostalPostalCode(postPostal);
                                return e;
                            });
                });
    }
}
