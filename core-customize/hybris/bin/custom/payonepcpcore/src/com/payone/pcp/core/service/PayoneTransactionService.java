package com.payone.pcp.core.service;

import com.payone.commerce.platform.lib.models.StatusValue;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;



/**
 * Manages the SAP PaymentTransaction / PaymentTransactionEntry lifecycle for PCP payments:
 * creates or retrieves a PaymentTransaction for an order, appends entries with status mapped
 * from PCP StatusValue, and keeps the order-level payment status in sync.
 */
public interface PayoneTransactionService
{
	/**
	 * Returns the existing PaymentTransaction for the given order/cart, or creates one. Never
	 * rewrites an existing transaction's {@code code} ({@code PaymentTransactions.transUniqueIdx}
	 * enforces uniqueness on it). Matching, in order:
	 * <ol>
	 *   <li>By derived code ({@code <cartOrOrderCode>_PAYONE}) - the stable path for cart
	 *       authorization/retry, where the same cart code is reused on every call. If this row
	 *       already carries a conflicting {@code payoneCommerceCaseId}/{@code payoneCheckoutId},
	 *       fails closed rather than overwrite it.</li>
	 *   <li>Otherwise by full identity match: both PCP ids must be non-blank in the request and
	 *       equal the candidate's stored values on both. This lets a webhook resolve the row
	 *       {@code placeOrder} cloned from the cart (code stays cart-derived) instead of creating
	 *       a duplicate keyed by the order's own code. A candidate with only one PCP id set can't
	 *       match on a single dimension alone.</li>
	 * </ol>
	 * A candidate that partially overlaps the requested identity - shares a non-blank value on
	 * one dimension without matching fully - is never skipped in favor of creating a new row: it
	 * might be the right one racing an incomplete identity, so the call fails closed instead of
	 * risking a duplicate. Only a candidate sharing no dimension at all is ignored as unrelated.
	 * More than one full match also fails closed rather than guessing.
	 *
	 * @param order             the SAP order or cart; must not be null
	 * @param payoneCommerceCaseId the PCP commerce case ID; may be null
	 * @param payoneCheckoutId  the PCP checkout ID; may be null
	 * @return the existing or newly created PaymentTransaction
	 * @throws IllegalArgumentException if order is null
	 * @throws PayoneTransactionIdentityConflictException if the code-matched
	 *         row (Step 1) conflicts with the requested identity, if more than
	 *         one candidate fully matches (Step 2), or if a candidate partially
	 *         overlaps the requested identity without fully matching (Step 2)
	 */
	PaymentTransactionModel getOrCreatePaymentTransaction(
			AbstractOrderModel order,
			String payoneCommerceCaseId,
			String payoneCheckoutId);

	/**
	 * Creates a new PaymentTransactionEntry for the given transaction.
	 * Skips creation if an entry with the same {@code transactionStatusDetails}
	 * and {@code requestId} already exists on the transaction (idempotency).
	 *
	 * <p>Returns {@code null} when the PCP status is {@code UPDATED}, which is
	 * informational only and produces no entry.
	 *
	 * @param transaction      the PaymentTransaction; must not be null
	 * @param requestId        the PCP request ID (payment/capture/cancel ID); must not be null
	 * @param order            the order; must not be null
	 * @param pcpStatus        the PCP StatusValue to map; may be null (maps to ERROR)
	 * @param amount           the amount in minor units; may be null
	 * @param currency         the currency; may be null
	 * @param transactionType  the SAP PaymentTransactionType; must not be null
	 * @return the existing or newly created PaymentTransactionEntry, or null
	 *         for UPDATED status
	 * @throws IllegalArgumentException if transaction, requestId, order, or transactionType is null
	 */
	PaymentTransactionEntryModel createPaymentTransactionEntry(
			PaymentTransactionModel transaction,
			String requestId,
			AbstractOrderModel order,
			StatusValue pcpStatus,
			Long amount,
			CurrencyModel currency,
			PaymentTransactionType transactionType);

	/**
	 * Updates the order-level payment status based on the aggregate state of
	 * its PaymentTransactionEntries.
	 * Called automatically by createPaymentTransactionEntry. Exposed separately
	 * for cases where status needs to be re-evaluated (e.g. after a webhook
	 * updates an existing entry's status).
	 *
	 * @param order the SAP order; must not be null
	 */
	void setOrderPaymentStatus(AbstractOrderModel order);
}
