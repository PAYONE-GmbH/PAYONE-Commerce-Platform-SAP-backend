package com.payone.pcp.webhook.handler.impl;

import com.payone.commerce.platform.lib.models.StatusValue;
import com.payone.pcp.core.enums.PayonePaymentStatus;
import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.core.service.PayoneTransactionService;
import com.payone.pcp.webhook.dto.PayoneWebhookAmountOfMoneyDto;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookDomain;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.exception.PayoneWebhookNotSupportedException;
import com.payone.pcp.webhook.handler.PayoneWebhookEventHandler;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.payload.PayoneWebhookPayloadUtil;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Common, idempotent implementation for webhook domains which represent a
 * payment transaction transition.
 */
public abstract class AbstractPayoneTransactionWebhookHandler implements PayoneWebhookEventHandler
{
	private static final Logger LOG = LoggerFactory.getLogger(AbstractPayoneTransactionWebhookHandler.class);

	private ModelService modelService;
	private PayoneTransactionService payoneTransactionService;
    private CommonI18NService commonI18NService;

	protected abstract PayoneWebhookDomain getDomain();

	@Override
	public boolean supports(final PayoneWebhookEventType eventType)
	{
		return eventType != null && eventType.getDomain() == getDomain();
	}

	@Override
	public void handle(final PayoneWebhookEventDto envelope, final PayoneWebhookEventType eventType,
			final PayoneWebhookTarget target, final PayoneWebhookEventModel event)
	{
		final PayonePaymentStatus payoneStatus = eventType.getStatus()
                .orElseThrow(() -> notImplementedYet(eventType, "There is no local status mapping"));
		final StatusValue transactionStatus;
		try
		{
			transactionStatus = StatusValue.fromValue(payoneStatus.getCode());
		}
		catch (final IllegalArgumentException exception)
		{
            throw notImplementedYet(eventType, "There is no PCP SDK transaction status mapping");
		}

		if (!target.hasCheckout())
		{
			LOG.info("[PAYONE] Webhook eventId={} type={} has no local checkout; transaction status was not applied",
					envelope.getId(), eventType.getCode());
			return;
		}

		final PayoneCheckoutModel checkout = target.getCheckout();
        final PayoneWebhookAmountOfMoneyDto amountOfMoney = PayoneWebhookPayloadUtil
                .getAmount(envelope, eventType).orElse(null);

        backfillCheckoutIds(envelope, checkout);
		updateCheckoutStatus(envelope, eventType, checkout, payoneStatus);

		final AbstractOrderModel order = resolveOrder(target);
		if (order == null)
		{
			LOG.info("[PAYONE] Webhook eventId={} type={} resolved checkout {} without an order; "
						+ "transaction status was not applied", envelope.getId(), eventType.getCode(), checkout.getCheckoutId());
			return;
		}

		final PayoneCommerceCaseModel commerceCase = target.getCommerceCase();
		final String commerceCaseId = commerceCase == null ? null : commerceCase.getCommerceCaseId();
		final PaymentTransactionModel transaction = getPayoneTransactionService().getOrCreatePaymentTransaction(
				order, commerceCaseId, checkout.getCheckoutId());

        recordPcpIds(envelope, transaction, checkout);

		getPayoneTransactionService().createPaymentTransactionEntry(
				transaction,
				resolveRequestId(envelope),
				order,
				transactionStatus,
                resolveAmount(amountOfMoney, checkout, envelope, eventType),
                resolveCurrency(amountOfMoney, order),
				resolveTransactionType(eventType));
	}

	private void updateCheckoutStatus(final PayoneWebhookEventDto envelope, final PayoneWebhookEventType eventType,
			final PayoneCheckoutModel checkout, final PayonePaymentStatus newStatus)
	{
		if (Objects.equals(checkout.getStatus(), newStatus))
		{
			return;
		}
		checkout.setStatus(newStatus);
		getModelService().save(checkout);
		LOG.info("[PAYONE] Webhook eventId={} type={}: checkout {} status updated to {}",
				envelope.getId(), eventType.getCode(), checkout.getCheckoutId(), newStatus.getCode());
	}

    /**
     * Fills in the PCP ids the checkout does not have yet.
     */
    private void backfillCheckoutIds(final PayoneWebhookEventDto envelope, final PayoneCheckoutModel checkout) {
        boolean dirty = false;

        if (StringUtils.isBlank(checkout.getPaymentId())) {
            final String paymentId = PayoneWebhookPayloadUtil.paymentIdOf(envelope, getDomain()).orElse(null);
            if (paymentId != null) {
                checkout.setPaymentId(paymentId);
                dirty = true;
            }
        }

        if (StringUtils.isBlank(checkout.getPaymentExecutionId())) {
            final String executionId = PayoneWebhookPayloadUtil.getPaymentExecutionId(envelope, getDomain()).orElse(null);
            if (executionId != null) {
                checkout.setPaymentExecutionId(executionId);
                dirty = true;
            }
        }

        if (dirty) {
            getModelService().save(checkout);
            LOG.debug("[PAYONE] Webhook eventId={}: backfilled checkout {} ids from the envelope",
                    envelope.getId(), checkout.getCheckoutId());
        }
    }

    /**
     * Records the PCP ids that later capture/refund/cancel calls send back to PCP.
     *
     */
    private void recordPcpIds(final PayoneWebhookEventDto envelope, final PaymentTransactionModel transaction,
                              final PayoneCheckoutModel checkout) {
        boolean dirty = false;

        if (StringUtils.isBlank(transaction.getPayonePaymentId())) {
            final String paymentId = PayoneWebhookPayloadUtil.paymentIdOf(envelope, getDomain())
                    .orElseGet(checkout::getPaymentId);
            if (StringUtils.isNotBlank(paymentId)) {
                transaction.setPayonePaymentId(paymentId);
                dirty = true;
            }
        }

        if (StringUtils.isBlank(transaction.getPayonePaymentExecutionId())) {
            final String executionId = PayoneWebhookPayloadUtil.getPaymentExecutionId(envelope, getDomain())
                    .orElseGet(checkout::getPaymentExecutionId);
            if (StringUtils.isNotBlank(executionId)) {
                transaction.setPayonePaymentExecutionId(executionId);
                dirty = true;
            }
        }

        if (dirty) {
            getModelService().save(transaction);
        }
    }

    /**
     * The order the transition applies to: the resolved checkout's own, or —
     * when the case holds several checkouts and this one was created before the
     * order existed — a sibling checkout's.
     */
	private AbstractOrderModel resolveOrder(final PayoneWebhookTarget target)
	{
		if (target.getCheckout().getOrder() != null) {
            return target.getCheckout().getOrder();
        }

        if (!target.hasCommerceCase() || target.getCommerceCase().getCheckouts() == null) {
            return null;
        }

        return target.getCommerceCase().getCheckouts().stream()
                .map(PayoneCheckoutModel::getOrder)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * The PCP operation id this transition belongs to. Falls back to the webhook
     * event id only for an envelope that carries no id for its own domain, where
	 * a per-delivery key is still better than none.
     */
    private String resolveRequestId(final PayoneWebhookEventDto envelope) {
        return PayoneWebhookPayloadUtil.resolveOperationId(envelope, getDomain())
                .orElseGet(() -> {
                    LOG.warn("[PAYONE] Webhook eventId={} carries no {} id; falling back to the event id as the "
                                    + "transaction requestId, which will not deduplicate against other deliveries",
                            envelope.getId(), getDomain().getEnvelopeMember());
                    return envelope.getId();
                });
    }

    /**
     * The amount in minor units, as PCP reported it for this transition.
     * {@code PayoneCheckout.amount} is the fallback for an envelope with no
     * amountOfMoney; it is the authorised total, so it is only correct for a
     * full-value movement.
     */
    private Long resolveAmount(final PayoneWebhookAmountOfMoneyDto amountOfMoney, final PayoneCheckoutModel checkout,
                               final PayoneWebhookEventDto envelope, final PayoneWebhookEventType eventType) {
        if (amountOfMoney != null && amountOfMoney.getAmount() != null) {
            return amountOfMoney.getAmount();
        }

        LOG.warn("[PAYONE] Webhook eventId={} type={} carries no amountOfMoney; falling back to the checkout's "
                        + "authorised total ({}). A partial movement would be recorded at full value.",
                envelope.getId(), eventType.getCode(), checkout.getAmount());
        return checkout.getAmount();
    }

    /**
     * The currency PCP reported, resolved against the platform's currencies.
     * Falls back to the order's currency when absent or unknown here — a
     * mismatch is a data problem to investigate, not a reason to drop the entry.
     */
    private CurrencyModel resolveCurrency(final PayoneWebhookAmountOfMoneyDto amountOfMoney,
                                          final AbstractOrderModel order) {
        final String isoCode = amountOfMoney == null ? null : amountOfMoney.getCurrencyCode();
        if (StringUtils.isBlank(isoCode)) {
            return order.getCurrency();
        }

        return lookUpCurrency(isoCode).orElseGet(() -> {
            LOG.warn("[PAYONE] Webhook reported currency '{}', which is not a currency of this installation; "
                            + "using the order's {} instead", isoCode,
                    order.getCurrency() == null ? "<none>" : order.getCurrency().getIsocode());
            return order.getCurrency();
        });
    }

    private Optional<CurrencyModel> lookUpCurrency(final String isoCode) {
        try {
            return Optional.ofNullable(getCommonI18NService().getCurrency(isoCode));
        } catch (final RuntimeException e) {
            // getCurrency throws UnknownIdentifierException for an unconfigured
            // iso code; caught broadly so no lookup fault can fail the delivery.
			return Optional.empty();
		}
	}

	private PaymentTransactionType resolveTransactionType(final PayoneWebhookEventType eventType)
	{
		switch (eventType)
		{
			case PAYMENT_CAPTURE_REQUESTED:
			case PAYMENT_CAPTURED:
			case PAYMENT_REJECTED_CAPTURE:
			case PAYMENT_EXECUTION_PAYMENT_CAPTURED:
			case PAYMENT_INFORMATION_PAYMENT_CAPTURED:
				return PaymentTransactionType.CAPTURE;

			case REFUND_REFUND_REQUESTED:
			case REFUND_CREATED:
			case REFUND_PENDING_APPROVAL:
			case REFUND_REJECTED:
			case REFUND_CAPTURED:
			case REFUND_REFUNDED:
			case REFUND_CANCELLED:
			case PAYMENT_REFUNDED:
			case PAYMENT_REJECTED_REFUND:
			case PAYMENT_EXECUTION_PAYMENT_REFUNDED:
			case PAYMENT_INFORMATION_PAYMENT_REFUNDED:
			case PAYMENT_ACCOUNT_CREDITED:
				return PaymentTransactionType.REFUND_FOLLOW_ON;

			case PAYMENT_CANCELLED:
			case PAYMENT_CANCELLATION_REQUESTED:
			case PAYMENT_REVERSED:
			case PAYMENT_EXECUTION_PAYMENT_REVERSED:
			case PAYMENT_INFORMATION_PAYMENT_REVERSED:
                return PaymentTransactionType.CANCEL;

            default:
                return PaymentTransactionType.AUTHORIZATION;
        }
    }


    private PayoneWebhookNotSupportedException notImplementedYet(final PayoneWebhookEventType eventType, final String reason)
	{
		return new PayoneWebhookNotSupportedException("Webhook type " + eventType.getCode() + " " + reason
				+ "; the merchant must define how this status affects the order and transaction");
	}

	public ModelService getModelService()
	{
		return modelService;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public PayoneTransactionService getPayoneTransactionService()
	{
		return payoneTransactionService;
	}

	public void setPayoneTransactionService(final PayoneTransactionService payoneTransactionService) {
        this.payoneTransactionService = payoneTransactionService;
    }

    public CommonI18NService getCommonI18NService() {
        return commonI18NService;
    }

    public void setCommonI18NService(final CommonI18NService commonI18NService)
	{
		this.commonI18NService = commonI18NService;
	}
}
