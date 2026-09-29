package com.payone.pcp.core.converters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.PaymentExecutionRequest;
import com.payone.pcp.core.converters.paymentmethod.*;
import de.hybris.bootstrap.annotations.UnitTest;

import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.payment.PaymentModeModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.order.PaymentModeService;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;

import com.payone.pcp.core.enums.PayoneAuthorizationModeEnum;
import com.payone.pcp.core.enums.PaymentFamily;
import com.payone.pcp.core.model.PayonePaymentInfoModel;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayoneMandateModel;

import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class PaymentExecutionRequestConverterTest {
    @Mock
    private AbstractOrderModel order;

    @Mock
    private CurrencyModel currency;

    @Mock
    private AbstractOrderEntryModel entry;

    @Mock
    private ProductModel product;

    @Mock
    private PayonePaymentInfoModel paymentInfo;

    @Mock
    private PaymentModeService paymentModeService;

    private PaymentExecutionRequestConverter converter;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        converter = new PaymentExecutionRequestConverter(new PcpAmountOfMoneyConverter(), new PcpShoppingCartConverter(new PcpLineItemConverter(), new PcpAmountOfMoneyConverter()), new PcpReferencesConverter(), new PcpCardPaymentMethodSpecificInputBuilder(), new PcpRedirectPaymentMethodSpecificInputBuilder(), new PcpSepaDirectDebitPaymentMethodSpecificInputBuilder(), new PcpMobilePaymentMethodSpecificInputBuilder(), new PcpFinancingPaymentMethodSpecificInputBuilder(), paymentModeService);
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    public void shouldConvertWithCardPaymentMethod() {
        when(order.getCode()).thenReturn("ORDER-001");
        when(order.getTotalPrice()).thenReturn(99.99);
        when(order.getCurrency()).thenReturn(currency);
        when(currency.getIsocode()).thenReturn("EUR");
        when(order.getEntries()).thenReturn(Collections.singletonList(entry));
        when(entry.getProduct()).thenReturn(product);
        when(product.getCode()).thenReturn("SKU-001");
        when(product.getName()).thenReturn("Item");
        when(entry.getQuantity()).thenReturn(1L);
        when(entry.getBasePrice()).thenReturn(99.99);
        when(entry.getTaxValues()).thenReturn(Collections.emptyList());

        when(paymentInfo.getPaymentProcessingToken()).thenReturn("tok-abc-123");
        when(paymentInfo.getPaymentProductId()).thenReturn(1);
        stubPaymentFamily(1, PaymentFamily.CARD);

        // Card builder reads authorization mode off config; a raw PayoneConfigurationModel
        // returns null there and NPEs, so mock it with a default mode.
        final PayoneConfigurationModel config = mock(PayoneConfigurationModel.class);
        when(config.getDefaultAuthorizationMode()).thenReturn(PayoneAuthorizationModeEnum.SALE);

        final PaymentExecutionRequest result = converter.convert(order, paymentInfo, config);

        assertNotNull(result);
        assertNotNull(result.getPaymentExecutionSpecificInput());
        assertNotNull(result.getPaymentExecutionSpecificInput().getAmountOfMoney());
        assertEquals(Long.valueOf(9999L), result.getPaymentExecutionSpecificInput().getAmountOfMoney().getAmount());

        assertNotNull(result.getPaymentMethodSpecificInput());
        assertNotNull(result.getPaymentMethodSpecificInput().getCardPaymentMethodSpecificInput());
        assertEquals("tok-abc-123", result.getPaymentMethodSpecificInput().getCardPaymentMethodSpecificInput().getPaymentProcessingToken());
    }

    // Family dispatch: every card scheme -> CARD, PAYONE BNPL products -> FINANCING.
    // Resolved via PaymentModeService.getPaymentModeForCode(...).getPaymentFamily(),
    // backed by PaymentMode.paymentFamily from projectdata-paymentmodes.impex.

    private void setUpMinimalOrder() {
        when(order.getCode()).thenReturn("ORDER-001");
        when(order.getTotalPrice()).thenReturn(50.0);
        when(order.getCurrency()).thenReturn(currency);
        when(currency.getIsocode()).thenReturn("EUR");
        when(order.getEntries()).thenReturn(Collections.emptyList());
    }

    /** Stubs paymentModeService to resolve {@code productId} to {@code family}. */
    private void stubPaymentFamily(final int productId, final PaymentFamily family) {
        final PaymentModeModel paymentMode = mock(PaymentModeModel.class);
        when(paymentMode.getPaymentFamily()).thenReturn(family);
        when(paymentModeService.getPaymentModeForCode(String.valueOf(productId))).thenReturn(paymentMode);
    }

    /** Stubs paymentModeService to have no PaymentMode row for {@code productId}. */
    private void stubNoPaymentMode(final int productId) {
        when(paymentModeService.getPaymentModeForCode(String.valueOf(productId)))
                .thenThrow(new UnknownIdentifierException("PaymentMode with code '" + productId + "' not found!"));
    }

    @Test
    public void shouldRouteMastercardToCardFamily() {
        assertCardSchemeRoutesToCardFamily(3);
    }

    @Test
    public void shouldRouteAmexToCardFamily() {
        assertCardSchemeRoutesToCardFamily(2);
    }

    @Test
    public void shouldRouteDinersToCardFamily() {
        assertCardSchemeRoutesToCardFamily(132);
    }

    private void assertCardSchemeRoutesToCardFamily(final int productId) {
        setUpMinimalOrder();
        when(paymentInfo.getPaymentProcessingToken()).thenReturn("tok-scheme");
        when(paymentInfo.getPaymentProductId()).thenReturn(productId);
        stubPaymentFamily(productId, PaymentFamily.CARD);

        final PayoneConfigurationModel config = mock(PayoneConfigurationModel.class);
        when(config.getDefaultAuthorizationMode()).thenReturn(PayoneAuthorizationModeEnum.SALE);

        final PaymentExecutionRequest result =
                converter.convert(order, paymentInfo, config);

        assertNotNull(result.getPaymentMethodSpecificInput());
        assertNotNull("Card scheme " + productId + " must dispatch to the CARD family",
                result.getPaymentMethodSpecificInput().getCardPaymentMethodSpecificInput());
        assertEquals(Integer.valueOf(productId),
                result.getPaymentMethodSpecificInput().getCardPaymentMethodSpecificInput().getPaymentProductId().getValue());
    }

    @Test
    public void shouldRouteSepaDirectDebitToSepaFamily() {
        setUpMinimalOrder();
        when(paymentInfo.getPaymentProductId()).thenReturn(771);
        final PayoneMandateModel mandate = mock(PayoneMandateModel.class);
        when(mandate.getIban()).thenReturn("DE89370400440532013000");
        when(mandate.getUniqueMandateReference()).thenReturn("MANDATE-001");
        when(paymentInfo.getMandate()).thenReturn(mandate);
        stubPaymentFamily(771, PaymentFamily.SEPA);

        final PaymentExecutionRequest result =
                converter.convert(order, paymentInfo, mock(PayoneConfigurationModel.class));

        assertNotNull(result.getPaymentMethodSpecificInput());
        assertNotNull("Product 771 must dispatch to the SEPA family",
                result.getPaymentMethodSpecificInput().getSepaDirectDebitPaymentMethodSpecificInput());
        assertEquals(Integer.valueOf(771),
                result.getPaymentMethodSpecificInput().getSepaDirectDebitPaymentMethodSpecificInput()
                        .getPaymentProductId().getValue());
    }

    /**
     * paymentChannel is required on PaymentMethodSpecificInput (docs.commerce.payone.com/api-reference).
     * Omitting it made PCP reject every payment execution with HTTP 500 on CCv2 test.
     */
    @Test
    public void shouldSetPaymentChannelOnMethodSpecificInput() {
        setUpMinimalOrder();
        when(paymentInfo.getPaymentProductId()).thenReturn(771);
        final PayoneMandateModel mandate = mock(PayoneMandateModel.class);
        when(mandate.getIban()).thenReturn("DE89370400440532013000");
        when(mandate.getUniqueMandateReference()).thenReturn("MANDATE-001");
        when(paymentInfo.getMandate()).thenReturn(mandate);
        stubPaymentFamily(771, PaymentFamily.SEPA);

        final PaymentExecutionRequest result =
                converter.convert(order, paymentInfo, mock(PayoneConfigurationModel.class));

        assertEquals(com.payone.commerce.platform.lib.models.PaymentChannel.ECOMMERCE,
                result.getPaymentMethodSpecificInput().getPaymentChannel());
    }

    @Test
    public void shouldRouteSecuredInvoiceToFinancingFamily() {
        assertFinancingProductRoutesToFinancingFamily(3390);
    }

    @Test
    public void shouldRouteSecuredDirectDebitToFinancingFamily() {
        assertFinancingProductRoutesToFinancingFamily(3392);
    }

    private void assertFinancingProductRoutesToFinancingFamily(final int productId) {
        setUpMinimalOrder();
        when(paymentInfo.getPaymentProcessingToken()).thenReturn("tok-bnpl");
        when(paymentInfo.getPaymentProductId()).thenReturn(productId);
        stubPaymentFamily(productId, PaymentFamily.FINANCING);

        // Secured Direct Debit (3392) requires a mandate: the builder attaches
        // paymentProduct3392SpecificInput.bankAccountInformation from it and requires it
        // non-null for this product id. Secured Invoice (3390) doesn't read it.
        if (productId == 3392) {
            final PayoneMandateModel mandate = mock(PayoneMandateModel.class);
            when(mandate.getIban()).thenReturn("DE89370400440532013000");
            when(mandate.getAccountHolder()).thenReturn("Max Mustermann");
            when(paymentInfo.getMandate()).thenReturn(mandate);
        }

        // The financing input builder requires a non-null returnUrl.
        final PaymentExecutionRequest result =
                converter.convert(order, paymentInfo, configWithReturnUrl());

        assertNotNull(result.getPaymentMethodSpecificInput());
        assertNotNull("Financing product " + productId + " must dispatch to the FINANCING family",
                result.getPaymentMethodSpecificInput().getFinancingPaymentMethodSpecificInput());
        assertEquals(Integer.valueOf(productId),
                result.getPaymentMethodSpecificInput().getFinancingPaymentMethodSpecificInput().getPaymentProductId());
    }

    /**
     * Product 3391 (Secured Installment) is not documented by current PCP
     * payment-methods docs and has no PaymentMode row - it must yield no
     * method-specific input rather than throwing or falling back to
     * FINANCING.
     */
    @Test
    public void shouldNotBuildMethodInputForUndocumentedProduct3391() {
        setUpMinimalOrder();
        when(paymentInfo.getPaymentProcessingToken()).thenReturn("tok-undocumented");
        when(paymentInfo.getPaymentProductId()).thenReturn(3391);
        stubNoPaymentMode(3391);

        final PaymentExecutionRequest result =
                converter.convert(order, paymentInfo, configWithReturnUrl());

        assertNull("Undocumented product 3391 must not produce a method-specific input",
                result.getPaymentMethodSpecificInput());
    }

    /** An unrecognized product ID is handled safely - no method-specific input, no exception. */
    @Test
    public void shouldNotBuildMethodInputForUnknownProductId() {
        setUpMinimalOrder();
        when(paymentInfo.getPaymentProcessingToken()).thenReturn("tok-unknown");
        when(paymentInfo.getPaymentProductId()).thenReturn(99999);
        stubNoPaymentMode(99999);

        final PaymentExecutionRequest result =
                converter.convert(order, paymentInfo, configWithReturnUrl());

        assertNull("Unknown product id must not produce a method-specific input",
                result.getPaymentMethodSpecificInput());
    }


    private PayoneConfigurationModel configWithReturnUrl() {
        final PayoneConfigurationModel config = mock(PayoneConfigurationModel.class);
        when(config.getReturnUrl()).thenReturn("https://shop.com/return");
        return config;
    }

    @Test
    public void shouldFailForNullOrder() {
        assertThrows(NullPointerException.class, () -> converter.convert(null, null, null));
    }

    @Test
    public void shouldHandleNullPaymentInfo() {
        when(order.getCode()).thenReturn("ORDER-001");
        when(order.getTotalPrice()).thenReturn(50.0);
        when(order.getCurrency()).thenReturn(currency);
        when(currency.getIsocode()).thenReturn("USD");
        when(order.getEntries()).thenReturn(Collections.emptyList());

        final PaymentExecutionRequest result = converter.convert(order, null, mock(PayoneConfigurationModel.class));

        assertNotNull(result);
        assertNotNull(result.getPaymentExecutionSpecificInput());
        assertNull(result.getPaymentMethodSpecificInput());
    }
}
