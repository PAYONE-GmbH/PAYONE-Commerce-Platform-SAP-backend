/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.facades;

import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.facades.data.PaymentExecutionResultData;


/**
 * Capture/Refund for a single PayoneCheckout, as triggered from Backoffice.
 * Every call re-validates the Checkout against its Order and against PCP's
 * live Checkout state before anything is sent.
 */
public interface PayoneCheckoutOperationsFacade
{
	/**
	 * Local-only availability check for UI enablement (no PCP call).
	 */
	boolean isAvailable(PayoneCheckoutModel checkout, PayoneCheckoutOperation operation);

	/**
	 * Executes the operation for the amount PCP reports as still open
	 * (Capture) or still refundable (Refund).
	 *
	 * @throws PayoneCheckoutOperationException REJECTED if refused before or by PCP,
	 *            INDETERMINATE if the PCP outcome is unknown
	 */
	PaymentExecutionResultData execute(PayoneCheckoutModel checkout, PayoneCheckoutOperation operation);
}
