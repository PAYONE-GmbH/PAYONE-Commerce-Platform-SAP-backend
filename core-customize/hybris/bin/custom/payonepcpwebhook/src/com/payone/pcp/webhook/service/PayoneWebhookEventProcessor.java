package com.payone.pcp.webhook.service;

import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;

/**
 * Applies a stored, verified event: resolves what it is about and dispatches it
 * to the {@link com.payone.pcp.webhook.handler.PayoneWebhookEventHandler}s.
 */
public interface PayoneWebhookEventProcessor
{
	/**
	 * Processes one event.
	 *
	 * @param envelope the deserialised envelope
	 * @param event    the persisted event record; may be updated (e.g. linked to
	 *                 its commerce case)
	 * @return {@code true} if the event was carried to completion and may be
	 *         marked processed; {@code false} if this build cannot act on it (an
	 *         unrecognised event type or unsupported {@code apiVersion}) — not an
	 *         error, the event stays stored as an unprocessed, replayable backlog
	 *         item, and the caller still acknowledges with 2XX
	 * @throws RuntimeException if a handler failed; the caller must leave the
	 *                          event unprocessed so PAYONE's retry schedule
	 *                          brings it back
	 */
	boolean process(PayoneWebhookEventDto envelope, PayoneWebhookEventModel event);
}
