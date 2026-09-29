package com.payone.pcp.facades;

/**
 * Thrown by PayoneCheckoutFacade when the current store has no active PAYONE configuration.
 * Maps to HTTP 409 at the OCC layer.
 */
public class PayoneConfigurationNotFoundException extends IllegalStateException
{
	public PayoneConfigurationNotFoundException(final String message)
	{
		super(message);
	}
}
