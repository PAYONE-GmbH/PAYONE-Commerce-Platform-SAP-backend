package com.payone.pcp.core.strategy.impl;


/**
 * Google Pay (product 320). The storefront tokenizes with the Google Pay API and passes
 * encryptedPaymentData through; PcpMobilePaymentMethodSpecificInputBuilder maps it.
 * <p>
 * Per docs.commerce.payone.com/docs/payment-methods/google-pay/standard-checkout, Google Pay
 * requires One-Step checkout (payment embedded in the initial commerce-cases request,
 * autoExecuteOrder=true), same as Wero. {@code PayoneCheckoutFacade}'s strategy-routed
 * authorizePayment is Step-by-Step only, so this strategy is not yet reachable end-to-end
 * through the facade.
 */
public class GooglePayStrategy extends AbstractPaymentStrategy {
}
