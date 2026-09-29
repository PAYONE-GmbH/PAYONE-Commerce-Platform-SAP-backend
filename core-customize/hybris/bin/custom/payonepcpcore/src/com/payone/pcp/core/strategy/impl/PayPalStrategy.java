package com.payone.pcp.core.strategy.impl;


/**
 * PayPal (product 840), PayPal REST flow
 * (docs.commerce.payone.com/docs/payment-methods/paypal/paypal-rest) - not PayPal Express or the
 * JavaScript SDK flow, which are separate and not implemented.
 * <p>
 * Uses {@code RedirectPaymentMethodSpecificInput}; {@code PcpRedirectPaymentMethodSpecificInputBuilder}
 * sets requiresApproval=false, tokenize=false and the product-840 sub-fields. The redirect
 * returned by executePayment is picked up in payonepcpfacades:
 * {@code PayoneCheckoutFacade.authorizePayment()} detects it via
 * {@code PaymentExecutionResponse.hasRedirectAction()}/{@code getRedirectUrl()} and returns the
 * URL to the frontend; {@code handleRedirectCallback()} handles the return after the customer
 * confirms on PayPal's site.
 */
public class PayPalStrategy extends AbstractPaymentStrategy {
}
