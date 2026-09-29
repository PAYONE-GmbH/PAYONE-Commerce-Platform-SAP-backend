package com.payone.pcp.core.converters.paymentmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.RedirectPaymentMethodSpecificInput;
import de.hybris.bootstrap.annotations.UnitTest;

import com.payone.pcp.core.model.PayonePaymentInfoModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class RedirectPaymentMethodSpecificInputBuilderTest {
    @Mock
    private PayonePaymentInfoModel paymentInfo;

    private PcpRedirectPaymentMethodSpecificInputBuilder builder;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        builder = new PcpRedirectPaymentMethodSpecificInputBuilder();
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void shouldBuildRedirectInputForPayPal() {
        when(paymentInfo.getPaymentProductId()).thenReturn(840);

        final RedirectPaymentMethodSpecificInput result = builder.build(paymentInfo, "https://shop.com/return");

        assertNotNull(result);
        assertFalse(result.getRequiresApproval());
        assertFalse(result.getTokenize());
        assertEquals(Integer.valueOf(840), result.getPaymentProductId().getValue());
        assertNotNull(result.getRedirectionData());
        assertEquals("https://shop.com/return", result.getRedirectionData().getReturnUrl());

        // PayPal (840) gets the REST-flow sub-input, per docs.commerce.payone.com/docs/payment-methods/paypal/paypal-rest.
        assertNotNull(result.getPaymentProduct840SpecificInput());
        assertFalse(result.getPaymentProduct840SpecificInput().getAddressSelectionAtPayPal());
        assertFalse(result.getPaymentProduct840SpecificInput().getJavaScriptSdkFlow());
        assertEquals(null, result.getPaymentProduct900SpecificInput());
    }

    @Test
    public void shouldBuildRedirectInputForWero() {
        when(paymentInfo.getPaymentProductId()).thenReturn(900);

        final RedirectPaymentMethodSpecificInput result = builder.build(paymentInfo, "https://shop.com/return");

        assertNotNull(result);
        assertFalse(result.getRequiresApproval());
        assertEquals(Integer.valueOf(900), result.getPaymentProductId().getValue());

        // Wero (900): no 840-specific sub-input, no 900 captureTrigger (doc:
        // "not yet supported" while requiresApproval=false).
        assertEquals(null, result.getPaymentProduct840SpecificInput());
        assertEquals(null, result.getPaymentProduct900SpecificInput());
    }

    @Test
    public void shouldFailForNullPaymentInfo() {
        assertThrows(NullPointerException.class, () -> builder.build(null, "https://shop.com/return"));
    }

    @Test
    public void shouldFailForNullReturnUrl() {
        assertThrows(NullPointerException.class, () -> builder.build(paymentInfo, null));
    }
}
