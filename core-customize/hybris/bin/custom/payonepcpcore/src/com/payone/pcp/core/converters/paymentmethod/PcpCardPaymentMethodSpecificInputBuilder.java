package com.payone.pcp.core.converters.paymentmethod;

import de.hybris.platform.core.HybrisEnumValue;

import com.payone.commerce.platform.lib.models.AuthorizationMode;
import com.payone.commerce.platform.lib.models.CardPaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.PaymentProductId;
import com.payone.commerce.platform.lib.models.TransactionChannel;

import com.payone.pcp.core.model.PayonePaymentInfoModel;
import com.payone.pcp.core.model.PayoneConfigurationModel;

import java.util.Objects;


/** Builds CardPaymentMethodSpecificInput from PayonePaymentInfo and config. */
public class PcpCardPaymentMethodSpecificInputBuilder
{
	/**
	 * @param paymentInfo must not be null
	 * @param config must not be null
	 */
	public CardPaymentMethodSpecificInput build(
			final PayonePaymentInfoModel paymentInfo,
			final PayoneConfigurationModel config)
	{
		Objects.requireNonNull(paymentInfo, "paymentInfo must not be null");
		Objects.requireNonNull(config, "config must not be null");

		final CardPaymentMethodSpecificInput input = new CardPaymentMethodSpecificInput();

		if (paymentInfo.getPaymentProcessingToken() != null)
		{
			input.paymentProcessingToken(paymentInfo.getPaymentProcessingToken());
		}

		if (paymentInfo.getPaymentProductId() != null)
		{
			input.paymentProductId(PaymentProductId.fromValue(paymentInfo.getPaymentProductId()));
		}

        input.authorizationMode(resolveAuthorizationMode(config.getDefaultAuthorizationMode()));

		if (config.getReturnUrl() != null)
		{
			input.returnUrl(config.getReturnUrl());
		}

		// always ECOMMERCE for web checkout
		input.transactionChannel(TransactionChannel.ECOMMERCE);

		return input;
	}

	/** Null mode (attribute unset) defaults to SALE, matching the itemtype default. */
	private static AuthorizationMode resolveAuthorizationMode(
			final HybrisEnumValue hybrisMode)
	{
        return hybrisMode != null && "PRE_AUTHORIZATION".equals(hybrisMode.getCode())
                ? AuthorizationMode.PRE_AUTHORIZATION
                : AuthorizationMode.SALE;
    }
}
