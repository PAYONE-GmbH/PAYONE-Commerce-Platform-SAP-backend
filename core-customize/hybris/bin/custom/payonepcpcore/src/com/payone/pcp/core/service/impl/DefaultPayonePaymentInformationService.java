package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.errors.ApiException;
import com.payone.commerce.platform.lib.models.PaymentInformationResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayonePaymentInformationService;
import com.payone.pcp.core.service.PayonePcpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;


public class DefaultPayonePaymentInformationService implements PayonePaymentInformationService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayonePaymentInformationService.class);

	private PayonePcpClientFactory clientFactory;

	@Override
	public PaymentInformationResponse getPaymentInformation(final PayoneConfigurationModel config,
			final String commerceCaseId, final String checkoutId)
	{
		Objects.requireNonNull(config, "config must not be null");
		Objects.requireNonNull(commerceCaseId, "commerceCaseId must not be null");
		Objects.requireNonNull(checkoutId, "checkoutId must not be null");

        LOG.debug("Getting payment information for merchantId [{}], commerceCaseId [{}], checkoutId [{}]",
				config.getMerchantId(), commerceCaseId, checkoutId);

		try
		{
			return clientFactory.createPaymentInformationApiClient(config)
					.getPaymentInformation(config.getMerchantId(), commerceCaseId, checkoutId,
							UUID.randomUUID().toString());
		}
		catch (final ApiException | IOException | RuntimeException e)
		{
			LOG.error("PCP getPaymentInformation failed for merchantId [{}]: {}",
					config.getMerchantId(), e.getMessage(), e);
			throw new IllegalStateException(
					"PCP getPaymentInformation failed for merchantId ["
							+ config.getMerchantId() + "]: " + e.getMessage(), e);
		}
    }

	public void setClientFactory(final PayonePcpClientFactory clientFactory)
	{
		this.clientFactory = clientFactory;
	}
}
