package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.ecommerce.common.dto.WholesaleApplicationDetailsDto;
import org.ecommerce.common.dto.WholesaleApplicationListItemDto;
import org.ecommerce.common.dto.WholesaleCustomerDto;
import org.ecommerce.common.entity.CustomerAddressEntity;
import org.ecommerce.common.entity.CustomerEntity;
import org.ecommerce.common.entity.WholesaleApplicationEntity;
import org.ecommerce.common.entity.WholesaleProfileEntity;
import org.ecommerce.common.entity.UserEntity;
import org.ecommerce.common.enums.AddressTypeEn;
import org.ecommerce.common.enums.CustomerStatusEn;
import org.ecommerce.common.enums.CustomerTypeEn;
import org.ecommerce.common.enums.WholesaleApplicationStatusEn;
import org.ecommerce.common.enums.WholesaleCustomerStatusEn;
import org.ecommerce.common.query.FilterRequest;
import org.ecommerce.common.query.PageRequest;
import org.ecommerce.common.repository.WholesaleApplicationRepository;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WholesaleCustomerService {

    @Inject
    MailTemplate wholesale_status;

    @Inject
    WholesaleApplicationRepository wholesaleApplicationRepository;

    private static final Logger LOG = Logger.getLogger(WholesaleCustomerService.class);

    public List<WholesaleApplicationListItemDto> getWholesaleApplications(PageRequest pageRequest, FilterRequest filterRequest) {
        return wholesaleApplicationRepository.findAll(pageRequest, filterRequest).stream()
                .map(this::toListItemDto)
                .toList();
    }

    public long wholesaleApplicationCount(FilterRequest filterRequest) {
        return wholesaleApplicationRepository.count(filterRequest);
    }

    public WholesaleApplicationDetailsDto getWholesaleApplicationById(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }

        WholesaleApplicationEntity application = wholesaleApplicationRepository.findById(id);
        if (application == null) {
            throw new IllegalArgumentException("wholesale application not found: " + id);
        }

        return toDetailsDto(application);
    }

    @Transactional
    public WholesaleCustomerDto createWholesaleCustomer(UUID applicationId) {
        if (applicationId == null) {
            throw new IllegalArgumentException("applicationId is required");
        }

        WholesaleApplicationEntity application = WholesaleApplicationEntity.findById(applicationId);
        if (application == null) {
            throw new IllegalArgumentException("wholesale application not found: " + applicationId);
        }
        if (application.customer != null) {
            throw new IllegalArgumentException("wholesale application already converted: " + applicationId);
        }

        String email = normalizeEmail(application.accountEmail);
        if (email == null) {
            throw new IllegalArgumentException("application accountEmail is required");
        }

        if (UserEntity.findByEmail(email) != null) {
            throw new IllegalArgumentException("customer already exists with email: " + email);
        }

        // ── Create user account ───────────────────────────────────────────
        UserEntity user = new UserEntity();
        user.email = email;
        user.passwordHash = ""; // placeholder until the customer sets a password
        user.roles = new String[]{"WHOLESALE"};
        UserEntity.persist(user);

        // ── Create customer profile ────────────────────────────────────────
        CustomerEntity customerEntity = new CustomerEntity();
        customerEntity.user = user;
        customerEntity.shopperType = CustomerTypeEn.WHOLESALER;
        customerEntity.status = CustomerStatusEn.PENDING;
        customerEntity.firstName = normalizeText(application.firstName);
        customerEntity.lastName = normalizeText(application.lastName);
        customerEntity.phone = normalizeText(application.phone);
        CustomerEntity.persist(customerEntity);

        // ── Create wholesale profile ───────────────────────────────────────
        WholesaleProfileEntity profile = new WholesaleProfileEntity();
        profile.customer = customerEntity;
        profile.companyName = firstNonBlank(normalizeText(application.companyName), customerEntity.firstName, "Unknown Company");
        profile.vatNumber = normalizeText(application.vatNumber);
        profile.regNumber = normalizeText(application.regNumber);
        WholesaleProfileEntity.persist(profile);

        // ── Apply addresses from application ───────────────────────────────
        applyAddressesFromApplication(customerEntity, application);

        // ── Mark application converted and link customer ───────────────────
        application.customer = customerEntity;
        application.status = WholesaleApplicationStatusEn.CONVERTED;
        application.processedAt = OffsetDateTime.now();

        return toDto(customerEntity);
    }

    @Transactional
    public WholesaleCustomerDto createWholesaleApplication(WholesaleCustomerDto customerDto) {
        if (customerDto == null) {
            throw new IllegalArgumentException("customer is required");
        }

        // applicantEmail — required
        WholesaleApplicationEntity application = new WholesaleApplicationEntity();
        application.applicantEmail = normalizeEmail(customerDto.getApplicantEmail());
        if (application.applicantEmail == null) {
            throw new IllegalArgumentException("applicantEmail is required");
        }

        // account_email is now optional
        String accountEmail = normalizeEmail(customerDto.getEmail());
        if (accountEmail != null) {
            WholesaleApplicationEntity existing = WholesaleApplicationEntity
                    .find("lower(accountEmail) = lower(?1)", accountEmail)
                    .firstResult();
            if (existing != null) {
                throw new IllegalArgumentException("wholesale application already exists with email: " + accountEmail);
            }
        }
        application.accountEmail = accountEmail;

        application.firstName = normalizeText(customerDto.getFirstName());
        if (application.firstName == null) {
            throw new IllegalArgumentException("firstName is required");
        }
        application.lastName = normalizeText(customerDto.getLastName());
        application.phone = normalizeText(customerDto.getPhone());

        application.companyName = firstNonBlank(normalizeText(customerDto.getCompanyName()), application.firstName);
        application.vatNumber = normalizeText(customerDto.getVatNumber());
        application.regNumber = normalizeText(customerDto.getRegNumber());

        // New optional fields
        application.tradingName = normalizeText(customerDto.getTradingName());
        application.companyPhone = normalizeText(customerDto.getCompanyPhone());
        application.companyEmail = normalizeText(customerDto.getCompanyEmail());
        application.financeContactName = normalizeText(customerDto.getFinanceContactName());
        application.financeContactEmail = normalizeText(customerDto.getFinanceContactEmail());
        application.financeContactPhone = normalizeText(customerDto.getFinanceContactPhone());
        application.purchaseOrderRequired = customerDto.getPurchaseOrderRequired() != null
                ? customerDto.getPurchaseOrderRequired()
                : false;

        // Server-controlled status — ignore any client-supplied status on the public create path
        application.status = WholesaleApplicationStatusEn.PENDING;
        application.notes = normalizeText(customerDto.getNotes());

        application.physicalAddressLine1 = normalizeText(customerDto.getPhysicalAddressLine1());
        application.physicalAddressLine2 = normalizeText(customerDto.getPhysicalAddressLine2());
        application.physicalSuburb = normalizeText(customerDto.getPhysicalSuburb());
        application.physicalCity = normalizeText(customerDto.getPhysicalCity());
        application.physicalProvince = normalizeText(customerDto.getPhysicalProvince());
        application.physicalPostalCode = normalizeText(customerDto.getPhysicalPostalCode());

        application.postalAddressLine1 = normalizeText(customerDto.getPostalAddressLine1());
        application.postalAddressLine2 = normalizeText(customerDto.getPostalAddressLine2());
        application.postalSuburb = normalizeText(customerDto.getPostalSuburb());
        application.postalCity = normalizeText(customerDto.getPostalCity());
        application.postalProvince = normalizeText(customerDto.getPostalProvince());
        application.postalPostalCode = normalizeText(customerDto.getPostalPostalCode());

        WholesaleApplicationEntity.persist(application);
        return toDto(application);
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

        // ── Email lives on UserEntity ─────────────────────────────────────
        if (customerDto.getEmail() != null) {
            String email = normalizeEmail(customerDto.getEmail());
            if (email == null) {
                throw new IllegalArgumentException("email cannot be blank");
            }
            UserEntity existing = UserEntity.findByEmail(email);
            if (existing != null && !existing.id.equals(customerEntity.user.id)) {
                throw new IllegalArgumentException("customer already exists with email: " + email);
            }
            customerEntity.user.email = email;
        }

        if (customerDto.getStatus() != null) {
            customerEntity.status = resolveStatus(customerDto.getStatus(), customerEntity.status);
        }

        customerEntity.shopperType = CustomerTypeEn.WHOLESALER;
        applyProfileFields(customerEntity, customerDto);
        applyAddresses(customerEntity, customerDto);
        customerEntity.persist();

        return toDto(customerEntity);
    }

    @Transactional
    public WholesaleApplicationDetailsDto approveWholesaleApplication(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }

        WholesaleApplicationEntity application = WholesaleApplicationEntity.findById(id);
        if (application == null) {
            throw new IllegalArgumentException("wholesale application not found: " + id);
        }

        if (application.status != WholesaleApplicationStatusEn.PENDING) {
            throw new IllegalArgumentException("application must be in PENDING status to approve");
        }

        // ── Create customer account from application ──────────────────────
        String email = normalizeEmail(application.accountEmail);
        if (email == null) {
            // Fall back to applicantEmail if accountEmail was not provided
            email = normalizeEmail(application.applicantEmail);
        }
        if (email == null) {
            throw new IllegalArgumentException("application must have an accountEmail or applicantEmail to approve");
        }

        if (application.customer == null) {
            if (UserEntity.findByEmail(email) != null) {
                throw new IllegalArgumentException("customer already exists with email: " + email);
            }

            // Create user account
            UserEntity user = new UserEntity();
            user.email = email;
            user.passwordHash = ""; // placeholder until the customer sets a password
            user.roles = new String[]{"WHOLESALE"};
            UserEntity.persist(user);

            // Create customer profile
            CustomerEntity customerEntity = new CustomerEntity();
            customerEntity.user = user;
            customerEntity.shopperType = CustomerTypeEn.WHOLESALER;
            customerEntity.status = CustomerStatusEn.ACTIVE;
            customerEntity.firstName = normalizeText(application.firstName);
            customerEntity.lastName = normalizeText(application.lastName);
            customerEntity.phone = normalizeText(application.phone);
            CustomerEntity.persist(customerEntity);

            // Create wholesale profile
            WholesaleProfileEntity profile = new WholesaleProfileEntity();
            profile.customer = customerEntity;
            profile.companyName = firstNonBlank(normalizeText(application.companyName), customerEntity.firstName, "Unknown Company");
            profile.vatNumber = normalizeText(application.vatNumber);
            profile.regNumber = normalizeText(application.regNumber);
            WholesaleProfileEntity.persist(profile);

            // Apply addresses from application
            applyAddressesFromApplication(customerEntity, application);

            // Link customer to application
            application.customer = customerEntity;
        }

        // ── Mark application approved ─────────────────────────────────────
        application.status = WholesaleApplicationStatusEn.APPROVED;
        application.processedAt = OffsetDateTime.now();
        application.persist();

        sendApprovalEmail(application);

        return toDetailsDto(application);
    }

    @Transactional
    public WholesaleApplicationDetailsDto rejectWholesaleApplication(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }

        WholesaleApplicationEntity application = WholesaleApplicationEntity.findById(id);
        if (application == null) {
            throw new IllegalArgumentException("wholesale application not found: " + id);
        }

        if (application.status != WholesaleApplicationStatusEn.PENDING) {
            throw new IllegalArgumentException("application must be in PENDING status to reject");
        }

        application.status = WholesaleApplicationStatusEn.REJECTED;
        application.processedAt = OffsetDateTime.now();
        application.persist();

        sendRejectionEmail(application);

        return toDetailsDto(application);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void applyProfileFields(CustomerEntity customerEntity, WholesaleCustomerDto dto) {
        if (dto.getFirstName() != null) customerEntity.firstName = dto.getFirstName();
        if (dto.getLastName()  != null) customerEntity.lastName  = dto.getLastName();
        if (dto.getPhone()     != null) customerEntity.phone     = dto.getPhone();
    }

    private void applyAddresses(CustomerEntity ce, WholesaleCustomerDto dto) {
        upsertAddress(ce, AddressTypeEn.PHYSICAL,
                dto.getPhysicalAddressLine1(), dto.getPhysicalAddressLine2(),
                dto.getPhysicalSuburb(), dto.getPhysicalCity(),
                dto.getPhysicalProvince(), dto.getPhysicalPostalCode());

        upsertAddress(ce, AddressTypeEn.POSTAL,
                dto.getPostalAddressLine1(), dto.getPostalAddressLine2(),
                dto.getPostalSuburb(), dto.getPostalCity(),
                dto.getPostalProvince(), dto.getPostalPostalCode());
    }

    private void applyAddressesFromApplication(CustomerEntity ce, WholesaleApplicationEntity application) {
        upsertAddress(ce, AddressTypeEn.PHYSICAL,
                normalizeText(application.physicalAddressLine1), normalizeText(application.physicalAddressLine2),
                normalizeText(application.physicalSuburb), normalizeText(application.physicalCity),
                normalizeText(application.physicalProvince), normalizeText(application.physicalPostalCode));

        upsertAddress(ce, AddressTypeEn.POSTAL,
                normalizeText(application.postalAddressLine1), normalizeText(application.postalAddressLine2),
                normalizeText(application.postalSuburb), normalizeText(application.postalCity),
                normalizeText(application.postalProvince), normalizeText(application.postalPostalCode));
    }

    private static void upsertAddress(CustomerEntity ce, AddressTypeEn type,
                                      String line1, String line2,
                                      String suburb, String city,
                                      String province, String postalCode) {
        if (line1 == null && city == null && province == null && postalCode == null) {
            return;
        }

        CustomerAddressEntity addr = ce.addresses.stream()
                .filter(a -> a.addressType == type)
                .findFirst()
                .orElseGet(() -> {
                    CustomerAddressEntity a = new CustomerAddressEntity();
                    a.customer = ce;
                    a.addressType = type;
                    ce.addresses.add(a);
                    return a;
                });

        if (line1      != null) addr.addressLine1 = line1;
        if (line2      != null) addr.addressLine2 = line2;
        if (suburb     != null) addr.suburb       = suburb;
        if (city       != null) addr.city         = city;
        if (province   != null) addr.province     = province;
        if (postalCode != null) addr.postalCode   = postalCode;
    }

    private String normalizeEmail(String email) {
        if (email == null) return null;
        String normalized = email.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeText(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private WholesaleApplicationStatusEn resolveApplicationStatus(WholesaleCustomerStatusEn status) {
        if (status == null) {
            return WholesaleApplicationStatusEn.PENDING;
        }
        return switch (status) {
            case PENDING -> WholesaleApplicationStatusEn.PENDING;
            case APPROVED -> WholesaleApplicationStatusEn.APPROVED;
            case REJECTED -> WholesaleApplicationStatusEn.REJECTED;
            case CONVERTED -> WholesaleApplicationStatusEn.CONVERTED;
            default -> throw new IllegalArgumentException("invalid application status: " + status);
        };
    }

    private CustomerStatusEn resolveStatus(WholesaleCustomerStatusEn statusValue, CustomerStatusEn fallback) {
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalizeText(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private WholesaleCustomerDto toDto(CustomerEntity ce) {
        WholesaleCustomerDto dto = new WholesaleCustomerDto();
        dto.setId(ce.id);
        dto.setEmail(ce.user != null ? ce.user.email : null);
        dto.setFirstName(ce.firstName);
        dto.setLastName(ce.lastName);
        dto.setPhone(ce.phone);

        Optional<CustomerAddressEntity> physical = ce.addresses.stream()
                .filter(a -> a.addressType == AddressTypeEn.PHYSICAL).findFirst();
        physical.ifPresent(a -> {
            dto.setPhysicalAddressLine1(a.addressLine1);
            dto.setPhysicalAddressLine2(a.addressLine2);
            dto.setPhysicalSuburb(a.suburb);
            dto.setPhysicalCity(a.city);
            dto.setPhysicalProvince(a.province);
            dto.setPhysicalPostalCode(a.postalCode);
        });

        Optional<CustomerAddressEntity> postal = ce.addresses.stream()
                .filter(a -> a.addressType == AddressTypeEn.POSTAL).findFirst();
        postal.ifPresent(a -> {
            dto.setPostalAddressLine1(a.addressLine1);
            dto.setPostalAddressLine2(a.addressLine2);
            dto.setPostalSuburb(a.suburb);
            dto.setPostalCity(a.city);
            dto.setPostalProvince(a.province);
            dto.setPostalPostalCode(a.postalCode);
        });

        if (ce.wholesaleProfile != null) {
            dto.setCompanyName(ce.wholesaleProfile.companyName);
            dto.setVatNumber(ce.wholesaleProfile.vatNumber);
            dto.setRegNumber(ce.wholesaleProfile.regNumber);
        }

        if (ce.status != null) {
            dto.setStatus(WholesaleCustomerStatusEn.valueOf(ce.status.name()));
        }
        return dto;
    }

    private WholesaleCustomerDto toDto(WholesaleApplicationEntity application) {
        WholesaleCustomerDto dto = new WholesaleCustomerDto();
        dto.setId(application.id);
        dto.setEmail(application.accountEmail);
        dto.setFirstName(application.firstName);
        dto.setLastName(application.lastName);
        dto.setPhone(application.phone);

        dto.setPhysicalAddressLine1(application.physicalAddressLine1);
        dto.setPhysicalAddressLine2(application.physicalAddressLine2);
        dto.setPhysicalSuburb(application.physicalSuburb);
        dto.setPhysicalCity(application.physicalCity);
        dto.setPhysicalProvince(application.physicalProvince);
        dto.setPhysicalPostalCode(application.physicalPostalCode);

        dto.setPostalAddressLine1(application.postalAddressLine1);
        dto.setPostalAddressLine2(application.postalAddressLine2);
        dto.setPostalSuburb(application.postalSuburb);
        dto.setPostalCity(application.postalCity);
        dto.setPostalProvince(application.postalProvince);
        dto.setPostalPostalCode(application.postalPostalCode);

        dto.setCompanyName(application.companyName);
        dto.setVatNumber(application.vatNumber);
        dto.setRegNumber(application.regNumber);
        dto.setNotes(application.notes);
        if (application.status != null) {
            dto.setStatus(WholesaleCustomerStatusEn.valueOf(application.status.name()));
        }
        return dto;
    }

    private WholesaleApplicationListItemDto toListItemDto(WholesaleApplicationEntity application) {
        WholesaleApplicationListItemDto dto = new WholesaleApplicationListItemDto();
        dto.setId(application.id);
        dto.setCreatedAt(application.createdAt);
        dto.setStatus(application.status);
        dto.setFirstName(application.firstName);
        dto.setLastName(application.lastName);
        dto.setEmail(application.accountEmail);
        return dto;
    }

    private WholesaleApplicationDetailsDto toDetailsDto(WholesaleApplicationEntity application) {
        WholesaleApplicationDetailsDto dto = new WholesaleApplicationDetailsDto();
        dto.setId(application.id);
        dto.setEmail(application.accountEmail);
        dto.setApplicantEmail(application.applicantEmail);
        dto.setFirstName(application.firstName);
        dto.setLastName(application.lastName);
        dto.setPhone(application.phone);

        dto.setPhysicalAddressLine1(application.physicalAddressLine1);
        dto.setPhysicalAddressLine2(application.physicalAddressLine2);
        dto.setPhysicalSuburb(application.physicalSuburb);
        dto.setPhysicalCity(application.physicalCity);
        dto.setPhysicalProvince(application.physicalProvince);
        dto.setPhysicalPostalCode(application.physicalPostalCode);

        dto.setPostalAddressLine1(application.postalAddressLine1);
        dto.setPostalAddressLine2(application.postalAddressLine2);
        dto.setPostalSuburb(application.postalSuburb);
        dto.setPostalCity(application.postalCity);
        dto.setPostalProvince(application.postalProvince);
        dto.setPostalPostalCode(application.postalPostalCode);

        dto.setCompanyName(application.companyName);
        dto.setTradingName(application.tradingName);
        dto.setCompanyPhone(application.companyPhone);
        dto.setCompanyEmail(application.companyEmail);
        dto.setVatNumber(application.vatNumber);
        dto.setRegNumber(application.regNumber);
        dto.setFinanceContactName(application.financeContactName);
        dto.setFinanceContactEmail(application.financeContactEmail);
        dto.setFinanceContactPhone(application.financeContactPhone);
        dto.setPurchaseOrderRequired(application.purchaseOrderRequired);
        dto.setNotes(application.notes);
        dto.setStatus(application.status);
        dto.setCreatedAt(application.createdAt);
        dto.setProcessedAt(application.processedAt);
        dto.setCustomerId(application.customer != null ? application.customer.id : null);
        return dto;
    }

    private void sendApprovalEmail(WholesaleApplicationEntity application) {
        String firstName = (application.firstName != null && !application.firstName.isBlank()) ? application.firstName : "Wholesale Customer";
        String customerEmail = application.accountEmail;
        wholesale_status.to(customerEmail)
                .from("shawn.debie@gmail.com")
                .subject("Wholesale Application Approved - " + application.companyName)
                .data("application", application)
                .data("customerName", firstName)
                .data("status", application.status)
                .send()
                .subscribe().with(
                        success -> LOG.info("Wholesale approval email sent to: " + customerEmail),
                        failure -> LOG.error("Wholesale approval email failed for: " + customerEmail, failure)
                );
    }

    private void sendRejectionEmail(WholesaleApplicationEntity application) {
        String firstName = (application.firstName != null && !application.firstName.isBlank()) ? application.firstName : "Wholesale Customer";
        String customerEmail = application.accountEmail;
        wholesale_status.to(customerEmail)
                .from("shawn.debie@gmail.com")
                .subject("Wholesale Application Status - " + application.companyName)
                .data("application", application)
                .data("customerName", firstName)
                .data("status", application.status)
                .send()
                .subscribe().with(
                        success -> LOG.info("Wholesale rejection email sent to: " + customerEmail),
                        failure -> LOG.error("Wholesale rejection email failed for: " + customerEmail, failure)
                );
    }
}

