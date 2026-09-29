package com.payone.pcp.core.converters.paymentmethod;

import com.payone.commerce.platform.lib.models.BankAccountInformation;
import com.payone.commerce.platform.lib.models.FinancingPaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.PaymentProduct3392SpecificInput;

import com.payone.pcp.core.model.PayoneMandateModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;

import java.util.Objects;


/** Builds FinancingPaymentMethodSpecificInput for PAYONE Secured Invoice / Installment / Direct Debit. */
public class PcpFinancingPaymentMethodSpecificInputBuilder
{
	/** docs.commerce.payone.com/docs/payment-methods/payone-bnpl/payone-secured-direct-debit */
	private static final int PRODUCT_ID_SECURED_DIRECT_DEBIT = 3392;

	/**
	 * @param paymentInfo must not be null
	 * @param returnUrl return URL for the financing redirect flow, must not be null
	 */
	public FinancingPaymentMethodSpecificInput build(
			final PayonePaymentInfoModel paymentInfo,
			final String returnUrl)
	{
		Objects.requireNonNull(paymentInfo, "paymentInfo must not be null");
		Objects.requireNonNull(returnUrl, "returnUrl must not be null");

		final FinancingPaymentMethodSpecificInput input = new FinancingPaymentMethodSpecificInput()
				.requiresApproval(Boolean.FALSE);

		if (paymentInfo.getPaymentProductId() != null)
		{
			input.paymentProductId(paymentInfo.getPaymentProductId());
		}

		// 3392 requires bankAccountInformation; PCP docs say it must NOT be set for Secured Invoice (3390)
		if (paymentInfo.getPaymentProductId() != null
				&& paymentInfo.getPaymentProductId() == PRODUCT_ID_SECURED_DIRECT_DEBIT)
		{
			final PayoneMandateModel mandate = paymentInfo.getMandate();
			Objects.requireNonNull(mandate, "Secured Direct Debit requires a mandate on paymentInfo");

			input.paymentProduct3392SpecificInput(new PaymentProduct3392SpecificInput()
					.bankAccountInformation(new BankAccountInformation()
							.iban(mandate.getIban())
							.bic(mandate.getBic())
							.accountHolder(mandate.getAccountHolder())));
		}

		return input;
	}
}
