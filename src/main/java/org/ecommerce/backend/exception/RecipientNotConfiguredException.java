package org.ecommerce.backend.exception;

/**
 * Thrown when no enquiry recipient email is configured in {@code storefront.contact.enquiryEmail}.
 * The enquiry endpoint returns HTTP 503 when this occurs — enquiries cannot be delivered
 * without an explicit recipient.
 */
public class RecipientNotConfiguredException extends RuntimeException {

    public RecipientNotConfiguredException() {
        super("No enquiry recipient email is configured in storefront.contact.enquiryEmail");
    }

    public RecipientNotConfiguredException(String message) {
        super(message);
    }
}
