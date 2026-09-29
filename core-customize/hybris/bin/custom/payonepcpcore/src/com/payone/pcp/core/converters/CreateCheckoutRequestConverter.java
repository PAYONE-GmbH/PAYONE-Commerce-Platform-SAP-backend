package com.payone.pcp.core.converters;

import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.commerce.platform.lib.models.CheckoutReferences;
import com.payone.commerce.platform.lib.models.CreateCheckoutRequest;
import com.payone.commerce.platform.lib.models.Shipping;
import com.payone.commerce.platform.lib.models.ShoppingCartInput;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.core.model.user.CustomerModel;

import java.util.Objects;


/**
 * Composes sub-converters to build a PCP SDK CreateCheckoutRequest from SAP Commerce models.
 * Stateless; missing SAP data maps to a null SDK field rather than an empty one.
 */
public class CreateCheckoutRequestConverter
{
	private final PcpAmountOfMoneyConverter amountOfMoneyConverter;
	private final PcpShoppingCartConverter shoppingCartConverter;
	private final PcpReferencesConverter referencesConverter;
	private final PcpAddressConverter addressConverter;

	public CreateCheckoutRequestConverter(
			final PcpAmountOfMoneyConverter amountOfMoneyConverter,
			final PcpShoppingCartConverter shoppingCartConverter,
			final PcpReferencesConverter referencesConverter,
			final PcpAddressConverter addressConverter)
	{
		this.amountOfMoneyConverter = amountOfMoneyConverter;
		this.shoppingCartConverter = shoppingCartConverter;
		this.referencesConverter = referencesConverter;
		this.addressConverter = addressConverter;
	}

	/**
	 * Builds a CreateCheckoutRequest from SAP Commerce models.
	 *
	 * @param order                 the SAP order; may not be null
	 * @param deliveryAddress       the delivery address for shipping; may not be null
	 * @param customer              the SAP customer; may not be null (used for personal name on shipping)
	 * @param merchantShopReference the store/site UID; may be null
	 * @return PCP CreateCheckoutRequest
	 */
	public CreateCheckoutRequest convert(
			final AbstractOrderModel order,
			final AddressModel deliveryAddress,
			final CustomerModel customer,
			final String merchantShopReference)
	{
		Objects.requireNonNull(order, "order must not be null");
		Objects.requireNonNull(deliveryAddress, "deliveryAddress must not be null");
		Objects.requireNonNull(customer, "customer must not be null");

		// Step-by-Step checkout (confirmed design decision): the platform default
		// is already false when omitted (docs.commerce.payone.com/api-reference),
		// set explicitly for clarity.
		final CreateCheckoutRequest request = new CreateCheckoutRequest()
				.autoExecuteOrder(Boolean.FALSE);

		final AmountOfMoney amount = amountOfMoneyConverter.convert(order);
		if (amount != null)
		{
			request.amountOfMoney(amount);
		}

		final ShoppingCartInput cart = shoppingCartConverter.convert(order);
		if (cart != null)
		{
			request.shoppingCart(cart);
		}

		final CheckoutReferences refs = referencesConverter.convertCheckoutReferences(order, merchantShopReference);
		if (refs != null)
		{
			request.references(refs);
		}

		final String firstName = customer.getDefaultPaymentAddress() != null
				? customer.getDefaultPaymentAddress().getFirstname()
				: null;
		final String surname = customer.getDefaultPaymentAddress() != null
				? customer.getDefaultPaymentAddress().getLastname()
				: null;
		request.shipping(new Shipping().address(addressConverter.toAddressPersonal(deliveryAddress, firstName, surname)));

		return request;
	}
}
