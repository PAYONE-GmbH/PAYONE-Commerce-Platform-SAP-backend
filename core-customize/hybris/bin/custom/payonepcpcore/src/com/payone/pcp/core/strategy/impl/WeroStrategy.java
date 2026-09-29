package com.payone.pcp.core.strategy.impl;

/**
 * Wero (product 900), REDIRECT family like PayPal. Per
 * docs.commerce.payone.com/docs/payment-methods/wero/, only Direct Sale (requiresApproval:
 * false) is supported today; Reservation/Pre-Authorisation and Capture require PAYONE
 * Customer Support to enable. Countries: Belgium, Germany; currency: EUR only (not enforced
 * in code). Do not add a requiresApproval=true / delayed-capture path here until PAYONE
 * confirms it is available.
 * <p>
 * {@code PcpRedirectPaymentMethodSpecificInputBuilder} omits
 * {@code paymentProduct900SpecificInput.captureTrigger}, which per the doc only applies once
 * requiresApproval=true is available. The redirect from {@code executePayment} is handled in
 * payonepcpfacades: {@code PayoneCheckoutFacade.authorizePayment()} returns the redirect URL
 * (QR code on desktop, app confirmation on mobile), and {@code handleRedirectCallback()}
 * handles the return after the customer confirms.
 */
public class WeroStrategy extends AbstractPaymentStrategy {
}
