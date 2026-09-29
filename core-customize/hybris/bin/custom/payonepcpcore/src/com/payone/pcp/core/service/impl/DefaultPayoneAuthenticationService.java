package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.errors.ApiErrorResponseException;
import com.payone.commerce.platform.lib.errors.ApiResponseRetrievalException;
import com.payone.commerce.platform.lib.models.AuthenticationToken;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayoneAuthenticationService;
import com.payone.pcp.core.service.PayonePcpClientFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;


/**
 * Thread-safe: dependencies are wired at construction and immutable thereafter, and the SDK
 * client factory is the one shared instance.
 */
public class DefaultPayoneAuthenticationService implements PayoneAuthenticationService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneAuthenticationService.class);

	private PayonePcpClientFactory clientFactory;

	@Override
	public AuthenticationToken createAuthenticationToken(
			final PayoneConfigurationModel config,
			final String referenceId)
	{
		if (config == null)
		{
			throw new IllegalArgumentException("config must not be null");
		}

		LOG.debug("Creating authentication token for merchantId [{}], referenceId [{}]",
				config.getMerchantId(), referenceId);

		try
		{
			return clientFactory.createAuthenticationApiClient(config)
					.getAuthenticationTokens(config.getMerchantId(), referenceId);
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw new IllegalStateException(
					"PCP createAuthenticationToken failed for merchantId ["
							+ config.getMerchantId() + "]: " + e.getMessage(), e);
		}
    }

	public void setClientFactory(final PayonePcpClientFactory clientFactory)
	{
		this.clientFactory = clientFactory;
	}
}
