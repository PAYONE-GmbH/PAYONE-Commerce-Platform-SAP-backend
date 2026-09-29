package com.payone.pcp.core.service.impl;

import static org.junit.Assert.assertEquals;

import de.hybris.bootstrap.annotations.UnitTest;

import com.payone.commerce.platform.lib.models.StatusValue;

import org.junit.Test;


/**
 * Verifies all 27 mappings (26 PCP StatusValue + null) to a SAP transaction status.
 * Expected values are the mapper's own String constants, not TransactionStatus enum values:
 * OOTB TransactionStatus has only 4 values (ACCEPTED, ERROR, REJECTED, REVIEW), but PCP also
 * needs REQUESTED and WAITING, which the mapper defines itself.
 */
@UnitTest
public class PayonePaymentStatusMapperTest
{
	private static final String REQ = PayonePaymentStatusMapper.REQUESTED;
	private static final String WAI = PayonePaymentStatusMapper.WAITING;
	private static final String ACC = PayonePaymentStatusMapper.ACCEPTED;
	private static final String REJ = PayonePaymentStatusMapper.REJECTED;
	private static final String ERR = PayonePaymentStatusMapper.ERROR;
	private static final String REV = PayonePaymentStatusMapper.REVIEW;

	// IN FLIGHT - REQUESTED (explicit client action pending)

	@Test
	public void shouldMapCreatedToRequested()
	{
		assertEquals(REQ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.CREATED));
	}

	@Test
	public void shouldMapAuthorizationRequestedToRequested()
	{
		assertEquals(REQ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.AUTHORIZATION_REQUESTED));
	}

	@Test
	public void shouldMapCaptureRequestedToRequested()
	{
		assertEquals(REQ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.CAPTURE_REQUESTED));
	}

	@Test
	public void shouldMapCancellationRequestedToRequested()
	{
		assertEquals(REQ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.CANCELLATION_REQUESTED));
	}

	@Test
	public void shouldMapRefundRequestedToRequested()
	{
		assertEquals(REQ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REFUND_REQUESTED));
	}

	@Test
	public void shouldMapPayoutRequestedToRequested()
	{
		assertEquals(REQ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.PAYOUT_REQUESTED));
	}

	// IN FLIGHT - WAITING (asynchronous action, redirect, or completion)

	@Test
	public void shouldMapRedirectedToWaiting()
	{
		assertEquals(WAI, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REDIRECTED));
	}

	@Test
	public void shouldMapPendingPaymentToWaiting()
	{
		assertEquals(WAI, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.PENDING_PAYMENT));
	}

	@Test
	public void shouldMapPendingCompletionToWaiting()
	{
		assertEquals(WAI, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.PENDING_COMPLETION));
	}

	@Test
	public void shouldMapPendingCaptureToWaiting()
	{
		assertEquals(WAI, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.PENDING_CAPTURE));
	}

	// SETTLED - ACCEPTED (terminal confirmed-good)

	@Test
	public void shouldMapCapturedToAccepted()
	{
		assertEquals(ACC, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.CAPTURED));
	}

	@Test
	public void shouldMapRefundedToAccepted()
	{
		assertEquals(ACC, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REFUNDED));
	}

	@Test
	public void shouldMapAccountCreditedToAccepted()
	{
		assertEquals(ACC, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.ACCOUNT_CREDITED));
	}

	@Test
	public void shouldMapAccountDebitedToAccepted()
	{
		assertEquals(ACC, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.ACCOUNT_DEBITED));
	}

	@Test
	public void shouldMapChargebackReversedToAccepted()
	{
		assertEquals(ACC, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.CHARGEBACK_REVERSED));
	}

	// FAILED / TERMINATED - REJECTED

	@Test
	public void shouldMapCancelledToRejected()
	{
		assertEquals(REJ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.CANCELLED));
	}

	@Test
	public void shouldMapRejectedToRejected()
	{
		assertEquals(REJ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REJECTED));
	}

	@Test
	public void shouldMapRejectedCaptureToRejected()
	{
		assertEquals(REJ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REJECTED_CAPTURE));
	}

	@Test
	public void shouldMapReversedToRejected()
	{
		assertEquals(REJ, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REVERSED));
	}

	// OPERATION OUTCOMES - ERROR (sub-action failed, lifecycle unchanged)

	@Test
	public void shouldMapRejectedPauseToError()
	{
		assertEquals(ERR, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REJECTED_PAUSE));
	}

	@Test
	public void shouldMapRejectedUpdateToError()
	{
		assertEquals(ERR, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REJECTED_UPDATE));
	}

	@Test
	public void shouldMapRejectedRefundToError()
	{
		assertEquals(ERR, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REJECTED_REFUND));
	}

	@Test
	public void shouldMapRejectedCreditToError()
	{
		assertEquals(ERR, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.REJECTED_CREDIT));
	}

	// ANOMALOUS - REVIEW (needs human intervention)

	@Test
	public void shouldMapChargebackedToReview()
	{
		assertEquals(REV, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.CHARGEBACKED));
	}

	@Test
	public void shouldMapPausedToReview()
	{
		assertEquals(REV, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.PAUSED));
	}

	@Test
	public void shouldMapUpdatedToReview()
	{
		assertEquals(REV, PayonePaymentStatusMapper.toTransactionStatus(StatusValue.UPDATED));
	}

	// EDGE CASES

	@Test
	public void shouldMapNullToError()
	{
		assertEquals(ERR, PayonePaymentStatusMapper.toTransactionStatus(null));
	}
}
