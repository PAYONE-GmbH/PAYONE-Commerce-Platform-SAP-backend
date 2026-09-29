package com.payone.pcp.core.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;

import com.payone.commerce.platform.lib.CommunicatorConfiguration;
import com.payone.commerce.platform.lib.endpoints.AuthenticationApiClient;
import com.payone.commerce.platform.lib.endpoints.CheckoutApiClient;
import com.payone.commerce.platform.lib.endpoints.CommerceCaseApiClient;
import com.payone.commerce.platform.lib.endpoints.PaymentExecutionApiClient;
import com.payone.commerce.platform.lib.endpoints.PaymentInformationApiClient;

import com.payone.pcp.core.model.PayoneConfigurationModel;

import okhttp3.OkHttpClient;

import org.junit.Before;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@UnitTest
public class DefaultPayonePcpClientFactoryTest
{
	private static final String MERCHANT_ID = "TEST_MERCHANT";
	private static final String API_SECRET = "test-secret";
	private static final String HOST = "api.preprod.commerce.payone.com";
	private static final String FULL_HOST = "https://api.preprod.commerce.payone.com";

	private DefaultPayonePcpClientFactory factory;
	private PayoneConfigurationModel config;

	@Before
	public void setUp()
	{
		factory = new DefaultPayonePcpClientFactory();
		factory.setConnectTimeoutSeconds(10);
		factory.setReadTimeoutSeconds(30);
		factory.setWriteTimeoutSeconds(30);

		config = configFor(MERCHANT_ID, API_SECRET);
	}

	/**
	 * apiSecret is a dynamic attribute resolved by InMemorySecretAttributeHandler at runtime;
	 * outside a hybris context getApiSecret() returns null on a plain model, so tests mock the
	 * accessors instead.
	 */
	private static PayoneConfigurationModel configFor(final String merchantId, final String apiSecret)
	{
		final PayoneConfigurationModel model = mock(PayoneConfigurationModel.class);
		when(model.getMerchantId()).thenReturn(merchantId);
		when(model.getApiEndpointHost()).thenReturn(FULL_HOST);
		when(model.getApiSecret()).thenReturn(apiSecret);
		return model;
	}

	@Test
	public void shouldBuildCommunicatorConfiguration()
	{
		final CommunicatorConfiguration sdkConfig = factory.buildCommunicatorConfiguration(config);

		assertNotNull(sdkConfig);
		assertEquals(API_SECRET, sdkConfig.getApiSecret());
		assertEquals(HOST, sdkConfig.getHost());
		assertNotNull(sdkConfig.getHttpClient());
	}

	/**
	 * The SDK wants a bare host. Anything an operator might reasonably paste into
	 * the Backoffice field - a scheme, an explicit port, a trailing path, stray
	 * whitespace - has to reduce to that one value.
	 */
	@Test
	public void extractHostShouldReduceAnyConfiguredUrlToItsHost()
	{
		assertEquals(HOST, DefaultPayonePcpClientFactory.extractHost(FULL_HOST, MERCHANT_ID));
		assertEquals(HOST, DefaultPayonePcpClientFactory.extractHost(HOST, MERCHANT_ID));
		assertEquals(HOST, DefaultPayonePcpClientFactory.extractHost("  " + FULL_HOST + "  ", MERCHANT_ID));
		assertEquals(HOST, DefaultPayonePcpClientFactory.extractHost("http://" + HOST + ":8443/v1/", MERCHANT_ID));
		assertNull(DefaultPayonePcpClientFactory.extractHost(null, MERCHANT_ID));
	}

	/**
	 * An unparseable host fails here, naming the merchant, rather than several
	 * layers down inside the SDK on the first live payment.
	 */
	@Test
	public void extractHostShouldRejectAnUnusableValue()
	{
		final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> DefaultPayonePcpClientFactory.extractHost("https://", MERCHANT_ID));

		assertTrue(thrown.getMessage(), thrown.getMessage().contains(MERCHANT_ID));
	}

	/**
	 * AuthenticationApiClient goes through the same createClient path as the rest,
	 * so its InvalidKeyException is translated instead of escaping as a checked
	 * exception the caller cannot handle.
	 */
	@Test
	public void shouldCreateAuthenticationApiClient()
	{
		final AuthenticationApiClient client = factory.createAuthenticationApiClient(config);

		assertNotNull(client);
		assertSame(client, factory.createAuthenticationApiClient(config));
	}

	@Test
	public void shouldCreateCommerceCaseApiClient()
	{
		final CommerceCaseApiClient client = factory.createCommerceCaseApiClient(config);
		assertNotNull(client);
	}

	@Test
	public void shouldCreateCheckoutApiClient()
	{
		final CheckoutApiClient client = factory.createCheckoutApiClient(config);
		assertNotNull(client);
	}

	@Test
	public void shouldCreatePaymentExecutionApiClient()
	{
		final PaymentExecutionApiClient client = factory.createPaymentExecutionApiClient(config);
		assertNotNull(client);
	}

	@Test
	public void shouldCreatePaymentInformationApiClient()
	{
		final PaymentInformationApiClient client = factory.createPaymentInformationApiClient(config);
		assertNotNull(client);
	}

	@Test
	public void shouldBuildHttpClientWithTimeouts()
	{
		final OkHttpClient httpClient = factory.buildHttpClient();

		assertNotNull(httpClient);
		assertEquals(10, httpClient.connectTimeoutMillis() / 1000);
		assertEquals(30, httpClient.readTimeoutMillis() / 1000);
		assertEquals(30, httpClient.writeTimeoutMillis() / 1000);
	}

	// sharing and caching

	/**
	 * One OkHttpClient for the whole factory. A per-call client means a fresh
	 * Dispatcher, ConnectionPool and TLS handshake on every checkout request.
	 */
	@Test
	public void shouldReuseOneHttpClientAcrossConfigurations()
	{
		final CommunicatorConfiguration first = factory.buildCommunicatorConfiguration(config);
		final CommunicatorConfiguration second = factory.buildCommunicatorConfiguration(config);

		assertSame(factory.getHttpClient(), first.getHttpClient());
		assertSame(first.getHttpClient(), second.getHttpClient());
	}

	/** Repeated requests for the same merchant and type hand back the same client. */
	@Test
	public void shouldCacheClientPerMerchantAndType()
	{
		assertSame(factory.createCheckoutApiClient(config), factory.createCheckoutApiClient(config));
		assertSame(factory.createCommerceCaseApiClient(config), factory.createCommerceCaseApiClient(config));
	}

	/** Different merchants must never share a client - each signs with its own secret. */
	@Test
	public void shouldNotShareClientsBetweenMerchants()
	{
		final PayoneConfigurationModel other = configFor("OTHER_MERCHANT", API_SECRET);

		assertNotSame(factory.createCheckoutApiClient(config), factory.createCheckoutApiClient(other));
	}

	/**
	 * The point of fingerprinting the credentials: after a secret rotation the
	 * cache must not keep signing with the retired one.
	 */
	@Test
	public void shouldReplaceCachedClientWhenSecretRotates()
	{
		final CheckoutApiClient before = factory.createCheckoutApiClient(config);
		final CheckoutApiClient after =
				factory.createCheckoutApiClient(configFor(MERCHANT_ID, "rotated-secret"));

		assertNotSame(before, after);
	}

	/** A configuration with no merchantId has nothing to key the cache on. */
	@Test
	public void shouldRejectConfigurationWithoutMerchantId()
	{
		final PayoneConfigurationModel anonymous = configFor(null, API_SECRET);

		assertThrows(IllegalArgumentException.class,
				() -> factory.createCheckoutApiClient(anonymous));
	}

	@Test
	public void shouldThrowOnEmptySecret()
	{
		final PayoneConfigurationModel badConfig = configFor(MERCHANT_ID, "");

		assertThrows(IllegalArgumentException.class,
				() -> factory.createCommerceCaseApiClient(badConfig));
	}

	@Test
	public void shouldHandleHostWithoutScheme()
	{
		final PayoneConfigurationModel bareConfig = mock(PayoneConfigurationModel.class);
		when(bareConfig.getMerchantId()).thenReturn(MERCHANT_ID);
		when(bareConfig.getApiEndpointHost()).thenReturn(HOST);
		when(bareConfig.getApiSecret()).thenReturn(API_SECRET);

		final CommunicatorConfiguration sdkConfig = factory.buildCommunicatorConfiguration(bareConfig);
		assertEquals(HOST, sdkConfig.getHost());
	}
}
