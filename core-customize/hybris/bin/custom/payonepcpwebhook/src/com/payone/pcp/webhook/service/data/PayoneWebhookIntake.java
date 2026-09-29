package com.payone.pcp.webhook.service.data;

import com.payone.pcp.webhook.model.PayoneWebhookEventModel;

/**
 * The persisted event plus what taking delivery of it established, before any business logic runs.
 * Three outcomes rather than a boolean "isDuplicate": a redelivery of the same eventId can mean
 * ALREADY_PROCESSED (the earlier 2XX was lost, or PAYONE is replaying) or RETRY (stored but never
 * completed). Collapsing those would turn a transient processing failure into silent data loss.
 */
public class PayoneWebhookIntake
{
	/** What taking delivery established about an event. */
	public enum Disposition
	{
		/** First delivery of this event id. Process it. */
		NEW,

		/** Seen before but never processed to completion. Process it again. */
		RETRY,

		/** Seen before and completed. Acknowledge, do nothing. */
		ALREADY_PROCESSED
	}

	/** The stored event record — always present, even when it will not be processed. */
	private final PayoneWebhookEventModel event;

	/** What should happen next. */
	private final Disposition disposition;

	public PayoneWebhookIntake(final PayoneWebhookEventModel event, final Disposition disposition)
	{
		this.event = event;
		this.disposition = disposition;
	}

	public PayoneWebhookEventModel getEvent()
	{
		return event;
	}

	public Disposition getDisposition()
	{
		return disposition;
	}

	/**
	 * @return whether the caller should run the processing pipeline. False only
	 *         for a genuine replay of an already-completed event.
	 */
	public boolean shouldProcess()
	{
		return disposition != Disposition.ALREADY_PROCESSED;
	}
}
