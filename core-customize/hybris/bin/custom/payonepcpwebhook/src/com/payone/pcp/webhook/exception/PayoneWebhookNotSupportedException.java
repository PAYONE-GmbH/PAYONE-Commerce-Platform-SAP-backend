package com.payone.pcp.webhook.exception;

/**
 * Thrown when a handler recognises the event but has no policy for it, e.g. an unmapped
 * PayonePaymentStatus or a business transition the merchant has not defined.
 */
public class PayoneWebhookNotSupportedException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	public PayoneWebhookNotSupportedException(final String message)
	{
		super(message);
	}
}
