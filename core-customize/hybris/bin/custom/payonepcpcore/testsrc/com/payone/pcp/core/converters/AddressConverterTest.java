package com.payone.pcp.core.converters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.Address;
import com.payone.commerce.platform.lib.models.AddressPersonal;
import de.hybris.bootstrap.annotations.UnitTest;

import de.hybris.platform.core.model.c2l.CountryModel;
import de.hybris.platform.core.model.c2l.RegionModel;
import de.hybris.platform.core.model.user.AddressModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class AddressConverterTest {
    @Mock
    private AddressModel address;

    @Mock
    private CountryModel country;

    @Mock
    private RegionModel region;

    private PcpAddressConverter converter;
    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        converter = new PcpAddressConverter();
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void shouldConvertFullAddress() {
        when(address.getStreetname()).thenReturn("Main Street");
        when(address.getStreetnumber()).thenReturn("42");
        when(address.getTown()).thenReturn("Berlin");
        when(address.getPostalcode()).thenReturn("10115");
        when(address.getCountry()).thenReturn(country);
        when(country.getIsocode()).thenReturn("DE");
        when(address.getRegion()).thenReturn(region);
        when(region.getIsocodeShort()).thenReturn("BE");

        final Address result = converter.toAddress(address);

        assertNotNull(result);
        assertEquals("Main Street", result.getStreet());
        assertEquals("42", result.getHouseNumber());
        assertEquals("Berlin", result.getCity());
        assertEquals("10115", result.getZip());
        assertEquals("DE", result.getCountryCode());
        assertEquals("BE", result.getState());
    }

    @Test
    public void shouldConvertAddressWithBlankStreetnumberToNullHouseNumber() {
        when(address.getStreetname()).thenReturn("Oak Avenue");
        when(address.getStreetnumber()).thenReturn("  ");
        when(address.getTown()).thenReturn("Munich");
        when(address.getPostalcode()).thenReturn("80331");
        when(address.getCountry()).thenReturn(country);
        when(country.getIsocode()).thenReturn("DE");

        final Address result = converter.toAddress(address);

        assertNotNull(result);
        assertNull(result.getHouseNumber());
    }

    @Test
    public void shouldConvertAddressWithoutRegion() {
        when(address.getStreetname()).thenReturn("Oak Avenue 10");
        when(address.getTown()).thenReturn("Munich");
        when(address.getPostalcode()).thenReturn("80331");
        when(address.getCountry()).thenReturn(country);
        when(country.getIsocode()).thenReturn("DE");
        when(address.getRegion()).thenReturn(null);

        final Address result = converter.toAddress(address);

        assertNotNull(result);
        assertEquals("Munich", result.getCity());
        assertNull(result.getState());
    }

    @Test
    public void shouldThrowForNullAddress() {
        assertThrows(NullPointerException.class, () -> converter.toAddress(null));
    }

    @Test
    public void shouldConvertAddressPersonal() {
        when(address.getStreetname()).thenReturn("High Street");
        when(address.getStreetnumber()).thenReturn("7");
        when(address.getTown()).thenReturn("Hamburg");
        when(address.getPostalcode()).thenReturn("20095");
        when(address.getCountry()).thenReturn(country);
        when(country.getIsocode()).thenReturn("DE");

        final AddressPersonal result = converter.toAddressPersonal(address, "Jane", "Doe");

        assertNotNull(result);
        assertEquals("High Street", result.getStreet());
        assertEquals("7", result.getHouseNumber());
        assertEquals("Hamburg", result.getCity());
        assertEquals("20095", result.getZip());
        assertEquals("DE", result.getCountryCode());
        assertNotNull(result.getName());
        assertEquals("Jane", result.getName().getFirstName());
        assertEquals("Doe", result.getName().getSurname());
    }

    @Test
    public void shouldConvertAddressPersonalWithNullNames() {
        when(address.getStreetname()).thenReturn("Broadway 1");
        when(address.getTown()).thenReturn("New York");
        when(address.getPostalcode()).thenReturn("10001");
        when(address.getCountry()).thenReturn(country);
        when(country.getIsocode()).thenReturn("US");

        final AddressPersonal result = converter.toAddressPersonal(address, null, null);

        assertNotNull(result);
        assertEquals("Broadway 1", result.getStreet());
        assertNotNull(result.getName());
        assertNull(result.getName().getFirstName());
        assertNull(result.getName().getSurname());
    }

    @Test
    public void shouldThrowForNullAddressPersonal() {
        assertThrows(NullPointerException.class, () -> converter.toAddressPersonal(null, "First", "Last"));
    }
}
