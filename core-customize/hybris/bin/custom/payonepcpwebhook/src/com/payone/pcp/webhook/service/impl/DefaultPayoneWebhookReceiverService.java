package com.payone.pcp.webhook.service.impl;

import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.DEFAULT_PROCESS_USER;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.PROCESS_USER;

import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.PayoneWebhookEventProcessor;
import com.payone.pcp.webhook.service.PayoneWebhookEventService;
import com.payone.pcp.webhook.service.PayoneWebhookPayloadParser;
import com.payone.pcp.webhook.service.PayoneWebhookReceiverService;
import com.payone.pcp.webhook.service.PayoneWebhookSignatureService;
import com.payone.pcp.webhook.service.data.PayoneWebhookIntake;
import com.payone.pcp.webhook.service.data.PayoneWebhookReceipt;
import com.payone.pcp.webhook.service.data.PayoneWebhookReceipt.Outcome;

import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.servicelayer.session.SessionExecutionBody;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.servicelayer.user.UserService;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link PayoneWebhookReceiverService}. Orchestrates: decode + parse, verify HMAC over the
 * raw bytes, switch to the technical user, dedup + store, process, mark processed / count the
 * attempt. Store happens before process so a crash mid-processing loses no event. Parsing runs
 * before verification because the MAC key is selected by the envelope's own merchantId, but
 * nothing is persisted, resolved or applied until {@code verify()} returns. Only the write half
 * runs as the technical user, since this endpoint is unauthenticated and writing as anonymous
 * would be a dishonest audit trail.
 */
public class DefaultPayoneWebhookReceiverService implements PayoneWebhookReceiverService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneWebhookReceiverService.class);

	private PayoneWebhookSignatureService payoneWebhookSignatureService;
	private PayoneWebhookPayloadParser payoneWebhookPayloadParser;
	private PayoneWebhookEventService payoneWebhookEventService;
	private PayoneWebhookEventProcessor payoneWebhookEventProcessor;
	private SessionService sessionService;
	private UserService userService;
	private ConfigurationService configurationService;

	@Override
	public PayoneWebhookReceipt receive(final byte[] rawBody, final String keyId, final String signature)
	{

		// 1. decode + parse (same bytes the MAC covered; PCP sends UTF-8 JSON, RFC 8259)
		final String rawPayload = new String(rawBody, StandardCharsets.UTF_8);
		final PayoneWebhookEventDto envelope = getPayoneWebhookPayloadParser().parse(rawPayload);

		// 2. verify — the trust boundary
		getPayoneWebhookSignatureService().verify(rawBody, keyId, signature, envelope.getMerchantId());

		// 3-6. the write half, under the technical user
		return getSessionService().executeInLocalView(new SessionExecutionBody()
		{
			@Override
			public Object execute()
			{
				return handleVerified(envelope, rawPayload);
			}
		}, resolveProcessUser());
	}

	/** Dedup, store, process and record. Runs inside the local view, as the technical user. */
	private PayoneWebhookReceipt handleVerified(final PayoneWebhookEventDto envelope, final String rawPayload)
	{
		final PayoneWebhookIntake intake = getPayoneWebhookEventService().intake(envelope, rawPayload);
		final PayoneWebhookEventModel event = intake.getEvent();

		if (!intake.shouldProcess())
		{
			return new PayoneWebhookReceipt(envelope.getId(), Outcome.REPLAY_IGNORED);
		}

		try
		{
			if (!getPayoneWebhookEventProcessor().process(envelope, event))
			{
				// Not actionable by this build; already stored and logged by the processor.
				return new PayoneWebhookReceipt(envelope.getId(), Outcome.STORED_UNPROCESSED);
			}

			getPayoneWebhookEventService().markProcessed(event);
			return new PayoneWebhookReceipt(envelope.getId(), Outcome.PROCESSED);
		}
		catch (final RuntimeException e)
		{
			// Count the attempt and rethrow, so PAYONE redelivers instead of getting a 204 for a failed event.
			getPayoneWebhookEventService().markFailed(event, e);
			throw e;
		}
	}

	/**
	 * Resolves the user the write half runs as. A configured-but-missing user is
	 * fatal on purpose: falling back to admin would let a typo in
	 * {@code payonepcpwebhook.process.user} silently grant full admin rights.
	 */
	private UserModel resolveProcessUser()
	{
		final String uid = getConfigurationService().getConfiguration().getString(PROCESS_USER, DEFAULT_PROCESS_USER);
		try
		{
			return getUserService().getUserForUID(uid);
		}
		catch (final UnknownIdentifierException e)
		{
			LOG.error("[PAYONE] Webhook processing user '{}' does not exist. Fix {} — webhooks cannot be persisted "
					+ "until it resolves.", uid, PROCESS_USER);
			throw e;
		}
	}

	public PayoneWebhookSignatureService getPayoneWebhookSignatureService()
	{
		return payoneWebhookSignatureService;
	}

	public void setPayoneWebhookSignatureService(final PayoneWebhookSignatureService payoneWebhookSignatureService)
	{
		this.payoneWebhookSignatureService = payoneWebhookSignatureService;
	}

	public PayoneWebhookPayloadParser getPayoneWebhookPayloadParser()
	{
		return payoneWebhookPayloadParser;
	}

	public void setPayoneWebhookPayloadParser(final PayoneWebhookPayloadParser payoneWebhookPayloadParser)
	{
		this.payoneWebhookPayloadParser = payoneWebhookPayloadParser;
	}

	public PayoneWebhookEventService getPayoneWebhookEventService()
	{
		return payoneWebhookEventService;
	}

	public void setPayoneWebhookEventService(final PayoneWebhookEventService payoneWebhookEventService)
	{
		this.payoneWebhookEventService = payoneWebhookEventService;
	}

	public PayoneWebhookEventProcessor getPayoneWebhookEventProcessor()
	{
		return payoneWebhookEventProcessor;
	}

	public void setPayoneWebhookEventProcessor(final PayoneWebhookEventProcessor payoneWebhookEventProcessor)
	{
		this.payoneWebhookEventProcessor = payoneWebhookEventProcessor;
	}

	public SessionService getSessionService()
	{
		return sessionService;
	}

	public void setSessionService(final SessionService sessionService)
	{
		this.sessionService = sessionService;
	}

	public UserService getUserService()
	{
		return userService;
	}

	public void setUserService(final UserService userService)
	{
		this.userService = userService;
	}

	public ConfigurationService getConfigurationService()
	{
		return configurationService;
	}

	public void setConfigurationService(final ConfigurationService configurationService)
	{
		this.configurationService = configurationService;
	}
}
