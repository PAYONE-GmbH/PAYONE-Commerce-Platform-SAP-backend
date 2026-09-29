package com.payone.pcp.webhook.service.impl;

import com.payone.pcp.webhook.dao.PayoneWebhookEventDao;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.data.PayoneWebhookIntake;
import com.payone.pcp.webhook.service.data.PayoneWebhookIntake.Disposition;
import de.hybris.platform.servicelayer.exceptions.ModelSavingException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultPayoneWebhookEventService}. intake() is the dedup boundary:
 * PAYONE can redeliver the same eventId, including two deliveries racing to insert it first.
 * Covers the steady-state dispositions and both branches of the insert-race recovery.
 */
@ExtendWith(MockitoExtension.class)
class DefaultPayoneWebhookEventServiceUnitTest {
    private static final String EVENT_ID = "686e5823-1ffd-42f7-9ba3-42b41b57d8dd";

    @Mock
    private ModelService modelService;

    @Mock
    private TimeService timeService;

    @Mock
    private PayoneWebhookEventDao payoneWebhookEventDao;

    private DefaultPayoneWebhookEventService service;

    @BeforeEach
    void setUp() {
        service = new DefaultPayoneWebhookEventService();
        service.setModelService(modelService);
        service.setTimeService(timeService);
        service.setPayoneWebhookEventDao(payoneWebhookEventDao);

        lenient().when(timeService.getCurrentTime()).thenReturn(new Date());
    }

    private PayoneWebhookEventDto givenEnvelope() {
        final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
        envelope.setId(EVENT_ID);
        envelope.setType("payment.captured");
        envelope.setMerchantId("P1_TestMerchant");
        return envelope;
    }

    private PayoneWebhookEventModel givenExisting(final boolean processed) {
        final PayoneWebhookEventModel existing = new PayoneWebhookEventModel();
        existing.setEventId(EVENT_ID);
        existing.setProcessed(Boolean.valueOf(processed));
        when(payoneWebhookEventDao.findEventByEventId(EVENT_ID)).thenReturn(Optional.of(existing));
        return existing;
    }

    // intake()
    @Test
    void intakeStoresANewEventWhenNoneExistsYet() {
        when(payoneWebhookEventDao.findEventByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(modelService.create(PayoneWebhookEventModel.class)).thenReturn(new PayoneWebhookEventModel());

        final PayoneWebhookIntake intake = service.intake(givenEnvelope(), "{}");

        assertThat(intake.getDisposition()).isEqualTo(Disposition.NEW);
        assertThat(intake.getEvent().getEventId()).isEqualTo(EVENT_ID);
        verify(modelService).save(intake.getEvent());
    }

    @Test
    void intakeReturnsAlreadyProcessedForACompletedEvent() {
        final PayoneWebhookEventModel existing = givenExisting(true);

        final PayoneWebhookIntake intake = service.intake(givenEnvelope(), "{}");

        assertThat(intake.getDisposition()).isEqualTo(Disposition.ALREADY_PROCESSED);
        assertThat(intake.getEvent()).isSameAs(existing);
    }

    @Test
    void intakeResolvesViaTheRacedRecordWhenTheInsertLosesTheRace() {
        final PayoneWebhookEventModel raced = new PayoneWebhookEventModel();
        raced.setEventId(EVENT_ID);
        raced.setProcessed(Boolean.FALSE);

        when(payoneWebhookEventDao.findEventByEventId(EVENT_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raced));
        when(modelService.create(PayoneWebhookEventModel.class)).thenReturn(new PayoneWebhookEventModel());
        doThrow(new ModelSavingException("duplicate eventId")).when(modelService).save(any());

        final PayoneWebhookIntake intake = service.intake(givenEnvelope(), "{}");

        assertThat(intake.getDisposition()).isEqualTo(Disposition.RETRY);
        assertThat(intake.getEvent()).isSameAs(raced);
    }

    @Test
    void intakePropagatesTheSavingExceptionWhenTheRaceReQueryStillFindsNothing() {
        when(payoneWebhookEventDao.findEventByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(modelService.create(PayoneWebhookEventModel.class)).thenReturn(new PayoneWebhookEventModel());
        final ModelSavingException saveFailure = new ModelSavingException("not a duplicate after all");
        doThrow(saveFailure).when(modelService).save(any());

        assertThatExceptionOfType(ModelSavingException.class)
                .isThrownBy(() -> service.intake(givenEnvelope(), "{}"))
                .isSameAs(saveFailure);
    }

    // markProcessed()
    @Test
    void markProcessedSetsProcessedFlagAndTimeAndIncrementsAttemptsFromNull() {
        final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
        event.setAttempts(null);

        service.markProcessed(event);

        assertThat(event.getProcessed()).isTrue();
        assertThat(event.getProcessedTime()).isNotNull();
        assertThat(event.getAttempts()).isEqualTo(Integer.valueOf(1));
        verify(modelService).save(event);
    }

    @Test
    void markProcessedIncrementsAttemptsFromAnExistingCount() {
        final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
        event.setAttempts(Integer.valueOf(3));

        service.markProcessed(event);

        assertThat(event.getAttempts()).isEqualTo(Integer.valueOf(4));
    }

    // markFailed()
    @Test
    void markFailedIncrementsAttemptsAndSaves() {
        final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
        event.setAttempts(Integer.valueOf(1));

        service.markFailed(event, new RuntimeException("handler blew up"));

        assertThat(event.getAttempts()).isEqualTo(Integer.valueOf(2));
        verify(modelService, times(1)).save(event);
    }

    /** The bookkeeping save's own failure must never mask the original processing cause. */
    @Test
    void markFailedSwallowsABookkeepingSaveFailure() {
        final PayoneWebhookEventModel event = new PayoneWebhookEventModel();
        event.setAttempts(Integer.valueOf(0));
        doThrow(new RuntimeException("db unavailable")).when(modelService).save(event);

        assertThatCode(() -> service.markFailed(event, new RuntimeException("original cause")))
                .doesNotThrowAnyException();
    }
}
