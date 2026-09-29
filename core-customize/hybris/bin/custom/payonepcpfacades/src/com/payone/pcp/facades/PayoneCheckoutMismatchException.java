package com.payone.pcp.facades;

/**
 * Thrown by PayoneCheckoutFacade.executePaymentAndPlaceOrder() when the request's
 * commerceCaseId/checkoutId do not match the active cart's. Maps to HTTP 409 at the OCC layer.
 */
public class PayoneCheckoutMismatchException extends IllegalStateException
{
	public PayoneCheckoutMismatchException(final String message)
	{
		super(message);
	}
}
