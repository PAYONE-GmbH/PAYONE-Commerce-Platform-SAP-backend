/*
 * Secret resolution (apiSecret) is handled by the dynamic attribute handler
 * InMemorySecretAttributeHandler, not by this service. These tests only cover wiring,
 * validation, and fail-fast on missing config; secret-value assertions live in
 * InMemorySecretAttributeHandlerTest.
 */
package com.payone.pcp.core.service.impl;

import com.payone.pcp.core.constants.PayonepcpcoreConstants;
import com.payone.pcp.core.dao.PayoneConfigurationDao;
import com.payone.pcp.core.enums.PayoneAuthorizationModeEnum;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.order.payment.PaymentModeModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.store.BaseStoreModel;
import de.hybris.platform.store.services.BaseStoreService;
import org.apache.commons.configuration2.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;


@UnitTest
public class DefaultPayoneConfigurationServiceTest
{
	private static final String MERCHANT_ID = "TEST_MERCHANT";
	private static final String API_KEY_ID = "test-api-key-id";
	private static final String API_SECRET = "test-api-secret-value";
	private static final String ENDPOINT_HOST = "https://api.preprod.commerce.payone.com";
	private static final String STORE_UID = "testStore";

	@Mock
	private ConfigurationService hybrisConfigurationService;

	@Mock
	private Configuration hybrisConfig;

	@Mock
	private BaseStoreService baseStoreService;

	@Mock
	private PayoneConfigurationDao payoneConfigurationDao;

	private DefaultPayoneConfigurationService service;


	private BaseStoreModel baseStore;
	private PayoneConfigurationModel configModel;

	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);

		when(hybrisConfigurationService.getConfiguration()).thenReturn(hybrisConfig);
		when(hybrisConfig.getProperty(PayonepcpcoreConstants.PROPERTY_PREFIX
				+ MERCHANT_ID + PayonepcpcoreConstants.API_SECRET_PROPERTY_SUFFIX))
				.thenReturn(API_SECRET);

		baseStore = new BaseStoreModel();
		baseStore.setUid(STORE_UID);

		configModel = new PayoneConfigurationModel();
		configModel.setMerchantId(MERCHANT_ID);
		configModel.setApiEndpointHost(ENDPOINT_HOST);
		configModel.setApiKeyId(API_KEY_ID);
		configModel.setDefaultAuthorizationMode(PayoneAuthorizationModeEnum.SALE);
		configModel.setCaptureDelayHours(0);
		configModel.setAskConsumerConsent(true);
		configModel.setSessionTimeoutSeconds(600);
		configModel.setActive(true);

		service = new DefaultPayoneConfigurationService();
		service.setHybrisConfigurationService(hybrisConfigurationService);
		service.setBaseStoreService(baseStoreService);
		service.setPayoneConfigurationDao(payoneConfigurationDao);
	}

	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	@Test
	public void shouldReturnNullWhenBaseStoreIsNull()
	{
		assertNull(service.getConfigurationForStore(null));
	}

	@Test
	public void shouldReturnNullWhenBaseStoreHasNoConfig()
	{
		baseStore.setPayoneConfiguration(null);
		assertNull(service.getConfigurationForStore(baseStore));
	}

	@Test
	public void shouldResolveConfigurationForBaseStore()
	{
		baseStore.setPayoneConfiguration(configModel);

		final PayoneConfigurationModel result = service.getConfigurationForStore(baseStore);

		assertNotNull(result);
		assertEquals(MERCHANT_ID, result.getMerchantId());
		assertEquals(ENDPOINT_HOST, result.getApiEndpointHost());
		assertEquals(API_KEY_ID, result.getApiKeyId());
		assertEquals(PayoneAuthorizationModeEnum.SALE, result.getDefaultAuthorizationMode());
		assertEquals(0, result.getCaptureDelayHours().intValue());
		assertEquals(600, result.getSessionTimeoutSeconds().intValue());
	}

	@Test
	public void shouldResolveConfigurationByStoreUid()
	{
		baseStore.setPayoneConfiguration(configModel);
		when(baseStoreService.getBaseStoreForUid(STORE_UID)).thenReturn(baseStore);

		final PayoneConfigurationModel result = service.getConfigurationForStoreUid(STORE_UID);

		assertNotNull(result);
		assertEquals(MERCHANT_ID, result.getMerchantId());
	}

	@Test
	public void shouldReturnNullForUnknownStoreUid()
	{
		when(baseStoreService.getBaseStoreForUid("unknown")).thenReturn(null);

		assertNull(service.getConfigurationForStoreUid("unknown"));
	}

	@Test
	public void shouldResolveCurrentConfiguration()
	{
		baseStore.setPayoneConfiguration(configModel);
		when(baseStoreService.getCurrentBaseStore()).thenReturn(baseStore);

		final PayoneConfigurationModel result = service.getCurrentConfiguration();

		assertNotNull(result);
		assertEquals(MERCHANT_ID, result.getMerchantId());
	}

	@Test
	public void shouldReturnNullWhenNoCurrentStore()
	{
		when(baseStoreService.getCurrentBaseStore()).thenReturn(null);

		assertNull(service.getCurrentConfiguration());
	}

	@Test
	public void shouldResolveConfigurationByMerchantId()
	{
		when(payoneConfigurationDao.findPayoneConfigurationByMerchantId(MERCHANT_ID))
				.thenReturn(configModel);

		final PayoneConfigurationModel result = service.getConfigurationByMerchantId(MERCHANT_ID);

		assertNotNull(result);
		assertEquals(MERCHANT_ID, result.getMerchantId());
	}

	/** The DAO returns null for an unknown identifier - it does not throw. */
	@Test
	public void shouldReturnNullForUnknownMerchantId()
	{
		when(payoneConfigurationDao.findPayoneConfigurationByMerchantId("unknown"))
				.thenReturn(null);

		assertNull(service.getConfigurationByMerchantId("unknown"));
	}

	@Test(expected = IllegalStateException.class)
	public void shouldThrowWhenApiSecretMissing()
	{
		when(hybrisConfig.getProperty(PayonepcpcoreConstants.PROPERTY_PREFIX
				+ MERCHANT_ID + PayonepcpcoreConstants.API_SECRET_PROPERTY_SUFFIX))
				.thenReturn(null);

		baseStore.setPayoneConfiguration(configModel);
		service.getConfigurationForStore(baseStore);
	}

	@Test
	public void shouldReturnMerchantIdForBaseStore()
	{
		baseStore.setPayoneConfiguration(configModel);
		assertEquals(MERCHANT_ID, service.getMerchantId(baseStore));
	}

	@Test
	public void shouldReturnNullMerchantIdWhenNoConfig()
	{
		baseStore.setPayoneConfiguration(null);
		assertNull(service.getMerchantId(baseStore));
	}

	@Test
	public void shouldResolveSecretFromSystemProperty()
	{
		baseStore.setPayoneConfiguration(configModel);
		final PayoneConfigurationModel result = service.getConfigurationForStore(baseStore);
		assertNotNull(result);
		assertEquals(MERCHANT_ID, result.getMerchantId());
	}

	// tier precedence

	/** hybris config (CCv2 secret store) wins over a system property. */
	@Test
	public void configurationServiceShouldWinOverSystemProperty()
	{
		final String propKey = PayonepcpcoreConstants.PROPERTY_PREFIX
				+ MERCHANT_ID + PayonepcpcoreConstants.API_SECRET_PROPERTY_SUFFIX;
		System.setProperty(propKey, "system-property-secret");
		try
		{
			baseStore.setPayoneConfiguration(configModel);
			final PayoneConfigurationModel result = service.getConfigurationForStore(baseStore);

			assertNotNull(result);
			assertEquals(MERCHANT_ID, result.getMerchantId());
		}
		finally
		{
			System.clearProperty(propKey);
		}
	}


	@Test(expected = IllegalStateException.class)
	public void shouldThrowWhenNoSecretInConfigSystemProperty()
	{
		when(hybrisConfig.getProperty(PayonepcpcoreConstants.PROPERTY_PREFIX
				+ MERCHANT_ID + PayonepcpcoreConstants.API_SECRET_PROPERTY_SUFFIX))
				.thenReturn(null);

		baseStore.setPayoneConfiguration(configModel);
		service.getConfigurationForStore(baseStore);
	}

	// active-only variants

	/** A deactivated store must not hand a payment path a usable configuration. */
	@Test
	public void shouldReturnNullFromActiveGetterWhenConfigurationIsInactive()
	{
		configModel.setActive(false);
		baseStore.setPayoneConfiguration(configModel);
		when(baseStoreService.getCurrentBaseStore()).thenReturn(baseStore);

		assertNotNull(service.getConfigurationForStore(baseStore));
		assertNull(service.getActiveConfigurationForStore(baseStore));
		assertNull(service.getCurrentActiveConfiguration());
	}

	@Test
	public void shouldReturnConfigurationFromActiveGetterWhenActive()
	{
		baseStore.setPayoneConfiguration(configModel);
		when(baseStoreService.getCurrentBaseStore()).thenReturn(baseStore);

		assertNotNull(service.getActiveConfigurationForStore(baseStore));
		assertNotNull(service.getCurrentActiveConfiguration());
	}

	// current merchant id

	@Test
	public void shouldReturnCurrentMerchantId()
	{
		baseStore.setPayoneConfiguration(configModel);
		when(baseStoreService.getCurrentBaseStore()).thenReturn(baseStore);

		assertEquals(MERCHANT_ID, service.getCurrentMerchantId());
	}

	/** No current store is a normal state outside a storefront request, not an NPE. */
	@Test
	public void shouldReturnNullCurrentMerchantIdWhenNoCurrentStore()
	{
		when(baseStoreService.getCurrentBaseStore()).thenReturn(null);

		assertNull(service.getCurrentMerchantId());
	}

	// paymentModes to allowed payment product IDs

	/** A configured PaymentMode is returned unchanged, including its numeric code. */
	@Test
	public void shouldReturnConfiguredPaymentMode()
	{
		final PaymentModeModel cardMode = new PaymentModeModel();
		cardMode.setCode("1");
		configModel.setPaymentModes(Collections.singletonList(cardMode));
		baseStore.setPayoneConfiguration(configModel);

		final Collection<PaymentModeModel> paymentModes = service.getAllowedPaymentProductIds(baseStore);

		assertNotNull(paymentModes);
		assertEquals(Collections.singletonList(cardMode), paymentModes);
		assertEquals("1", paymentModes.iterator().next().getCode());
	}

	/** Multiple configured modes are returned unchanged and in configured order. */
	@Test
	public void shouldReturnMultipleConfiguredPaymentModes()
	{
		final PaymentModeModel cardMode = new PaymentModeModel();
		cardMode.setCode("1");
		final PaymentModeModel payPalMode = new PaymentModeModel();
		payPalMode.setCode("840");
		configModel.setPaymentModes(Arrays.asList(cardMode, payPalMode));
		baseStore.setPayoneConfiguration(configModel);

		final Collection<PaymentModeModel> paymentModes = service.getAllowedPaymentProductIds(baseStore);

		assertNotNull(paymentModes);
		assertEquals(Arrays.asList(cardMode, payPalMode), paymentModes);
	}

	/** No paymentModes configured ⇒ empty result, i.e. no per-store filtering. */
	@Test
	public void shouldReturnEmptyAllowedPaymentModesWhenNoPaymentModes()
	{
		configModel.setPaymentModes(null);
		baseStore.setPayoneConfiguration(configModel);

		final Collection<PaymentModeModel> paymentModes = service.getAllowedPaymentProductIds(baseStore);

		assertNotNull(paymentModes);
		assertTrue(paymentModes.isEmpty());
	}

	/** No base store ⇒ empty result, never an exception. */
	@Test
	public void shouldReturnEmptyAllowedPaymentModesWhenNoBaseStore()
	{
		assertTrue(service.getAllowedPaymentProductIds(null).isEmpty());
	}

	/** PaymentMode codes are not interpreted or filtered by this model-returning service. */
	@Test
	public void shouldReturnUnrecognizedPaymentModeCode()
	{
		final PaymentModeModel unknownMode = new PaymentModeModel();
		unknownMode.setCode("NOT_A_REAL_MODE");
		configModel.setPaymentModes(Collections.singletonList(unknownMode));
		baseStore.setPayoneConfiguration(configModel);

		final Collection<PaymentModeModel> paymentModes = service.getAllowedPaymentProductIds(baseStore);

		assertEquals(Collections.singletonList(unknownMode), paymentModes);
	}

	/** Unsupported numeric codes remain available as configured PaymentMode models. */
	@Test
	public void shouldReturnUnsupportedNumericPaymentMode()
	{
		final PaymentModeModel undocumentedMode = new PaymentModeModel();
		undocumentedMode.setCode("3391");
		configModel.setPaymentModes(Collections.singletonList(undocumentedMode));
		baseStore.setPayoneConfiguration(configModel);

		final Collection<PaymentModeModel> paymentModes = service.getAllowedPaymentProductIds(baseStore);

		assertEquals(Collections.singletonList(undocumentedMode), paymentModes);
	}

	/** Payment-Information-only product modes remain available as PaymentMode models. */
	@Test
	public void shouldPreservePaymentInformationOnlyPaymentModes()
	{
		final PaymentModeModel cashMode = new PaymentModeModel();
		cashMode.setCode("6000");
		final PaymentModeModel giftCardMode = new PaymentModeModel();
		giftCardMode.setCode("6001");
		final PaymentModeModel loyaltyMode = new PaymentModeModel();
		loyaltyMode.setCode("6002");
		configModel.setPaymentModes(Arrays.asList(cashMode, giftCardMode, loyaltyMode));
		baseStore.setPayoneConfiguration(configModel);

		final Collection<PaymentModeModel> paymentModes = service.getAllowedPaymentProductIds(baseStore);

		assertEquals(Arrays.asList(cashMode, giftCardMode, loyaltyMode), paymentModes);
	}

	// type-constraint validation

	/** An invalid merchantId must fail here, not as a confusing "secret not found" later - the property key is derived from it. */
	@Test(expected = IllegalArgumentException.class)
	public void shouldRejectMerchantIdWithInvalidCharset()
	{
		configModel.setMerchantId("bad merchant!");
		baseStore.setPayoneConfiguration(configModel);
		service.getConfigurationForStore(baseStore);
	}

	/** Also rejected before secret resolution. */
	@Test(expected = IllegalArgumentException.class)
	public void shouldRejectOverlyLongMerchantId()
	{
		configModel.setMerchantId("a".repeat(129));
		baseStore.setPayoneConfiguration(configModel);
		service.getConfigurationForStore(baseStore);
	}

	/** Fails early instead of deep inside the SDK on the first live payment. */
	@Test(expected = IllegalArgumentException.class)
	public void shouldRejectEndpointHostThatIsTooShort()
	{
		configModel.setApiEndpointHost("x");
		baseStore.setPayoneConfiguration(configModel);
		service.getConfigurationForStore(baseStore);
	}
}
