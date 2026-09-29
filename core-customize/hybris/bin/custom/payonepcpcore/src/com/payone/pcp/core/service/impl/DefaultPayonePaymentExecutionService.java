package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.errors.ApiErrorResponseException;
import com.payone.commerce.platform.lib.errors.ApiResponseRetrievalException;
import com.payone.commerce.platform.lib.models.CancelPaymentRequest;
import com.payone.commerce.platform.lib.models.CancelPaymentResponse;
import com.payone.commerce.platform.lib.models.CapturePaymentRequest;
import com.payone.commerce.platform.lib.models.CapturePaymentResponse;
import com.payone.commerce.platform.lib.models.CompletePaymentRequest;
import com.payone.commerce.platform.lib.models.CompletePaymentResponse;
import com.payone.commerce.platform.lib.models.CreatePaymentResponse;
import com.payone.commerce.platform.lib.models.PausePaymentRequest;
import com.payone.commerce.platform.lib.models.PausePaymentResponse;
import com.payone.commerce.platform.lib.models.PaymentExecution;
import com.payone.commerce.platform.lib.models.PaymentExecutionRequest;
import com.payone.commerce.platform.lib.models.RefreshPaymentRequest;
import com.payone.commerce.platform.lib.models.RefundPaymentResponse;
import com.payone.commerce.platform.lib.models.RefundRequest;

import com.payone.commerce.platform.lib.models.StatusValue;
import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayonePaymentExecutionService;
import com.payone.pcp.core.service.PayonePcpClientFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.UUID;



/**
 * SDK 1.13.0 PaymentExecutionApiClient methods take merchantId first, then commerceCaseId,
 * checkoutId and paymentId (where applicable), and have no idempotencyKey parameter -
 * X-GCS-Idempotence-Key is a newer-SDK feature.
 */
public class DefaultPayonePaymentExecutionService implements PayonePaymentExecutionService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayonePaymentExecutionService.class);

	private PayonePcpClientFactory clientFactory;

	@Override
	public PaymentExecutionResponse executePayment(final PayoneConfigurationModel config,
			final String commerceCaseId, final String checkoutId, final PaymentExecutionRequest request)
	{
		assertArgs(config, commerceCaseId, checkoutId, request);
		LOG.debug("Executing payment for merchantId [{}], commerceCaseId [{}], checkoutId [{}]",
				config.getMerchantId(), commerceCaseId, checkoutId);

		try
		{
			final CreatePaymentResponse response = clientFactory.createPaymentExecutionApiClient(config)
					.createPayment(config.getMerchantId(), commerceCaseId, checkoutId, request);
			final UUID execId = response.getPaymentExecutionId();
			return wrapResponse(response, extractPaymentId(response),
					execId != null ? execId.toString() : null,
					extractPaymentStatus(response));
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("createPayment", config.getMerchantId(), e);
		}
    }

	@Override
	public PaymentExecutionResponse capturePayment(final PayoneConfigurationModel config,
			final String commerceCaseId, final String checkoutId, final String paymentId,
			final CapturePaymentRequest request)
	{
		assertArgs(config, commerceCaseId, checkoutId, paymentId, request);
		LOG.debug("Capturing payment for merchantId [{}], checkoutId [{}], paymentId [{}]",
				config.getMerchantId(), checkoutId, paymentId);

		try
		{
			final CapturePaymentResponse response = clientFactory.createPaymentExecutionApiClient(config)
					.capturePayment(config.getMerchantId(), commerceCaseId, checkoutId, paymentId, request);
			return wrapResponse(response, response.getId(), null, response.getStatus());
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("capturePayment", config.getMerchantId(), e);
		}
    }

	@Override
	public PaymentExecutionResponse cancelPayment(final PayoneConfigurationModel config,
			final String commerceCaseId, final String checkoutId, final String paymentId,
			final CancelPaymentRequest request)
	{
		assertArgs(config, commerceCaseId, checkoutId, paymentId, request);
		LOG.debug("Cancelling payment for merchantId [{}], checkoutId [{}], paymentId [{}]",
				config.getMerchantId(), checkoutId, paymentId);

		try
		{
			final CancelPaymentResponse response = clientFactory.createPaymentExecutionApiClient(config)
					.cancelPayment(config.getMerchantId(), commerceCaseId, checkoutId, paymentId, request);
			return wrapResponse(response, extractPaymentId(response), null, null);
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("cancelPayment", config.getMerchantId(), e);
		}
    }

	@Override
	public PaymentExecutionResponse refundPayment(final PayoneConfigurationModel config,
			final String commerceCaseId, final String checkoutId, final String paymentId,
			final RefundRequest request)
	{
		assertArgs(config, commerceCaseId, checkoutId, paymentId, request);
		LOG.debug("Refunding payment for merchantId [{}], checkoutId [{}], paymentId [{}]",
				config.getMerchantId(), checkoutId, paymentId);

		try
		{
			final RefundPaymentResponse response = clientFactory.createPaymentExecutionApiClient(config)
					.refundPayment(config.getMerchantId(), commerceCaseId, checkoutId, paymentId, request);
			return wrapResponse(response, extractPaymentId(response), null, extractPaymentStatus(response));
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("refundPayment", config.getMerchantId(), e);
		}
    }

	@Override
	public PaymentExecutionResponse completePayment(final PayoneConfigurationModel config,
			final String commerceCaseId, final String checkoutId, final String paymentId,
			final CompletePaymentRequest request)
	{
		assertArgs(config, commerceCaseId, checkoutId, paymentId, request);
		LOG.debug("Completing payment for merchantId [{}], checkoutId [{}], paymentId [{}]",
				config.getMerchantId(), checkoutId, paymentId);

		try
		{
			final CompletePaymentResponse response = clientFactory.createPaymentExecutionApiClient(config)
					.completePayment(config.getMerchantId(), commerceCaseId, checkoutId, paymentId, request);
			return wrapResponse(response, extractPaymentId(response), null, null);
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("completePayment", config.getMerchantId(), e);
		}
    }

	@Override
	public PaymentExecutionResponse pausePayment(final PayoneConfigurationModel config,
			final String commerceCaseId, final String checkoutId, final String paymentId)
	{
		assertArgs(config, commerceCaseId, checkoutId, paymentId);
		LOG.debug("Pausing payment for merchantId [{}], checkoutId [{}], paymentId [{}]",
				config.getMerchantId(), checkoutId, paymentId);

		try
		{
			final PausePaymentResponse response = clientFactory.createPaymentExecutionApiClient(config)
					.pausePayment(config.getMerchantId(), commerceCaseId, checkoutId, paymentId,
							new PausePaymentRequest());
			return wrapResponse(response, null, null, response.getStatus());
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("pausePayment", config.getMerchantId(), e);
		}
    }

	@Override
	public PaymentExecutionResponse refreshPayment(final PayoneConfigurationModel config,
			final String commerceCaseId, final String checkoutId, final String paymentId)
	{
		assertArgs(config, commerceCaseId, checkoutId, paymentId);
		LOG.debug("Refreshing payment for merchantId [{}], checkoutId [{}], paymentId [{}]",
				config.getMerchantId(), checkoutId, paymentId);

		try
		{
			final PaymentExecution response = clientFactory.createPaymentExecutionApiClient(config)
					.refreshPayment(config.getMerchantId(), commerceCaseId, checkoutId, paymentId,
							new RefreshPaymentRequest());
			return wrapResponse(response,
					response.getPaymentId(),
					response.getPaymentExecutionId() != null
							? response.getPaymentExecutionId().toString()
							: null,
					null);
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException | RuntimeException e)
		{
			throw wrapSdkError("refreshPayment", config.getMerchantId(), e);
		}
    }

	/** Payment ID lives in the nested PaymentResponse.id. */
	private static String extractPaymentId(final CreatePaymentResponse response)
	{
		if (response.getPayment() == null)
		{
			return null;
		}
		return response.getPayment().getId();
	}

	private static String extractPaymentId(final CancelPaymentResponse response)
	{
		if (response.getPayment() == null)
		{
			return null;
		}
		return response.getPayment().getId();
	}

	private static String extractPaymentId(final CompletePaymentResponse response)
	{
		if (response.getPayment() == null)
		{
			return null;
		}
		return response.getPayment().getId();
	}

	private static StatusValue extractPaymentStatus(
			final CreatePaymentResponse response)
	{
		if (response.getPayment() == null)
		{
			return null;
		}
		return response.getPayment().getStatus();
	}

	/** Payment ID lives in the top-level id field. */
	private static String extractPaymentId(final RefundPaymentResponse response)
	{
		return response.getId();
	}

	private static StatusValue extractPaymentStatus(
			final RefundPaymentResponse response)
	{
		return response.getStatus();
	}

	private static PaymentExecutionResponse wrapResponse(
			final Serializable rawResponse,
			final String paymentId,
			final String paymentExecutionId,
			final StatusValue status)
	{
		return new PaymentExecutionResponse(paymentId, paymentExecutionId, status, rawResponse);
	}

	private static void assertArgs(final PayoneConfigurationModel config,
			final Object... args)
	{
		if (config == null)
		{
			throw new IllegalArgumentException("config must not be null");
		}
		for (final Object arg : args)
		{
			if (arg == null)
			{
				throw new IllegalArgumentException("argument must not be null");
			}
		}
	}

	private static IllegalStateException wrapSdkError(final String operation,
			final String merchantId, final Exception cause)
	{
		// cause.getMessage() is null for ApiErrorResponseException; the mapper pulls
		// httpStatus and per-error errorCode/category/message from the response body instead.
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
