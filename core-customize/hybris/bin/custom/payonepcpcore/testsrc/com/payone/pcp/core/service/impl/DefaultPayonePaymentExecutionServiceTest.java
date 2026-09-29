/*
 * SDK 1.13.0 signature: paymentId comes before the request, no idempotency-key parameter.
 */
package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.endpoints.PaymentExecutionApiClient;
import com.payone.commerce.platform.lib.models.*;
import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayonePcpClientFactory;
import de.hybris.bootstrap.annotations.UnitTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@UnitTest
public class DefaultPayonePaymentExecutionServiceTest
{
	private static final String MERCHANT_ID = "TEST_MERCHANT";
	private static final String COMMERCE_CASE_ID = "case-123";
	private static final String CHECKOUT_ID = "chk-456";
	private static final String PAYMENT_ID = "pay-789";

	@Mock
	private PayonePcpClientFactory clientFactory;

	@Mock
	private PaymentExecutionApiClient apiClient;

	private DefaultPayonePaymentExecutionService service;
	private PayoneConfigurationModel config;

	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);

		when(clientFactory.createPaymentExecutionApiClient(any())).thenReturn(apiClient);

		service = new DefaultPayonePaymentExecutionService();
		service.setClientFactory(clientFactory);

		config = new PayoneConfigurationModel();
		config.setMerchantId(MERCHANT_ID);
	}

	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	@Test
	public void shouldExecutePayment() throws Exception
	{
		final PaymentExecutionRequest request = new PaymentExecutionRequest();
		final PaymentResponse paymentResponse = new PaymentResponse()
				.id(PAYMENT_ID)
				.status(StatusValue.CAPTURED);
		final CreatePaymentResponse sdkResponse = new CreatePaymentResponse()
				.payment(paymentResponse)
				.paymentExecutionId(UUID.randomUUID());

		when(apiClient.createPayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(request)))
				.thenReturn(sdkResponse);

		final PaymentExecutionResponse result = service.executePayment(
				config, COMMERCE_CASE_ID, CHECKOUT_ID, request);

		assertNotNull(result);
		assertNotNull(result.getPaymentExecutionId());
		verify(apiClient).createPayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(request));
	}

	@Test
	public void shouldCapturePayment() throws Exception
	{
		final CapturePaymentRequest request = new CapturePaymentRequest();
		final CapturePaymentResponse sdkResponse = new CapturePaymentResponse()
				.id("capture-1")
				.status(StatusValue.CAPTURED);

		when(apiClient.capturePayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID), eq(request)))
				.thenReturn(sdkResponse);

		final PaymentExecutionResponse result = service.capturePayment(
				config, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_ID, request);

		assertNotNull(result);
		verify(apiClient).capturePayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID), eq(request));
	}

	@Test
	public void shouldCancelPayment() throws Exception
	{
		final CancelPaymentRequest request = new CancelPaymentRequest();
		final PaymentResponse paymentResponse = new PaymentResponse().id(PAYMENT_ID);
		final CancelPaymentResponse sdkResponse = new CancelPaymentResponse()
				.payment(paymentResponse);

		when(apiClient.cancelPayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID), eq(request)))
				.thenReturn(sdkResponse);

		final PaymentExecutionResponse result = service.cancelPayment(
				config, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_ID, request);

		assertNotNull(result);
		verify(apiClient).cancelPayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID), eq(request));
	}

	@Test
	public void shouldRefundPayment() throws Exception
	{
		final RefundRequest request = new RefundRequest();
		final RefundPaymentResponse sdkResponse = new RefundPaymentResponse()
				.id("refund-1")
				.status(StatusValue.REFUNDED);

		when(apiClient.refundPayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID), eq(request)))
				.thenReturn(sdkResponse);

		final PaymentExecutionResponse result = service.refundPayment(
				config, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_ID, request);

		assertNotNull(result);
		verify(apiClient).refundPayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID), eq(request));
	}

	@Test
	public void shouldCompletePayment() throws Exception
	{
		final CompletePaymentRequest request = new CompletePaymentRequest();
		final PaymentResponse paymentResponse = new PaymentResponse().id(PAYMENT_ID);
		final CompletePaymentResponse sdkResponse = new CompletePaymentResponse()
				.payment(paymentResponse);

		when(apiClient.completePayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID), eq(request)))
				.thenReturn(sdkResponse);

		final PaymentExecutionResponse result = service.completePayment(
				config, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_ID, request);

		assertNotNull(result);
		verify(apiClient).completePayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID), eq(request));
	}

	@Test
	public void shouldPausePayment() throws Exception
	{
		final PausePaymentResponse sdkResponse = new PausePaymentResponse()
				.status(StatusValue.PAUSED);

		when(apiClient.pausePayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID),
				any(PausePaymentRequest.class)))
				.thenReturn(sdkResponse);

		final PaymentExecutionResponse result = service.pausePayment(
				config, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_ID);

		assertNotNull(result);
		verify(apiClient).pausePayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID),
				any(PausePaymentRequest.class));
	}

	@Test
	public void shouldRefreshPayment() throws Exception
	{
		final PaymentExecution sdkResponse = new PaymentExecution()
				.paymentId(PAYMENT_ID)
				.paymentExecutionId(UUID.randomUUID());

		when(apiClient.refreshPayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID),
				any(RefreshPaymentRequest.class)))
				.thenReturn(sdkResponse);

		final PaymentExecutionResponse result = service.refreshPayment(
				config, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_ID);

		assertNotNull(result);
		verify(apiClient).refreshPayment(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), eq(PAYMENT_ID),
				any(RefreshPaymentRequest.class));
	}

	@Test
	public void shouldRejectNullConfig()
	{
		assertThrows(IllegalArgumentException.class,
				() -> service.executePayment(null, COMMERCE_CASE_ID, CHECKOUT_ID, new PaymentExecutionRequest()));
	}

	@Test
	public void shouldRejectNullCommerceCaseId()
	{
		assertThrows(IllegalArgumentException.class,
				() -> service.executePayment(config, null, CHECKOUT_ID, new PaymentExecutionRequest()));
	}

	@Test
	public void shouldRejectNullCheckoutId()
	{
		assertThrows(IllegalArgumentException.class,
				() -> service.executePayment(config, COMMERCE_CASE_ID, null, new PaymentExecutionRequest()));
	}

	@Test
	public void shouldRejectNullRequest()
	{
		assertThrows(IllegalArgumentException.class,
				() -> service.executePayment(config, COMMERCE_CASE_ID, CHECKOUT_ID, null));
	}

	@Test
	public void shouldWrapSdkException() throws Exception
	{
		when(apiClient.createPayment(any(), any(), any(), any()))
				.thenThrow(new RuntimeException("API timeout"));

		assertThrows(IllegalStateException.class,
				() -> service.executePayment(config, COMMERCE_CASE_ID, CHECKOUT_ID, new PaymentExecutionRequest()));
	}
}
