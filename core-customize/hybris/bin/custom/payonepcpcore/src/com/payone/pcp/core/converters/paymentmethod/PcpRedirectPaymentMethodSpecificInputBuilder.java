package com.payone.pcp.core.converters.paymentmethod;

import com.payone.commerce.platform.lib.models.PaymentProductId;
import com.payone.commerce.platform.lib.models.RedirectPaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.RedirectPaymentProduct840SpecificInput;
import com.payone.commerce.platform.lib.models.RedirectionData;

import com.payone.pcp.core.model.PayonePaymentInfoModel;

import java.util.Objects;


/**
 * Builds RedirectPaymentMethodSpecificInput for PayPal (840) and Wero (900), the two
 * REDIRECT-family products wired in payonepcpcore-spring.xml's paymentStrategyRegistry.
 * requiresApproval=false and tokenize=false because both target the direct-sale flow only:
 * Wero doc states requiresApproval=false is the only supported value today, and PayPal's
 * direct-sale flow captures on customer confirmation with no billing-agreement token reuse.
 * A future billing-agreement flow would need a field on PayonePaymentInfo to carry the flag.
 * paymentProduct840SpecificInput targets the plain PayPal REST redirect, not Express or the
 * JS SDK flow. paymentProduct900SpecificInput.captureTrigger is omitted for Wero - PCP docs
 * say it only applies when requiresApproval=true.
 * (docs.commerce.payone.com/docs/payment-methods/paypal/paypal-rest and .../wero/)
 */
public class PcpRedirectPaymentMethodSpecificInputBuilder
{
	private static final int PRODUCT_ID_PAYPAL = 840;

	/**
	 * @param paymentInfo must not be null
	 * @param returnUrl must not be null
	 */
	public RedirectPaymentMethodSpecificInput build(
			final PayonePaymentInfoModel paymentInfo,
			final String returnUrl)
	{
		Objects.requireNonNull(paymentInfo, "paymentInfo must not be null");
		Objects.requireNonNull(returnUrl, "returnUrl must not be null");

		final RedirectPaymentMethodSpecificInput input = new RedirectPaymentMethodSpecificInput()
				.requiresApproval(Boolean.FALSE)
				.tokenize(Boolean.FALSE)
				.redirectionData(new RedirectionData().returnUrl(returnUrl));

		if (paymentInfo.getPaymentProductId() != null)
		{
			input.paymentProductId(PaymentProductId.fromValue(paymentInfo.getPaymentProductId()));

			if (paymentInfo.getPaymentProductId() == PRODUCT_ID_PAYPAL)
			{
				input.paymentProduct840SpecificInput(
						new RedirectPaymentProduct840SpecificInput()
								.addressSelectionAtPayPal(Boolean.FALSE)
								.javaScriptSdkFlow(Boolean.FALSE));
			}
		}

		return input;
	}
}
