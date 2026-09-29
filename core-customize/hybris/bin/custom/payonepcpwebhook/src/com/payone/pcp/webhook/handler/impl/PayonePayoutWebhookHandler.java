package com.payone.pcp.webhook.handler.impl;

import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookDomain;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.exception.PayoneWebhookNotSupportedException;
import com.payone.pcp.webhook.handler.PayoneWebhookEventHandler;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

/**
 * Placeholder for merchant-specific {@code payout.*} handling.
 */
public class PayonePayoutWebhookHandler implements PayoneWebhookEventHandler
{
	@Override
	public boolean supports(final PayoneWebhookEventType eventType)
	{
		return eventType != null && eventType.getDomain() == PayoneWebhookDomain.PAYOUT;
	}

	@Override
	public void handle(final PayoneWebhookEventDto envelope, final PayoneWebhookEventType eventType,
			final PayoneWebhookTarget target, final PayoneWebhookEventModel event)
	{
		throw new PayoneWebhookNotSupportedException("Webhook domain " + PayoneWebhookDomain.PAYOUT
				+ " is not implemented for event " + eventType.getCode()
				+ "; the merchant must define the desired business-state transition");
	}
}
