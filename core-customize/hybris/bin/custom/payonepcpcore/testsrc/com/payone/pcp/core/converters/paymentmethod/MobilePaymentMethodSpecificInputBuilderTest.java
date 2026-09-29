package com.payone.pcp.core.converters.paymentmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.AuthorizationMode;
import com.payone.commerce.platform.lib.models.MobilePaymentMethodSpecificInput;

import com.payone.pcp.core.enums.PayoneAuthorizationModeEnum;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class MobilePaymentMethodSpecificInputBuilderTest
{
	@Mock
	private PayonePaymentInfoModel paymentInfo;

	private PcpMobilePaymentMethodSpecificInputBuilder builder;

	private AutoCloseable mocks;

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		builder = new PcpMobilePaymentMethodSpecificInputBuilder();
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	@Test
	public void shouldBuildMobileInputWithEncryptedPaymentData()
	{
		when(paymentInfo.getGooglePayEncryptedPaymentData()).thenReturn("base64-token");
		when(paymentInfo.getPaymentProductId()).thenReturn(320);

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");
		config.setDefaultAuthorizationMode(PayoneAuthorizationModeEnum.SALE);
		config.setReturnUrl("https://shop.com/return");

		final MobilePaymentMethodSpecificInput result = builder.build(paymentInfo, config);

		assertNotNull(result);
		assertEquals("base64-token", result.getEncryptedPaymentData());
		assertEquals(Integer.valueOf(320), result.getPaymentProductId().getValue());
		assertEquals(AuthorizationMode.SALE, result.getAuthorizationMode());
		assertNotNull(result.getThreeDSecure());
		assertEquals("https://shop.com/return", result.getThreeDSecure().getRedirectionData().getReturnUrl());
	}

	@Test
	public void shouldUsePreAuthorizationMode()
	{
		when(paymentInfo.getGooglePayEncryptedPaymentData()).thenReturn("base64-token");

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");
		config.setDefaultAuthorizationMode(PayoneAuthorizationModeEnum.PRE_AUTHORIZATION);

		final MobilePaymentMethodSpecificInput result = builder.build(paymentInfo, config);

		assertNotNull(result);
		assertEquals(AuthorizationMode.PRE_AUTHORIZATION, result.getAuthorizationMode());
	}

	@Test
	public void shouldDefaultAuthorizationModeToSale()
	{
		when(paymentInfo.getGooglePayEncryptedPaymentData()).thenReturn("base64-token");

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");

		final MobilePaymentMethodSpecificInput result = builder.build(paymentInfo, config);

		assertNotNull(result);
		assertEquals(AuthorizationMode.SALE, result.getAuthorizationMode());
	}

	@Test
	public void shouldHandleNullProductId()
	{
		when(paymentInfo.getGooglePayEncryptedPaymentData()).thenReturn("base64-token");
		when(paymentInfo.getPaymentProductId()).thenReturn(null);

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");

		final MobilePaymentMethodSpecificInput result = builder.build(paymentInfo, config);

		assertNotNull(result);
		assertNull(result.getPaymentProductId());
	}

	@Test
	public void shouldSkipThreeDSecureWhenNoReturnUrlConfigured()
	{
		when(paymentInfo.getGooglePayEncryptedPaymentData()).thenReturn("base64-token");

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");

		final MobilePaymentMethodSpecificInput result = builder.build(paymentInfo, config);

		assertNotNull(result);
		assertNull(result.getThreeDSecure());
	}

	@Test
	public void shouldFailForBlankEncryptedPaymentData()
	{
		when(paymentInfo.getGooglePayEncryptedPaymentData()).thenReturn("  ");

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");

		assertThrows(IllegalArgumentException.class, () -> builder.build(paymentInfo, config));
	}

	@Test
	public void shouldFailForNullPaymentInfo()
	{
		assertThrows(NullPointerException.class, () -> builder.build(null, new PayoneConfigurationModel()));
	}

	@Test
	public void shouldFailForNullConfig()
	{
		assertThrows(NullPointerException.class, () -> builder.build(paymentInfo, null));
	}

	// Apple Pay (302) - minimal cut

	@Test
	public void shouldBuildApplePayInputWithMandatoryFields()
	{
		when(paymentInfo.getPaymentProductId()).thenReturn(302);
		when(paymentInfo.getApplePayEncryptedPaymentData()).thenReturn("apple-encrypted-data");
		when(paymentInfo.getApplePayPublicKeyHash()).thenReturn("apple-public-key-hash");
		when(paymentInfo.getApplePayEphemeralKey()).thenReturn("apple-ephemeral-key");

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");
		config.setDefaultAuthorizationMode(PayoneAuthorizationModeEnum.PRE_AUTHORIZATION);
		config.setReturnUrl("https://shop.com/return");

		final MobilePaymentMethodSpecificInput result = builder.build(paymentInfo, config);

		assertNotNull(result);
		assertEquals("apple-encrypted-data", result.getEncryptedPaymentData());
		assertEquals("apple-public-key-hash", result.getPublicKeyHash());
		assertEquals("apple-ephemeral-key", result.getEphemeralKey());
		assertEquals(Integer.valueOf(302), result.getPaymentProductId().getValue());
		assertEquals(AuthorizationMode.PRE_AUTHORIZATION, result.getAuthorizationMode());
		assertNotNull(result.getThreeDSecure());
		assertEquals("https://shop.com/return", result.getThreeDSecure().getRedirectionData().getReturnUrl());
		assertNull(result.getPaymentProduct302SpecificInput());
	}

	@Test
	public void shouldFailForApplePayMissingPublicKeyHash()
	{
		when(paymentInfo.getPaymentProductId()).thenReturn(302);
		when(paymentInfo.getApplePayEncryptedPaymentData()).thenReturn("apple-encrypted-data");
		when(paymentInfo.getApplePayEphemeralKey()).thenReturn("apple-ephemeral-key");

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");

		assertThrows(IllegalArgumentException.class, () -> builder.build(paymentInfo, config));
	}

	@Test
	public void shouldFailForApplePayMissingEphemeralKey()
	{
		when(paymentInfo.getPaymentProductId()).thenReturn(302);
		when(paymentInfo.getApplePayEncryptedPaymentData()).thenReturn("apple-encrypted-data");
		when(paymentInfo.getApplePayPublicKeyHash()).thenReturn("apple-public-key-hash");

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");

		assertThrows(IllegalArgumentException.class, () -> builder.build(paymentInfo, config));
	}

	@Test
	public void shouldFailForApplePayMissingEncryptedPaymentData()
	{
		when(paymentInfo.getPaymentProductId()).thenReturn(302);
		when(paymentInfo.getApplePayPublicKeyHash()).thenReturn("apple-public-key-hash");
		when(paymentInfo.getApplePayEphemeralKey()).thenReturn("apple-ephemeral-key");

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");

		assertThrows(IllegalArgumentException.class, () -> builder.build(paymentInfo, config));
	}
}
