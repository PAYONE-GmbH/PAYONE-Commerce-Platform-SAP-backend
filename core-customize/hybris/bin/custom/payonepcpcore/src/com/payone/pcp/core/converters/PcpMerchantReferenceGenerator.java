package com.payone.pcp.core.converters;

import de.hybris.platform.core.model.order.AbstractOrderModel;

import java.util.Objects;
import java.util.UUID;


/**
 * Derives and uniquifies merchantReference for PCP requests (CreateCommerceCase, CreateCheckout,
 * PaymentExecution). PCP requires merchantReference to be unique per call
 * (docs.commerce.payone.com/api-reference); order.getCode() alone collides on a failed-payment
 * retry or a second payment product on the same cart, which PCP rejects with HTTP 409
 * payment-error-colliding-merchant-reference.
 */
public final class PcpMerchantReferenceGenerator
{
	/** Fixed length of the "-" + 8-char suffix appended by {@link #unique}. */
	private static final int SUFFIX_LENGTH = 9;

	private PcpMerchantReferenceGenerator()
	{
		// utility class
	}

	/**
	 * Resolves the base merchantReference value for an order: its code, or
	 * its guid if the code is null.
	 *
	 * @param order the SAP order; must not be null
	 * @return the base reference value
	 */
	public static String resolveBase(final AbstractOrderModel order)
	{
		Objects.requireNonNull(order, "order must not be null");
		return order.getCode() != null ? order.getCode() : order.getGuid();
	}

	/**
	 * Builds a merchantReference that is unique per call: {@code base}
	 * trimmed to leave room for a fixed 9-character "-" + 8-char random
	 * suffix, followed by that suffix, all within {@code maxLength}.
	 *
	 * @param base      the base reference value (e.g. {@link #resolveBase});
	 *                  may be null
	 * @param maxLength the maximum length declared for this field in the
	 *                  PCP API reference; must be greater than the 9-char
	 *                  suffix
	 * @return the unique reference, or just the suffix if base is null
	 */
	public static String unique(final String base, final int maxLength)
	{
		return PcpFieldTrimmer.trim(base, maxLength - SUFFIX_LENGTH) + "-" + randomSuffix();
	}

	private static String randomSuffix()
	{
		return UUID.randomUUID()
				.toString()
				.replace("-", "")
				.substring(0, 8);
	}
}
