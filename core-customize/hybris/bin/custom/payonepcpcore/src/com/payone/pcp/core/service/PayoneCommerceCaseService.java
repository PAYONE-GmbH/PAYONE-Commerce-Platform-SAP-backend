package com.payone.pcp.core.service;

import com.payone.commerce.platform.lib.models.CommerceCaseResponse;
import com.payone.commerce.platform.lib.models.CreateCommerceCaseRequest;
import com.payone.commerce.platform.lib.models.CreateCommerceCaseResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;


/**
 * Thin service layer over the PCP SDK CommerceCaseApiClient. Wraps SDK exceptions in
 * {@link IllegalStateException} with the merchantId in the message.
 */
public interface PayoneCommerceCaseService
{
	/**
	 * @param config  PAYONE configuration for the target merchant (secrets populated)
	 * @param request the PCP CreateCommerceCaseRequest payload
	 * @return the PCP CreateCommerceCaseResponse
	 * @throws IllegalArgumentException if {@code config} or {@code request} is null
	 * @throws IllegalStateException    if the SDK client cannot be created (invalid credentials)
	 *                                  or the PCP API call fails
	 */
	CreateCommerceCaseResponse createCommerceCase(PayoneConfigurationModel config,
			CreateCommerceCaseRequest request);

	/**
	 * @param config         PAYONE configuration for the target merchant (secrets populated)
	 * @param commerceCaseId the PCP commerce case identifier
	 * @return the PCP CommerceCaseResponse
	 * @throws IllegalArgumentException if {@code config} or {@code commerceCaseId} is null
	 * @throws IllegalStateException    if the SDK client cannot be created or the PCP API call fails
	 */
	CommerceCaseResponse getCommerceCase(PayoneConfigurationModel config, String commerceCaseId);
}
