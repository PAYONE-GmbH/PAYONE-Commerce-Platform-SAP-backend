package com.payone.pcp.webhook.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.exception.PayoneWebhookPayloadException;
import com.payone.pcp.webhook.service.PayoneWebhookPayloadParser;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link PayoneWebhookPayloadParser}, backed by Jackson. Only {@code id} is enforced (it's
 * the dedup key); a missing or unknown {@code type} is left to the processor, not a parse error.
 */
public class DefaultPayoneWebhookPayloadParser implements PayoneWebhookPayloadParser
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneWebhookPayloadParser.class);

	// Private, static, and thread-safe once configured: FAIL_ON_UNKNOWN_PROPERTIES must stay off
	// even if another extension reconfigures the platform's shared ObjectMapper, since PCP adds
	// payload fields over time.
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	@Override
	public PayoneWebhookEventDto parse(final String rawPayload)
	{
		if (StringUtils.isBlank(rawPayload))
		{
			throw new PayoneWebhookPayloadException("Webhook payload is empty");
		}

		final PayoneWebhookEventDto envelope;
		try
		{
			envelope = OBJECT_MAPPER.readValue(rawPayload, PayoneWebhookEventDto.class);
		}
		catch (final JsonProcessingException e)
		{
			// The body itself is never logged — it's still payment data.
			LOG.warn("[PAYONE] Webhook payload is not valid JSON ({} chars): {}", rawPayload.length(),
					e.getOriginalMessage());
			throw new PayoneWebhookPayloadException("Webhook payload is not valid JSON", e);
		}

		if (envelope == null || StringUtils.isBlank(envelope.getId()))
		{
			LOG.warn("[PAYONE] Webhook payload carries no event id; cannot be deduplicated, rejecting");
			throw new PayoneWebhookPayloadException("Webhook payload carries no event id");
		}

		if (StringUtils.isBlank(envelope.getType()))
		{
			// Not fatal — stored and acknowledged, just not dispatched.
			LOG.warn("[PAYONE] Webhook eventId={} carries no event type; it will be stored but not processed",
					envelope.getId());
		}

		return envelope;
	}
}
