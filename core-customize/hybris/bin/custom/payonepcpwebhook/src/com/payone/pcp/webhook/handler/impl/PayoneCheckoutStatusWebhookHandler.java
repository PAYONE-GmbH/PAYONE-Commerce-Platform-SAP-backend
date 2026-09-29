package com.payone.pcp.webhook.handler.impl;

import com.payone.pcp.core.enums.PayonePaymentStatus;
import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookDomain;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.handler.PayoneWebhookEventHandler;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

import de.hybris.platform.servicelayer.model.ModelService;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies an event's mapped {@link PayonePaymentStatus} to the resolved
 * {@code PayoneCheckout}. Status comes from the event type, not the raw `status` string on
 * the envelope, so only reviewed values can land here. PCP gives no delivery ordering
 * guarantee, so a late event can overwrite a newer status; every transition is logged.
 */
public class PayoneCheckoutStatusWebhookHandler implements PayoneWebhookEventHandler
{
	private static final Logger LOG = LoggerFactory.getLogger(PayoneCheckoutStatusWebhookHandler.class);

	private ModelService modelService;

	@Override
	public boolean supports(final PayoneWebhookEventType eventType)
	{
		return eventType != null && eventType.getDomain() == PayoneWebhookDomain.CHECKOUT;
	}

	@Override
	public void handle(final PayoneWebhookEventDto envelope, final PayoneWebhookEventType eventType,
			final PayoneWebhookTarget target, final PayoneWebhookEventModel event)
	{
		if (!target.hasCheckout())
		{
			// Normal for POS events and cases owned elsewhere — see PayoneWebhookTarget.
			LOG.debug("[PAYONE] Webhook eventId={} type={} has no local checkout; no status applied",
					envelope.getId(), eventType.getCode());
			return;
		}

		final Optional<PayonePaymentStatus> mapped = eventType.getStatus();
		if (mapped.isEmpty())
		{
			return;
		}

		final PayoneCheckoutModel checkout = target.getCheckout();
		final PayonePaymentStatus newStatus = mapped.get();
		final PayonePaymentStatus currentStatus = checkout.getStatus();

		if (Objects.equals(currentStatus, newStatus))
		{
			LOG.debug("[PAYONE] Webhook eventId={} type={}: checkout {} already {}; no write",
					envelope.getId(), eventType.getCode(), checkout.getCheckoutId(), newStatus.getCode());
			return;
		}

		checkout.setStatus(newStatus);
		getModelService().save(checkout);

		LOG.info("[PAYONE] Webhook eventId={} type={}: checkout {} status {} -> {}",
				envelope.getId(), eventType.getCode(), checkout.getCheckoutId(),
				currentStatus == null ? "<unset>" : currentStatus.getCode(), newStatus.getCode());
	}

	public ModelService getModelService()
	{
		return modelService;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}
}
