package com.payone.pcp.core.service.impl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;

import com.payone.commerce.platform.lib.endpoints.PaymentInformationApiClient;
import com.payone.commerce.platform.lib.models.PaymentInformationResponse;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayonePcpClientFactory;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class DefaultPayonePaymentInformationServiceTest
{
	private static final String MERCHANT_ID = "TEST_MERCHANT";
	private static final String COMMERCE_CASE_ID = "case-123";
	private static final String CHECKOUT_ID = "chk-456";

	@Mock
	private PayonePcpClientFactory clientFactory;

	@Mock
	private PaymentInformationApiClient apiClient;

	private DefaultPayonePaymentInformationService service;
	private PayoneConfigurationModel config;

	@Before
	public void setUp()
	{
		MockitoAnnotations.openMocks(this);

		when(clientFactory.createPaymentInformationApiClient(any())).thenReturn(apiClient);

		service = new DefaultPayonePaymentInformationService();
		service.setClientFactory(clientFactory);

		config = new PayoneConfigurationModel();
		config.setMerchantId(MERCHANT_ID);
	}

	@Test
	public void shouldGetPaymentInformation() throws Exception
	{
		final PaymentInformationResponse expected = new PaymentInformationResponse();

		when(apiClient.getPaymentInformation(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), any()))
				.thenReturn(expected);

		final PaymentInformationResponse result = service.getPaymentInformation(config, COMMERCE_CASE_ID, CHECKOUT_ID);

		assertNotNull(result);
		verify(apiClient).getPaymentInformation(eq(MERCHANT_ID), eq(COMMERCE_CASE_ID), eq(CHECKOUT_ID), any());
	}

	@Test
	public void shouldRejectNullConfig()
	{
		assertThrows(NullPointerException.class,
				() -> service.getPaymentInformation(null, COMMERCE_CASE_ID, CHECKOUT_ID));
	}

	@Test
	public void shouldRejectNullCommerceCaseId()
	{
		assertThrows(NullPointerException.class,
				() -> service.getPaymentInformation(config, null, CHECKOUT_ID));
	}

	@Test
	public void shouldRejectNullCheckoutId()
	{
		assertThrows(NullPointerException.class,
				() -> service.getPaymentInformation(config, COMMERCE_CASE_ID, null));
	}

	@Test
	public void shouldWrapSdkException() throws Exception
	{
		when(apiClient.getPaymentInformation(any(), any(), any(), any()))
				.thenThrow(new RuntimeException("API timeout"));

		assertThrows(IllegalStateException.class,
				() -> service.getPaymentInformation(config, COMMERCE_CASE_ID, CHECKOUT_ID));
	}
}
