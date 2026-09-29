package com.payone.pcp.core.strategy.impl;

import com.payone.commerce.platform.lib.models.CancelPaymentRequest;
import com.payone.commerce.platform.lib.models.CapturePaymentRequest;
import com.payone.commerce.platform.lib.models.CompletePaymentRequest;
import com.payone.commerce.platform.lib.models.PaymentExecutionRequest;
import com.payone.commerce.platform.lib.models.RefundRequest;
import java.util.Objects;
import com.payone.commerce.platform.lib.models.PositiveAmountOfMoney;
import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.pcp.core.converters.PaymentExecutionRequestConverter;
import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.strategy.PayonePaymentStrategy;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;
import com.payone.pcp.core.service.PayonePaymentExecutionService;
import com.payone.pcp.core.service.PayoneTransactionService;

import de.hybris.platform.core.model.order.AbstractOrderModel;


/** Shared base for PCP payment strategies. */
public abstract class AbstractPaymentStrategy implements PayonePaymentStrategy {
    private PayonePaymentExecutionService payonePaymentExecutionService;
    private PayoneTransactionService payoneTransactionService;
    private PaymentExecutionRequestConverter paymentExecutionRequestConverter;

    private int paymentProductId;

    @Override
    public int getPaymentProductId() {
        return paymentProductId;
    }

    /**
     * Builds the method-specific request and executes it. Used as-is by SEPA, redirect, mobile
     * and financing strategies; {@code CardPaymentStrategy} overrides to also record a
     * PaymentTransactionEntry.
     */
    @Override
    public PaymentExecutionResponse authorize(final PayoneConfigurationModel config,
            final String commerceCaseId, final String checkoutId,
            final AbstractOrderModel order, final PayonePaymentInfoModel paymentInfo) {
        return doAuthorize(config, commerceCaseId, checkoutId, order, paymentInfo);
    }

    protected PaymentExecutionResponse doAuthorize(final PayoneConfigurationModel config,
            final String commerceCaseId, final String checkoutId,
            final AbstractOrderModel order, final PayonePaymentInfoModel paymentInfo) {
        final PaymentExecutionRequest request = buildPaymentExecutionRequest(order, paymentInfo, config);
        return getPaymentExecutionService().executePayment(config, commerceCaseId, checkoutId, request);
    }

    private PaymentExecutionRequest buildPaymentExecutionRequest(final AbstractOrderModel order,
            final PayonePaymentInfoModel paymentInfo, final PayoneConfigurationModel config) {
        return getRequestConverter().convert(order, paymentInfo, config);
    }

    @Override
    public PaymentExecutionResponse capture(final PayoneConfigurationModel config,
            final String commerceCaseId, final String checkoutId,
            final String paymentExecutionId, final Long amount) {
        final CapturePaymentRequest request = amount == null
                ? new CapturePaymentRequest()
                : new CapturePaymentRequest().amount(amount).isFinal(Boolean.TRUE);
        return payonePaymentExecutionService.capturePayment(config, commerceCaseId,
                checkoutId, paymentExecutionId, request);
    }

    @Override
    public PaymentExecutionResponse cancel(final PayoneConfigurationModel config,
            final String commerceCaseId, final String checkoutId,
            final String paymentExecutionId) {
        return payonePaymentExecutionService.cancelPayment(config, commerceCaseId,
                checkoutId, paymentExecutionId, new CancelPaymentRequest());
    }

    @Override
    public PaymentExecutionResponse refund(final PayoneConfigurationModel config,
            final String commerceCaseId, final String checkoutId,
            final String paymentExecutionId, final AmountOfMoney amountOfMoney) {
        Objects.requireNonNull(amountOfMoney, "amountOfMoney must not be null");
        final RefundRequest request = new RefundRequest()
                .amountOfMoney(new PositiveAmountOfMoney()
                        .amount(amountOfMoney.getAmount())
                        .currencyCode(amountOfMoney.getCurrencyCode()));
        return payonePaymentExecutionService.refundPayment(config, commerceCaseId,
                checkoutId, paymentExecutionId, request);
    }

    @Override
    public PaymentExecutionResponse complete(final PayoneConfigurationModel config,
            final String commerceCaseId, final String checkoutId,
            final String paymentExecutionId) {
        return payonePaymentExecutionService.completePayment(config, commerceCaseId,
                checkoutId, paymentExecutionId, new CompletePaymentRequest());
    }

    @Override
    public PaymentExecutionResponse pause(final PayoneConfigurationModel config,
            final String commerceCaseId, final String checkoutId,
            final String paymentExecutionId) {
        return payonePaymentExecutionService.pausePayment(config, commerceCaseId,
                checkoutId, paymentExecutionId);
    }

    @Override
    public PaymentExecutionResponse refresh(final PayoneConfigurationModel config,
            final String commerceCaseId, final String checkoutId,
            final String paymentExecutionId) {
        return payonePaymentExecutionService.refreshPayment(config, commerceCaseId,
                checkoutId, paymentExecutionId);
    }

    protected PayonePaymentExecutionService getPaymentExecutionService() {
        return payonePaymentExecutionService;
    }

    protected PayoneTransactionService getTransactionService() {
        return payoneTransactionService;
    }

    protected PaymentExecutionRequestConverter getRequestConverter() {
        return paymentExecutionRequestConverter;
    }

    public void setPayonePaymentExecutionService(final PayonePaymentExecutionService payonePaymentExecutionService) {
        this.payonePaymentExecutionService = payonePaymentExecutionService;
    }

    public void setPayoneTransactionService(final PayoneTransactionService payoneTransactionService) {
        this.payoneTransactionService = payoneTransactionService;
    }

    public void setPaymentExecutionRequestConverter(final PaymentExecutionRequestConverter paymentExecutionRequestConverter) {
        this.paymentExecutionRequestConverter = paymentExecutionRequestConverter;
    }

    public void setPaymentProductId(final int paymentProductId) {
        this.paymentProductId = paymentProductId;
    }
}
