package com.payone.pcp.webhook.exception;

/**
 * Thrown when a signature-verified webhook body can't be used: not JSON, or missing the
 * `id` dedup key. Surfaced as HTTP 400. An unknown event type or apiVersion is not a
 * payload error; those are stored and acknowledged instead of rejected.
 */
public class PayoneWebhookPayloadException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	public PayoneWebhookPayloadException(final String message)
	{
		super(message);
	}

	public PayoneWebhookPayloadException(final String message, final Throwable cause)
	{
		super(message, cause);
	}
}
