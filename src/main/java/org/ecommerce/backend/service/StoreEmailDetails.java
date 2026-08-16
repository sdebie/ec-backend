package org.ecommerce.backend.service;

/**
 * The store's own details, as an email needs to present them to a shopper.
 * <p>
 * Every field is nullable because every one of them is operator-supplied and may not be
 * filled in yet. Templates guard each individually rather than assuming a complete set —
 * an unconfigured phone number should cost the reader a line, not the whole footer.
 *
 * @param name           the trading name, from {@code storefront.config → clientName}
 * @param currencySymbol what to put in front of an amount, derived from the configured
 *                       currency; falls back to the currency code when the symbol is not
 *                       one this platform knows, which reads oddly but never wrongly
 * @param address        the physical address, used as the collection address
 * @param businessHours  when a shopper can actually come and collect
 * @param enquiryEmail   where a shopper replies with a question
 * @param phone          the first configured phone number, or the landline
 */
public record StoreEmailDetails(
        String name,
        String currencySymbol,
        String address,
        String businessHours,
        String enquiryEmail,
        String phone)
{
}
