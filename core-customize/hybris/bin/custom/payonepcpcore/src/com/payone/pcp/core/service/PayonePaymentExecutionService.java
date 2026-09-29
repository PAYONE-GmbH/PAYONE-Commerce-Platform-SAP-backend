package com.payone.pcp.core.service;

import com.payone.commerce.platform.lib.models.CancelPaymentRequest;
import com.payone.commerce.platform.lib.models.CapturePaymentRequest;
import com.payone.commerce.platform.lib.models.CompletePaymentRequest;
import com.payone.commerce.platform.lib.models.PaymentExecutionRequest;
import com.payone.commerce.platform.lib.models.RefundRequest;

import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;


/**
 * Thin service layer over the PCP SDK PaymentExecutionApiClient, covering the full lifecycle:
 * create, capture, cancel, refund, complete, pause, refresh. Wraps SDK exceptions with merchant
 * context.
 * <p>
 * {@code paymentId} here is the PCP payment execution ID
 * ({@code CreatePaymentResponse.paymentExecutionId}), not the PSP payment transaction ID
 * ({@code PaymentResponse.id}) - the two are different identifiers and the distinction matters
 * for the capture/cancel/refund/complete endpoint URLs. SDK 1.13.0 has no idempotencyKey parameter.
 */
public interface PayonePaymentExecutionService
{
	/** Execute a payment against an existing checkout. */
	PaymentExecutionResponse executePayment(PayoneConfigurationModel config,
			String commerceCaseId, String checkoutId, PaymentExecutionRequest request);

	/** Capture an authorised payment. */
	PaymentExecutionResponse capturePayment(PayoneConfigurationModel config,
			String commerceCaseId, String checkoutId, String paymentId,
			CapturePaymentRequest request);

	/** Cancel an authorised, uncaptured payment. */
	PaymentExecutionResponse cancelPayment(PayoneConfigurationModel config,
			String commerceCaseId, String checkoutId, String paymentId,
			CancelPaymentRequest request);

	/** Refund a captured payment. */
	PaymentExecutionResponse refundPayment(PayoneConfigurationModel config,
			String commerceCaseId, String checkoutId, String paymentId,
			RefundRequest request);

	/** Finalise a delayed capture / redirect completion. */
	PaymentExecutionResponse completePayment(PayoneConfigurationModel config,
			String commerceCaseId, String checkoutId, String paymentId,
			CompletePaymentRequest request);

	/** Place a payment on hold. */
	PaymentExecutionResponse pausePayment(PayoneConfigurationModel config,
			String commerceCaseId, String checkoutId, String paymentId);

	/** Refresh/update a payment's authorisation. */
	PaymentExecutionResponse refreshPayment(PayoneConfigurationModel config,
			String commerceCaseId, String checkoutId, String paymentId);
}
