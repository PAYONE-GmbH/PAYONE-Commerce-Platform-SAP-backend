package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.models.StatusValue;

import com.payone.pcp.core.service.PayoneTransactionIdentityConflictException;
import com.payone.pcp.core.service.PayoneTransactionService;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.enums.PaymentStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;



/**
 * createPaymentTransactionEntry is idempotent on (requestId, transactionStatusDetails) so a
 * replayed webhook never produces a second entry. Order.paymentStatus is recomputed
 * amount-aware for CAPTURED/ACCOUNT_DEBITED and REFUNDED/ACCOUNT_CREDITED, and left alone for
 * operation-outcome entries (sub-action results that don't affect the payment lifecycle).
 */
public class DefaultPayoneTransactionService implements PayoneTransactionService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneTransactionService.class);

	/** Suffix appended to the order code to form the PaymentTransaction code. */
	private static final String TX_CODE_SUFFIX = "_PAYONE";

	/** Status detail values for amount-aware capture/debit aggregate. */
	private static final String STATUS_CAPTURED = "CAPTURED";
	private static final String STATUS_ACCOUNT_DEBITED = "ACCOUNT_DEBITED";

	/** Status detail values for amount-aware refund/credit aggregate. */
	private static final String STATUS_REFUNDED = "REFUNDED";
	private static final String STATUS_ACCOUNT_CREDITED = "ACCOUNT_CREDITED";

	private ModelService modelService;


	public PaymentTransactionModel getOrCreatePaymentTransaction(
			final AbstractOrderModel order,
			final String payoneCommerceCaseId,
			final String payoneCheckoutId)
	{
		if (order == null)
		{
			throw new IllegalArgumentException("order must not be null");
		}

		final String txCode = order.getCode() + TX_CODE_SUFFIX;

		// 1) Preserve cart-code lookup behavior: match by derived code first (Cart
		// authorization/retry path, and Order rows whose code happens to already be
		// <orderCode>_PAYONE). Never rewrite an existing transaction's code.
		for (final PaymentTransactionModel tx : order.getPaymentTransactions())
		{
			if (txCode.equals(tx.getCode()))
			{
				// Fail closed if the requested identity conflicts with what this
				// code-matched row already carries - never silently merge/backfill
				// onto a row that plausibly belongs to a different PCP checkout.
				if (conflicts(payoneCommerceCaseId, tx.getPayoneCommerceCaseId())
						|| conflicts(payoneCheckoutId, tx.getPayoneCheckoutId()))
				{
					throw new PayoneTransactionIdentityConflictException(
							"PaymentTransaction [" + tx.getCode() + "] for order [" + order.getCode()
									+ "] already carries a conflicting PCP identity (existing "
									+ "payoneCommerceCaseId [" + tx.getPayoneCommerceCaseId()
									+ "], payoneCheckoutId [" + tx.getPayoneCheckoutId()
									+ "]) vs requested payoneCommerceCaseId [" + payoneCommerceCaseId
									+ "], payoneCheckoutId [" + payoneCheckoutId + "]");
				}

				// Update PCP identifiers in case this is a retry with more context
				if (payoneCommerceCaseId != null && tx.getPayoneCommerceCaseId() == null)
				{
					tx.setPayoneCommerceCaseId(payoneCommerceCaseId);
				}
				if (payoneCheckoutId != null && tx.getPayoneCheckoutId() == null)
				{
					tx.setPayoneCheckoutId(payoneCheckoutId);
				}
				if (payoneCommerceCaseId != null || payoneCheckoutId != null)
				{
					modelService.save(tx);
				}
				return tx;
			}
		}

		// 2) No code match (e.g. placeOrder cloned the cart's transaction, whose code
		// remains cart-derived, and a webhook now resolves by order + PCP ids). Fall
		// back to identity-based matching on the same order so the webhook reuses
		// that row instead of creating a duplicate incomplete transaction.
		//
		// Reuse requires a *full* identity match: both requested ids must be
		// non-blank AND equal the candidate's stored values on BOTH dimensions.
		// An incomplete candidate (only one id populated) can never be reused via
		// a single matching dimension - that would risk merging two distinct PCP
		// checkouts that merely share one id (or share none, coincidentally).
		//
		// A candidate that *partially* overlaps the requested identity - shares a
		// non-blank id on at least one dimension without being a full match, or
		// conflicts outright on a dimension - is never safely skippable: it might
		// be the correct row wearing an incomplete/racing identity, so we fail
		// closed rather than risk creating a duplicate transaction. Only a
		// candidate with NO non-blank dimension in common with the request is
		// treated as genuinely unrelated and safely ignored.
		PaymentTransactionModel identityMatch = null;
		for (final PaymentTransactionModel tx : order.getPaymentTransactions())
		{
			if (isBlank(payoneCommerceCaseId) && isBlank(payoneCheckoutId))
			{
				// Nothing to match on beyond the code check already performed above.
				break;
			}

			final boolean fullMatch = fullyMatches(payoneCommerceCaseId, payoneCheckoutId, tx);
			final boolean overlaps = overlaps(payoneCommerceCaseId, payoneCheckoutId, tx);

			if (fullMatch)
			{
				if (identityMatch != null)
				{
					// Ambiguous: more than one candidate fully matches. Fail closed
					// rather than guess.
					throw new PayoneTransactionIdentityConflictException(
							"Ambiguous PaymentTransaction match for order [" + order.getCode()
									+ "] with payoneCommerceCaseId [" + payoneCommerceCaseId
									+ "], payoneCheckoutId [" + payoneCheckoutId
									+ "]: more than one existing transaction fully matches");
				}
				identityMatch = tx;
			}
			else if (overlaps)
			{
				// Partial identity overlap without a full match: this candidate
				// plausibly belongs to the requested identity (shares a non-blank
				// id) but is either incomplete or conflicting on another
				// dimension. Never merge onto it and never silently create a
				// duplicate row alongside it - fail closed instead.
				throw new PayoneTransactionIdentityConflictException(
						"PaymentTransaction [" + tx.getCode() + "] for order [" + order.getCode()
								+ "] has a partially overlapping PCP identity (existing "
								+ "payoneCommerceCaseId [" + tx.getPayoneCommerceCaseId()
								+ "], payoneCheckoutId [" + tx.getPayoneCheckoutId()
								+ "]) vs requested payoneCommerceCaseId [" + payoneCommerceCaseId
								+ "], payoneCheckoutId [" + payoneCheckoutId
								+ "]: cannot safely reuse or ignore, refusing to create a duplicate");
			}
			// else: no overlap at all on either dimension - genuinely unrelated
			// transaction, safely ignored.
		}

		if (identityMatch != null)
		{
			// Reuse the matched row as-is; never rewrite its code (platform
			// transUniqueIdx uniqueness on code was previously violated this way).
			if (payoneCommerceCaseId != null && identityMatch.getPayoneCommerceCaseId() == null)
			{
				identityMatch.setPayoneCommerceCaseId(payoneCommerceCaseId);
			}
			if (payoneCheckoutId != null && identityMatch.getPayoneCheckoutId() == null)
			{
				identityMatch.setPayoneCheckoutId(payoneCheckoutId);
			}
			if (payoneCommerceCaseId != null || payoneCheckoutId != null)
			{
				modelService.save(identityMatch);
			}
			LOG.debug("Reused PaymentTransaction [{}] for order [{}] via PCP identity match "
					+ "(payoneCommerceCaseId [{}], payoneCheckoutId [{}])",
					identityMatch.getCode(), order.getCode(), payoneCommerceCaseId, payoneCheckoutId);
			return identityMatch;
		}

		// Create new
		final PaymentTransactionModel tx = modelService.create(PaymentTransactionModel.class);
		tx.setCode(txCode);
		tx.setOrder(order);
		tx.setRequestId(order.getCode());
		tx.setRequestToken(UUID.randomUUID().toString());
		tx.setPayoneCommerceCaseId(payoneCommerceCaseId);
		tx.setPayoneCheckoutId(payoneCheckoutId);
		// Note: payonePaymentExecutionId and payonePaymentId are set per-entry

		modelService.save(tx);
		LOG.debug("Created PaymentTransaction [{}] for order [{}]", txCode, order.getCode());

		return tx;
	}

	/**
	 * True if both sides carry a non-blank value and they differ. A blank/null
	 * value on either side is "unknown", not a conflict (POC assumption: an
	 * incomplete identity on the candidate does not by itself disqualify it,
	 * but a genuinely different non-blank value always does).
	 */
	private static boolean conflicts(final String requested, final String candidate)
	{
		return !isBlank(requested) && !isBlank(candidate) && !requested.equals(candidate);
	}

	private static boolean isBlank(final String value)
	{
		return value == null || value.isBlank();
	}

	/**
	 * True only if BOTH requested ids are non-blank and BOTH equal the
	 * candidate's stored values. An incomplete candidate (one id blank) can
	 * never fully match, even if the other dimension is identical.
	 */
	private static boolean fullyMatches(final String payoneCommerceCaseId, final String payoneCheckoutId,
			final PaymentTransactionModel tx)
	{
		return !isBlank(payoneCommerceCaseId) && !isBlank(payoneCheckoutId)
				&& payoneCommerceCaseId.equals(tx.getPayoneCommerceCaseId())
				&& payoneCheckoutId.equals(tx.getPayoneCheckoutId());
	}

	/**
	 * True if the candidate shares a non-blank value with the request on at
	 * least one dimension - whether that shared value matches or conflicts.
	 * Used to distinguish a candidate that plausibly belongs to the requested
	 * PCP identity (must never be silently skipped) from one that is fully
	 * unrelated (no non-blank dimension in common, safe to ignore).
	 */
	private static boolean overlaps(final String payoneCommerceCaseId, final String payoneCheckoutId,
			final PaymentTransactionModel tx)
	{
		final boolean caseOverlap = !isBlank(payoneCommerceCaseId) && !isBlank(tx.getPayoneCommerceCaseId());
		final boolean checkoutOverlap = !isBlank(payoneCheckoutId) && !isBlank(tx.getPayoneCheckoutId());
		return caseOverlap || checkoutOverlap;
	}


	public PaymentTransactionEntryModel createPaymentTransactionEntry(
			final PaymentTransactionModel transaction,
			final String requestId,
			final AbstractOrderModel order,
			final StatusValue pcpStatus,
			final Long amount,
			final CurrencyModel currency,
			final PaymentTransactionType transactionType)
	{
		if (transaction == null)
		{
			throw new IllegalArgumentException("transaction must not be null");
		}
		if (requestId == null)
		{
			throw new IllegalArgumentException("requestId must not be null");
		}
		if (order == null)
		{
			throw new IllegalArgumentException("order must not be null");
		}
		if (transactionType == null)
		{
			throw new IllegalArgumentException("transactionType must not be null");
		}

		// UPDATED is informational only - no entry is written
		if (pcpStatus == StatusValue.UPDATED)
		{
			LOG.debug("Skipping entry creation for UPDATED status (informational only), requestId [{}]", requestId);
			return null;
		}

		final String statusDetails = pcpStatus != null ? pcpStatus.getValue() : null;

		// Idempotency check: skip if same statusDetails + requestId already exists
		for (final PaymentTransactionEntryModel existing : transaction.getEntries())
		{
			if (requestId.equals(existing.getRequestId())
					&& (statusDetails == null
							? existing.getTransactionStatusDetails() == null
							: statusDetails.equals(existing.getTransactionStatusDetails())))
			{
				LOG.debug("Skipping duplicate entry for requestId [{}], status [{}]",
						requestId, statusDetails);
				return existing;
			}
		}

		final String txStatus = PayonePaymentStatusMapper.toTransactionStatus(pcpStatus);

		final PaymentTransactionEntryModel entry = modelService.create(PaymentTransactionEntryModel.class);
		entry.setCode(UUID.randomUUID().toString());
		entry.setType(transactionType);
		entry.setPaymentTransaction(transaction);
		entry.setRequestId(requestId);
		entry.setTransactionStatus(txStatus);
		entry.setTransactionStatusDetails(statusDetails != null ? statusDetails : PayonePaymentStatusMapper.UNKNOWN_CODE);
		entry.setTime(new Date());

		if (amount != null)
		{
			entry.setAmount(BigDecimal.valueOf(amount).movePointLeft(2));  // minor units -> SAP Decimal
		}
		if (currency != null)
		{
			entry.setCurrency(currency);
		}

		// Do NOT infer payonePaymentId/payonePaymentExecutionId from
		// the generic entry requestId - requestId is a per-entry PCP operation id
		// (payment/capture/cancel/refund id depending on transactionType), not
		// necessarily the Checkout-level PaymentExecution/Payment id. Those two
		// identity fields are recorded exclusively from explicit PCP identity
		// fields elsewhere (authorization response in CardPaymentStrategy, or
		// webhook payload/Checkout in AbstractPayoneTransactionWebhookHandler#recordPcpIds).

		modelService.save(entry);

		LOG.debug("Created PaymentTransactionEntry [{}] for transaction [{}], type [{}], status [{}]",
				entry.getCode(), transaction.getCode(), transactionType, txStatus);

		// Update order-level payment status (only for payment-lifecycle-affecting entries)
		setOrderPaymentStatus(order, pcpStatus);

		return entry;
	}


	public void setOrderPaymentStatus(final AbstractOrderModel order)
	{
		setOrderPaymentStatus(order, null); // TODO arch review: trigger is always null here, is this correct? we cannot reach PARTPAID with this method as intended by test cases.
	}

	/**
	 * Determines whether the given PCP status is a "sub-action outcome" that does
	 * not change the payment lifecycle.
	 */
	private static boolean isOperationOutcome(final StatusValue status)
	{
		if (status == null)
		{
			return false;
		}
		switch (status)
		{
			case REJECTED_PAUSE:
			case REJECTED_UPDATE:
			case REJECTED_REFUND:
			case REJECTED_CREDIT:
			case CANCELLATION_REQUESTED:
			case REFUND_REQUESTED:
			case PAYOUT_REQUESTED:
			case PAUSED:
				return true;
			default:
				return false;
		}
	}

	/**
	 * @param order    the SAP order; must not be null
	 * @param trigger  the PCP status that triggered this update; null is a top-level
	 *                 call that re-evaluates all entries instead of one status
	 */
	private void setOrderPaymentStatus(final AbstractOrderModel order,
									   final StatusValue trigger)
	{
		if (order == null)
		{
			throw new IllegalArgumentException("order must not be null");
		}

		if (isOperationOutcome(trigger))
		{
			return;
		}

		if (trigger == StatusValue.CHARGEBACKED)
		{
			order.setPaymentStatus(PaymentStatus.NOTPAID);
			modelService.save(order);
			LOG.debug("Updated order [{}] paymentStatus to [NOTPAID] (chargeback)", order.getCode());
			return;
		}

		if (trigger == StatusValue.CAPTURED || trigger == StatusValue.REFUNDED
				|| trigger == StatusValue.ACCOUNT_DEBITED || trigger == StatusValue.ACCOUNT_CREDITED)
		{
			final PaymentStatus amountBasedStatus = computeAmountBasedPaymentStatus(order, trigger);
			if (amountBasedStatus != null)
			{
				order.setPaymentStatus(amountBasedStatus);
				modelService.save(order);
				LOG.debug("Updated order [{}] paymentStatus to [{}] (amount-aware {})",
						order.getCode(), amountBasedStatus, trigger);
			}
			return;
		}

		// Non-amount-aware trigger or legacy no-arg call: re-evaluate all entries
		boolean hasError = false;
		boolean hasRejected = false;
		boolean hasSuccessful = false;

		for (final PaymentTransactionModel tx : order.getPaymentTransactions())
		{
			for (final PaymentTransactionEntryModel entry : tx.getEntries())
			{
				final String txStatus = entry.getTransactionStatus();
				if (txStatus == null)
				{
					continue;
				}
				if (PayonePaymentStatusMapper.ERROR.equals(txStatus))
				{
					hasError = true;
				}
				else if (PayonePaymentStatusMapper.REJECTED.equals(txStatus))
				{
					hasRejected = true;
				}
				else if (PayonePaymentStatusMapper.ACCEPTED.equals(txStatus))
				{
					hasSuccessful = true;
				}
			}
		}

		if (hasSuccessful)
		{
			order.setPaymentStatus(PaymentStatus.PAID);
		}
		else if (hasError)
		{
			order.setPaymentStatus(PaymentStatus.NOTPAID);
		}
		else if (hasRejected)
		{
			order.setPaymentStatus(PaymentStatus.NOTPAID);
		}
		else
		{
			// No terminal entries yet - leave current status as-is
			return;
		}

		modelService.save(order);
		LOG.debug("Updated order [{}] paymentStatus to [{}]", order.getCode(), order.getPaymentStatus());
	}

	/**
	 * Full capture/debit vs. order total -> PAID, partial -> PARTPAID.
	 * Full refund/credit -> NOTPAID, partial -> PARTPAID, zero -> null (no change).
	 */
	private PaymentStatus computeAmountBasedPaymentStatus(final AbstractOrderModel order,
														  final StatusValue trigger)
	{
		final Double rawTotal = order.getTotalPrice();
		if (rawTotal == null || rawTotal == 0D)
		{
			return null;
		}
		final BigDecimal orderTotal = BigDecimal.valueOf(rawTotal);

		BigDecimal cumulativeAffected = BigDecimal.ZERO;

		for (final PaymentTransactionModel tx : order.getPaymentTransactions())
		{
			for (final PaymentTransactionEntryModel entry : tx.getEntries())
			{
				final String txStatus = entry.getTransactionStatus();
				if (!PayonePaymentStatusMapper.ACCEPTED.equals(txStatus))
				{
					continue;
				}
				final String details = entry.getTransactionStatusDetails();
				if (details == null)
				{
					continue;
				}

				if (trigger == StatusValue.CAPTURED || trigger == StatusValue.ACCOUNT_DEBITED)
				{
					if (STATUS_CAPTURED.equals(details) || STATUS_ACCOUNT_DEBITED.equals(details))
					{
						if (entry.getAmount() != null)
						{
							cumulativeAffected = cumulativeAffected.add(entry.getAmount());
						}
					}
				}
				else if (trigger == StatusValue.REFUNDED || trigger == StatusValue.ACCOUNT_CREDITED)
				{
					if (STATUS_REFUNDED.equals(details) || STATUS_ACCOUNT_CREDITED.equals(details))
					{
						if (entry.getAmount() != null)
						{
							cumulativeAffected = cumulativeAffected.add(entry.getAmount());
						}
					}
				}
			}
		}

		if (trigger == StatusValue.CAPTURED || trigger == StatusValue.ACCOUNT_DEBITED)
		{
			return cumulativeAffected.compareTo(orderTotal) >= 0
					? PaymentStatus.PAID
					: PaymentStatus.PARTPAID;
		}
		else  // REFUNDED or ACCOUNT_CREDITED
		{
			if (cumulativeAffected.compareTo(orderTotal) >= 0)
			{
				return PaymentStatus.NOTPAID;
			}
			else if (cumulativeAffected.compareTo(BigDecimal.ZERO) > 0)
			{
				return PaymentStatus.PARTPAID;
			}
			else
			{
				return null; // no change
			}
		}
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}
}
