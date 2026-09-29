package com.payone.pcp.webhook.dao;

import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;

import java.util.Optional;

/**
 * Lookups the webhook receiver needs, by PCP natural key.
 * <p>
 * All three types carry a unique index on the queried attribute
 * (payonepcpwebhook-items.xml / payonepcpcore-items.xml), so every method
 * returns at most one row.
 */
public interface PayoneWebhookEventDao
{
	/**
	 * Finds a previously received event by its PCP event id (the idempotency key).
	 *
	 * @param eventId the {@code id} member of the webhook envelope
	 * @return the stored event, or empty if this id has not been seen
	 */
	Optional<PayoneWebhookEventModel> findEventByEventId(String eventId);

	/**
	 * Finds the local commerce case a webhook refers to.
	 *
	 * @param commerceCaseId the PCP commerce case id (envelope
	 *                       {@code commerceCase.id})
	 * @return the case, or empty if unknown here (e.g. created by another
	 *         channel or environment sharing the merchant account)
	 */
	Optional<PayoneCommerceCaseModel> findCommerceCaseById(String commerceCaseId);

	/**
	 * Finds the local checkout a webhook refers to.
	 *
	 * @param checkoutId the PCP checkout id (envelope {@code checkout.id})
	 * @return the checkout, or empty if unknown here
	 */
	Optional<PayoneCheckoutModel> findCheckoutById(String checkoutId);

	/**
	 * Finds the local checkout that a payment operation belongs to, by the PCP
	 * paymentExecutionId the operation was issued against.
	 *
	 * @param paymentExecutionId the PCP paymentExecutionId (envelope
	 *                           {@code paymentExecution.id})
	 * @return the checkout, or empty if unknown here
	 */
	Optional<PayoneCheckoutModel> findCheckoutByPaymentExecutionId(String paymentExecutionId);

	/**
	 * Finds the local checkout that a payment belongs to, by PCP paymentId.
	 * Second-choice resolution for {@code payment.*} events, which carry a
	 * payment id but no checkout id.
	 *
	 * @param paymentId the PCP paymentId
	 * @return the checkout, or empty if unknown here
	 */
	Optional<PayoneCheckoutModel> findCheckoutByPaymentId(String paymentId);
}
