package com.payone.pcp.core.dao;

import com.payone.pcp.core.model.PayoneConfigurationModel;


/** Data access for PayoneConfigurationModel, keyed by merchantId (used for webhook dispatch). */
public interface PayoneConfigurationDao
{
	/**
	 * @param merchantId the PCP merchant identifier (natural key)
	 * @return the matching configuration, or {@code null} if not found. Never throws for an
	 *         unknown identifier.
	 */
	PayoneConfigurationModel findPayoneConfigurationByMerchantId(String merchantId);

}
