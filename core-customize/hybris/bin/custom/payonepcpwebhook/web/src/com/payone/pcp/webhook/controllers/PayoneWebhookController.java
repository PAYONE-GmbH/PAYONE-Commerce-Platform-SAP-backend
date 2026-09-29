package com.payone.pcp.webhook.controllers;

import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.DEFAULT_MAX_BODY_BYTES;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.HEADER_KEY_ID;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.HEADER_SIGNATURE;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.MAX_BODY_BYTES;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.WEBHOOK_PATH;

import com.payone.pcp.webhook.exception.PayoneWebhookPayloadException;
import com.payone.pcp.webhook.service.PayoneWebhookReceiverService;
import com.payone.pcp.webhook.service.data.PayoneWebhookReceipt;

import de.hybris.platform.servicelayer.config.ConfigurationService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound receiver for PAYONE Commerce Platform webhooks: Portal &gt; Configuration &gt;
 * Webhooks, URL {@code https://<host>/payonepcpwebhook/webhooks}. Matches POST /webhooks in
 * the Commerce Webhook API reference (only documented response: 204).
 *
 * <p>A transport adapter only: reads raw bytes and two headers, delegates to
 * {@link PayoneWebhookReceiverService}, answers 204. All decisions live in that service, so
 * the trust boundary is testable without a servlet container.
 */
@RestController
@RequestMapping(value = WEBHOOK_PATH)
public class PayoneWebhookController
{
	private static final Logger LOG = LoggerFactory.getLogger(PayoneWebhookController.class);

	@Resource(name = "payoneWebhookReceiverService")
	private PayoneWebhookReceiverService payoneWebhookReceiverService;

	@Resource(name = "configurationService")
	private ConfigurationService configurationService;

	/**
	 * Takes delivery of one webhook. Body is read as raw bytes, not an {@code @RequestBody}
	 * DTO, because the signature covers the exact raw JSON; bind-then-reserialize would
	 * produce a different byte sequence and fail verification.
	 *
	 * @param keyId     {@code X-GCS-KeyId}; optional so a missing value is rejected as
	 *                  unauthenticated (401) by the verifier rather than 400 by Spring
	 * @param signature {@code X-GCS-Signature}; optional for the same reason
	 * @param request   the raw request, read once for its exact body bytes
	 * @throws IOException if the body cannot be read off the wire (500, retried)
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void receive(@RequestHeader(name = HEADER_KEY_ID, required = false) final String keyId,
			@RequestHeader(name = HEADER_SIGNATURE, required = false) final String signature,
			final HttpServletRequest request) throws IOException
	{
		final byte[] rawBody = readBody(request);

		final PayoneWebhookReceipt receipt = payoneWebhookReceiverService.receive(rawBody, keyId, signature);

		LOG.info("[PAYONE] Webhook eventId={} outcome={}", receipt.getEventId(), receipt.getOutcome());
	}

	/**
	 * Reads the body, refusing anything over the configured ceiling. Enforced on bytes
	 * actually read, not Content-Length (absent under chunked encoding, and can be wrong).
	 * Reads one byte past the limit to tell "exactly at the limit" from "truncated".
	 */
	private byte[] readBody(final HttpServletRequest request) throws IOException
	{
		final int maxBytes = configurationService.getConfiguration().getInt(MAX_BODY_BYTES, DEFAULT_MAX_BODY_BYTES);

		try (InputStream in = request.getInputStream())
		{
			final byte[] body = in.readNBytes(maxBytes + 1);
			if (body.length > maxBytes)
			{
				// 400 rather than 413: keeps this trust boundary to two exception cases.
				LOG.warn("[PAYONE] Webhook rejected: body exceeds the {} byte limit. Raise {} only if PAYONE has "
						+ "genuinely started sending larger envelopes.", maxBytes, MAX_BODY_BYTES);
				throw new PayoneWebhookPayloadException("Webhook payload exceeds the configured size limit");
			}
			return body;
		}
	}
}
