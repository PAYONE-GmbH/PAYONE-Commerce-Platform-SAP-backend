package com.payone.pcp.core.converters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.CreateCommerceCaseRequest;
import de.hybris.bootstrap.annotations.UnitTest;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.core.model.user.CustomerModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class CreateCommerceCaseRequestConverterTest {
    @Mock
    private AbstractOrderModel order;

    @Mock
    private CustomerModel customer;

    @Mock
    private AddressModel billingAddress;

    private CreateCommerceCaseRequestConverter converter;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        converter = new CreateCommerceCaseRequestConverter(new PcpCustomerConverter(new PcpAddressConverter()));
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void shouldConvertWithAllFields() {
        when(order.getCode()).thenReturn("ORDER-001");
        when(customer.getUid()).thenReturn("cust@test.com");

        final CreateCommerceCaseRequest result = converter.convert(order, customer, billingAddress);

        assertNotNull(result);
        assertEquals("ORDER-001", result.getMerchantReference());
        assertNotNull(result.getCustomer());
        // merchantCustomerId must stay unset: bisection against PCP preprod found it triggers
        // a PCP-side rejection of real SEPA payments.
        assertNull(result.getCustomer().getMerchantCustomerId());
    }

    @Test
    public void shouldFailForNullOrder() {
        assertThrows(NullPointerException.class, () -> converter.convert(null, customer, billingAddress));
    }

    @Test
    public void shouldFailForNullCustomer() {
		assertThrows(NullPointerException.class, () -> converter.convert(order, null, billingAddress));
    }

    @Test
    public void shouldUseGuidAsFallbackMerchantReference() {
        when(order.getCode()).thenReturn(null);
        when(order.getGuid()).thenReturn("guid-001");

        final CreateCommerceCaseRequest result = converter.convert(order, customer, null);

        assertNotNull(result);
        assertEquals("guid-001", result.getMerchantReference());
    }
}
