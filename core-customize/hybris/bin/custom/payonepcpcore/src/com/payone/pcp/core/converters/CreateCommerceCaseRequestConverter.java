package com.payone.pcp.core.converters;

import com.payone.commerce.platform.lib.models.CreateCommerceCaseRequest;
import com.payone.commerce.platform.lib.models.Customer;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.core.model.user.CustomerModel;

import java.util.Objects;


/** Composes sub-converters to build a PCP SDK CreateCommerceCaseRequest from SAP Commerce models. */
public class CreateCommerceCaseRequestConverter
{
	private final PcpCustomerConverter customerConverter;

	public CreateCommerceCaseRequestConverter(final PcpCustomerConverter customerConverter)
	{
		this.customerConverter = customerConverter;
	}

	/**
	 * Builds a CreateCommerceCaseRequest from SAP Commerce models.
	 *
	 * @param order          the SAP order providing the merchant reference; must not be null
	 * @param customer       the SAP customer; must not be null
	 * @param billingAddress the billing address; may be null
	 * @return PCP CreateCommerceCaseRequest
	 */
	public CreateCommerceCaseRequest convert(
			final AbstractOrderModel order,
			final CustomerModel customer,
			final AddressModel billingAddress)
	{
		Objects.requireNonNull(order, "order must not be null");
		Objects.requireNonNull(customer, "customer must not be null");

		final CreateCommerceCaseRequest request =
				new CreateCommerceCaseRequest().merchantReference(PcpMerchantReferenceGenerator.resolveBase(order));

		final Customer pcpCustomer = customerConverter.convert(customer, billingAddress);
		if (pcpCustomer != null)
		{
			request.customer(pcpCustomer);
		}

		return request;
	}
}
