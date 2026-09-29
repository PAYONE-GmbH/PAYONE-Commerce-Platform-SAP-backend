package com.payone.pcp.core.strategy;

import com.payone.commerce.platform.lib.models.CancelPaymentRequest;
import com.payone.commerce.platform.lib.models.CapturePaymentRequest;
import com.payone.commerce.platform.lib.models.RefundRequest;
import com.payone.commerce.platform.lib.models.StatusValue;
import com.payone.pcp.core.converters.PaymentExecutionRequestConverter;
import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;
import com.payone.pcp.core.service.PayonePaymentExecutionService;
import com.payone.pcp.core.service.PayoneTransactionService;
import com.payone.pcp.core.strategy.impl.WeroStrategy;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.order.CartModel;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


@UnitTest
public class WeroStrategyTest {
    private WeroStrategy strategy;
    private PayonePaymentExecutionService executionService;
    private CartModel order;
    private PayoneConfigurationModel config;

    @Before
    public void setUp() {
        strategy = new WeroStrategy();
        executionService = mock(PayonePaymentExecutionService.class);
        strategy.setPayonePaymentExecutionService(executionService);
        strategy.setPayoneTransactionService(mock(PayoneTransactionService.class));
        strategy.setPaymentExecutionRequestConverter(mock(PaymentExecutionRequestConverter.class));
        order = mock(CartModel.class);
        config = mock(PayoneConfigurationModel.class);
    }

    @Test
    public void shouldHandleAuthorizeWithoutPaymentInfoGracefully() {
        when(executionService.executePayment(any(), any(), any(), any())).thenReturn(null);
        assertNull(strategy.authorize(config, "commerceCase-1", "checkout-1", order, null));
    }

    @Test
    public void shouldPropagateNonNullResponseFromService() {
        final PaymentExecutionResponse response = new PaymentExecutionResponse("paymentId-wero", "execId-wero", null, null);
        when(executionService.executePayment(any(), any(), any(), any())).thenReturn(response);
        assertSame(response, strategy.authorize(config, "commerceCase-1", "checkout-1", order, mock(PayonePaymentInfoModel.class)));
    }

    @Test
    public void shouldDelegateFollowOnOperationsWithEmptyRequests() {
        when(executionService.capturePayment(any(), any(), any(), any(), any())).thenReturn(new PaymentExecutionResponse("cap", null, StatusValue.CAPTURED, null));
        when(executionService.cancelPayment(any(), any(), any(), any(), any())).thenReturn(new PaymentExecutionResponse("cxl", null, StatusValue.CANCELLED, null));
        when(executionService.refundPayment(any(), any(), any(), any(), any())).thenReturn(new PaymentExecutionResponse("ref", null, StatusValue.REFUNDED, null));
        assertNotNull(strategy.capture(config, "commerceCase-1", "checkout-1", "exec-1", null));
        assertNotNull(strategy.cancel(config, "commerceCase-1", "checkout-1", "exec-1"));
        assertNotNull(strategy.refund(config, "commerceCase-1", "checkout-1", "exec-1",
                new com.payone.commerce.platform.lib.models.AmountOfMoney().amount(1000L).currencyCode("EUR")));
        verify(executionService).capturePayment(eq(config), eq("commerceCase-1"), eq("checkout-1"), eq("exec-1"), any(CapturePaymentRequest.class));
        verify(executionService).cancelPayment(eq(config), eq("commerceCase-1"), eq("checkout-1"), eq("exec-1"), any(CancelPaymentRequest.class));
        verify(executionService).refundPayment(eq(config), eq("commerceCase-1"), eq("checkout-1"), eq("exec-1"), any(RefundRequest.class));
    }
}
