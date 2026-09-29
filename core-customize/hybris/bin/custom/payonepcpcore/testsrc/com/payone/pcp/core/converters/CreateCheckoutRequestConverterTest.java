package com.payone.pcp.core.converters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.CreateCheckoutRequest;
import de.hybris.bootstrap.annotations.UnitTest;

import de.hybris.platform.core.model.c2l.CountryModel;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.core.model.user.CustomerModel;

import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class CreateCheckoutRequestConverterTest
{
	@Mock
	private AbstractOrderModel order;

	@Mock
	private CurrencyModel currency;

	@Mock
	private AbstractOrderEntryModel entry;

	@Mock
	private ProductModel product;

	@Mock
	private AddressModel deliveryAddress;

	@Mock
	private CustomerModel customer;

	@Mock
	private CountryModel country;

	private CreateCheckoutRequestConverter converter;

	private AutoCloseable mocks;

	@Before
	public void setUp() {
		mocks = MockitoAnnotations.openMocks(this);
		converter = new CreateCheckoutRequestConverter(
				new PcpAmountOfMoneyConverter(),
				new PcpShoppingCartConverter(new PcpLineItemConverter(), new PcpAmountOfMoneyConverter()),
				new PcpReferencesConverter(),
				new PcpAddressConverter());
	}

	@After
	public void tearDown() throws Exception {
		mocks.close();
	}

	@Test
	public void shouldConvertWithAllFields()
	{
		when(order.getCode()).thenReturn("ORDER-001");
		when(order.getTotalPrice()).thenReturn(100.0);
		when(order.getCurrency()).thenReturn(currency);
		when(currency.getIsocode()).thenReturn("EUR");
		when(order.getEntries()).thenReturn(Collections.singletonList(entry));
		when(entry.getProduct()).thenReturn(product);
		when(product.getCode()).thenReturn("SKU-001");
		when(product.getName()).thenReturn("Item");
		when(entry.getQuantity()).thenReturn(1L);
		when(entry.getBasePrice()).thenReturn(100.0);
		when(entry.getTaxValues()).thenReturn(Collections.emptyList());

		when(deliveryAddress.getStreetname()).thenReturn("Shipping St 5");
		when(deliveryAddress.getTown()).thenReturn("Hamburg");
		when(deliveryAddress.getPostalcode()).thenReturn("20095");
		when(deliveryAddress.getCountry()).thenReturn(country);
		when(country.getIsocode()).thenReturn("DE");

		when(customer.getDefaultPaymentAddress()).thenReturn(deliveryAddress);
		when(deliveryAddress.getFirstname()).thenReturn("Sam");
		when(deliveryAddress.getLastname()).thenReturn("Wilson");

		final CreateCheckoutRequest result =
				converter.convert(order, deliveryAddress, customer, "my-store");

		assertNotNull(result);
		assertEquals(Boolean.FALSE, result.getAutoExecuteOrder());
		assertNotNull(result.getAmountOfMoney());
		assertEquals(Long.valueOf(10000L), result.getAmountOfMoney().getAmount());
		assertEquals("EUR", result.getAmountOfMoney().getCurrencyCode());

		assertNotNull(result.getShoppingCart());
		assertEquals(1, result.getShoppingCart().getItems().size());

		assertNotNull(result.getReferences());
		assertEquals("ORDER-001", result.getReferences().getMerchantReference());
		assertEquals("my-store", result.getReferences().getMerchantShopReference());

		assertNotNull(result.getShipping());
		assertNotNull(result.getShipping().getAddress());
		assertEquals("Shipping St 5", result.getShipping().getAddress().getStreet());
		assertEquals("Sam", result.getShipping().getAddress().getName().getFirstName());
		assertEquals("Wilson", result.getShipping().getAddress().getName().getSurname());
	}

	@Test
	public void shouldFailForNullOrder()
	{
		assertThrows(NullPointerException.class, () -> converter.convert(null, null, null, null));
	}
}
