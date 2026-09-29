package com.payone.pcp.webhook.service;

import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

/**
 * Maps an inbound webhook onto the local entities it describes. Which id to
 * resolve by is decided from the event TYPE (per the PCP guide's "branch by
 * type first"), not by scanning which envelope members happen to be populated.
 */
public interface PayoneWebhookTargetResolver
{
	/**
	 * Resolves the local checkout / commerce case for an event.
	 *
	 * @param envelope  the deserialised envelope
	 * @param eventType the recognised event type, which selects the id to use
	 * @return the resolution, possibly {@link PayoneWebhookTarget#NONE}. Never
	 *         throws for an unresolvable event — that's a normal outcome.
	 */
	PayoneWebhookTarget resolve(PayoneWebhookEventDto envelope, PayoneWebhookEventType eventType);
}
