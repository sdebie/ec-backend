package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.backend.mapper.WholesaleMapper;
import org.ecommerce.common.dto.AddressDto;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.dto.WholesaleApplicationListItemDto;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.entity.WholesaleProfileEntity;
import org.ecommerce.common.enums.*;
import org.ecommerce.common.query.Filter;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.query.SortRequest;
import org.ecommerce.common.repository.WholesaleApplicationRepository;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class WholesaleCustomerService
{
    @Inject
    WholesaleApplicationRepository wholesaleApplicationRepository;

    @Inject
    WholesaleMapper wholesaleMapper;

    @Inject
    CustomerAddressService customerAddressService;

    @Inject
    Event<WholesaleDecisionEvent> decisionEvent;

    @Inject
    Event<WholesaleApplicationSubmittedEvent> submittedEvent;

    private static final Logger LOG = Logger.getLogger(WholesaleCustomerService.class);

    /**
     * @param fromDate inclusive ISO date (yyyy-MM-dd), or null/blank for no lower bound
     * @param toDate   inclusive ISO date (yyyy-MM-dd), or null/blank for no upper bound
     */
    public List<WholesaleApplicationListItemDto> getWholesaleApplications(PageRequest pageRequest, FilterRequest filterRequest, String fromDate, String toDate)
    {
        WholesaleApplicationStatusEn status = extractStatusFilter(filterRequest);
        SortRequest sort = extractSort(filterRequest);
        OffsetDateTime from = toInclusiveStart(fromDate);
        OffsetDateTime toExclusive = toExclusiveEnd(toDate);

        return wholesaleApplicationRepository.findForAdmin(status, from, toExclusive, sort, pageRequest)
                .stream()
                .map(wholesaleMapper::toListItemDto)
                .toList();
    }

    /**
     * @see #getWholesaleApplications(PageRequest, FilterRequest, String, String)
     */
    public long wholesaleApplicationCount(FilterRequest filterRequest, String fromDate, String toDate)
    {
        WholesaleApplicationStatusEn status = extractStatusFilter(filterRequest);
        OffsetDateTime from = toInclusiveStart(fromDate);
        OffsetDateTime toExclusive = toExclusiveEnd(toDate);

        return wholesaleApplicationRepository.countForAdmin(status, from, toExclusive);
    }

    public WholesaleApplicationDetailsDto getWholesaleApplicationById(UUID id)
    {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }

        WholesaleApplicationEntity application = wholesaleApplicationRepository.findById(id);
        if (application == null) {
            throw new IllegalArgumentException("wholesale application not found: " + id);
        }

        return wholesaleMapper.toDetailsDto(application);
    }

    @Transactional
    public WholesaleCustomerDto createWholesaleApplication(WholesaleCustomerDto customerDto)
    {
        if (customerDto == null) {
            throw new IllegalArgumentException("customer is required");
        }

        // applicantEmail — required
        WholesaleApplicationEntity application = new WholesaleApplicationEntity();
        application.setApplicantEmail(normalizeEmail(customerDto.getApplicantEmail()));
        if (application.getApplicantEmail() == null) {
            throw new IllegalArgumentException("applicantEmail is required");
        }

        // account_email is now optional
        String accountEmail = normalizeEmail(customerDto.getEmail());
        if (accountEmail != null) {
            WholesaleApplicationEntity existing = WholesaleApplicationEntity.find("lower(accountEmail) = lower(?1)", accountEmail).firstResult();
            if (existing != null) {
                throw new IllegalArgumentException("wholesale application already exists with email: " + accountEmail);
            }
        }
        application.setAccountEmail(accountEmail);

        application.setFirstName(normalizeText(customerDto.getFirstName()));
        if (application.getFirstName() == null) {
            throw new IllegalArgumentException("firstName is required");
        }
        application.setLastName(normalizeText(customerDto.getLastName()));
        application.setPhone(normalizeText(customerDto.getPhone()));

        application.setCompanyName(firstNonBlank(normalizeText(customerDto.getCompanyName()), application.getFirstName()));
        application.setVatNumber(normalizeText(customerDto.getVatNumber()));
        application.setRegNumber(normalizeText(customerDto.getRegNumber()));

        // New optional fields
        application.setTradingName(normalizeText(customerDto.getTradingName()));
        application.setCompanyPhone(normalizeText(customerDto.getCompanyPhone()));
        application.setCompanyEmail(normalizeText(customerDto.getCompanyEmail()));
        application.setFinanceContactName(normalizeText(customerDto.getFinanceContactName()));
        application.setFinanceContactEmail(normalizeText(customerDto.getFinanceContactEmail()));
        application.setFinanceContactPhone(normalizeText(customerDto.getFinanceContactPhone()));
        application.setPurchaseOrderRequired(customerDto.getPurchaseOrderRequired() != null && customerDto.getPurchaseOrderRequired());

        // Server-controlled status — ignore any client-supplied status on the public create path
        application.setStatus(WholesaleApplicationStatusEn.PENDING);
        application.setNotes(normalizeText(customerDto.getNotes()));

        AddressDto physical = customerDto.getPhysicalAddress();
        application.setPhysicalAddressLine1(physical != null ? normalizeText(physical.getLine1()) : null);
        application.setPhysicalAddressLine2(physical != null ? normalizeText(physical.getLine2()) : null);
        application.setPhysicalSuburb(physical != null ? normalizeText(physical.getSuburb()) : null);
        application.setPhysicalCity(physical != null ? normalizeText(physical.getCity()) : null);
        application.setPhysicalProvince(physical != null ? normalizeText(physical.getProvince()) : null);
        application.setPhysicalPostalCode(physical != null ? normalizeText(physical.getPostalCode()) : null);

        AddressDto postal = customerDto.getPostalAddress();
        application.setPostalAddressLine1(postal != null ? normalizeText(postal.getLine1()) : null);
        application.setPostalAddressLine2(postal != null ? normalizeText(postal.getLine2()) : null);
        application.setPostalSuburb(postal != null ? normalizeText(postal.getSuburb()) : null);
        application.setPostalCity(postal != null ? normalizeText(postal.getCity()) : null);
        application.setPostalProvince(postal != null ? normalizeText(postal.getProvince()) : null);
        application.setPostalPostalCode(postal != null ? normalizeText(postal.getPostalCode()) : null);

        WholesaleApplicationEntity.persist(application);

        WholesaleCustomerDto result = wholesaleMapper.toDto(application);

        // Observed AFTER_SUCCESS: the notification emails only go out if the submission commits
        submittedEvent.fire(new WholesaleApplicationSubmittedEvent(application.getId(), result));

        return result;
    }

    @Transactional
    public WholesaleCustomerDto updateWholesaleCustomer(UUID id, WholesaleCustomerDto customerDto)
    {
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
        if (customerEntity.getShopperType() != CustomerTypeEn.WHOLESALER) {
            throw new IllegalArgumentException("customer is not a wholesale customer: " + id);
        }

        // ── Email lives on UserEntity ─────────────────────────────────────
        if (customerDto.getEmail() != null) {
            String email = normalizeEmail(customerDto.getEmail());
            if (email == null) {
                throw new IllegalArgumentException("email cannot be blank");
            }
            UserEntity existing = UserEntity.findByEmail(email);
            if (existing != null && !existing.getId().equals(customerEntity.getUser().getId())) {
                throw new IllegalArgumentException("customer already exists with email: " + email);
            }
            customerEntity.getUser().setEmail(email);
        }

        if (customerDto.getStatus() != null) {
            customerEntity.setStatus(resolveStatus(customerDto.getStatus(), customerEntity.getStatus()));
        }

        customerEntity.setShopperType(CustomerTypeEn.WHOLESALER);
        applyProfileFields(customerEntity, customerDto);
        applyAddresses(customerEntity, customerDto);
        customerEntity.persist();

        return wholesaleMapper.toDto(customerEntity);
    }

    @Transactional
    public WholesaleApplicationDetailsDto approveWholesaleApplication(UUID id)
    {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }

        WholesaleApplicationEntity application = WholesaleApplicationEntity.findById(id);
        if (application == null) {
            throw new IllegalArgumentException("wholesale application not found: " + id);
        }

        if (application.getStatus() != WholesaleApplicationStatusEn.PENDING) {
            throw new IllegalArgumentException("application must be in PENDING status to approve");
        }

        // ── Create customer account from application ──────────────────────
        String email = normalizeEmail(application.getAccountEmail());
        if (email == null) {
            // Fall back to applicantEmail if accountEmail was not provided
            email = normalizeEmail(application.getApplicantEmail());
        }
        if (email == null) {
            throw new IllegalArgumentException("application must have an accountEmail or applicantEmail to approve");
        }

        boolean newAccountCreated = false;

        if (application.getCustomer() == null) {
            UserEntity existingUser = UserEntity.findByEmail(email);
            CustomerEntity customerEntity;
            if (existingUser != null) {
                customerEntity = upgradeExistingAccountToWholesaler(application, existingUser);
            } else {
                customerEntity = createNewWholesaleAccount(application, email);
                newAccountCreated = true;
            }

            // Link customer to application
            application.setCustomer(customerEntity);
        }

        // ── Mark application approved ─────────────────────────────────────
        application.setStatus(WholesaleApplicationStatusEn.APPROVED);
        application.setProcessedAt(OffsetDateTime.now());
        application.persist();

        decisionEvent.fire(buildDecisionEvent(application, null, newAccountCreated));

        return wholesaleMapper.toDetailsDto(application);
    }

    /**
     * Creates a brand-new user + customer + wholesale profile from the application.
     * {@code passwordHash} is left as an empty (never-matchable) placeholder — the
     * approval email directs the customer to the Forgot Password flow to set one.
     */
    private CustomerEntity createNewWholesaleAccount(WholesaleApplicationEntity application, String email)
    {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash(""); // placeholder until the customer sets a password
        UserEntity.persist(user);

        CustomerEntity customerEntity = new CustomerEntity();
        customerEntity.setUser(user);
        customerEntity.setShopperType(CustomerTypeEn.WHOLESALER);
        customerEntity.setStatus(CustomerStatusEn.ACTIVE);
        customerEntity.setFirstName(normalizeText(application.getFirstName()));
        customerEntity.setLastName(normalizeText(application.getLastName()));
        customerEntity.setPhone(normalizeText(application.getPhone()));
        CustomerEntity.persist(customerEntity);

        WholesaleProfileEntity profile = new WholesaleProfileEntity();
        profile.setCustomer(customerEntity);
        profile.setCompanyName(firstNonBlank(normalizeText(application.getCompanyName()), customerEntity.getFirstName(), "Unknown Company"));
        profile.setVatNumber(normalizeText(application.getVatNumber()));
        profile.setRegNumber(normalizeText(application.getRegNumber()));
        WholesaleProfileEntity.persist(profile);

        applyAddressesFromApplication(customerEntity, application);

        return customerEntity;
    }

    /**
     * Upgrades an existing account to wholesale rather than rejecting the approval.
     * Never touches {@code status} beyond promoting PENDING→ACTIVE — a staff-disabled
     * account must not be silently reactivated by a wholesale approval, mirroring the
     * same restraint applies on password-reset completion.
     */
    private CustomerEntity upgradeExistingAccountToWholesaler(WholesaleApplicationEntity application, UserEntity existingUser)
    {
        CustomerEntity customerEntity = existingUser.getCustomer();
        if (customerEntity == null) {
            throw new IllegalArgumentException("account already exists for " + existingUser.getEmail()
                    + " but has no linked customer profile — cannot approve wholesale application");
        }

        if (customerEntity.getShopperType() == CustomerTypeEn.WHOLESALER) {
            // Already wholesale — a second/duplicate approval must not silently
            // overwrite wholesale data staff may have since edited.
            return customerEntity;
        }

        customerEntity.setShopperType(CustomerTypeEn.WHOLESALER);
        if (customerEntity.getStatus() == null || customerEntity.getStatus() == CustomerStatusEn.PENDING) {
            customerEntity.setStatus(CustomerStatusEn.ACTIVE);
        }

        if (customerEntity.getWholesaleProfile() == null) {
            WholesaleProfileEntity profile = new WholesaleProfileEntity();
            profile.setCustomer(customerEntity);
            profile.setCompanyName(firstNonBlank(normalizeText(application.getCompanyName()), customerEntity.getFirstName(), "Unknown Company"));
            profile.setVatNumber(normalizeText(application.getVatNumber()));
            profile.setRegNumber(normalizeText(application.getRegNumber()));
            WholesaleProfileEntity.persist(profile);
        }

        applyAddressesFromApplication(customerEntity, application);
        customerEntity.persist();

        return customerEntity;
    }

    @Transactional
    public WholesaleApplicationDetailsDto rejectWholesaleApplication(UUID id, String reason)
    {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }

        WholesaleApplicationEntity application = WholesaleApplicationEntity.findById(id);
        if (application == null) {
            throw new IllegalArgumentException("wholesale application not found: " + id);
        }

        if (application.getStatus() != WholesaleApplicationStatusEn.PENDING) {
            throw new IllegalArgumentException("application must be in PENDING status to reject");
        }

        application.setStatus(WholesaleApplicationStatusEn.REJECTED);
        application.setProcessedAt(OffsetDateTime.now());
        application.setRejectionReason(reason.trim());
        application.persist();

        decisionEvent.fire(buildDecisionEvent(application, reason.trim(), false));

        return wholesaleMapper.toDetailsDto(application);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * The admin queue's only status filter is a single equality — pulled out of the generic
     * {@link FilterRequest} by hand because {@link #getWholesaleApplications} routes through
     * {@link WholesaleApplicationRepository#findForAdmin}, hand-written JPQL rather than the
     * generic {@code PanacheQueryBuilder} path (see that repository for why: the generic
     * coercion cannot express the date-range bound this method also needs).
     */
    private WholesaleApplicationStatusEn extractStatusFilter(FilterRequest filterRequest)
    {
        if (filterRequest == null || filterRequest.getFilters() == null) {
            return null;
        }
        return filterRequest.getFilters().stream()
                .filter(f -> "status".equals(f.getKey()))
                .findFirst()
                .map(Filter::getValue)
                .map(value -> {
                    try {
                        return WholesaleApplicationStatusEn.valueOf(value);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("invalid status: " + value);
                    }
                })
                .orElse(null);
    }

    private SortRequest extractSort(FilterRequest filterRequest)
    {
        if (filterRequest == null || filterRequest.getSort() == null || filterRequest.getSort().isEmpty()) {
            return null;
        }
        return filterRequest.getSort().getFirst();
    }

    private LocalDate parseDate(String value, String fieldName)
    {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid " + fieldName + ": " + value + " (expected yyyy-MM-dd)");
        }
    }

    /**
     * Day boundaries are anchored to UTC, not the JVM's default zone — a server-local zone
     * would make the window depend on which machine runs the query (see
     * {@code .kiro/specs/temporal-type-correctness}, which exists because exactly this kind
     * of implicit-zone comparison has already produced a live defect elsewhere).
     */
    private OffsetDateTime toInclusiveStart(String date)
    {
        LocalDate day = parseDate(date, "fromDate");
        return day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }

    /**
     * The UI sends whole days, so an inclusive "to" is every instant before the following
     * midnight — a plain {@code <=} would drop everything after 00:00 on the "to" day.
     */
    private OffsetDateTime toExclusiveEnd(String date)
    {
        LocalDate day = parseDate(date, "toDate");
        return day == null ? null : day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }

    private void applyProfileFields(CustomerEntity customerEntity, WholesaleCustomerDto dto)
    {
        if (dto.getFirstName() != null) {
            customerEntity.setFirstName(dto.getFirstName());
        }
        if (dto.getLastName() != null) {
            customerEntity.setLastName(dto.getLastName());
        }
        if (dto.getPhone() != null) {
            customerEntity.setPhone(dto.getPhone());
        }
    }

    private void applyAddresses(CustomerEntity ce, WholesaleCustomerDto dto)
    {
        customerAddressService.upsertAddress(ce, AddressTypeEn.PHYSICAL, dto.getPhysicalAddress());
        customerAddressService.upsertAddress(ce, AddressTypeEn.POSTAL, dto.getPostalAddress());
    }

    /**
     * WholesaleApplicationEntity's flat columns are written exactly once, by
     * {@link #createWholesaleApplication}, which already normalizes — but this read is a
     * second, independent use of that data, so it re-normalizes defensively rather than
     * trusting the invariant to hold forever.
     */
    private void applyAddressesFromApplication(CustomerEntity ce, WholesaleApplicationEntity application)
    {
        customerAddressService.upsertAddress(ce, AddressTypeEn.PHYSICAL,
                normalizeAddress(wholesaleMapper.wholesaleApplicationPhysicalAddress(application)));
        customerAddressService.upsertAddress(ce, AddressTypeEn.POSTAL,
                normalizeAddress(wholesaleMapper.wholesaleApplicationPostalAddress(application)));
    }

    private AddressDto normalizeAddress(AddressDto address)
    {
        address.setLine1(normalizeText(address.getLine1()));
        address.setLine2(normalizeText(address.getLine2()));
        address.setSuburb(normalizeText(address.getSuburb()));
        address.setCity(normalizeText(address.getCity()));
        address.setProvince(normalizeText(address.getProvince()));
        address.setPostalCode(normalizeText(address.getPostalCode()));
        return address;
    }

    private String normalizeEmail(String email)
    {
        if (email == null) return null;
        String normalized = email.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeText(String value)
    {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private CustomerStatusEn resolveStatus(WholesaleCustomerStatusEn statusValue, CustomerStatusEn fallback)
    {
        if (statusValue == null) {
            return fallback;
        }
        return switch (statusValue) {
            case ACTIVE -> CustomerStatusEn.ACTIVE;
            case DISABLED -> CustomerStatusEn.DISABLED;
            case PENDING -> CustomerStatusEn.PENDING;
            default -> throw new IllegalArgumentException("invalid status: " + statusValue);
        };
    }

    private String firstNonBlank(String... values)
    {
        for (String value : values) {
            String normalized = normalizeText(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }


    private WholesaleDecisionEvent buildDecisionEvent(WholesaleApplicationEntity application, String rejectionReason, boolean newAccountCreated)
    {
        // Recipient: applicantEmail first (nullable = false column), fallback to accountEmail if blank
        String recipientEmail = application.getApplicantEmail();
        if (recipientEmail == null || recipientEmail.isBlank()) {
            recipientEmail = application.getAccountEmail();
        }

        String firstName = (application.getFirstName() != null && !application.getFirstName().isBlank()) ? application.getFirstName() : "Wholesale Customer";

        return new WholesaleDecisionEvent(
                application.getId(),
                application.getStatus(),
                recipientEmail,
                firstName,
                application.getCompanyName(),
                rejectionReason,
                newAccountCreated
        );
    }

}

