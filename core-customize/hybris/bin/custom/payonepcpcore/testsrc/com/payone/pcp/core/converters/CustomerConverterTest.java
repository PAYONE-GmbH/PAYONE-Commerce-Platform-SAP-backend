package com.payone.pcp.core.converters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.BusinessRelation;
import com.payone.commerce.platform.lib.models.Customer;
import de.hybris.bootstrap.annotations.UnitTest;

import de.hybris.platform.commerceservices.enums.CustomerType;

import de.hybris.platform.core.model.c2l.CountryModel;
import de.hybris.platform.core.model.c2l.LanguageModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.core.model.user.CustomerModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class CustomerConverterTest {
    @Mock
    private CustomerModel customer;

    @Mock
    private AddressModel paymentAddress;

    @Mock
    private AddressModel billingAddress;

    @Mock
    private CountryModel country;

    @Mock
    private LanguageModel language;

    private PcpCustomerConverter converter;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        converter = new PcpCustomerConverter(new PcpAddressConverter());
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void shouldConvertFullB2CCustomer() {
        when(customer.getUid()).thenReturn("john@example.com");
        when(customer.getDefaultPaymentAddress()).thenReturn(paymentAddress);
        when(paymentAddress.getFirstname()).thenReturn("John");
        when(paymentAddress.getLastname()).thenReturn("Doe");
        when(customer.getContactEmail()).thenReturn("john@example.com");
        when(customer.getType()).thenReturn(CustomerType.REGISTERED);
        when(customer.getSessionLanguage()).thenReturn(language);
        when(language.getIsocode()).thenReturn("en");

        when(billingAddress.getStreetname()).thenReturn("Billing St 1");
        when(billingAddress.getTown()).thenReturn("Berlin");
        when(billingAddress.getPostalcode()).thenReturn("10115");
        when(billingAddress.getCountry()).thenReturn(country);
        when(country.getIsocode()).thenReturn("DE");

        final Customer result = converter.convert(customer, billingAddress);

        assertNotNull(result);
        // merchantCustomerId stays unset: see PcpCustomerConverter, it triggers a PCP-side
        // rejection of real SEPA payments on preprod.
        assertNull(result.getMerchantCustomerId());
        assertEquals("en", result.getLocale());
        assertEquals(BusinessRelation.B2C, result.getBusinessRelation());

        assertNotNull(result.getPersonalInformation());
        assertNotNull(result.getPersonalInformation().getName());
        assertEquals("John", result.getPersonalInformation().getName().getFirstName());
        assertEquals("Doe", result.getPersonalInformation().getName().getSurname());

        assertNotNull(result.getContactDetails());
        assertEquals("john@example.com", result.getContactDetails().getEmailAddress());

        assertNotNull(result.getBillingAddress());
        assertEquals("Billing St 1", result.getBillingAddress().getStreet());
    }

    @Test
    public void shouldConvertGuestCustomer() {
        when(customer.getUid()).thenReturn("biz@company.com");
        when(customer.getDefaultPaymentAddress()).thenReturn(paymentAddress);
        when(paymentAddress.getFirstname()).thenReturn("Jane");
        when(paymentAddress.getLastname()).thenReturn("Smith");
        when(customer.getContactEmail()).thenReturn("biz@company.com");
        when(customer.getType()).thenReturn(CustomerType.GUEST);

        final Customer result = converter.convert(customer, null);

        assertNotNull(result);
        assertEquals(BusinessRelation.B2C, result.getBusinessRelation());
    }

    @Test
    public void shouldUseUidAsFallbackEmail() {
        when(customer.getUid()).thenReturn("user@example.com");
        when(customer.getDefaultPaymentAddress()).thenReturn(paymentAddress);
        when(paymentAddress.getFirstname()).thenReturn("Sam");
        when(paymentAddress.getLastname()).thenReturn("Brown");
        when(customer.getContactEmail()).thenReturn(null);
        when(customer.getType()).thenReturn(CustomerType.REGISTERED);

        final Customer result = converter.convert(customer, null);

        assertNotNull(result);
        assertEquals("user@example.com", result.getContactDetails().getEmailAddress());
    }

    @Test
    public void shouldFailForNullCustomer() {
        assertThrows(NullPointerException.class, () -> converter.convert(null, null));
    }

    @Test
    public void shouldHandleCustomerWithNoPaymentAddress() {
        when(customer.getUid()).thenReturn("guest@shop.com");
        when(customer.getDefaultPaymentAddress()).thenReturn(null);
        when(customer.getContactEmail()).thenReturn("guest@shop.com");
        when(customer.getType()).thenReturn(CustomerType.REGISTERED);

        final Customer result = converter.convert(customer, null);

        assertNotNull(result);
        // merchantCustomerId stays unset - see PcpCustomerConverter.
        assertNull(result.getMerchantCustomerId());
        assertNull(result.getPersonalInformation());
    }
}
