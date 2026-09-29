package com.payone.pcp.webhook.service.impl;

import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.exception.PayoneWebhookNotSupportedException;
import com.payone.pcp.webhook.handler.PayoneWebhookEventHandler;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.PayoneWebhookTargetResolver;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.model.ModelService;
import org.apache.commons.configuration2.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.DEFAULT_SUPPORTED_API_VERSIONS;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.SUPPORTED_API_VERSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultPayoneWebhookEventProcessor}. process() gates "verified and
 * stored" from "acted on": an unsupported apiVersion or unrecognised event type must leave the
 * event unprocessed rather than throw, and dispatch must only reach handlers that claim the type.
 */
@ExtendWith(MockitoExtension.class)
class DefaultPayoneWebhookEventProcessorUnitTest
{
	@Mock
	private ConfigurationService configurationService;

	@Mock
	private Configuration configuration;

	@Mock
	private ModelService modelService;

	@Mock
	private PayoneWebhookTargetResolver payoneWebhookTargetResolver;

	@Mock
	private PayoneWebhookEventHandler handlerA;

	@Mock
	private PayoneWebhookEventHandler handlerB;

	private DefaultPayoneWebhookEventProcessor processor;

	@BeforeEach
	void setUp()
	{
		processor = new DefaultPayoneWebhookEventProcessor();
		processor.setConfigurationService(configurationService);
		processor.setModelService(modelService);
		processor.setPayoneWebhookTargetResolver(payoneWebhookTargetResolver);

		lenient().when(configurationService.getConfiguration()).thenReturn(configuration);
		lenient().when(configuration.getString(SUPPORTED_API_VERSIONS, DEFAULT_SUPPORTED_API_VERSIONS))
				.thenReturn(DEFAULT_SUPPORTED_API_VERSIONS);
	}

	private PayoneWebhookEventDto givenEnvelope(final String apiVersion, final String type)
	{
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setId("event-1");
		envelope.setApiVersion(apiVersion);
		envelope.setType(type);
		return envelope;
	}

	// apiVersion gate
	@Test
	void testProcess_whenApiVersionIsUnsupported_thenReturnsFalse()
	{
		final boolean processed = processor.process(givenEnvelope("v2", "payment.captured"), new PayoneWebhookEventModel());

		assertThat(processed).isFalse();
		verify(payoneWebhookTargetResolver, never()).resolve(any(), any());
	}

	@Test
	void testProcess_whenApiVersionIsBlank_thenTreatsItAsSupported()
	{
		processor.setHandlers(java.util.List.of());
		when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(PayoneWebhookTarget.NONE);

		final boolean processed = processor.process(givenEnvelope("   ", "payment.captured"), new PayoneWebhookEventModel());

		assertThat(processed).isTrue();
	}

	@Test
	void testProcess_whenApiVersionMatchingIsConfigured_thenComparisonIsTrimmedAndCaseInsensitive()
	{
		when(configuration.getString(SUPPORTED_API_VERSIONS, DEFAULT_SUPPORTED_API_VERSIONS)).thenReturn(" V1 , v2 ");
		processor.setHandlers(java.util.List.of());
		when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(PayoneWebhookTarget.NONE);

		final boolean processed = processor.process(givenEnvelope("V1", "payment.captured"), new PayoneWebhookEventModel());

		assertThat(processed).isTrue();
	}

	// event type gate
	@Test
	void testProcess_whenEventTypeCodeIsUnrecognised_thenReturnsFalse()
	{
		final boolean processed = processor.process(givenEnvelope("v1", "not.a.real.type"), new PayoneWebhookEventModel());

		assertThat(processed).isFalse();
		verify(payoneWebhookTargetResolver, never()).resolve(any(), any());
	}

	// happy path: resolution, linking, dispatch
	@Test
	void testProcess_whenResolvedCommerceCaseChanges_thenLinksItToTheEventAndSaves()
	{
		final PayoneCommerceCaseModel commerceCase = new PayoneCommerceCaseModel();
		final PayoneWebhookTarget target = new PayoneWebhookTarget(null, commerceCase);
		when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(target);
		processor.setHandlers(java.util.List.of());

		final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
		final boolean processed = processor.process(givenEnvelope("v1", "payment.captured"), event);

		assertThat(processed).isTrue();
		assertThat(event.getCommerceCase()).isSameAs(commerceCase);
		verify(modelService).save(event);
	}

    /** The stored event must be navigable to the checkout, not just the case. */
    @Test
    void testProcess_whenTargetResolvesACheckout_thenLinksItOntoTheEventRecord() {
        final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
        when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(new PayoneWebhookTarget(checkout, null));
        processor.setHandlers(java.util.List.of());

        final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
        processor.process(givenEnvelope("v1", "payment.captured"), event);

        assertThat(event.getPayoneCheckout()).isSameAs(checkout);
        verify(modelService).save(event);
    }

    @Test
    void testProcess_whenResolvedTargetIsUnchanged_thenDoesNotSave()
	{
		final PayoneCommerceCaseModel commerceCase = new PayoneCommerceCaseModel();
        final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
		event.setCommerceCase(commerceCase);
        event.setPayoneCheckout(checkout);

        final PayoneWebhookTarget target = new PayoneWebhookTarget(checkout, commerceCase);
		when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(target);
		processor.setHandlers(java.util.List.of());

		processor.process(givenEnvelope("v1", "payment.captured"), event);

		verify(modelService, never()).save(any());
	}

	@Test
	void testProcess_whenHandlersAreRegistered_thenDispatchesOnlyToThoseThatSupportTheEventType()
	{
		when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(PayoneWebhookTarget.NONE);
		when(handlerA.supports(PayoneWebhookEventType.PAYMENT_CAPTURED)).thenReturn(true);
		when(handlerB.supports(PayoneWebhookEventType.PAYMENT_CAPTURED)).thenReturn(false);
		processor.setHandlers(java.util.List.of(handlerA, handlerB));

		final boolean processed = processor.process(givenEnvelope("v1", "payment.captured"), new PayoneWebhookEventModel());

		assertThat(processed).isTrue();
		verify(handlerA, times(1)).handle(any(), any(), any(), any());
		verify(handlerB, never()).handle(any(), any(), any(), any());
	}

    @Test
    void testProcess_whenEventTypeIsRecognisedButUnmappedToAnyHandler_thenStillReturnsProcessed()
	{
		when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(PayoneWebhookTarget.NONE);
		processor.setHandlers(java.util.List.of());

		final boolean processed = processor.process(givenEnvelope("v1", "payment.paid"), new PayoneWebhookEventModel());

		assertThat(processed).isTrue();
    }

    @Test
    void testProcess_whenHandlerDeclinesOnPolicyGrounds_thenLeavesTheEventUnprocessedWithoutThrowing() {
        when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(PayoneWebhookTarget.NONE);
        when(handlerA.supports(PayoneWebhookEventType.PAYOUT_CREATED)).thenReturn(true);
        org.mockito.Mockito.doThrow(new PayoneWebhookNotSupportedException("no policy"))
                .when(handlerA).handle(any(), any(), any(), any());
        processor.setHandlers(java.util.List.of(handlerA));

        final boolean processed = processor.process(givenEnvelope("v1", "payout.created"), new PayoneWebhookEventModel());

        assertThat(processed).isFalse();
    }

    @Test
    void testProcess_whenHandlerThrowsAnUnexpectedException_thenExceptionPropagates() {
        when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(PayoneWebhookTarget.NONE);
        when(handlerA.supports(PayoneWebhookEventType.PAYMENT_CAPTURED)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("row is locked"))
                .when(handlerA).handle(any(), any(), any(), any());
        processor.setHandlers(java.util.List.of(handlerA));

        final PayoneWebhookEventDto envelope = givenEnvelope("v1", "payment.captured");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> processor.process(envelope, new PayoneWebhookEventModel()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testProcess_whenAHandlerDeclinesTheEvent_thenRemainingHandlersAreNotInvoked() {
        when(payoneWebhookTargetResolver.resolve(any(), any())).thenReturn(PayoneWebhookTarget.NONE);
        when(handlerA.supports(PayoneWebhookEventType.PAYMENT_CAPTURED)).thenReturn(true);
        org.mockito.Mockito.doThrow(new PayoneWebhookNotSupportedException("no policy"))
                .when(handlerA).handle(any(), any(), any(), any());
        processor.setHandlers(java.util.List.of(handlerA, handlerB));

        processor.process(givenEnvelope("v1", "payment.captured"), new PayoneWebhookEventModel());

        verify(handlerB, never()).handle(any(), any(), any(), any());
	}

	// handlers
	@Test
	void testSetHandlers_whenGivenNull_thenDefaultsToAnEmptyList()
	{
		processor.setHandlers(null);

		assertThat(processor.getHandlers()).isEmpty();
	}
}
