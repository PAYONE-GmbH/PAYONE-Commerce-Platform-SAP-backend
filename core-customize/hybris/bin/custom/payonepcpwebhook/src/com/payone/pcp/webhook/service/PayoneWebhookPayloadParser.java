package com.payone.pcp.webhook.service;

import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.exception.PayoneWebhookPayloadException;

/**
 * Turns a verified raw body into the typed envelope. Runs only after signature
 * verification, per the PCP guide's "verify first, then parse" rule.
 */
public interface PayoneWebhookPayloadParser
{
	/**
	 * Deserialises and minimally validates an envelope.
	 *
	 * @param rawPayload the verified request body
	 * @return the envelope
	 * @throws PayoneWebhookPayloadException if the body is not JSON, or carries
	 *                                       no {@code id} (the dedup key)
	 */
	PayoneWebhookEventDto parse(String rawPayload);
}
