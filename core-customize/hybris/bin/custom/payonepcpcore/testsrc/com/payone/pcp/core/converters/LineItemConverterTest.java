package com.payone.pcp.core.converters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.CartItemInput;
import de.hybris.bootstrap.annotations.UnitTest;

import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.product.ProductModel;

import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


/**
 * TaxValueModel isn't available in this platform download (no tax extension), so tax-related
 * assertions here use empty/null tax lists only. Full tax mapping is covered by integration
 * tests against the real platform.
 */
@UnitTest
public class LineItemConverterTest
{
	@Mock
	private AbstractOrderEntryModel entry;

	@Mock
	private ProductModel product;

	private PcpLineItemConverter converter;

	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);
		converter = new PcpLineItemConverter();
	}

	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	@Test
	public void shouldConvertFullEntry()
	{
		when(entry.getProduct()).thenReturn(product);
		when(product.getCode()).thenReturn("SKU-001");
		when(product.getName()).thenReturn("Test Product");
		when(entry.getQuantity()).thenReturn(2L);
		when(entry.getBasePrice()).thenReturn(19.99);
		when(entry.getTaxValues()).thenReturn(Collections.emptyList());

		final CartItemInput result = converter.convert(entry);

		assertNotNull(result);
		assertNotNull(result.getOrderLineDetails());
		assertEquals("SKU-001", result.getOrderLineDetails().getProductCode());
		assertEquals("Test Product", result.getOrderLineDetails().getProductName());
		assertEquals(Long.valueOf(2L), result.getOrderLineDetails().getQuantity());
		assertEquals(Long.valueOf(1999L), result.getOrderLineDetails().getProductPrice());
		assertEquals(Long.valueOf(0L), result.getOrderLineDetails().getTaxAmount());
	}

	@Test
	public void shouldConvertZeroQuantityEntry()
	{
		when(entry.getProduct()).thenReturn(product);
		when(product.getCode()).thenReturn("SKU-000");
		when(product.getName()).thenReturn("Zero Qty Item");
		when(entry.getQuantity()).thenReturn(0L);
		when(entry.getBasePrice()).thenReturn(10.0);
		when(entry.getTaxValues()).thenReturn(Collections.emptyList());

		final CartItemInput result = converter.convert(entry);

		assertNotNull(result);
		assertEquals(Long.valueOf(0L), result.getOrderLineDetails().getQuantity());
	}

	@Test
	public void shouldHandleNullTaxValues()
	{
		when(entry.getProduct()).thenReturn(product);
		when(product.getCode()).thenReturn("SKU-002");
		when(product.getName()).thenReturn("No Tax Item");
		when(entry.getQuantity()).thenReturn(1L);
		when(entry.getBasePrice()).thenReturn(10.0);
		when(entry.getTaxValues()).thenReturn(null);

		final CartItemInput result = converter.convert(entry);

		assertNotNull(result);
		assertEquals(Long.valueOf(0L), result.getOrderLineDetails().getTaxAmount());
	}

	@Test
	public void shouldFailForNullEntry()
	{
		assertThrows(NullPointerException.class, () -> converter.convert(null));
	}

	@Test
	public void shouldReturnNullForEntryWithoutProduct()
	{
		when(entry.getProduct()).thenReturn(null);
		assertNull(converter.convert(entry));
	}

	@Test
	public void shouldHandleNegativeBasePrice()
	{
		when(entry.getProduct()).thenReturn(product);
		when(product.getCode()).thenReturn("SKU-NEG");
		when(product.getName()).thenReturn("Discount Item");
		when(entry.getQuantity()).thenReturn(1L);
		when(entry.getBasePrice()).thenReturn(-5.0);
		when(entry.getTaxValues()).thenReturn(Collections.emptyList());

		final CartItemInput result = converter.convert(entry);

		assertNotNull(result);
		assertEquals(Long.valueOf(-500L), result.getOrderLineDetails().getProductPrice());
	}

	@Test
	public void shouldHandleNullQuantity()
	{
		when(entry.getProduct()).thenReturn(product);
		when(product.getCode()).thenReturn("SKU-NQ");
		when(product.getName()).thenReturn("Null Qty");
		when(entry.getQuantity()).thenReturn(null);
		when(entry.getBasePrice()).thenReturn(10.0);
		when(entry.getTaxValues()).thenReturn(Collections.emptyList());

		final CartItemInput result = converter.convert(entry);

		assertNotNull(result);
		assertEquals(Long.valueOf(0L), result.getOrderLineDetails().getQuantity());
	}
}
