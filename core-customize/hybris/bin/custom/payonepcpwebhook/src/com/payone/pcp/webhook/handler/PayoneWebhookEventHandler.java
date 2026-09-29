package com.payone.pcp.webhook.handler;

import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

/**
 * Acts on one aspect of an inbound PCP webhook event. One handler is registered per
 * PayoneWebhookDomain.
 * <p>
 * Called only for a signature-verified, deduplicated, persisted event, and may be called
 * more than once for the same event id (a replayed retry), so implementations must be
 * idempotent. Throwing aborts processing so PAYONE redelivers the event; throw only for a
 * transient fault. For a permanent gap (unmapped status, undefined merchant policy), throw
 * {@code PayoneWebhookNotSupportedException} instead: the processor stores and
 * acknowledges the event without asking PAYONE to redeliver a payload that can never
 * succeed.
 */
public interface PayoneWebhookEventHandler
{
	/**
	 * @param eventType the recognised event type
	 * @return whether this handler wants to see the event. Called before
	 *         {@link #handle}; a handler that declines is not invoked.
	 */
	boolean supports(PayoneWebhookEventType eventType);

	/**
	 * Applies the event.
	 *
	 * @param envelope  the deserialised envelope — the authoritative snapshot, so
	 *                  no follow-up GET against the PCP API is needed
	 * @param eventType the recognised event type
	 * @param target    the local entities the event resolved to; may be
	 *                  {@link PayoneWebhookTarget#NONE}, which handlers must
	 *                  tolerate
	 * @param event     the persisted event record
	 */
	void handle(PayoneWebhookEventDto envelope, PayoneWebhookEventType eventType, PayoneWebhookTarget target,
			PayoneWebhookEventModel event);
}
