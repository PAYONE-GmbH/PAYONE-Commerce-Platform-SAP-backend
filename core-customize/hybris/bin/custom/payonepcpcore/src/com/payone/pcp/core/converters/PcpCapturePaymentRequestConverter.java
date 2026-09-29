package com.payone.pcp.core.converters;

import com.payone.commerce.platform.lib.models.CapturePaymentRequest;

import java.util.Objects;


/** Builds a PCP SDK CapturePaymentRequest from primitive parameters. */
public class PcpCapturePaymentRequestConverter
{
	/**
	 * Builds a CapturePaymentRequest from amount and final flag.
	 *
	 * @param amount  the amount to capture in minor units; must not be null
	 * @param isFinal whether this is the final capture; null is treated as false
	 * @return PCP CapturePaymentRequest
	 */
	public CapturePaymentRequest convert(
			final Long amount,
			final Boolean isFinal)
	{
		Objects.requireNonNull(amount, "amount must not be null");

		return new CapturePaymentRequest()
				.amount(amount)
				.isFinal(isFinal != null ? isFinal : Boolean.FALSE);
	}
}
