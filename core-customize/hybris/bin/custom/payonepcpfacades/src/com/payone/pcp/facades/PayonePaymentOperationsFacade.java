package com.payone.pcp.facades;

import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.pcp.facades.data.PaymentExecutionResultData;

import de.hybris.platform.core.model.order.OrderModel;


/**
 * Post-authorization payment operations for a placed order: capture, cancel, refund, complete,
 * pause, refresh. These are merchant/back-office operations, never customer-facing; no OCC
 * controller exposes this facade. Callers are Backoffice actions today.
 */
public interface PayonePaymentOperationsFacade
{
	/**
	 * Captures the full authorised amount (partial capture not yet supported). Resolves the
	 * order's PaymentTransaction and the PCP strategy for the product id on its
	 * PayonePaymentInfo, then delegates to {@code PayonePaymentStrategy.capture}.
	 *
	 * @param order the placed SAP order; must not be null and must carry a PayonePaymentInfo.
	 * @throws IllegalStateException if the store has no active PAYONE configuration, the order
	 *                                has no PCP PaymentTransaction/PaymentInfo, no strategy is
	 *                                registered for the product, or the PCP call fails.
	 */
	PaymentExecutionResultData capturePayment(OrderModel order);

	/**
	 * Cancels the full authorised amount. Same resolution/error contract as
	 * {@link #capturePayment(OrderModel)}.
	 */
	PaymentExecutionResultData cancelPayment(OrderModel order);

	/**
	 * Refunds the full captured amount. Same resolution/error contract as
	 * {@link #capturePayment(OrderModel)}.
	 */
	PaymentExecutionResultData refundPayment(OrderModel order);

	/**
	 * Captures exactly {@code amount} (minor units) as the final capture. Same
	 * resolution/error contract as {@link #capturePayment(OrderModel)}.
	 */
	PaymentExecutionResultData capturePayment(OrderModel order, long amount);

	/**
	 * Refunds exactly {@code amountOfMoney}. Same resolution/error contract as
	 * {@link #capturePayment(OrderModel)}.
	 */
	PaymentExecutionResultData refundPayment(OrderModel order, AmountOfMoney amountOfMoney);

	/**
	 * Finalises a delayed capture / redirect completion. Same resolution/error contract as
	 * {@link #capturePayment(OrderModel)}.
	 */
	PaymentExecutionResultData completePayment(OrderModel order);

	/**
	 * Places the payment on hold. Same resolution/error contract as
	 * {@link #capturePayment(OrderModel)}.
	 */
	PaymentExecutionResultData pausePayment(OrderModel order);

	/**
	 * Refreshes/updates the payment's authorisation. Same resolution/error contract as
	 * {@link #capturePayment(OrderModel)}.
	 */
	PaymentExecutionResultData refreshPayment(OrderModel order);
}
