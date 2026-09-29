package com.payone.pcp.webhook.service.impl;

import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.webhook.dao.PayoneWebhookEventDao;
import com.payone.pcp.webhook.dto.PayoneWebhookCheckoutDto;
import com.payone.pcp.webhook.dto.PayoneWebhookCommerceCaseDto;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.dto.PayoneWebhookExecutionDto;
import com.payone.pcp.webhook.dto.PayoneWebhookStatusObjectDto;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.service.PayoneWebhookTargetResolver;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link PayoneWebhookTargetResolver}.
 */
public class DefaultPayoneWebhookTargetResolver implements PayoneWebhookTargetResolver
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneWebhookTargetResolver.class);

	private PayoneWebhookEventDao payoneWebhookEventDao;

	@Override
	public PayoneWebhookTarget resolve(final PayoneWebhookEventDto envelope, final PayoneWebhookEventType eventType)
	{
		final Optional<PayoneCheckoutModel> checkout = resolveCheckout(envelope, eventType);
		final Optional<PayoneCommerceCaseModel> commerceCase = resolveCommerceCase(envelope, checkout);

		if (checkout.isEmpty() && commerceCase.isEmpty())
		{
			LOG.info("[PAYONE] Webhook eventId={} type={} does not match any local checkout or commerce case; "
					+ "stored unlinked", envelope.getId(), envelope.getType());
			return PayoneWebhookTarget.NONE;
		}

		return new PayoneWebhookTarget(checkout.orElse(null), commerceCase.orElse(null));
	}

	private Optional<PayoneCheckoutModel> resolveCheckout(final PayoneWebhookEventDto envelope,
			final PayoneWebhookEventType eventType)
	{
		return resolveCheckoutByDomainId(envelope, eventType).or(() -> byCheckoutId(envelope.getCheckout()));
	}

	/**
	 * Finds the local checkout by the id specific to the event's domain, or empty
	 * when the domain has no such id or it matches nothing here.
	 */
	private Optional<PayoneCheckoutModel> resolveCheckoutByDomainId(final PayoneWebhookEventDto envelope,
                                                                    final PayoneWebhookEventType eventType)
	{
		switch (eventType.getDomain())
		{
			// A container-level event; the case is resolved directly below, and
			// the envelope's checkout member is left to the fallback above.
			case COMMERCE_CASE:
				return Optional.empty();

			case CHECKOUT:
				return byCheckoutId(envelope.getCheckout());

			// Only a payment id is available on a payment.* envelope.
			case PAYMENT:
				return byPaymentId(envelope.getPayment());

			// Refund and payout ids are payment-derived; see the class comment.
			case REFUND:
				return byPaymentDerivedId(envelope.getRefund());
			case PAYOUT:
				return byPaymentDerivedId(envelope.getPayout());

			case PAYMENT_EXECUTION:
				return byExecution(envelope.getPaymentExecution());
			case PAYMENT_INFORMATION:
				return byExecution(envelope.getPaymentInformation());

			default:
				return Optional.empty();
		}
	}

	/**
	 * Finds the local commerce case: the envelope's own {@code commerceCase}
	 * member when present (authoritative), otherwise the one the resolved
	 * checkout belongs to.
	 */
	private Optional<PayoneCommerceCaseModel> resolveCommerceCase(final PayoneWebhookEventDto envelope,
			final Optional<PayoneCheckoutModel> checkout)
	{
		final PayoneWebhookCommerceCaseDto dto = envelope.getCommerceCase();
		final Optional<PayoneCommerceCaseModel> fromEnvelope = dto == null
				? Optional.empty()
				: getPayoneWebhookEventDao().findCommerceCaseById(dto.getId());

		return fromEnvelope.or(() -> checkout.map(PayoneCheckoutModel::getCommerceCase));
	}

	private Optional<PayoneCheckoutModel> byCheckoutId(final PayoneWebhookCheckoutDto checkout)
	{
		return checkout == null ? Optional.empty() : getPayoneWebhookEventDao().findCheckoutById(checkout.getId());
	}

	private Optional<PayoneCheckoutModel> byPaymentId(final PayoneWebhookStatusObjectDto payment)
	{
		return payment == null ? Optional.empty() : getPayoneWebhookEventDao().findCheckoutByPaymentId(payment.getId());
	}

	/** Resolves a refund / payout id: tries it as delivered, then the part before the last '_'. */
	private Optional<PayoneCheckoutModel> byPaymentDerivedId(final PayoneWebhookStatusObjectDto statusObject)
	{
		if (statusObject == null || StringUtils.isBlank(statusObject.getId()))
		{
			return Optional.empty();
		}

		final String id = statusObject.getId();
		return getPayoneWebhookEventDao().findCheckoutByPaymentId(id)
				.or(() -> {
					final String base = StringUtils.substringBeforeLast(id, "_");
					return StringUtils.equals(base, id)
							? Optional.empty()
							: getPayoneWebhookEventDao().findCheckoutByPaymentId(base);
				});
	}

	/** Resolves by paymentExecutionId first, then by paymentId. */
	private Optional<PayoneCheckoutModel> byExecution(final PayoneWebhookExecutionDto execution)
	{
		if (execution == null)
		{
			return Optional.empty();
		}
		return getPayoneWebhookEventDao().findCheckoutByPaymentExecutionId(execution.getId())
				.or(() -> getPayoneWebhookEventDao().findCheckoutByPaymentId(execution.getPaymentId()));
	}

	public PayoneWebhookEventDao getPayoneWebhookEventDao()
	{
		return payoneWebhookEventDao;
	}

	public void setPayoneWebhookEventDao(final PayoneWebhookEventDao payoneWebhookEventDao)
	{
		this.payoneWebhookEventDao = payoneWebhookEventDao;
	}
}
