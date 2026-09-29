package com.payone.pcp.core.service;

import com.payone.commerce.platform.lib.models.PaymentInformationResponse;

import com.payone.pcp.core.model.PayoneConfigurationModel;


/**
 * Thin wrapper over the PCP SDK PaymentInformationApiClient, retrieving payment details
 * (e.g. card, status) for a completed payment within a checkout.
 */
public interface PayonePaymentInformationService
{
	/**
	 * Retrieve payment information for a given checkout.
	 *
	 * @param config         PAYONE configuration for the target merchant (secrets populated).
	 * @param commerceCaseId the PCP commerce case identifier.
	 * @param checkoutId     the PCP checkout identifier.
	 * @return the PCP PaymentInformationResponse.
	 * @throws IllegalArgumentException if any parameter is null.
	 * @throws IllegalStateException    if the SDK client cannot be created or the
	 *                                  PCP API call fails.
	 */
	PaymentInformationResponse getPaymentInformation(PayoneConfigurationModel config,
			String commerceCaseId, String checkoutId);
}
