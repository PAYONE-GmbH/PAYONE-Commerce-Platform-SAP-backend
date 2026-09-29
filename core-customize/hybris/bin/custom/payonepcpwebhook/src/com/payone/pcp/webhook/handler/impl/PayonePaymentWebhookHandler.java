package com.payone.pcp.webhook.handler.impl;

import com.payone.pcp.webhook.enums.PayoneWebhookDomain;

/** Applies {@code payment.*} events to the SAP payment transaction and order payment status. */
public class PayonePaymentWebhookHandler extends AbstractPayoneTransactionWebhookHandler
{
	@Override
	protected PayoneWebhookDomain getDomain()
	{
		return PayoneWebhookDomain.PAYMENT;
	}
}
