package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.endpoints.CheckoutApiClient;
import com.payone.commerce.platform.lib.models.CheckoutResponse;
import com.payone.commerce.platform.lib.models.CreateCheckoutRequest;
import com.payone.commerce.platform.lib.models.CreateCheckoutResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayonePcpClientFactory;
import de.hybris.bootstrap.annotations.UnitTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@UnitTest
public class DefaultPayoneCheckoutServiceTest
{
	private static final String MERCHANT_ID = "TEST_MERCHANT";
	private static final String COMMERCE_CASE_ID = "case-123";

	@Mock
	private PayonePcpClientFactory clientFactory;

	@Mock
	private CheckoutApiClient apiClient;

	private DefaultPayoneCheckoutService service;
	private PayoneConfigurationModel config;

	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);

		when(clientFactory.createCheckoutApiClient(any())).thenReturn(apiClient);

		service = new DefaultPayoneCheckoutService();
		service.setClientFactory(clientFactory);

		config = new PayoneConfigurationModel();
		config.setMerchantId(MERCHANT_ID);
	}

	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	@Test
	public void shouldCreateCheckout() throws Exception
	{
		final CreateCheckoutRequest request = new CreateCheckoutRequest();
		final CreateCheckoutResponse expected = new CreateCheckoutResponse();

		when(apiClient.createCheckoutRequest(MERCHANT_ID, COMMERCE_CASE_ID, request)).thenReturn(expected);

		final CreateCheckoutResponse result = service.createCheckout(config, COMMERCE_CASE_ID, request);

		assertNotNull(result);
		verify(apiClient).createCheckoutRequest(MERCHANT_ID, COMMERCE_CASE_ID, request);
	}

	@Test
	public void shouldGetCheckout() throws Exception
	{
		final String checkoutId = "chk-456";
		final CheckoutResponse expected = new CheckoutResponse();

		when(apiClient.getCheckoutRequest(MERCHANT_ID, COMMERCE_CASE_ID, checkoutId)).thenReturn(expected);

		final CheckoutResponse result = service.getCheckout(config, COMMERCE_CASE_ID, checkoutId);

		assertNotNull(result);
		verify(apiClient).getCheckoutRequest(MERCHANT_ID, COMMERCE_CASE_ID, checkoutId);
	}

	@Test
	public void shouldRejectNullConfig()
	{
		assertThrows(NullPointerException.class,
				() -> service.createCheckout(null, COMMERCE_CASE_ID, new CreateCheckoutRequest()));
	}

	@Test
	public void shouldRejectNullCommerceCaseId()
	{
		assertThrows(NullPointerException.class,
				() -> service.createCheckout(config, null, new CreateCheckoutRequest()));
	}

	@Test
	public void shouldRejectNullRequest()
	{
		assertThrows(NullPointerException.class,
				() -> service.createCheckout(config, COMMERCE_CASE_ID, null));
	}

	@Test
	public void shouldRejectNullCheckoutId()
	{
		assertThrows(NullPointerException.class,
				() -> service.getCheckout(config, COMMERCE_CASE_ID, null));
	}

	@Test
	public void shouldWrapSdkException() throws Exception
	{
		when(apiClient.createCheckoutRequest(any(), any(), any()))
				.thenThrow(new RuntimeException("API timeout"));

		assertThrows(IllegalStateException.class,
				() -> service.createCheckout(config, COMMERCE_CASE_ID, new CreateCheckoutRequest()));
	}
}
