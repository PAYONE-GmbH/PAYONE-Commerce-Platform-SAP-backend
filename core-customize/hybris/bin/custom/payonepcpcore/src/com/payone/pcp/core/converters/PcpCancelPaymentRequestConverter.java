package com.payone.pcp.core.converters;

import com.payone.commerce.platform.lib.models.CancelPaymentRequest;
import com.payone.commerce.platform.lib.models.CancellationReason;

import java.util.Objects;


/** Builds a PCP SDK CancelPaymentRequest from primitive parameters. */
public class PcpCancelPaymentRequestConverter
{
	/**
	 * Builds a CancelPaymentRequest from a cancellation reason.
	 *
	 * @param cancellationReason the PCP cancellation reason; must not be null
	 * @return PCP CancelPaymentRequest
	 */
	public CancelPaymentRequest convert(final CancellationReason cancellationReason)
	{
		Objects.requireNonNull(cancellationReason, "cancellationReason must not be null");

		return new CancelPaymentRequest()
				.cancellationReason(cancellationReason);
	}
}
