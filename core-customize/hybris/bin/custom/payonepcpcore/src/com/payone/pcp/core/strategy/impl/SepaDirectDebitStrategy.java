package com.payone.pcp.core.strategy.impl;

/**
 * SEPA Direct Debit (product 771). SDK input is {@code SepaDirectDebitPaymentMethodSpecificInput},
 * built from the transient {@code PayoneMandate} carried on paymentInfo. This is the live checkout
 * path used by {@code PayoneCheckoutFacade.executePaymentAndPlaceOrder}.
 */
public class SepaDirectDebitStrategy extends AbstractPaymentStrategy {
}
