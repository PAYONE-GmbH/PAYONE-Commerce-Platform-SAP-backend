package com.payone.pcp.webhook.exception;

/**
 * Signature verification failure on an inbound PCP webhook: missing/malformed/non-matching
 * X-GCS-Signature, an unusable X-GCS-KeyId, or no secret configured for that key id.
 * Surfaced as HTTP 401 so PAYONE's retry schedule (10 min, 1 h, 2 h, 8 h, 24 h) gives an
 * operator time to fix a misconfigured secret without losing the event. The message is
 * coarse and never carries the secret, digest or body; it is both logged and returned.
 */
public class PayoneWebhookSignatureException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	public PayoneWebhookSignatureException(final String message)
	{
		super(message);
	}

	public PayoneWebhookSignatureException(final String message, final Throwable cause)
	{
		super(message, cause);
	}
}
