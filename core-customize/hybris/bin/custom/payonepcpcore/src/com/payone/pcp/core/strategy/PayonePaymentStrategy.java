package com.payone.pcp.core.strategy;

import com.payone.commerce.platform.lib.models.AmountOfMoney;

import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;

import de.hybris.platform.core.model.order.AbstractOrderModel;


/** Strategy SPI for PCP payment methods. */
public interface PayonePaymentStrategy {
    /**
     * PCP payment product ID handled by this strategy. Product catalogue and
     * per-store enablement are owned by OOTB PaymentMode items.
     */
    int getPaymentProductId();

    /**
     * Authorize / create a payment against an existing PCP checkout.
     *
     * @param config resolved PCP configuration for the target store
     * @param commerceCaseId PCP commerce case ID
     * @param checkoutId PCP checkout ID
     * @param order order whose amount and shopping cart are sent to PCP
     * @param paymentInfo PCP payment info carrying product-specific data
     * @return normalized payment execution response from PCP
     */
    PaymentExecutionResponse authorize(PayoneConfigurationModel config, String commerceCaseId,
            String checkoutId, AbstractOrderModel order, PayonePaymentInfoModel paymentInfo);

    /**
     * Capture an authorised payment. With {@code amount == null} the request body is empty,
     * which PCP treats as "full amount will be captured and the request will be final"; with
     * an amount (minor units) exactly that amount is captured as the final capture
     * (https://docs.commerce.payone.com/api-reference, Capture a Payment).
     */
    PaymentExecutionResponse capture(PayoneConfigurationModel config, String commerceCaseId,
            String checkoutId, String paymentExecutionId, Long amount);

    /** Cancel / void an authorised payment with the SDK default request shape. */
    PaymentExecutionResponse cancel(PayoneConfigurationModel config, String commerceCaseId,
            String checkoutId, String paymentExecutionId);

    /**
     * Refund a captured payment. PCP requires amountOfMoney (amount + currencyCode) on every
     * refund request - an empty body is rejected with 50508004 "Refund amount or currency code
     * missing" (https://docs.commerce.payone.com/api-reference, Refund a Payment Execution).
     */
    PaymentExecutionResponse refund(PayoneConfigurationModel config, String commerceCaseId,
            String checkoutId, String paymentExecutionId, AmountOfMoney amountOfMoney);

    /** Complete a payment (finalise a delayed capture / redirect completion) with the SDK default request shape. */
    PaymentExecutionResponse complete(PayoneConfigurationModel config, String commerceCaseId,
            String checkoutId, String paymentExecutionId);

    /** Pause a payment (place it on hold). */
    PaymentExecutionResponse pause(PayoneConfigurationModel config, String commerceCaseId,
            String checkoutId, String paymentExecutionId);

    /** Refresh/update a payment's authorisation. */
    PaymentExecutionResponse refresh(PayoneConfigurationModel config, String commerceCaseId,
            String checkoutId, String paymentExecutionId);
}
