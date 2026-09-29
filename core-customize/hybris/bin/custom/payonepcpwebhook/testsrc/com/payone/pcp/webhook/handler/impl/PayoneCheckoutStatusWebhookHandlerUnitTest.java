package com.payone.pcp.webhook.handler.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.payone.pcp.core.enums.PayonePaymentStatus;
import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

import de.hybris.platform.servicelayer.model.ModelService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PayoneCheckoutStatusWebhookHandler}.
 */
@ExtendWith(MockitoExtension.class)
class PayoneCheckoutStatusWebhookHandlerUnitTest
{
	@Mock
	private ModelService modelService;

	private PayoneCheckoutStatusWebhookHandler handler;

	@BeforeEach
	void setUp()
	{
		handler = new PayoneCheckoutStatusWebhookHandler();
		handler.setModelService(modelService);
	}

	// supports()

	@Test
	void supportsACheckoutDomainEventType()
	{
		assertThat(handler.supports(PayoneWebhookEventType.CHECKOUT_CREATED)).isTrue();
	}

	@Test
	void declinesAnEventTypeOutsideTheCheckoutDomainEvenWithAMappedStatus()
	{
		// PAYMENT_CAPTURED has a mapped status, but belongs to the payment domain,
		// not checkout - supports() dispatches by domain, not by status mapping.
		assertThat(handler.supports(PayoneWebhookEventType.PAYMENT_CAPTURED)).isFalse();
	}

	// handle()

	@Test
	void doesNothingWhenTheTargetHasNoLocalCheckout()
	{
		handler.handle(new PayoneWebhookEventDto(), PayoneWebhookEventType.PAYMENT_CAPTURED, PayoneWebhookTarget.NONE,
				new PayoneWebhookEventModel());

		verify(modelService, never()).save(any());
	}

	@Test
	void doesNotSaveWhenTheCheckoutAlreadyHasTheMappedStatus()
	{
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		checkout.setStatus(PayonePaymentStatus.CAPTURED);
		final PayoneWebhookTarget target = new PayoneWebhookTarget(checkout, null);

		handler.handle(new PayoneWebhookEventDto(), PayoneWebhookEventType.PAYMENT_CAPTURED, target,
				new PayoneWebhookEventModel());

		verify(modelService, never()).save(any());
	}

	@Test
	void setsTheNewStatusAndSavesWhenItDiffers()
	{
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		checkout.setStatus(PayonePaymentStatus.PENDING_CAPTURE);
		final PayoneWebhookTarget target = new PayoneWebhookTarget(checkout, null);

		handler.handle(new PayoneWebhookEventDto(), PayoneWebhookEventType.PAYMENT_CAPTURED, target,
				new PayoneWebhookEventModel());

		assertThat(checkout.getStatus()).isEqualTo(PayonePaymentStatus.CAPTURED);
		verify(modelService).save(checkout);
	}

	@Test
	void setsTheStatusWhenTheCheckoutHadNoPriorStatus()
	{
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		final PayoneWebhookTarget target = new PayoneWebhookTarget(checkout, null);

		handler.handle(new PayoneWebhookEventDto(), PayoneWebhookEventType.PAYMENT_CAPTURED, target,
				new PayoneWebhookEventModel());

		assertThat(checkout.getStatus()).isEqualTo(PayonePaymentStatus.CAPTURED);
		verify(modelService).save(checkout);
	}

	/** Defence in depth: handle() re-checks the mapping even though the processor only dispatches here when supports() is true. */
	@Test
	void doesNothingWhenCalledDirectlyForAnUnmappedEventTypeDespiteAResolvedCheckout()
	{
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		final PayoneWebhookTarget target = new PayoneWebhookTarget(checkout, null);

		handler.handle(new PayoneWebhookEventDto(), PayoneWebhookEventType.PAYMENT_PAID, target,
				new PayoneWebhookEventModel());

		assertThat(checkout.getStatus()).isNull();
		verify(modelService, never()).save(any());
	}
}
