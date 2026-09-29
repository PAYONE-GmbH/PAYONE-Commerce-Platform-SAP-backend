package com.payone.pcp.core.converters.paymentmethod;

import de.hybris.platform.core.HybrisEnumValue;

import com.payone.commerce.platform.lib.models.AuthorizationMode;
import com.payone.commerce.platform.lib.models.MobilePaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.MobilePaymentThreeDSecure;
import com.payone.commerce.platform.lib.models.PaymentProductId;
import com.payone.commerce.platform.lib.models.RedirectionData;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;

import org.apache.commons.lang3.StringUtils;

import java.util.Objects;


/**
 * Builds MobilePaymentMethodSpecificInput for Google Pay (320) and a minimal Apple Pay (302) cut.
 * Field names verified against docs.commerce.payone.com/docs/payment-methods/{google-pay,apple-pay}
 * and the SDK jar. Google Pay only needs encryptedPaymentData - publicKeyHash/ephemeralKey belong
 * to a different token format the doc doesn't use here. Apple Pay's minimal cut covers only the
 * top-level mandatory fields; paymentProduct302SpecificInput (integrationType/network/token.*)
 * needs a real PKPaymentToken and merchant config fields that don't exist yet, so it's omitted -
 * this only needs to produce a syntactically valid One-Step CommerceCase request.
 * customerDevice/paymentChannel are set centrally by PaymentExecutionRequestConverter, not here.
 */
public class PcpMobilePaymentMethodSpecificInputBuilder
{
	private static final int PRODUCT_ID_APPLE_PAY = 302;

	/**
	 * @param paymentInfo must not be null; must carry the family-appropriate encrypted-payment-data
	 *                    field (googlePayEncryptedPaymentData for 320, or
	 *                    applePayEncryptedPaymentData/PublicKeyHash/EphemeralKey for 302)
	 * @param config must not be null
	 * @throws IllegalArgumentException if the family-appropriate mandatory field(s) are blank
	 */
	public MobilePaymentMethodSpecificInput build(
			final PayonePaymentInfoModel paymentInfo,
			final PayoneConfigurationModel config)
	{
		Objects.requireNonNull(paymentInfo, "paymentInfo must not be null");
		Objects.requireNonNull(config, "config must not be null");

		final MobilePaymentMethodSpecificInput input = paymentInfo.getPaymentProductId() != null
				&& paymentInfo.getPaymentProductId() == PRODUCT_ID_APPLE_PAY
						? buildApplePay(paymentInfo)
						: buildGooglePay(paymentInfo);

		input.authorizationMode(resolveAuthorizationMode(config.getDefaultAuthorizationMode()));

		if (paymentInfo.getPaymentProductId() != null)
		{
			input.paymentProductId(PaymentProductId.fromValue(paymentInfo.getPaymentProductId()));
		}

		if (config.getReturnUrl() != null)
		{
			input.threeDSecure(new MobilePaymentThreeDSecure()
					.redirectionData(new RedirectionData().returnUrl(config.getReturnUrl())));
		}

		return input;
	}

	private static MobilePaymentMethodSpecificInput buildGooglePay(final PayonePaymentInfoModel paymentInfo)
	{
		if (StringUtils.isBlank(paymentInfo.getGooglePayEncryptedPaymentData()))
		{
			throw new IllegalArgumentException(
					"paymentInfo.googlePayEncryptedPaymentData must not be blank - Google Pay has no payload to authorize");
		}

		return new MobilePaymentMethodSpecificInput()
				.encryptedPaymentData(paymentInfo.getGooglePayEncryptedPaymentData());
	}

	private static MobilePaymentMethodSpecificInput buildApplePay(final PayonePaymentInfoModel paymentInfo)
	{
		if (StringUtils.isBlank(paymentInfo.getApplePayEncryptedPaymentData())
				|| StringUtils.isBlank(paymentInfo.getApplePayPublicKeyHash())
				|| StringUtils.isBlank(paymentInfo.getApplePayEphemeralKey()))
		{
			throw new IllegalArgumentException(
					"paymentInfo.applePayEncryptedPaymentData/PublicKeyHash/EphemeralKey must all be set - "
							+ "Apple Pay has no payload to authorize");
		}

		return new MobilePaymentMethodSpecificInput()
				.encryptedPaymentData(paymentInfo.getApplePayEncryptedPaymentData())
				.publicKeyHash(paymentInfo.getApplePayPublicKeyHash())
				.ephemeralKey(paymentInfo.getApplePayEphemeralKey());
	}

	/**
	 * Null mode (config unset) defaults to SALE, per Google Pay's doc example (Apple Pay's example
	 * uses PRE_AUTHORIZATION, but this builder keeps SALE as the unset default for both, matching
	 * the Card builder).
	 */
	private static AuthorizationMode resolveAuthorizationMode(final HybrisEnumValue hybrisMode)
	{
		return hybrisMode != null && "PRE_AUTHORIZATION".equals(hybrisMode.getCode())
				? AuthorizationMode.PRE_AUTHORIZATION
				: AuthorizationMode.SALE;
	}
}
