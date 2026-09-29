package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.errors.ApiErrorResponseException;
import com.payone.commerce.platform.lib.errors.ApiResponseRetrievalException;
import com.payone.commerce.platform.lib.models.CheckoutResponse;
import com.payone.commerce.platform.lib.models.CreateCheckoutRequest;
import com.payone.commerce.platform.lib.models.CreateCheckoutResponse;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayoneCheckoutService;
import com.payone.pcp.core.service.PayonePcpClientFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;


/**
 * Thread-safe: dependencies are wired at construction and immutable thereafter, and the SDK
 * client factory is the one shared instance.
 */
public class DefaultPayoneCheckoutService implements PayoneCheckoutService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneCheckoutService.class);

	private PayonePcpClientFactory clientFactory;

	@Override
	public CreateCheckoutResponse createCheckout(final PayoneConfigurationModel config,
			final String commerceCaseId, final CreateCheckoutRequest request)
	{
		Objects.requireNonNull(config, "config must not be null");
		Objects.requireNonNull(commerceCaseId, "commerceCaseId must not be null");
		Objects.requireNonNull(request, "request must not be null");

		LOG.debug("Creating checkout for merchantId [{}], commerceCaseId [{}]",
				config.getMerchantId(), commerceCaseId);

		try
		{
			return clientFactory.createCheckoutApiClient(config)
					.createCheckoutRequest(config.getMerchantId(), commerceCaseId, request);
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("createCheckout", config.getMerchantId(), e);
		}
    }

	@Override
	public CheckoutResponse getCheckout(final PayoneConfigurationModel config,
			final String commerceCaseId, final String checkoutId)
	{
		Objects.requireNonNull(config, "config must not be null");
		Objects.requireNonNull(commerceCaseId, "commerceCaseId must not be null");
		Objects.requireNonNull(checkoutId, "checkoutId must not be null");

		LOG.debug("Getting checkout [{}] for merchantId [{}], commerceCaseId [{}]",
				checkoutId, config.getMerchantId(), commerceCaseId);

		try
		{
			return clientFactory.createCheckoutApiClient(config)
					.getCheckoutRequest(config.getMerchantId(), commerceCaseId, checkoutId);
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("getCheckout", config.getMerchantId(), e);
		}
    }

	private static IllegalStateException wrapSdkError(final String operation,
			final String merchantId, final Exception cause)
	{
		// cause.getMessage() is null for ApiErrorResponseException when PCP's error body
		// carries no message string, so extract the real detail via the mapper instead.
		final String detail = PayoneApiErrorMapper.describe(cause);
		LOG.error("PCP API call [{}] failed for merchantId [{}]: {}",
				operation, merchantId, detail, cause);
		return new IllegalStateException(
				"PCP " + operation + " failed for merchantId [" + merchantId + "]: " + detail, cause);
	}

	public void setClientFactory(final PayonePcpClientFactory clientFactory)
	{
		this.clientFactory = clientFactory;
	}
}
