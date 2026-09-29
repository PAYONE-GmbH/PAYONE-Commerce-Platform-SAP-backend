package com.payone.pcp.core.converters.paymentmethod;

import com.payone.commerce.platform.lib.models.BankAccountInformation;
import com.payone.commerce.platform.lib.models.MandateRecurrenceType;
import com.payone.commerce.platform.lib.models.PaymentProductId;
import com.payone.commerce.platform.lib.models.ProcessingMandateInformation;
import com.payone.commerce.platform.lib.models.SepaDirectDebitPaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.SepaDirectDebitPaymentProduct771SpecificInput;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayoneMandateModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;

import java.util.Objects;


/**
 * Builds SepaDirectDebitPaymentMethodSpecificInput (product 771) from the transient
 * PayoneMandate carried on paymentInfo - never persisted, like paymentProcessingToken
 * on the CARD path.
 */
public class PcpSepaDirectDebitPaymentMethodSpecificInputBuilder
{
	/**
	 * @param paymentInfo carries the transient mandate; must not be null
	 * @param config unused, SEPA needs no config-derived field
	 * @throws NullPointerException if paymentInfo or its mandate is null
	 */
	public SepaDirectDebitPaymentMethodSpecificInput build(
			final PayonePaymentInfoModel paymentInfo,
			final PayoneConfigurationModel config)
	{
		Objects.requireNonNull(paymentInfo, "paymentInfo must not be null");
		final PayoneMandateModel mandate = paymentInfo.getMandate();
		Objects.requireNonNull(mandate, "SEPA Direct Debit requires a mandate on paymentInfo");

		return new SepaDirectDebitPaymentMethodSpecificInput()
				.paymentProductId(new PaymentProductId(771))
				.paymentProduct771SpecificInput(
						new SepaDirectDebitPaymentProduct771SpecificInput()
								.mandate(
										new ProcessingMandateInformation()
												.bankAccountIban(
														new BankAccountInformation()
																.iban(mandate.getIban())
																.accountHolder(mandate.getAccountHolder()))
												.dateOfSignature(mandate.getDateOfSignature())
												.recurrenceType(MandateRecurrenceType.UNIQUE)
												.uniqueMandateReference(mandate.getUniqueMandateReference())
												.creditorId(mandate.getCreditorId())));
	}
}
