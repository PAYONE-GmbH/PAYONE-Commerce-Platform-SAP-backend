package com.payone.pcp.core.converters;

import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.commerce.platform.lib.models.CardPaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.CustomerDevice;
import com.payone.commerce.platform.lib.models.FinancingPaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.MobilePaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.PaymentChannel;
import com.payone.commerce.platform.lib.models.PaymentExecutionRequest;
import com.payone.commerce.platform.lib.models.PaymentExecutionSpecificInput;
import com.payone.commerce.platform.lib.models.PaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.RedirectPaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.References;
import com.payone.commerce.platform.lib.models.SepaDirectDebitPaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.ShoppingCartInput;

import com.payone.pcp.core.converters.paymentmethod.PcpCardPaymentMethodSpecificInputBuilder;
import com.payone.pcp.core.converters.paymentmethod.PcpFinancingPaymentMethodSpecificInputBuilder;
import com.payone.pcp.core.converters.paymentmethod.PcpMobilePaymentMethodSpecificInputBuilder;
import com.payone.pcp.core.converters.paymentmethod.PcpRedirectPaymentMethodSpecificInputBuilder;
import com.payone.pcp.core.converters.paymentmethod.PcpSepaDirectDebitPaymentMethodSpecificInputBuilder;
import com.payone.pcp.core.enums.PaymentFamily;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.payment.PaymentModeModel;
import de.hybris.platform.order.PaymentModeService;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;

import java.util.Objects;


/**
 * Composes sub-converters and payment-method-specific input builders to build a PCP SDK
 * PaymentExecutionRequest from SAP Commerce models.
 */
public class PaymentExecutionRequestConverter
{
	private final PcpAmountOfMoneyConverter amountOfMoneyConverter;
	private final PcpShoppingCartConverter shoppingCartConverter;
	private final PcpReferencesConverter referencesConverter;
	private final PcpCardPaymentMethodSpecificInputBuilder cardBuilder;
	private final PcpRedirectPaymentMethodSpecificInputBuilder redirectBuilder;
	private final PcpSepaDirectDebitPaymentMethodSpecificInputBuilder sepaBuilder;
	private final PcpMobilePaymentMethodSpecificInputBuilder mobileBuilder;
	private final PcpFinancingPaymentMethodSpecificInputBuilder financingBuilder;
	private final PaymentModeService paymentModeService;

	public PaymentExecutionRequestConverter(
			final PcpAmountOfMoneyConverter amountOfMoneyConverter,
			final PcpShoppingCartConverter shoppingCartConverter,
			final PcpReferencesConverter referencesConverter,
			final PcpCardPaymentMethodSpecificInputBuilder cardBuilder,
			final PcpRedirectPaymentMethodSpecificInputBuilder redirectBuilder,
			final PcpSepaDirectDebitPaymentMethodSpecificInputBuilder sepaBuilder,
			final PcpMobilePaymentMethodSpecificInputBuilder mobileBuilder,
			final PcpFinancingPaymentMethodSpecificInputBuilder financingBuilder,
			final PaymentModeService paymentModeService)
	{
		this.amountOfMoneyConverter = amountOfMoneyConverter;
		this.shoppingCartConverter = shoppingCartConverter;
		this.referencesConverter = referencesConverter;
		this.cardBuilder = cardBuilder;
		this.redirectBuilder = redirectBuilder;
		this.sepaBuilder = sepaBuilder;
		this.mobileBuilder = mobileBuilder;
		this.financingBuilder = financingBuilder;
		this.paymentModeService = paymentModeService;
	}

	/**
	 * Builds a PaymentExecutionRequest from SAP Commerce models.
	 *
	 * @param order       the SAP order; must not be null
	 * @param paymentInfo the PCP payment info carrying token and product id; may be null
	 * @param config      the resolved PCP configuration; must not be null
	 * @return PCP PaymentExecutionRequest
	 */
	public PaymentExecutionRequest convert(
			final AbstractOrderModel order,
			final PayonePaymentInfoModel paymentInfo,
			final PayoneConfigurationModel config)
	{
		Objects.requireNonNull(order, "order must not be null");
		Objects.requireNonNull(config, "config must not be null");

		final PaymentExecutionRequest request = new PaymentExecutionRequest();

		final PaymentExecutionSpecificInput specificInput = new PaymentExecutionSpecificInput();

		final AmountOfMoney amount = amountOfMoneyConverter.convert(order);
		if (amount != null)
		{
			specificInput.amountOfMoney(amount);
		}

		final ShoppingCartInput cart = shoppingCartConverter.convert(order);
		if (cart != null)
		{
			specificInput.shoppingCart(cart);
		}

		final References refs = referencesConverter.convertReferences(order);
		if (refs != null)
		{
			specificInput.paymentReferences(refs);
		}

		request.paymentExecutionSpecificInput(specificInput);

		// Dispatches to the per-family builder based on paymentInfo's product id, so
		// exactly one method input is attached for the family in play.
		final PaymentMethodSpecificInput methodInput = buildMethodSpecificInput(paymentInfo, config.getReturnUrl(), config);
		if (methodInput != null)
		{
			request.paymentMethodSpecificInput(methodInput);
		}

		return request;
	}

	/**
	 * Builds the single {@link PaymentMethodSpecificInput} for the payment family in play,
	 * dispatching on the payment product id carried on the payment info.
	 *
	 * @param paymentInfo the PCP payment info carrying token and product id; may be null
	 * @param config      the resolved PCP configuration; may be null
	 * @return the per-family input, or null if the family is unknown, no product id is
	 *         available, or the input could not be built
	 */
	private PaymentMethodSpecificInput buildMethodSpecificInput(
			final PayonePaymentInfoModel paymentInfo, final String returnUrl, final PayoneConfigurationModel config)
	{
		if (paymentInfo == null || paymentInfo.getPaymentProductId() == null)
		{
			return null;
		}

		final PaymentFamily family = familyForProductId(paymentInfo.getPaymentProductId());
		if (family == null)
		{
			return null;
		}

		final PaymentMethodSpecificInput methodInput = switch (family)
		{
			case CARD ->
			{
				final CardPaymentMethodSpecificInput cardInput = cardBuilder.build(paymentInfo, config);
				yield cardInput == null ? null : new PaymentMethodSpecificInput().cardPaymentMethodSpecificInput(cardInput);
			}
			case REDIRECT ->
			{
				final RedirectPaymentMethodSpecificInput redirectInput = redirectBuilder.build(paymentInfo, returnUrl);
				yield redirectInput == null ? null : new PaymentMethodSpecificInput().redirectPaymentMethodSpecificInput(redirectInput);
			}
			case MOBILE ->
			{
				final MobilePaymentMethodSpecificInput mobileInput = mobileBuilder.build(paymentInfo, config);
				yield mobileInput == null ? null : new PaymentMethodSpecificInput().mobilePaymentMethodSpecificInput(mobileInput);
			}
			case SEPA ->
			{
				final SepaDirectDebitPaymentMethodSpecificInput sepaInput = sepaBuilder.build(paymentInfo, config);
				yield sepaInput == null ? null : new PaymentMethodSpecificInput().sepaDirectDebitPaymentMethodSpecificInput(sepaInput);
			}
			case FINANCING ->
			{
				final FinancingPaymentMethodSpecificInput financingInput = financingBuilder.build(paymentInfo, returnUrl);
				yield financingInput == null ? null : new PaymentMethodSpecificInput().financingPaymentMethodSpecificInput(financingInput);
			}
		};

		// paymentChannel is required on every PaymentMethodSpecificInput
		// (docs.commerce.payone.com/api-reference), same value for all families.
		if (methodInput != null)
		{
			methodInput.paymentChannel(PaymentChannel.ECOMMERCE);

			// customerDevice sits alongside the per-family input, not inside it.
			// Mandatory for PAYONE BNPL (docs.commerce.payone.com/docs/payment-methods/
			// payone-bnpl/payone-secured-invoice) but the SDK doesn't restrict it to
			// FINANCING, so set it here whenever an IP address is available.
			if (paymentInfo.getCustomerIpAddress() != null)
			{
				methodInput.customerDevice(new CustomerDevice().ipAddress(paymentInfo.getCustomerIpAddress()));
			}
		}
		return methodInput;
	}

	/**
	 * Resolves the payment-method family for a PCP payment product ID via the OOTB
	 * {@code PaymentMode.paymentFamily} attribute (populated by projectdata-paymentmodes.impex).
	 *
	 * @param productId the PCP payment product ID; may be {@code null}
	 * @return the matching {@link PaymentFamily}, or {@code null} if {@code productId} is
	 *         {@code null} or no {@code PaymentMode} with that code exists (e.g. undocumented 3391)
	 */
	private PaymentFamily familyForProductId(final Integer productId)
	{
		if (productId == null)
		{
			return null;
		}
		try
		{
			final PaymentModeModel paymentMode = paymentModeService.getPaymentModeForCode(String.valueOf(productId));
			return paymentMode.getPaymentFamily();
		}
		catch (final UnknownIdentifierException e)
		{
			return null;
		}
	}
}
