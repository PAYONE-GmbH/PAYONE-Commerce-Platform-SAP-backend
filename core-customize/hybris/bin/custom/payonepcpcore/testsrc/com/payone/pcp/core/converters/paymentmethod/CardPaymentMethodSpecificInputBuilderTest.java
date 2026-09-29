package com.payone.pcp.core.converters.paymentmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.CardPaymentMethodSpecificInput;
import de.hybris.bootstrap.annotations.UnitTest;

import com.payone.commerce.platform.lib.models.AuthorizationMode;
import com.payone.commerce.platform.lib.models.TransactionChannel;

import com.payone.pcp.core.model.PayonePaymentInfoModel;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.enums.PayoneAuthorizationModeEnum;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class CardPaymentMethodSpecificInputBuilderTest
{
	@Mock
	private PayonePaymentInfoModel paymentInfo;

	private PcpCardPaymentMethodSpecificInputBuilder builder;

	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);
		builder = new PcpCardPaymentMethodSpecificInputBuilder();
	}

	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	@Test
	public void shouldBuildCardInputWithToken()
	{
		when(paymentInfo.getPaymentProcessingToken()).thenReturn("tok-abc");
		when(paymentInfo.getPaymentProductId()).thenReturn(1);

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");
		config.setDefaultAuthorizationMode(PayoneAuthorizationModeEnum.SALE);
		config.setReturnUrl("https://shop.com/return");

		final CardPaymentMethodSpecificInput result =
				builder.build(paymentInfo, config);

		assertNotNull(result);
		assertEquals("tok-abc", result.getPaymentProcessingToken());
		assertEquals(Integer.valueOf(1), result.getPaymentProductId().getValue());
		assertEquals(AuthorizationMode.SALE, result.getAuthorizationMode());
		assertEquals(TransactionChannel.ECOMMERCE, result.getTransactionChannel());
		assertEquals("https://shop.com/return", result.getReturnUrl());
	}

	@Test
	public void shouldUsePreAuthorizationMode()
	{
		when(paymentInfo.getPaymentProcessingToken()).thenReturn("tok-xyz");
		when(paymentInfo.getPaymentProductId()).thenReturn(1);

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");
		config.setDefaultAuthorizationMode(PayoneAuthorizationModeEnum.PRE_AUTHORIZATION);

		final CardPaymentMethodSpecificInput result =
				builder.build(paymentInfo, config);

		assertNotNull(result);
		assertEquals(AuthorizationMode.PRE_AUTHORIZATION, result.getAuthorizationMode());
	}

	@Test
	public void shouldFailForNullPaymentInfo()
	{
		assertThrows(NullPointerException.class, () ->  builder.build(null, new PayoneConfigurationModel()));
	}

	@Test
	public void shouldFailForNullConfig()
	{
		assertThrows(NullPointerException.class, () -> builder.build(paymentInfo, null));
	}

	@Test
	public void shouldDefaultAuthorizationModeToSale()
	{
		when(paymentInfo.getPaymentProcessingToken()).thenReturn("tok-xyz");
		when(paymentInfo.getPaymentProductId()).thenReturn(1);

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");

		final CardPaymentMethodSpecificInput result =
				builder.build(paymentInfo, config);

		assertNotNull(result);
		assertEquals(AuthorizationMode.SALE, result.getAuthorizationMode());
	}

	@Test
	public void shouldHandleNullProductId()
	{
		when(paymentInfo.getPaymentProcessingToken()).thenReturn("tok-xyz");
		when(paymentInfo.getPaymentProductId()).thenReturn(null);

		final PayoneConfigurationModel config = new PayoneConfigurationModel();
		config.setMerchantId("TEST");

		final CardPaymentMethodSpecificInput result =
				builder.build(paymentInfo, config);

		assertNotNull(result);
		assertNull(result.getPaymentProductId());
	}
}
