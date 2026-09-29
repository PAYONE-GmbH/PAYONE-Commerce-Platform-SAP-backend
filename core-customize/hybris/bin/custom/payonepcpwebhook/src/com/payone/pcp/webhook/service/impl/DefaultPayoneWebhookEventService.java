package com.payone.pcp.webhook.service.impl;

import com.payone.pcp.webhook.dao.PayoneWebhookEventDao;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.PayoneWebhookEventService;
import com.payone.pcp.webhook.service.data.PayoneWebhookIntake;
import com.payone.pcp.webhook.service.data.PayoneWebhookIntake.Disposition;

import de.hybris.platform.servicelayer.exceptions.ModelSavingException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link PayoneWebhookEventService}. Dedup is check-then-insert, and PAYONE can deliver
 * the same eventId concurrently, so both callers can see "not seen yet" before either inserts.
 * {@code PayoneWebhookEvent.eventId} has a unique index, so the losing insert's
 * {@link ModelSavingException} is caught and re-resolved against the now-committed row rather than
 * propagated as a 500; any other persistence fault is rethrown.
 */
public class DefaultPayoneWebhookEventService implements PayoneWebhookEventService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneWebhookEventService.class);

	private ModelService modelService;
	private TimeService timeService;
	private PayoneWebhookEventDao payoneWebhookEventDao;

	@Override
	public PayoneWebhookIntake intake(final PayoneWebhookEventDto envelope, final String rawPayload)
	{
		final String eventId = envelope.getId();

		final Optional<PayoneWebhookEventModel> existing = getPayoneWebhookEventDao().findEventByEventId(eventId);
		if (existing.isPresent())
		{
			return dispositionOf(existing.get());
		}

		try
		{
			return new PayoneWebhookIntake(store(envelope, rawPayload), Disposition.NEW);
		}
		catch (final ModelSavingException e)
		{
			// Re-resolve to tell a lost insert race apart from a genuine failure.
			final Optional<PayoneWebhookEventModel> raced = getPayoneWebhookEventDao().findEventByEventId(eventId);
			if (raced.isEmpty())
			{
				throw e;
			}
			LOG.info("[PAYONE] Webhook eventId={} lost the insert race to a concurrent delivery; "
					+ "deferring to the stored record", eventId);
			return dispositionOf(raced.get());
		}
	}

	/** Classifies an already-stored event: RETRY if stored but never completed, ALREADY_PROCESSED otherwise. */
	private PayoneWebhookIntake dispositionOf(final PayoneWebhookEventModel event)
	{
		if (Boolean.TRUE.equals(event.getProcessed()))
		{
			LOG.info("[PAYONE] Webhook eventId={} type={} already processed at {}; acknowledging replay without reprocessing",
					event.getEventId(), event.getEventType(), event.getProcessedTime());
			return new PayoneWebhookIntake(event, Disposition.ALREADY_PROCESSED);
		}

		LOG.info("[PAYONE] Webhook eventId={} type={} was received but not processed ({} previous attempt(s)); retrying",
				event.getEventId(), event.getEventType(), event.getAttempts());
		return new PayoneWebhookIntake(event, Disposition.RETRY);
	}

	/** Persists a first-time event. {@code rawPayload} is stored verbatim; re-serialising would break signature re-verification. */
	private PayoneWebhookEventModel store(final PayoneWebhookEventDto envelope, final String rawPayload)
	{
		final PayoneWebhookEventModel event = getModelService().create(PayoneWebhookEventModel.class);
		event.setEventId(envelope.getId());
		event.setEventType(envelope.getType());
		event.setMerchantId(envelope.getMerchantId());
		event.setPayload(rawPayload);
		event.setProcessed(Boolean.FALSE);
		event.setAttempts(Integer.valueOf(0));
		event.setReceivedTime(getTimeService().getCurrentTime());

		getModelService().save(event);

		LOG.debug("[PAYONE] Webhook eventId={} type={} stored ({} payload chars)",
				envelope.getId(), envelope.getType(), rawPayload == null ? 0 : rawPayload.length());
		return event;
	}

	@Override
	public void markProcessed(final PayoneWebhookEventModel event)
	{
		event.setProcessed(Boolean.TRUE);
		event.setProcessedTime(getTimeService().getCurrentTime());
		event.setAttempts(Integer.valueOf(attemptsOf(event) + 1));
		getModelService().save(event);

		LOG.debug("[PAYONE] Webhook eventId={} type={} processed on attempt {}",
				event.getEventId(), event.getEventType(), event.getAttempts());
	}

	@Override
	public void markFailed(final PayoneWebhookEventModel event, final Exception cause)
	{
		final int attempt = attemptsOf(event) + 1;
		LOG.error("[PAYONE] Webhook eventId={} type={} failed on attempt {}; left unprocessed for redelivery",
				event.getEventId(), event.getEventType(), attempt, cause);

		try
		{
			event.setAttempts(Integer.valueOf(attempt));
			getModelService().save(event);
		}
		catch (final RuntimeException bookkeepingFailure)
		{
			// Swallowed so it doesn't mask `cause`, which the caller is already handling.
			LOG.error("[PAYONE] Could not record the failed attempt for webhook eventId={}", event.getEventId(),
					bookkeepingFailure);
		}
	}

	/** Null-safe read of {@code attempts}. */
	private int attemptsOf(final PayoneWebhookEventModel event)
	{
		final Integer attempts = event.getAttempts();
		return attempts == null ? 0 : attempts.intValue();
	}

	public ModelService getModelService()
	{
		return modelService;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public TimeService getTimeService()
	{
		return timeService;
	}

	public void setTimeService(final TimeService timeService)
	{
		this.timeService = timeService;
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
