package org.ecommerce.backend.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.ecommerce.common.dto.ContactEnquiryRequestDto;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates DTO Bean Validation constraints on {@link ContactEnquiryRequestDto}.
 * <p>
 * Covers: blank required fields rejected, over-max sizes rejected,
 * invalid email rejected, valid payload accepted, optional fields nullable.
 *
 * Validates: Requirements 3.1
 */
class ContactEnquiryRequestDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeContainer
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterContainer
    static void closeFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    // --- Property: valid payloads produce zero violations ---

    /**
     * Validates: Requirements 3.1
     *
     * For any DTO where all required fields are non-blank and within size limits,
     * and email is syntactically valid, validation SHALL produce zero constraint violations.
     */
    @Property(tries = 200)
    void validPayloadPassesValidation(
            @ForAll("validNames") String name,
            @ForAll("validEmails") String email,
            @ForAll("validPhones") String phone,
            @ForAll("validCompanies") String company,
            @ForAll("validMessages") String message
    ) {
        var dto = new ContactEnquiryRequestDto(name, email, phone, company, message, null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(),
                "Valid DTO should have no violations but got: " + violations);
    }

    // --- Property: blank required fields produce violations ---

    /**
     * Validates: Requirements 3.1
     *
     * For any blank name (null, empty, or whitespace-only), validation SHALL reject
     * with at least one violation on the 'name' property.
     */
    @Property(tries = 50)
    void blankNameIsRejected(@ForAll("blankStrings") String name) {
        var dto = new ContactEnquiryRequestDto(name, "valid@example.com", "0123456789", null, "Hello", null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Blank name should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("name")),
                "Should have a violation on 'name'");
    }

    /**
     * Validates: Requirements 3.1
     *
     * For any blank email, validation SHALL reject with at least one violation on 'email'.
     */
    @Property(tries = 50)
    void blankEmailIsRejected(@ForAll("blankStrings") String blankEmail) {
        var dto = new ContactEnquiryRequestDto("John", blankEmail, "0123456789", null, "Hello", null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Blank email should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("email")),
                "Should have a violation on 'email'");
    }

    /**
     * Validates: Requirements 3.1
     *
     * For any blank phone, validation SHALL reject with at least one violation on 'phone'.
     */
    @Property(tries = 50)
    void blankPhoneIsRejected(@ForAll("blankStrings") String blankPhone) {
        var dto = new ContactEnquiryRequestDto("John", "valid@example.com", blankPhone, null, "Hello", null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Blank phone should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("phone")),
                "Should have a violation on 'phone'");
    }

    /**
     * Validates: Requirements 3.1
     *
     * For any blank message, validation SHALL reject with at least one violation on 'message'.
     */
    @Property(tries = 50)
    void blankMessageIsRejected(@ForAll("blankStrings") String blankMessage) {
        var dto = new ContactEnquiryRequestDto("John", "valid@example.com", "0123456789", null, blankMessage, null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Blank message should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("message")),
                "Should have a violation on 'message'");
    }

    // --- Property: over-max sizes produce violations ---

    /**
     * Validates: Requirements 3.1
     *
     * For any name longer than 120 characters, validation SHALL reject.
     */
    @Property(tries = 50)
    void nameExceedingMaxLengthIsRejected(@ForAll("overMaxNames") String longName) {
        var dto = new ContactEnquiryRequestDto(longName, "valid@example.com", "0123456789", null, "Hello", null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Name > 120 chars should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("name")),
                "Should have a violation on 'name'");
    }

    /**
     * Validates: Requirements 3.1
     *
     * For any email longer than 254 characters, validation SHALL reject.
     */
    @Property(tries = 50)
    void emailExceedingMaxLengthIsRejected(@ForAll("overMaxEmails") String longEmail) {
        var dto = new ContactEnquiryRequestDto("John", longEmail, "0123456789", null, "Hello", null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Email > 254 chars should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("email")),
                "Should have a violation on 'email'");
    }

    /**
     * Validates: Requirements 3.1
     *
     * For any phone longer than 40 characters, validation SHALL reject.
     */
    @Property(tries = 50)
    void phoneExceedingMaxLengthIsRejected(@ForAll("overMaxPhones") String longPhone) {
        var dto = new ContactEnquiryRequestDto("John", "valid@example.com", longPhone, null, "Hello", null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Phone > 40 chars should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("phone")),
                "Should have a violation on 'phone'");
    }

    /**
     * Validates: Requirements 3.1
     *
     * For any company longer than 160 characters, validation SHALL reject.
     */
    @Property(tries = 50)
    void companyExceedingMaxLengthIsRejected(@ForAll("overMaxCompanies") String longCompany) {
        var dto = new ContactEnquiryRequestDto("John", "valid@example.com", "0123456789", longCompany, "Hello", null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Company > 160 chars should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("company")),
                "Should have a violation on 'company'");
    }

    /**
     * Validates: Requirements 3.1
     *
     * For any message longer than 4000 characters, validation SHALL reject.
     */
    @Property(tries = 50)
    void messageExceedingMaxLengthIsRejected(@ForAll("overMaxMessages") String longMessage) {
        var dto = new ContactEnquiryRequestDto("John", "valid@example.com", "0123456789", null, longMessage, null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Message > 4000 chars should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("message")),
                "Should have a violation on 'message'");
    }

    /**
     * Validates: Requirements 3.1
     *
     * For any website (honeypot) longer than 200 characters, validation SHALL reject.
     */
    @Property(tries = 50)
    void websiteExceedingMaxLengthIsRejected(@ForAll("overMaxWebsite") String longWebsite) {
        var dto = new ContactEnquiryRequestDto("John", "valid@example.com", "0123456789", null, "Hello", longWebsite);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Website > 200 chars should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("website")),
                "Should have a violation on 'website'");
    }

    // --- Property: invalid email format is rejected ---

    /**
     * Validates: Requirements 3.1
     *
     * For any string that is not a valid email format (no '@' or structurally invalid),
     * validation SHALL reject with a violation on 'email'.
     */
    @Property(tries = 100)
    void invalidEmailFormatIsRejected(@ForAll("invalidEmails") String badEmail) {
        var dto = new ContactEnquiryRequestDto("John", badEmail, "0123456789", null, "Hello", null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty(), "Invalid email '" + badEmail + "' should produce violations");
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("email")),
                "Should have a violation on 'email'");
    }

    // --- Property: optional fields can be null ---

    /**
     * Validates: Requirements 3.1
     *
     * A DTO with null company and null website SHALL pass validation
     * (they are optional fields with no @NotBlank).
     */
    @Property(tries = 50)
    void optionalFieldsCanBeNull(
            @ForAll("validNames") String name,
            @ForAll("validEmails") String email,
            @ForAll("validPhones") String phone,
            @ForAll("validMessages") String message
    ) {
        var dto = new ContactEnquiryRequestDto(name, email, phone, null, message, null);
        Set<ConstraintViolation<ContactEnquiryRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(),
                "DTO with null optional fields should pass but got: " + violations);
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> validNames() {
        // Ensure at least one alpha char by prefixing with a letter
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .ofMinLength(1)
                .ofMaxLength(120);
    }

    @Provide
    Arbitrary<String> validEmails() {
        // Generate valid email addresses: local@domain.tld
        // Local part starts with a letter to avoid leading dots/underscores
        var localPrefix = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(1);
        var localSuffix = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(0)
                .ofMaxLength(15);
        var domains = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(2)
                .ofMaxLength(10);
        var tlds = Arbitraries.of("com", "org", "net", "co.za", "io");
        return Combinators.combine(localPrefix, localSuffix, domains, tlds)
                .as((prefix, suffix, domain, tld) -> prefix + suffix + "@" + domain + "." + tld);
    }

    @Provide
    Arbitrary<String> validPhones() {
        // Start with a digit to ensure non-blank
        var prefix = Arbitraries.strings()
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(1);
        var rest = Arbitraries.strings()
                .withCharRange('0', '9')
                .withChars('+', '-')
                .ofMinLength(0)
                .ofMaxLength(39);
        return Combinators.combine(prefix, rest).as((p, r) -> p + r);
    }

    @Provide
    Arbitrary<String> validCompanies() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .ofMinLength(1)
                .ofMaxLength(160);
    }

    @Provide
    Arbitrary<String> validMessages() {
        // Start with a letter to ensure non-blank
        var prefix = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(1);
        var rest = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withChars(' ', '.', ',', '!', '?')
                .ofMinLength(0)
                .ofMaxLength(3999);
        return Combinators.combine(prefix, rest).as((p, r) -> p + r);
    }

    @Provide
    Arbitrary<String> blankStrings() {
        return Arbitraries.of("", " ", "  ", "\t", "\n", "   \t\n  ");
    }

    @Provide
    Arbitrary<String> overMaxNames() {
        // Strings of length 121-250
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(121)
                .ofMaxLength(250);
    }

    @Provide
    Arbitrary<String> overMaxEmails() {
        // Generate overlong emails > 254 chars: long_local@domain.com
        return Arbitraries.integers().between(255, 300).map(len -> {
            String suffix = "@example.com";
            String local = "a".repeat(len - suffix.length());
            return local + suffix;
        });
    }

    @Provide
    Arbitrary<String> overMaxPhones() {
        return Arbitraries.strings()
                .withCharRange('0', '9')
                .ofMinLength(41)
                .ofMaxLength(80);
    }

    @Provide
    Arbitrary<String> overMaxCompanies() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(161)
                .ofMaxLength(300);
    }

    @Provide
    Arbitrary<String> overMaxMessages() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(4001)
                .ofMaxLength(5000);
    }

    @Provide
    Arbitrary<String> overMaxWebsite() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(201)
                .ofMaxLength(400);
    }

    @Provide
    Arbitrary<String> invalidEmails() {
        return Arbitraries.of(
                "plaintext",
                "missing-at.com",
                "@no-local.com",
                "spaces in@email.com",
                "double@@at.com"
        );
    }
}
