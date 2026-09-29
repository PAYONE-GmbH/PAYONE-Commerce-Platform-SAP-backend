package com.payone.pcp.webhook.service.data;

import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;

/**
 * Resolution result: the local checkout and/or commerce case an event refers to. Both null is a
 * normal outcome, not a failure: a POS transaction never had a Commerce checkout, a case may
 * belong to another environment sharing the merchant account, or a payment.* event may race ahead
 * of the local checkout being committed. An unresolved target never rejects the delivery; the
 * event is stored either way and can be replayed later.
 */
public class PayoneWebhookTarget
{
	/** Nothing local matched — not an error, see class Javadoc. */
	public static final PayoneWebhookTarget NONE = new PayoneWebhookTarget(null, null);

	/** The resolved {@code PayoneCheckout}, or null if unknown here. */
	private final PayoneCheckoutModel checkout;

	/** The resolved {@code PayoneCommerceCase}, or null if unknown here. */
	private final PayoneCommerceCaseModel commerceCase;

	public PayoneWebhookTarget(final PayoneCheckoutModel checkout, final PayoneCommerceCaseModel commerceCase)
	{
		this.checkout = checkout;
		this.commerceCase = commerceCase;
	}

	public PayoneCheckoutModel getCheckout()
	{
		return checkout;
	}

	public PayoneCommerceCaseModel getCommerceCase()
	{
		return commerceCase;
	}

	public boolean hasCheckout()
	{
		return checkout != null;
	}

	public boolean hasCommerceCase()
	{
		return commerceCase != null;
	}

	/** @return true when neither entity could be resolved locally. */
	public boolean isEmpty()
	{
		return !hasCheckout() && !hasCommerceCase();
	}
}
