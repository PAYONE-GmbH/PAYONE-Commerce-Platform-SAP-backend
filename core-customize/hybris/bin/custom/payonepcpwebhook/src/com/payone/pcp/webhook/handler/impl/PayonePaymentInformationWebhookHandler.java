package com.payone.pcp.webhook.handler.impl;

import com.payone.pcp.webhook.enums.PayoneWebhookDomain;

/** Applies {@code payment_information.*} events to a locally resolved SAP transaction and order. */
public class PayonePaymentInformationWebhookHandler extends AbstractPayoneTransactionWebhookHandler
{
	@Override
	protected PayoneWebhookDomain getDomain()
	{
		return PayoneWebhookDomain.PAYMENT_INFORMATION;
	}
}
