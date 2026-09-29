package com.payone.pcp.webhook.enums;

/**
 * Domain of a PCP webhook event, i.e. which root object of the envelope the event describes.
 * This is the branch key the PCP guide's "examine type first" rule refers to: derived from the
 * event type token ({@link PayoneWebhookEventType}), never from which JSON members are present.
 */
public enum PayoneWebhookDomain
{
	/** {@code payment} — lifecycle of a payment transaction. */
	PAYMENT("payment"),

	/** {@code refund} — refund process states. */
	REFUND("refund"),

	/** {@code payout} — merchant outbound fund movement. */
	PAYOUT("payout"),

	/** {@code commerceCase} — order/case context changes. */
	COMMERCE_CASE("commerceCase"),

	/** {@code checkout} — checkout progression. */
	CHECKOUT("checkout"),

	/** {@code paymentExecution} — payment-operation specific processes. */
	PAYMENT_EXECUTION("paymentExecution"),

	/** {@code paymentInformation} — near-time notification of a terminal / POS payment. */
	PAYMENT_INFORMATION("paymentInformation");

	private final String envelopeMember;

	PayoneWebhookDomain(final String envelopeMember)
	{
		this.envelopeMember = envelopeMember;
	}

	/** @return the JSON member this domain's snapshot is delivered under at the envelope root. */
	public String getEnvelopeMember()
	{
		return envelopeMember;
	}
}
