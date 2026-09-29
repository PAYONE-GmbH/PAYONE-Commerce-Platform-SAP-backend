package com.payone.pcp.webhook.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payone.pcp.webhook.constants.PayonepcpwebhookConstants;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.PayoneWebhookEventProcessor;
import com.payone.pcp.webhook.service.PayoneWebhookEventService;
import com.payone.pcp.webhook.service.PayoneWebhookPayloadParser;
import com.payone.pcp.webhook.service.PayoneWebhookSignatureService;
import com.payone.pcp.webhook.service.data.PayoneWebhookIntake;
import com.payone.pcp.webhook.service.data.PayoneWebhookIntake.Disposition;
import com.payone.pcp.webhook.service.data.PayoneWebhookReceipt;
import com.payone.pcp.webhook.service.data.PayoneWebhookReceipt.Outcome;

import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.servicelayer.session.SessionExecutionBody;
import de.hybris.platform.servicelayer.session.SessionService;
import de.hybris.platform.servicelayer.user.UserService;

import java.nio.charset.StandardCharsets;

import org.apache.commons.configuration2.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * receive() parses, verifies, then runs the write half as the configured technical user.
 * SessionService is stubbed to invoke the passed SessionExecutionBody synchronously so the
 * write half runs inline and its outcome can be asserted without a real Hybris session.
 */
@ExtendWith(MockitoExtension.class)
class DefaultPayoneWebhookReceiverServiceUnitTest
{
	private static final byte[] RAW_BODY = "{\"id\":\"event-1\",\"type\":\"payment.captured\",\"merchantId\":\"P1_TestMerchant\"}"
			.getBytes(StandardCharsets.UTF_8);

	@Mock
	private PayoneWebhookSignatureService payoneWebhookSignatureService;

	@Mock
	private PayoneWebhookPayloadParser payoneWebhookPayloadParser;

	@Mock
	private PayoneWebhookEventService payoneWebhookEventService;

	@Mock
	private PayoneWebhookEventProcessor payoneWebhookEventProcessor;

	@Mock
	private SessionService sessionService;

	@Mock
	private UserService userService;

	@Mock
	private ConfigurationService configurationService;

	@Mock
	private Configuration configuration;

	@Mock
	private UserModel processUser;

	private DefaultPayoneWebhookReceiverService receiverService;

	@BeforeEach
	void setUp()
	{
		receiverService = new DefaultPayoneWebhookReceiverService();
		receiverService.setPayoneWebhookSignatureService(payoneWebhookSignatureService);
		receiverService.setPayoneWebhookPayloadParser(payoneWebhookPayloadParser);
		receiverService.setPayoneWebhookEventService(payoneWebhookEventService);
		receiverService.setPayoneWebhookEventProcessor(payoneWebhookEventProcessor);
		receiverService.setSessionService(sessionService);
		receiverService.setUserService(userService);
		receiverService.setConfigurationService(configurationService);

		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setId("event-1");
		envelope.setType("payment.captured");
		envelope.setMerchantId("P1_TestMerchant");
		lenient().when(payoneWebhookPayloadParser.parse(any())).thenReturn(envelope);

		lenient().when(configurationService.getConfiguration()).thenReturn(configuration);
		lenient().when(configuration.getString(PayonepcpwebhookConstants.PROCESS_USER,
				PayonepcpwebhookConstants.DEFAULT_PROCESS_USER)).thenReturn(PayonepcpwebhookConstants.DEFAULT_PROCESS_USER);
		lenient().when(userService.getUserForUID(PayonepcpwebhookConstants.DEFAULT_PROCESS_USER)).thenReturn(processUser);

		lenient().when(sessionService.executeInLocalView(any(SessionExecutionBody.class), any(UserModel.class)))
				.thenAnswer(invocation -> {
					final SessionExecutionBody body = invocation.getArgument(0);
					return body.execute();
				});
	}

	private PayoneWebhookIntake givenIntake(final Disposition disposition, final PayoneWebhookEventModel event)
	{
		final PayoneWebhookIntake intake = new PayoneWebhookIntake(event, disposition);
		when(payoneWebhookEventService.intake(any(), any())).thenReturn(intake);
		return intake;
	}

	// receive() outcomes

	@Test
	void ignoresAReplayOfAnAlreadyProcessedEvent()
	{
		givenIntake(Disposition.ALREADY_PROCESSED, new PayoneWebhookEventModel());

		final PayoneWebhookReceipt receipt = receiverService.receive(RAW_BODY, "key-id", "signature");

		assertThat(receipt.getOutcome()).isEqualTo(Outcome.REPLAY_IGNORED);
		verify(payoneWebhookEventProcessor, never()).process(any(), any());
	}

	@Test
	void returnsStoredUnprocessedWhenTheProcessorDeclines()
	{
		final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
		givenIntake(Disposition.NEW, event);
		when(payoneWebhookEventProcessor.process(any(), eq(event))).thenReturn(false);

		final PayoneWebhookReceipt receipt = receiverService.receive(RAW_BODY, "key-id", "signature");

		assertThat(receipt.getOutcome()).isEqualTo(Outcome.STORED_UNPROCESSED);
		verify(payoneWebhookEventService, never()).markProcessed(any());
	}

	@Test
	void marksProcessedAndReturnsProcessedWhenTheProcessorSucceeds()
	{
		final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
		givenIntake(Disposition.NEW, event);
		when(payoneWebhookEventProcessor.process(any(), eq(event))).thenReturn(true);

		final PayoneWebhookReceipt receipt = receiverService.receive(RAW_BODY, "key-id", "signature");

		assertThat(receipt.getOutcome()).isEqualTo(Outcome.PROCESSED);
		verify(payoneWebhookEventService).markProcessed(event);
	}

	@Test
	void marksFailedAndRethrowsWhenTheProcessorThrows()
	{
		final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
		givenIntake(Disposition.NEW, event);
		final RuntimeException failure = new RuntimeException("handler blew up");
		when(payoneWebhookEventProcessor.process(any(), eq(event))).thenThrow(failure);

		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(() -> receiverService.receive(RAW_BODY, "key-id", "signature"))
				.isSameAs(failure);

		verify(payoneWebhookEventService).markFailed(event, failure);
	}

	// resolveProcessUser()

	@Test
	void propagatesAnUnknownProcessUserRatherThanFallingBackToAdmin()
	{
		final UnknownIdentifierException unknownUser = new UnknownIdentifierException("no such user");
		when(userService.getUserForUID(PayonepcpwebhookConstants.DEFAULT_PROCESS_USER)).thenThrow(unknownUser);

		assertThatExceptionOfType(UnknownIdentifierException.class)
				.isThrownBy(() -> receiverService.receive(RAW_BODY, "key-id", "signature"))
				.isSameAs(unknownUser);
	}
}
