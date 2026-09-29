package com.payone.pcp.core.converters;

import de.hybris.platform.core.model.order.AbstractOrderModel;

import com.payone.commerce.platform.lib.models.CheckoutReferences;
import com.payone.commerce.platform.lib.models.References;

import java.util.Objects;


/**
 * Converts SAP {@link AbstractOrderModel} to PCP {@link References} and {@link CheckoutReferences}.
 */
public class PcpReferencesConverter
{
	/**
	 * Converts an order to PCP References (used in PaymentExecution).
	 * <p>
	 * PaymentExecution.references.merchantReference must be unique merchant-wide: reusing a
	 * cart's plain code across retries or a second payment product on the same cart gets
	 * rejected with HTTP 409 payment-error-colliding-merchant-reference. See
	 * {@link PcpMerchantReferenceGenerator}.
	 *
	 * @param order the SAP order; must not be null
	 * @return PCP References
	 */
	public References convertReferences(final AbstractOrderModel order)
	{
		Objects.requireNonNull(order, "order must not be null");

		// merchantReference maxLength 20 (api-reference)
		return new References()
				.merchantReference(PcpMerchantReferenceGenerator.unique(
						PcpMerchantReferenceGenerator.resolveBase(order), 20));
	}

	/**
	 * Converts an order to PCP CheckoutReferences (used in CreateCheckout).
	 *
	 * @param order                  the SAP order; must not be null
	 * @param merchantShopReference  the store/site UID; may be null
	 * @return PCP CheckoutReferences
	 */
	public CheckoutReferences convertCheckoutReferences(
			final AbstractOrderModel order,
			final String merchantShopReference)
	{
		Objects.requireNonNull(order, "order must not be null");

		// merchantReference maxLength 40, merchantShopReference maxLength 64 (api-reference)
		// Not suffixed here: DefaultPayoneCheckoutFacade.createOrGetCommerceCase overrides this
		// with the same suffixed reference used for CreateCommerceCaseRequest, so both requests match.
		return new CheckoutReferences()
				.merchantReference(PcpFieldTrimmer.trim(PcpMerchantReferenceGenerator.resolveBase(order), 40))
				.merchantShopReference(PcpFieldTrimmer.trim(merchantShopReference, 64));
	}
}
