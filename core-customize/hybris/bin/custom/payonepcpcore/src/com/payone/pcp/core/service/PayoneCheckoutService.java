package com.payone.pcp.core.service;

import com.payone.commerce.platform.lib.models.CheckoutResponse;
import com.payone.commerce.platform.lib.models.CreateCheckoutRequest;
import com.payone.commerce.platform.lib.models.CreateCheckoutResponse;

import com.payone.pcp.core.model.PayoneConfigurationModel;


/** Thin wrapper over the PCP SDK CheckoutApiClient. */
public interface PayoneCheckoutService
{
	/**
	 * Creates a checkout within an existing commerce case.
	 *
	 * @throws IllegalArgumentException if any parameter is null
	 * @throws IllegalStateException if the SDK client cannot be created or the PCP API call fails
	 */
	CreateCheckoutResponse createCheckout(PayoneConfigurationModel config,
			String commerceCaseId, CreateCheckoutRequest request);

	/**
	 * @throws IllegalArgumentException if any parameter is null
	 * @throws IllegalStateException if the SDK client cannot be created or the PCP API call fails
	 */
	CheckoutResponse getCheckout(PayoneConfigurationModel config,
			String commerceCaseId, String checkoutId);
}
