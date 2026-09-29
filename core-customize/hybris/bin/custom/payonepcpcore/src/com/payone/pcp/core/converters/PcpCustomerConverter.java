package com.payone.pcp.core.converters;

import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.core.model.user.CustomerModel;

import com.payone.commerce.platform.lib.models.Address;
import com.payone.commerce.platform.lib.models.BusinessRelation;
import com.payone.commerce.platform.lib.models.ContactDetails;
import com.payone.commerce.platform.lib.models.Customer;
import com.payone.commerce.platform.lib.models.PersonalInformation;
import com.payone.commerce.platform.lib.models.PersonalName;

import java.text.SimpleDateFormat;
import java.util.Objects;


/** Converts SAP CustomerModel to PCP SDK Customer. */
public class PcpCustomerConverter
{
	private final PcpAddressConverter addressConverter;

	public PcpCustomerConverter(final PcpAddressConverter addressConverter)
	{
		this.addressConverter = addressConverter;
	}

	/**
	 * Converts an SAP CustomerModel with optional billing address to a PCP Customer.
	 *
	 * @param customer       the SAP customer; must not be null
	 * @param billingAddress the billing address to attach; may be null
	 * @return PCP Customer
	 */
	public Customer convert(
			final CustomerModel customer,
			final AddressModel billingAddress)
	{
		Objects.requireNonNull(customer, "customer must not be null");

		// merchantCustomerId is not set: bisection against a real SEPA payment on PCP
		// preprod showed that setting it together with personalInformation gets an
		// otherwise identical SEPA payment rejected, while personalInformation alone
		// captures fine. Root cause is unconfirmed on PCP's side; leave unset until
		// PAYONE support clarifies.
		final Customer pcpCustomer = new Customer();

		// dateOfBirth is mandatory for every PAYONE BNPL payment
		// (docs.commerce.payone.com/docs/payment-methods/payone-bnpl/payone-secured-invoice).
		// PCP's own example sends "19780101" (yyyyMMdd), not ISO yyyy-MM-dd.
		final AddressModel paymentAddress = customer.getDefaultPaymentAddress();
		if (paymentAddress != null)
		{
			pcpCustomer.personalInformation(new PersonalInformation()
					.dateOfBirth(paymentAddress.getDateOfBirth() == null
							? null
							: new SimpleDateFormat("yyyyMMdd").format(paymentAddress.getDateOfBirth()))
					.name(new PersonalName()
							.firstName(PcpFieldTrimmer.trim(paymentAddress.getFirstname(), 35))
							.surname(PcpFieldTrimmer.trim(paymentAddress.getLastname(), 35))));
		}

		// phoneNumber is mandatory for every PAYONE BNPL payment (same doc as dateOfBirth above).
		final String email = customer.getContactEmail() != null
				? customer.getContactEmail()
				: customer.getUid();
		pcpCustomer.contactDetails(new ContactDetails()
				.emailAddress(PcpFieldTrimmer.trim(email, 70))
				.phoneNumber(paymentAddress == null ? null : paymentAddress.getPhone1()));

		if (billingAddress != null)
		{
			final Address pcpBillingAddress = addressConverter.toAddress(billingAddress);
			pcpCustomer.billingAddress(pcpBillingAddress);
		}

		// SAP Commerce CustomerType only has GUEST/REGISTERED; B2B/B2C differentiation needs
		// b2bUnit or B2BUnitGroup, not available on the basic CustomerModel. Everyone maps to
		// B2C until the B2B integration extends this converter.
		pcpCustomer.businessRelation(BusinessRelation.B2C);

		// Customer.locale only supports the bare language code (api-reference).
		if (customer.getSessionLanguage() != null)
		{
			pcpCustomer.locale(PcpFieldTrimmer.trim(customer.getSessionLanguage().getIsocode(), 2));
		}

		return pcpCustomer;
	}
}
