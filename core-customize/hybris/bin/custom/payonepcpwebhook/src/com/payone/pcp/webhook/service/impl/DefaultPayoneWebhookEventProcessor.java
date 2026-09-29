package com.payone.pcp.webhook.service.impl;

import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.DEFAULT_SUPPORTED_API_VERSIONS;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.SUPPORTED_API_VERSIONS;

import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.exception.PayoneWebhookNotSupportedException;
import com.payone.pcp.webhook.handler.PayoneWebhookEventHandler;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.PayoneWebhookEventProcessor;
import com.payone.pcp.webhook.service.PayoneWebhookTargetResolver;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.model.ModelService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link PayoneWebhookEventProcessor}. Dispatches a stored, verified event to the
 * registered handlers. An unsupported apiVersion, an unrecognised event type, or a handler
 * declining on policy grounds all leave the event stored with {@code processed = false} and
 * acknowledged 2XX, so it becomes a replayable backlog item instead of a rejection.
 */
public class DefaultPayoneWebhookEventProcessor implements PayoneWebhookEventProcessor
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneWebhookEventProcessor.class);

	private ConfigurationService configurationService;
	private ModelService modelService;
	private PayoneWebhookTargetResolver payoneWebhookTargetResolver;
	private List<PayoneWebhookEventHandler> handlers = Collections.emptyList();

	@Override
	public boolean process(final PayoneWebhookEventDto envelope, final PayoneWebhookEventModel event)
	{
		if (!isApiVersionSupported(envelope.getApiVersion()))
		{
			LOG.warn("[PAYONE] Webhook eventId={} type={} carries unsupported apiVersion '{}' (supported: {}); "
							+ "stored but not processed. Widen {} once this build understands it.",
					envelope.getId(), envelope.getType(), envelope.getApiVersion(), supportedApiVersions(), SUPPORTED_API_VERSIONS);
			return false;
		}

		final Optional<PayoneWebhookEventType> resolvedType = PayoneWebhookEventType.fromCode(envelope.getType());
		if (resolvedType.isEmpty())
		{
			LOG.warn("[PAYONE] Webhook eventId={} carries unrecognised event type '{}'; stored but not processed. "
							+ "Add it to PayoneWebhookEventType if PAYONE has extended the catalogue.",
					envelope.getId(), envelope.getType());
			return false;
		}

		final PayoneWebhookEventType eventType = resolvedType.get();
		final PayoneWebhookTarget target = getPayoneWebhookTargetResolver().resolve(envelope, eventType);

		linkTarget(event, target);
		reportUnmappedStatus(eventType, envelope);
		return dispatch(envelope, eventType, target, event);
	}

	private void linkTarget(final PayoneWebhookEventModel event, final PayoneWebhookTarget target)
	{
		boolean dirty = false;

		if (target.hasCommerceCase() && !Objects.equals(event.getCommerceCase(), target.getCommerceCase()))
		{
			event.setCommerceCase(target.getCommerceCase());
			dirty = true;
		}

		if (target.hasCheckout() && !Objects.equals(event.getPayoneCheckout(), target.getCheckout()))
		{
			event.setPayoneCheckout(target.getCheckout());
			dirty = true;
		}

		if (dirty)
		{
			getModelService().save(event);
			final PayoneCommerceCaseModel commerceCase = target.getCommerceCase();
			LOG.debug("[PAYONE] Webhook eventId={} linked to commerce case {} / checkout {}", event.getEventId(),
					commerceCase == null ? "<none>" : commerceCase.getCommerceCaseId(),
					target.hasCheckout() ? target.getCheckout().getCheckoutId() : "<none>");
		}
	}

	/** Warns when a recognised event type has no {@code PayonePaymentStatus} counterpart, so the gap stays visible. */
	private void reportUnmappedStatus(final PayoneWebhookEventType eventType, final PayoneWebhookEventDto envelope)
	{
		if (eventType.getStatus().isEmpty())
		{
			LOG.warn("[PAYONE] Webhook eventId={} type={} has no PayonePaymentStatus counterpart; "
							+ "local status left unchanged. Extend the PayonePaymentStatus enum in payonepcpcore to act on it.",
					envelope.getId(), eventType.getCode());
		}
	}

	/**
	 * Runs every handler that claims the event, in configured order. A handler that fails is not
	 * isolated: the exception propagates and the event is left for redelivery, since handlers must
	 * be idempotent and replay is safer than marking a half-applied event processed. A handler
	 * declining on policy grounds stops the dispatch instead, since a later handler acting while an
	 * earlier one refused would leave exactly that half-applied state.
	 *
	 * @return whether the event was fully applied
	 */
	private boolean dispatch(final PayoneWebhookEventDto envelope, final PayoneWebhookEventType eventType,
			final PayoneWebhookTarget target, final PayoneWebhookEventModel event)
	{
		int handled = 0;
		for (final PayoneWebhookEventHandler handler : getHandlers())
		{
			if (!handler.supports(eventType))
			{
				continue;
			}

			try
			{
				handler.handle(envelope, eventType, target, event);
			}
			catch (final PayoneWebhookNotSupportedException e)
			{
				LOG.warn("[PAYONE] Webhook eventId={} type={} declined by {}: {} Stored but not processed; replay it "
								+ "once the policy is defined.", envelope.getId(), eventType.getCode(),
						handler.getClass().getSimpleName(), e.getMessage());
				return false;
			}
			handled++;
		}

		LOG.debug("[PAYONE] Webhook eventId={} type={} dispatched to {} of {} handler(s)",
				envelope.getId(), eventType.getCode(), handled, getHandlers().size());
		return true;
	}

	private boolean isApiVersionSupported(final String apiVersion)
	{
		if (StringUtils.isBlank(apiVersion))
		{
			// Absent, not a bad value; can't signal a future contract, so processing continues.
			LOG.warn("[PAYONE] Webhook carries no apiVersion; processing it as {}", supportedApiVersions());
			return true;
		}
		return supportedApiVersions().contains(apiVersion.trim().toLowerCase(Locale.ROOT));
	}

	/** Supported {@code apiVersion} values, lower-cased for comparison. */
	private Set<String> supportedApiVersions()
	{
		final String configured = getConfigurationService().getConfiguration()
				.getString(SUPPORTED_API_VERSIONS, DEFAULT_SUPPORTED_API_VERSIONS);

		return Arrays.stream(StringUtils.split(configured, ','))
				.map(String::trim)
				.filter(StringUtils::isNotBlank)
				.map(version -> version.toLowerCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
	}

	public ConfigurationService getConfigurationService()
	{
		return configurationService;
	}

	public void setConfigurationService(final ConfigurationService configurationService)
	{
		this.configurationService = configurationService;
	}

	public ModelService getModelService()
	{
		return modelService;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public PayoneWebhookTargetResolver getPayoneWebhookTargetResolver()
	{
		return payoneWebhookTargetResolver;
	}

	public void setPayoneWebhookTargetResolver(final PayoneWebhookTargetResolver payoneWebhookTargetResolver)
	{
		this.payoneWebhookTargetResolver = payoneWebhookTargetResolver;
	}

	public List<PayoneWebhookEventHandler> getHandlers()
	{
		return handlers;
	}

	public void setHandlers(final List<PayoneWebhookEventHandler> handlers)
	{
		this.handlers = handlers == null ? Collections.emptyList() : handlers;
	}
}
