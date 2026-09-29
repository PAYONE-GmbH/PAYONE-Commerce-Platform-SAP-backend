package com.payone.pcp.webhook.handler.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.webhook.dao.PayoneWebhookEventDao;
import com.payone.pcp.webhook.dto.PayoneWebhookCommerceCaseDto;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.servicelayer.exceptions.ModelSavingException;
import de.hybris.platform.servicelayer.model.ModelService;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@UnitTest
class PayoneCommerceCaseWebhookHandlerUnitTest
{
	private static final String CASE_ID = "0c3ab9d7-19ed-40da-9a0e-1f96f4cfb8ae";

	@Mock
	private ModelService modelService;
	@Mock
	private PayoneWebhookEventDao payoneWebhookEventDao;

	private PayoneCommerceCaseWebhookHandler handler;

	@BeforeEach
	void setUp()
	{
		handler = new PayoneCommerceCaseWebhookHandler();
		handler.setModelService(modelService);
		handler.setPayoneWebhookEventDao(payoneWebhookEventDao);
	}

	private static PayoneWebhookEventDto envelope(final String merchantReference)
	{
		final PayoneWebhookCommerceCaseDto dto = new PayoneWebhookCommerceCaseDto();
		dto.setId(CASE_ID);
		dto.setMerchantReference(merchantReference);

		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setId("event-1");
		envelope.setCommerceCase(dto);
		return envelope;
	}

	// first sighting

	@Test
	void testHandle_whenCaseIsNotKnownYet_thenCreatesLocalCase()
	{
		final PayoneCommerceCaseModel created = new PayoneCommerceCaseModel();
		when(modelService.create(PayoneCommerceCaseModel.class)).thenReturn(created);

		final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
		handler.handle(envelope("cart-1-ab12cd34"), PayoneWebhookEventType.COMMERCE_CASE_CREATED,
				PayoneWebhookTarget.NONE, event);

		assertThat(created.getCommerceCaseId()).isEqualTo(CASE_ID);
		assertThat(created.getMerchantReference()).isEqualTo("cart-1-ab12cd34");
		assertThat(event.getCommerceCase()).isSameAs(created);
	}

	/** A failed save is not swallowed; PCP redelivers the event. */
	@Test
	void testHandle_whenSaveFailsForAReasonOtherThanALostRace_thenExceptionIsRethrown()
	{
		final PayoneCommerceCaseModel losing = new PayoneCommerceCaseModel();
		when(modelService.create(PayoneCommerceCaseModel.class)).thenReturn(losing);
		doThrow(new ModelSavingException("disk on fire")).when(modelService).save(losing);
		when(payoneWebhookEventDao.findCommerceCaseById(CASE_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> handler.handle(envelope("cart-1"), PayoneWebhookEventType.COMMERCE_CASE_CREATED,
				PayoneWebhookTarget.NONE, new PayoneWebhookEventModel()))
				.isInstanceOf(ModelSavingException.class);
	}

	// already known: converge the mutable field

	@Test
	void testHandle_whenCaseIsAlreadyKnownAndReferenceChanged_thenUpdatesMerchantReference()
	{
		final PayoneCommerceCaseModel known = new PayoneCommerceCaseModel();
		known.setCommerceCaseId(CASE_ID);
		known.setMerchantReference("stale");

		handler.handle(envelope("fresh"), PayoneWebhookEventType.COMMERCE_CASE_UPDATED,
				new PayoneWebhookTarget(null, known), new PayoneWebhookEventModel());

		assertThat(known.getMerchantReference()).isEqualTo("fresh");
		verify(modelService).save(known);
		verify(modelService, never()).create(PayoneCommerceCaseModel.class);
	}

	@Test
	void testHandle_whenMerchantReferenceAlreadyMatches_thenDoesNotSave()
	{
		final PayoneCommerceCaseModel known = new PayoneCommerceCaseModel();
		known.setCommerceCaseId(CASE_ID);
		known.setMerchantReference("same");

		handler.handle(envelope("same"), PayoneWebhookEventType.COMMERCE_CASE_UPDATED,
				new PayoneWebhookTarget(null, known), new PayoneWebhookEventModel());

		verify(modelService, never()).save(any());
	}

	/** An absent reference means "not reported", never "cleared". */
	@Test
	void testHandle_whenSnapshotHasNoMerchantReference_thenDoesNotClearTheStoredReference()
	{
		final PayoneCommerceCaseModel known = new PayoneCommerceCaseModel();
		known.setCommerceCaseId(CASE_ID);
		known.setMerchantReference("keep me");

		handler.handle(envelope(null), PayoneWebhookEventType.COMMERCE_CASE_UPDATED,
				new PayoneWebhookTarget(null, known), new PayoneWebhookEventModel());

		assertThat(known.getMerchantReference()).isEqualTo("keep me");
		verify(modelService, never()).save(any());
	}
}
