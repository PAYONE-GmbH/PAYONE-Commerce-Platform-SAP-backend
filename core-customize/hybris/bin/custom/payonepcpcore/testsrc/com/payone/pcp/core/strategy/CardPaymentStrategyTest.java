package com.payone.pcp.core.strategy;

import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.commerce.platform.lib.models.CapturePaymentRequest;
import com.payone.commerce.platform.lib.models.RefundRequest;
import com.payone.commerce.platform.lib.models.StatusValue;
import com.payone.pcp.core.converters.PaymentExecutionRequestConverter;
import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;
import com.payone.pcp.core.service.PayonePaymentExecutionService;
import com.payone.pcp.core.service.PayoneTransactionService;
import com.payone.pcp.core.strategy.impl.VisaCardStrategy;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


@UnitTest
public class CardPaymentStrategyTest {
    private VisaCardStrategy strategy;
    private PayonePaymentExecutionService executionService;
    private PayoneTransactionService transactionService;
    private CartModel order;
    private PayoneConfigurationModel config;
    private PayonePaymentInfoModel paymentInfo;
    private CurrencyModel currency;

    @Before
    public void setUp() {
        strategy = new VisaCardStrategy();
        executionService = mock(PayonePaymentExecutionService.class);
        transactionService = mock(PayoneTransactionService.class);
        strategy.setPayonePaymentExecutionService(executionService);
        strategy.setPayoneTransactionService(transactionService);
        strategy.setPaymentExecutionRequestConverter(mock(PaymentExecutionRequestConverter.class));
        currency = mock(CurrencyModel.class);
        when(currency.getIsocode()).thenReturn("EUR");
        order = mock(CartModel.class);
        when(order.getTotalPrice()).thenReturn(100.00);
        when(order.getCurrency()).thenReturn(currency);
        config = mock(PayoneConfigurationModel.class);
        paymentInfo = mock(PayonePaymentInfoModel.class);
    }

    @Test
    public void shouldExecuteAuthorizeAndCreateTransactionEntry() {
        final PaymentExecutionResponse response = new PaymentExecutionResponse("paymentId-1", "execId-1", StatusValue.CAPTURED, null);
        final PaymentTransactionModel transaction = mock(PaymentTransactionModel.class);
        when(executionService.executePayment(any(), any(), any(), any())).thenReturn(response);
        when(transactionService.getOrCreatePaymentTransaction(any(), any(), any())).thenReturn(transaction);

        final PaymentExecutionResponse result = strategy.authorize(config, "commerceCase-1", "checkout-1", order, paymentInfo);

        assertSame(response, result);
        verify(transactionService).createPaymentTransactionEntry(transaction, "execId-1", order,
                StatusValue.CAPTURED, 10000L, currency, PaymentTransactionType.AUTHORIZATION);
    }

    @Test
    public void shouldNotCreateEntryWhenOrderIsNull() {
        when(executionService.executePayment(any(), any(), any(), any()))
                .thenReturn(new PaymentExecutionResponse("paymentId-1", "execId-1", StatusValue.CAPTURED, null));

        strategy.authorize(config, "commerceCase-1", "checkout-1", null, paymentInfo);

        verifyNoInteractions(transactionService);
    }

    @Test
    public void shouldSkipAuthorizationEntryWhenResponseHasNoExecutionId() {
        final PaymentTransactionModel transaction = mock(PaymentTransactionModel.class);
        when(executionService.executePayment(any(), any(), any(), any()))
                .thenReturn(new PaymentExecutionResponse(null, null, StatusValue.CAPTURED, null));
        when(transactionService.getOrCreatePaymentTransaction(any(), any(), any())).thenReturn(transaction);

        strategy.authorize(config, "commerceCase-1", "checkout-1", order, paymentInfo);

        verify(transactionService).getOrCreatePaymentTransaction(order, "commerceCase-1", "checkout-1");
        verify(transactionService, never()).createPaymentTransactionEntry(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void shouldCaptureWithEmptyRequest() {
        when(executionService.capturePayment(any(), any(), any(), any(), any()))
                .thenReturn(new PaymentExecutionResponse("paymentId", null, StatusValue.CAPTURED, null));

        assertNotNull(strategy.capture(config, "commerceCase-1", "checkout-1", "execId-1", null));
        final ArgumentCaptor<CapturePaymentRequest> request = ArgumentCaptor.forClass(CapturePaymentRequest.class);
        verify(executionService).capturePayment(eq(config), eq("commerceCase-1"), eq("checkout-1"), eq("execId-1"), request.capture());
        assertNull(request.getValue().getAmount());
        // The strategy sends an empty request, but the SDK's CapturePaymentRequest
        // constructor defaults isFinal to false - so it is never null here.
        assertFalse(request.getValue().getIsFinal());
        verifyNoInteractions(transactionService);
    }

    @Test
    public void shouldDelegateCancelAndRefundWithEmptyRequests() {
        when(executionService.cancelPayment(any(), any(), any(), any(), any()))
                .thenReturn(new PaymentExecutionResponse("paymentId", null, StatusValue.CANCELLED, null));
        when(executionService.refundPayment(any(), any(), any(), any(), any()))
                .thenReturn(new PaymentExecutionResponse("paymentId", null, StatusValue.REFUNDED, null));

        assertNotNull(strategy.cancel(config, "commerceCase-1", "checkout-1", "execId-1"));
        assertNotNull(strategy.refund(config, "commerceCase-1", "checkout-1", "execId-1",
                new AmountOfMoney().amount(1999L).currencyCode("EUR")));
        verifyNoInteractions(transactionService);
    }

    @Test
    public void shouldSendExplicitAmountAsFinalCapture() {
        when(executionService.capturePayment(any(), any(), any(), any(), any()))
                .thenReturn(new PaymentExecutionResponse("paymentId", null, StatusValue.CAPTURE_REQUESTED, null));

        strategy.capture(config, "commerceCase-1", "checkout-1", "execId-1", 1500L);

        final ArgumentCaptor<CapturePaymentRequest> request = ArgumentCaptor.forClass(CapturePaymentRequest.class);
        verify(executionService).capturePayment(eq(config), eq("commerceCase-1"), eq("checkout-1"), eq("execId-1"),
                request.capture());
        assertEquals(Long.valueOf(1500L), request.getValue().getAmount());
        assertTrue(request.getValue().getIsFinal());
    }

    @Test
    public void shouldSendAmountAndCurrencyOnRefund() {
        when(executionService.refundPayment(any(), any(), any(), any(), any()))
                .thenReturn(new PaymentExecutionResponse("paymentId", null, StatusValue.REFUND_REQUESTED, null));

        strategy.refund(config, "commerceCase-1", "checkout-1", "execId-1",
                new AmountOfMoney().amount(1999L).currencyCode("EUR"));

        final ArgumentCaptor<RefundRequest> request = ArgumentCaptor.forClass(RefundRequest.class);
        verify(executionService).refundPayment(eq(config), eq("commerceCase-1"), eq("checkout-1"), eq("execId-1"),
                request.capture());
        assertEquals(Long.valueOf(1999L), request.getValue().getAmountOfMoney().getAmount());
        assertEquals("EUR", request.getValue().getAmountOfMoney().getCurrencyCode());
    }
}
