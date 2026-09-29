package com.payone.pcp.core.converters;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.product.ProductModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;


@UnitTest
public class ShoppingCartConverterTest
{
	@Mock
	private AbstractOrderModel order;

	@Mock
	private AbstractOrderEntryModel entry1;

	@Mock
	private AbstractOrderEntryModel entry2;

	@Mock
	private ProductModel product1;

	@Mock
	private ProductModel product2;

	@Mock
	private CurrencyModel currency;

	private PcpShoppingCartConverter converter;

	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);
		converter = new PcpShoppingCartConverter(new PcpLineItemConverter(), new PcpAmountOfMoneyConverter());
		when(order.getCurrency()).thenReturn(currency);
		when(currency.getIsocode()).thenReturn("EUR");
	}

	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	@Test
	public void shouldConvertMultipleEntries()
	{
		when(order.getEntries()).thenReturn(Arrays.asList(entry1, entry2));
		when(entry1.getProduct()).thenReturn(product1);
		when(product1.getCode()).thenReturn("SKU-001");
		when(product1.getName()).thenReturn("Item 1");
		when(entry1.getQuantity()).thenReturn(1L);
		when(entry1.getBasePrice()).thenReturn(10.0);
		when(entry1.getTaxValues()).thenReturn(Collections.emptyList());
		when(entry2.getProduct()).thenReturn(product2);
		when(product2.getCode()).thenReturn("SKU-002");
		when(product2.getName()).thenReturn("Item 2");
		when(entry2.getQuantity()).thenReturn(3L);
		when(entry2.getBasePrice()).thenReturn(5.0);
		when(entry2.getTaxValues()).thenReturn(Collections.emptyList());
		// item total (1*10 + 3*5 = 25) matches order total, no SHIPMENT line expected
		when(order.getTotalPrice()).thenReturn(25.0);

		final var result = converter.convert(order);

		assertNotNull(result);
		assertNotNull(result.getItems());
		assertEquals(2, result.getItems().size());
	}

	@Test
	public void shouldFilterEntriesWithoutProduct()
	{
		when(order.getEntries()).thenReturn(Collections.singletonList(entry1));
		when(entry1.getProduct()).thenReturn(null);
		when(order.getTotalPrice()).thenReturn(0.0);

		final var result = converter.convert(order);

		assertNotNull(result);
		assertNotNull(result.getItems());
		assertTrue(result.getItems().isEmpty());
	}

	@Test
	public void shouldReturnEmptyCartForNoEntries()
	{
		when(order.getEntries()).thenReturn(Collections.emptyList());
		when(order.getTotalPrice()).thenReturn(0.0);

		final var result = converter.convert(order);

		assertNotNull(result);
		assertNotNull(result.getItems());
		assertTrue(result.getItems().isEmpty());
	}

	@Test
	public void shouldFailForNullOrder()
	{
		assertThrows(NullPointerException.class, () -> converter.convert(null));
	}

	@Test
	public void shouldAppendShipmentLine_whenItemTotalIsLowerThanOrderTotal()
	{
		when(order.getEntries()).thenReturn(Collections.singletonList(entry1));
		when(entry1.getProduct()).thenReturn(product1);
		when(product1.getCode()).thenReturn("SKU-001");
		when(product1.getName()).thenReturn("Item 1");
		when(entry1.getQuantity()).thenReturn(1L);
		when(entry1.getBasePrice()).thenReturn(10.0);
		when(entry1.getTaxValues()).thenReturn(Collections.emptyList());
		// order total (15) exceeds item total (10) by 5 - delivery cost, not an order entry
		when(order.getTotalPrice()).thenReturn(15.0);

		final var result = converter.convert(order);

		assertNotNull(result);
		assertEquals(2, result.getItems().size());
		final var shipmentLine = result.getItems().get(1).getOrderLineDetails();
		assertEquals("Shipping & other charges", shipmentLine.getProductName());
		assertEquals(Long.valueOf(500L), shipmentLine.getProductPrice());
		assertEquals(com.payone.commerce.platform.lib.models.ProductType.SHIPMENT, shipmentLine.getProductType());
	}

	@Test
	public void shouldNotAppendShipmentLine_whenItemTotalMatchesOrderTotalExactly()
	{
		when(order.getEntries()).thenReturn(Collections.singletonList(entry1));
		when(entry1.getProduct()).thenReturn(product1);
		when(product1.getCode()).thenReturn("SKU-001");
		when(product1.getName()).thenReturn("Item 1");
		when(entry1.getQuantity()).thenReturn(1L);
		when(entry1.getBasePrice()).thenReturn(10.0);
		when(entry1.getTaxValues()).thenReturn(Collections.emptyList());
		when(order.getTotalPrice()).thenReturn(10.0);

		final var result = converter.convert(order);

		assertNotNull(result);
		assertEquals(1, result.getItems().size());
	}
}
