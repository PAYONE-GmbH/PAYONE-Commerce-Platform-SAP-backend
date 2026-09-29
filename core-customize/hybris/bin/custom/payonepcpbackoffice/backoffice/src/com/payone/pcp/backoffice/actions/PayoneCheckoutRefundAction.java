/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.backoffice.actions;

import com.payone.pcp.facades.PayoneCheckoutOperation;


/** Full-amount PCP Refund for the Order linked to the selected PayoneCheckout (demo). */
public class PayoneCheckoutRefundAction extends AbstractPayoneCheckoutPaymentAction
{
	@Override
	protected PayoneCheckoutOperation getOperation()
	{
		return PayoneCheckoutOperation.REFUND;
	}
}
