package com.payone.pcp.core.converters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;

import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class AmountOfMoneyConverterTest
{
	@Mock
	private AbstractOrderModel order;

	@Mock
	private CurrencyModel currency;

	private PcpAmountOfMoneyConverter converter;
	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);
		converter = new PcpAmountOfMoneyConverter();
	}

	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	@Test
	public void shouldConvertFullAmount()
	{
		when(order.getTotalPrice()).thenReturn(99.99);
		when(order.getCurrency()).thenReturn(currency);
		when(currency.getIsocode()).thenReturn("EUR");

		final com.payone.commerce.platform.lib.models.AmountOfMoney result = converter.convert(order);

		assertNotNull(result);
		assertEquals(Long.valueOf(9999L), result.getAmount());
		assertEquals("EUR", result.getCurrencyCode());
	}

	@Test
	public void shouldConvertZeroAmount()
	{
		when(order.getTotalPrice()).thenReturn(0.0);
		when(order.getCurrency()).thenReturn(currency);
		when(currency.getIsocode()).thenReturn("USD");

		final com.payone.commerce.platform.lib.models.AmountOfMoney result = converter.convert(order);

		assertNotNull(result);
		assertEquals(Long.valueOf(0L), result.getAmount());
		assertEquals("USD", result.getCurrencyCode());
	}

	@Test
	public void shouldFailForNullOrder()
	{
		assertThrows(NullPointerException.class, () -> converter.convert(null));
	}

	@Test
	public void shouldFailForNullCurrency()
	{
		when(order.getTotalPrice()).thenReturn(50.0);
		when(order.getCurrency()).thenReturn(null);

		assertThrows(NullPointerException.class, () -> converter.convert(order));
	}
}
