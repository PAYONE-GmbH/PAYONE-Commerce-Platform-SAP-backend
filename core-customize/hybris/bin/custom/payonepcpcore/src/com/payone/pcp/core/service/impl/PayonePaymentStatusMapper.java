package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.models.StatusValue;


/**
 * OOTB {@code de.hybris.platform.payment.dto.TransactionStatus} has only four values
 * (ACCEPTED, ERROR, REJECTED, REVIEW - verified via javap on models.jar; no REQUESTED or
 * WAITING). This mapper returns plain String constants instead, extending that vocabulary
 * with REQUESTED/WAITING for in-flight PCP states, since transactionStatus on
 * PaymentTransactionEntryModel is stored as a string, not the enum. Amount-aware rows
 * (CAPTURED, REFUNDED) always map to ACCEPTED here; Order.paymentStatus is computed
 * separately in DefaultPayoneTransactionService.
 */
public final class PayonePaymentStatusMapper
{
	/** Stored in TransactionStatusDetails when a PCP status is unknown or unmapped. */
	public static final String UNKNOWN_CODE = "UNKNOWN_CODE";

	/** Sent - awaiting completion. Not in OOTB TransactionStatus. */
	public static final String REQUESTED = "REQUESTED";

	/** Waiting for user action or async callback. */
	public static final String WAITING = "WAITING";

	/** Payment accepted / settled. */
	public static final String ACCEPTED = "ACCEPTED";

	/** Payment rejected / failed / declined. */
	public static final String REJECTED = "REJECTED";

	/** Non-terminal error. */
	public static final String ERROR = "ERROR";

	/** Requires manual review (e.g. chargeback, fraud hold). */
	public static final String REVIEW = "REVIEW";

	private PayonePaymentStatusMapper()
	{
		// static utility
	}

	/**
	 * @param status the PCP status; may be null
	 * @return the mapped status string; never null - defaults to {@link #ERROR}
	 *         for null or unrecognised values
	 */
	public static String toTransactionStatus(final StatusValue status)
	{
		if (status == null)
		{
			return ERROR;
		}

		switch (status)
		{
			// --- IN FLIGHT: REQUESTED (explicit client action pending) ---
			case CREATED:
			case AUTHORIZATION_REQUESTED:
			case CAPTURE_REQUESTED:
			case CANCELLATION_REQUESTED:
			case REFUND_REQUESTED:
			case PAYOUT_REQUESTED:
				return REQUESTED;

			// --- IN FLIGHT: WAITING (asynchronous action, redirect, or completion) ---
			case REDIRECTED:
			case PENDING_PAYMENT:
			case PENDING_COMPLETION:
			case PENDING_CAPTURE:
				return WAITING;

			// --- SETTLED: terminal confirmed-good ---
			case CAPTURED:
			case REFUNDED:
			case ACCOUNT_CREDITED:
			case ACCOUNT_DEBITED:
			case CHARGEBACK_REVERSED:
				return ACCEPTED;

			// --- FAILED / TERMINATED ---
			case CANCELLED:
			case REJECTED:
			case REJECTED_CAPTURE:
			case REVERSED:
				return REJECTED;

			// --- OPERATION OUTCOMES: ERROR - sub-action that failed, lifecycle unchanged ---
			case REJECTED_PAUSE:
			case REJECTED_UPDATE:
			case REJECTED_REFUND:
			case REJECTED_CREDIT:
				return ERROR;

			// --- ANOMALOUS: requires human intervention ---
			case CHARGEBACKED:
				return REVIEW;

			// --- PAUSED: operation paused, not failed ---
			case PAUSED:
				return REVIEW;

			// --- UPDATED: informational only, no entry should be written at the service level ---
			case UPDATED:
				return REVIEW;

			default:
				return ERROR;
		}
	}
}
