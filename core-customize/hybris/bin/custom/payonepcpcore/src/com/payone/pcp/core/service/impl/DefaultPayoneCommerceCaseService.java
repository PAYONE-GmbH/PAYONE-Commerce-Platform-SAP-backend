package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.errors.ApiErrorResponseException;
import com.payone.commerce.platform.lib.errors.ApiResponseRetrievalException;
import com.payone.commerce.platform.lib.models.CommerceCaseResponse;
import com.payone.commerce.platform.lib.models.CreateCommerceCaseRequest;
import com.payone.commerce.platform.lib.models.CreateCommerceCaseResponse;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayoneCommerceCaseService;
import com.payone.pcp.core.service.PayonePcpClientFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;


/**
 * Thread-safe: dependencies are wired at construction and immutable thereafter, and the SDK
 * client factory is the one shared instance.
 */
public class DefaultPayoneCommerceCaseService implements PayoneCommerceCaseService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneCommerceCaseService.class);

	private PayonePcpClientFactory clientFactory;

	@Override
	public CreateCommerceCaseResponse createCommerceCase(final PayoneConfigurationModel config,
			final CreateCommerceCaseRequest request)
	{
		Objects.requireNonNull(config, "config must not be null");
		Objects.requireNonNull(request, "request must not be null");

		LOG.debug("Creating commerce case for merchantId [{}]", config.getMerchantId());

		try
		{
			return clientFactory.createCommerceCaseApiClient(config)
					.createCommerceCaseRequest(config.getMerchantId(), request);
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("createCommerceCase", config.getMerchantId(), e);
		}
    }

	@Override
	public CommerceCaseResponse getCommerceCase(final PayoneConfigurationModel config,
			final String commerceCaseId)
	{
		Objects.requireNonNull(config, "config must not be null");
		Objects.requireNonNull(commerceCaseId, "commerceCaseId must not be null");

		LOG.debug("Getting commerce case [{}] for merchantId [{}]", commerceCaseId, config.getMerchantId());

		try
		{
			return clientFactory.createCommerceCaseApiClient(config)
					.getCommerceCaseRequest(config.getMerchantId(), commerceCaseId);
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("getCommerceCase", config.getMerchantId(), e);
		}
    }

	private static IllegalStateException wrapSdkError(final String operation,
			final String merchantId, final Exception cause)
	{
		// cause.getMessage() is null for ApiErrorResponseException when PCP's error body
		// carries no message string, so extract the real detail via the mapper instead.
		final String detail = PayoneApiErrorMapper.describe(cause);
		LOG.error("PCP API call [{}] failed for merchantId [{}]: {}", operation, merchantId, detail, cause);
		return new IllegalStateException(
				"PCP " + operation + " failed for merchantId [" + merchantId + "]: " + detail, cause);
	}

	public void setClientFactory(final PayonePcpClientFactory clientFactory)
	{
		this.clientFactory = clientFactory;
	}
}
