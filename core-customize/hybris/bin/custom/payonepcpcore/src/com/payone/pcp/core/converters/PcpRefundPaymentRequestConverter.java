package com.payone.pcp.core.converters;

import com.payone.commerce.platform.lib.models.PositiveAmountOfMoney;
import com.payone.commerce.platform.lib.models.RefundRequest;

import java.util.Objects;


/**
 * Builds a PCP {@link RefundRequest} from an amount.
 */
public class PcpRefundPaymentRequestConverter
{
	/**
	 * captureReference is not set here; the facade supplies it for the specific capture being refunded.
	 *
	 * @param amount the amount to refund in minor units; must not be null
	 * @return PCP RefundRequest
	 */
	public RefundRequest convert(
			final Long amount)
	{
		Objects.requireNonNull(amount, "amount must not be null");

		return new RefundRequest()
				.amountOfMoney(new PositiveAmountOfMoney()
						.amount(amount));
	}
}
