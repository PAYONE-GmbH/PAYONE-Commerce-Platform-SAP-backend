package com.payone.pcp.core.converters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;

import de.hybris.platform.core.model.order.AbstractOrderModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class ReferencesConverterTest
{
	@Mock
	private AbstractOrderModel order;

	private PcpReferencesConverter converter;

	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);
		converter = new PcpReferencesConverter();
	}

	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	@Test
	public void shouldConvertReferencesWithOrderCodePrefixedAndUniqueSuffix()
	{
		// merchantReference must be unique merchant-wide on PCP's side - a reused plain
		// order.getCode() gets HTTP 409 payment-error-colliding-merchant-reference - so
		// convertReferences appends a fresh random suffix on every call.
		when(order.getCode()).thenReturn("ORDER-001");
		when(order.getGuid()).thenReturn("guid-001");

		final com.payone.commerce.platform.lib.models.References result =
				converter.convertReferences(order);

		assertNotNull(result);
		assertTrue(result.getMerchantReference().startsWith("ORDER-001-"));
		assertTrue(result.getMerchantReference().length() <= 20);
	}

	@Test
	public void shouldConvertReferencesWithDifferentSuffixEachCall()
	{
		when(order.getCode()).thenReturn("ORDER-001");

		final String first = converter.convertReferences(order).getMerchantReference();
		final String second = converter.convertReferences(order).getMerchantReference();

		assertNotEquals("each PaymentExecution attempt must get its own unique reference", first, second);
	}

	@Test
	public void shouldUseGuidFallbackWhenCodeIsNull()
	{
		when(order.getCode()).thenReturn(null);
		when(order.getGuid()).thenReturn("guid-001");

		final com.payone.commerce.platform.lib.models.References result =
				converter.convertReferences(order);

		assertNotNull(result);
		assertTrue(result.getMerchantReference().startsWith("guid-001-"));
	}

	@Test
	public void shouldFailReferencesForNullOrder()
	{
		assertThrows(NullPointerException.class, () -> converter.convertReferences(null));
	}

	@Test
	public void shouldConvertCheckoutReferences()
	{
		when(order.getCode()).thenReturn("ORDER-001");

		final com.payone.commerce.platform.lib.models.CheckoutReferences result =
				converter.convertCheckoutReferences(order, "my-store");

		assertNotNull(result);
		assertEquals("ORDER-001", result.getMerchantReference());
		assertEquals("my-store", result.getMerchantShopReference());
	}

	@Test
	public void shouldFailCheckoutReferencesForNullOrder()
	{
		assertThrows(NullPointerException.class, () -> converter.convertCheckoutReferences(null, "store"));
	}

	@Test
	public void shouldAllowNullMerchantShopReference()
	{
		when(order.getCode()).thenReturn("ORDER-001");

		final com.payone.commerce.platform.lib.models.CheckoutReferences result =
				converter.convertCheckoutReferences(order, null);

		assertNotNull(result);
		assertEquals("ORDER-001", result.getMerchantReference());
		assertNull(result.getMerchantShopReference());
	}
}
