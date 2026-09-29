package com.payone.pcp.core.strategy.impl;


/**
 * Apple Pay (product 302) - minimal cut. Maps only the mandatory encrypted payment data fields,
 * not the full PKPaymentToken shape (merchant validation callback, .well-known domain
 * association).
 * <p>
 * Per docs.commerce.payone.com/docs/payment-methods/apple-pay, Apple Pay requires One-Step
 * checkout (payment embedded in the initial commerce-cases request, autoExecuteOrder=true), same
 * as Wero and Google Pay. {@code PayoneCheckoutFacade}'s strategy-routed authorizePayment is
 * Step-by-Step only, so this strategy is not yet reachable end-to-end through the facade.
 */
public class ApplePayStrategy extends AbstractPaymentStrategy {
}
