package com.payone.pcp.webhook.service;

import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.data.PayoneWebhookIntake;

/**
 * The durable record of inbound webhooks: dedup on {@code eventId}, storage of
 * the exact verified payload, and the processed / attempts bookkeeping that makes
 * a redelivery distinguishable from a replay.
 */
public interface PayoneWebhookEventService
{
	/**
	 * Takes delivery of a verified event: stores it if new, and reports whether
	 * it should be processed. Must only be called after
	 * {@link PayoneWebhookSignatureService#verify} has passed.
	 *
	 * @param envelope   the deserialised envelope; its {@code id} is the dedup key
	 * @param rawPayload the exact body the signature was verified over, stored
	 *                   verbatim on {@code PayoneWebhookEvent.payload}
	 * @return the stored event and what to do with it
	 */
	PayoneWebhookIntake intake(PayoneWebhookEventDto envelope, String rawPayload);

	/**
	 * Marks an event as successfully processed, stamps {@code processedTime} and
	 * increments {@code attempts}.
	 */
	void markProcessed(PayoneWebhookEventModel event);

	/**
	 * Counts a failed processing attempt, leaving {@code processed} false so the
	 * next redelivery is treated as a RETRY. Must not rethrow, since this runs on
	 * the failure path.
	 *
	 * @param event the event whose processing failed
	 * @param cause what went wrong, for the log
	 */
	void markFailed(PayoneWebhookEventModel event, Exception cause);
}
