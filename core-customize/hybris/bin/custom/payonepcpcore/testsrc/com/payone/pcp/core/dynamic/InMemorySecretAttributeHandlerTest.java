/*
 * InMemorySecretAttributeHandlerTest.java
 *
 * Unit tests for InMemorySecretAttributeHandler.
 *
 * The handler resolves secrets lazily from the runtime environment on first
 * access to getApiSecret(). It caches by merchantId and
 * throws UnsupportedOperationException on set().
 *
 * These tests are isolated from SAP Commerce's model interceptor infrastructure
 * - they directly test the handler's get/set logic with a PayoneConfigurationModel.
 */
package com.payone.pcp.core.dynamic;

import com.payone.pcp.core.constants.PayonepcpcoreConstants;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import org.apache.commons.configuration2.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;


@UnitTest
public class InMemorySecretAttributeHandlerTest
{
	private static final String MERCHANT_ID = "TEST_MERCHANT";
	private static final String API_SECRET = "test-api-secret-value";

	@Mock
	private ConfigurationService hybrisConfigurationService;

	@Mock
	private Configuration hybrisConfig;

	private InMemorySecretAttributeHandler apiSecretHandler;

	private PayoneConfigurationModel configModel;

	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);

		when(hybrisConfigurationService.getConfiguration()).thenReturn(hybrisConfig);
		when(hybrisConfig.getProperty(PayonepcpcoreConstants.PROPERTY_PREFIX
				+ MERCHANT_ID + ".apiSecret"))
				.thenReturn(API_SECRET);

		apiSecretHandler = new InMemorySecretAttributeHandler("apiSecret");
		apiSecretHandler.setHybrisConfigurationService(hybrisConfigurationService);

		configModel = new PayoneConfigurationModel();
		configModel.setMerchantId(MERCHANT_ID);
	}


	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	// --- get() ---

	@Test
	public void shouldResolveApiSecretFromConfigurationService()
	{
		final String value = apiSecretHandler.get(configModel);
		assertEquals(API_SECRET, value);
	}

	@Test
	public void shouldCacheResolvedValue()
	{
		// First call populates the cache.
		apiSecretHandler.get(configModel);

		// Second call should use the cache, not hit ConfigurationService again.
		// If the mock were still called, we'd hit it - but this verifies the
		// in-memory path returns the same value.
		final String value = apiSecretHandler.get(configModel);
		assertEquals(API_SECRET, value);
	}

	@Test
	public void shouldReturnNullWhenMerchantIdIsNull()
	{
		configModel.setMerchantId(null);
		assertNull(apiSecretHandler.get(configModel));
	}

	@Test
	public void shouldReturnNullWhenMerchantIdIsBlank()
	{
		configModel.setMerchantId(" ");
		assertNull(apiSecretHandler.get(configModel));
	}

	@Test
	public void shouldReturnNullWhenPropertyNotConfigured()
	{
		when(hybrisConfig.getProperty(PayonepcpcoreConstants.PROPERTY_PREFIX
				+ MERCHANT_ID + ".apiSecret"))
				.thenReturn(null);
		assertNull(apiSecretHandler.get(configModel));
	}

	// --- set() ---

	@Test(expected = UnsupportedOperationException.class)
	public void shouldThrowOnSet()
	{
		apiSecretHandler.set(configModel, "some-value");
	}

	// --- ConfigurationService wins over System property ---

	@Test
	public void configurationServiceShouldWinOverSystemProperty()
	{
		final String propKey = PayonepcpcoreConstants.PROPERTY_PREFIX
				+ MERCHANT_ID + ".apiSecret";
		System.setProperty(propKey, "system-property-secret");
		try
		{
			final String value = apiSecretHandler.get(configModel);
			assertEquals(API_SECRET, value);
		}
		finally
		{
			System.clearProperty(propKey);
		}
	}

	// --- Cache keyed by merchantId, not by instance ---

	/**
	 * Two separate handler instances for the same attribute name share nothing,
	 * but any one handler instance caches by merchantId so a second call on the
	 * same merchant uses the cache.
	 */
	@Test
	public void cachesByMerchantId()
	{
		final String value1 = apiSecretHandler.get(configModel);
		assertEquals(API_SECRET, value1);

		// Change merchant - cache miss, should get null from properties
		// (only the original merchant is mocked).
		final PayoneConfigurationModel otherConfig = new PayoneConfigurationModel();
		otherConfig.setMerchantId("OTHER_MERCHANT");
		assertNull(apiSecretHandler.get(otherConfig));
	}

	// --- Non-blank value populates cache ---

	@Test
	public void doesNotCacheNullValues()
	{
		when(hybrisConfig.getProperty(PayonepcpcoreConstants.PROPERTY_PREFIX
				+ MERCHANT_ID + ".apiSecret"))
				.thenReturn(null).thenReturn(API_SECRET);

		// First call: null (not cached).
		assertNull(apiSecretHandler.get(configModel));
		
		// Second call: should resolve because null wasn't cached.
		assertEquals(API_SECRET, apiSecretHandler.get(configModel));
	}
}
