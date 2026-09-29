package com.payone.pcp.core.strategy.impl;

import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/** Shared base for scheme-specific card strategies; records a PaymentTransactionEntry on authorize. */
public abstract class CardPaymentStrategy extends AbstractPaymentStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(CardPaymentStrategy.class);

    @Override
    public PaymentExecutionResponse authorize(final PayoneConfigurationModel config,
            final String commerceCaseId, final String checkoutId,
            final AbstractOrderModel order, final PayonePaymentInfoModel paymentInfo) {
        final PaymentExecutionResponse response = doAuthorize(config, commerceCaseId,
                checkoutId, order, paymentInfo);
        if (response != null && order != null) {
            createAuthorizationEntry(response, order, commerceCaseId, checkoutId);
        }
        return response;
    }

    private void createAuthorizationEntry(final PaymentExecutionResponse response,
            final AbstractOrderModel order, final String commerceCaseId,
            final String checkoutId) {
        final PaymentTransactionModel transaction = getTransactionService()
                .getOrCreatePaymentTransaction(order, commerceCaseId, checkoutId);
        final String requestId = response.getPaymentExecutionId();
        if (requestId == null) {
            LOG.warn("Cannot create PaymentTransactionEntry for type [{}]: response lacks paymentExecutionId",
                    PaymentTransactionType.AUTHORIZATION);
            return;
        }
        getTransactionService().createPaymentTransactionEntry(transaction, requestId, order,
                response.getStatus(), amountInCents(order), order.getCurrency(),
                PaymentTransactionType.AUTHORIZATION);
    }

    private static Long amountInCents(final AbstractOrderModel order) {
        if (order == null || order.getTotalPrice() == null) {
            return 0L;
        }
        return BigDecimal.valueOf(order.getTotalPrice()).movePointRight(2).longValue();
    }
}
