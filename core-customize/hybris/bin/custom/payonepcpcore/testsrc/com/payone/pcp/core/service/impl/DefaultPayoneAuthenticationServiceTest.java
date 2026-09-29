package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.endpoints.AuthenticationApiClient;
import com.payone.commerce.platform.lib.models.AuthenticationToken;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayonePcpClientFactory;
import de.hybris.bootstrap.annotations.UnitTest;
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
public class DefaultPayoneAuthenticationServiceTest
{
	private static final String MERCHANT_ID = "TEST_MERCHANT";
	private static final String REFERENCE_ID = "order-001";

	@Mock
	private PayonePcpClientFactory clientFactory;

	@Mock
	private AuthenticationApiClient apiClient;

	private DefaultPayoneAuthenticationService service;
	private PayoneConfigurationModel config;

	@Before
	public void setUp()
	{
		MockitoAnnotations.openMocks(this);

		when(clientFactory.createAuthenticationApiClient(any())).thenReturn(apiClient);

		service = new DefaultPayoneAuthenticationService();
		service.setClientFactory(clientFactory);

		config = new PayoneConfigurationModel();
		config.setMerchantId(MERCHANT_ID);
	}

	@Test
	public void shouldCreateAuthenticationToken() throws Exception
	{
		final AuthenticationToken expected = new AuthenticationToken();

		when(apiClient.getAuthenticationTokens(MERCHANT_ID, REFERENCE_ID)).thenReturn(expected);

		final AuthenticationToken result = service.createAuthenticationToken(config, REFERENCE_ID);

		assertNotNull(result);
		verify(apiClient).getAuthenticationTokens(MERCHANT_ID, REFERENCE_ID);
	}

	@Test
	public void shouldRejectNullConfig()
	{
		assertThrows(IllegalArgumentException.class,
				() -> service.createAuthenticationToken(null, REFERENCE_ID));
	}

	@Test
	public void shouldWrapSdkException() throws Exception
	{
		when(apiClient.getAuthenticationTokens(any(), any()))
				.thenThrow(new RuntimeException("API timeout"));

		assertThrows(IllegalStateException.class,
				() -> service.createAuthenticationToken(config, REFERENCE_ID));
	}
}
