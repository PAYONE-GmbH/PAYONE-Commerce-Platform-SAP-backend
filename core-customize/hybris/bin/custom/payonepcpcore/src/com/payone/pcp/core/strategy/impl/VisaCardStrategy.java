package com.payone.pcp.core.strategy.impl;

/**
 * Visa card (product 1). Inherits the full authorise/capture/cancel/refund flow from
 * {@link CardPaymentStrategy}; only the PCP product ID differs per scheme.
 */
public class VisaCardStrategy extends CardPaymentStrategy {
}
