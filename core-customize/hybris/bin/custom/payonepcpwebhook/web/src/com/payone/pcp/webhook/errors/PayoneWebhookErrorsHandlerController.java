package com.payone.pcp.webhook.errors;

import com.payone.pcp.webhook.exception.PayoneWebhookPayloadException;
import com.payone.pcp.webhook.exception.PayoneWebhookSignatureException;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Maps receiver failures onto the status codes PAYONE's retry logic reads. Anything not 2XX
 * is retried (10 min, 1 h, 2 h, 8 h, 24 h): 401 is usually our own misconfigured secret and
 * gives an operator ~35 hours to fix it; 400 means the verified body is unusable, so retries
 * are wasted but a 2XX would silently accept garbage; 500 covers everything else. An unknown
 * event type or unsupported apiVersion is not handled here since the controller answers 204
 * for those (see {@code PayoneWebhookReceipt}) - the event was still accepted and stored.
 *
 * <p>Response bodies are a Map, not a bare String, because springmvc-servlet.xml only
 * registers json/xml converters and a String would depend on Jackson to quote it. Messages
 * are coarse by design and never carry the expected signature, the secret, or the payload.
 *
 * <p>Scoped to the webhook receiver by {@code basePackages} so it cannot alter error handling
 * elsewhere in the webapp.
 */
@ControllerAdvice(basePackages = { "com.payone.pcp.webhook.controllers" })
public class PayoneWebhookErrorsHandlerController
{
	private static final Logger LOG = LoggerFactory.getLogger(PayoneWebhookErrorsHandlerController.class);

	private static final String ERROR_KEY = "error";

	/** The verifier already logged the specific reason. */
	@ExceptionHandler(PayoneWebhookSignatureException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	@ResponseBody
	public Map<String, String> handleSignatureFailure(final PayoneWebhookSignatureException ex)
	{
		LOG.warn("[PAYONE] Webhook rejected with {}: {}", HttpStatus.UNAUTHORIZED.value(), ex.getMessage());
		return Map.of(ERROR_KEY, ex.getMessage());
	}

	@ExceptionHandler(PayoneWebhookPayloadException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ResponseBody
	public Map<String, String> handlePayloadFailure(final PayoneWebhookPayloadException ex)
	{
		LOG.warn("[PAYONE] Webhook rejected with {}: {}", HttpStatus.BAD_REQUEST.value(), ex.getMessage());
		return Map.of(ERROR_KEY, ex.getMessage());
	}

	/** Logged with the stack trace; the response says nothing beyond "internal error" since exception text can leak class names, SQL, and configuration and this endpoint is public. */
	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	@ResponseBody
	public Map<String, String> handleUnexpected(final Exception ex)
	{
		LOG.error("[PAYONE] Webhook processing failed unexpectedly; PAYONE will redeliver", ex);
		return Map.of(ERROR_KEY, "Internal error while processing the webhook");
	}
}
