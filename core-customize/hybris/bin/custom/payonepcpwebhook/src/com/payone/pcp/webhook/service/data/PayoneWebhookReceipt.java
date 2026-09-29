package com.payone.pcp.webhook.service.data;

/**
 * Outcome of taking delivery of one webhook. All three outcomes are answered 204: a verified,
 * durably-stored event counts as accepted whether or not it could be acted on yet. The distinction
 * is for logs/operators, not the caller.
 */
public class PayoneWebhookReceipt
{
	/** Terminal states of a successfully received delivery. */
	public enum Outcome
	{
		/** Verified, stored and processed to completion. */
		PROCESSED,

		/** A replay of an event already processed. Acknowledged, nothing re-run. */
		REPLAY_IGNORED,

		/**
		 * Verified and stored, not processed: an unrecognised event type or an unsupported
		 * apiVersion. Replayable from the stored payload once support lands.
		 */
		STORED_UNPROCESSED
	}

	/** The PCP event id, for correlation with the stored record. */
	private final String eventId;

	/** What happened. */
	private final Outcome outcome;

	public PayoneWebhookReceipt(final String eventId, final Outcome outcome)
	{
		this.eventId = eventId;
		this.outcome = outcome;
	}

	public String getEventId()
	{
		return eventId;
	}

	public Outcome getOutcome()
	{
		return outcome;
	}
}
