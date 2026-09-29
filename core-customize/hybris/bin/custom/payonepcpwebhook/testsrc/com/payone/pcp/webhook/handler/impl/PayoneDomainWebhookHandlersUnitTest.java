package com.payone.pcp.webhook.handler.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.StatusValue;
import com.payone.pcp.core.enums.PayonePaymentStatus;
import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.core.service.PayoneTransactionService;
import com.payone.pcp.webhook.dto.PayoneWebhookAmountOfMoneyDto;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.dto.PayoneWebhookExecutionDto;
import com.payone.pcp.webhook.dto.PayoneWebhookExecutionEventDto;
import com.payone.pcp.webhook.dto.PayoneWebhookOutputDto;
import com.payone.pcp.webhook.dto.PayoneWebhookStatusObjectDto;
import com.payone.pcp.webhook.enums.PayoneWebhookDomain;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.exception.PayoneWebhookNotSupportedException;
import com.payone.pcp.webhook.handler.PayoneWebhookEventHandler;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins two things a transaction-domain handler must get right, because both are silently wrong
 * rather than loudly broken when they are not: the amount booked on the
 * {@code PaymentTransactionEntry} is the one PCP reported for the transition, not the checkout's
 * authorised total (a partial capture booked at full value makes the order PAID); and the
 * requestId is the PCP operation id, not the webhook delivery id (half of the entry idempotency
 * key, stamped onto the transaction's payonePaymentId).
 */
@ExtendWith(MockitoExtension.class)
@UnitTest
class PayoneDomainWebhookHandlersUnitTest
{
	@Mock
	private ModelService modelService;
	@Mock
	private PayoneTransactionService payoneTransactionService;
	@Mock
	private CommonI18NService commonI18NService;
	@Mock
	private PayoneCheckoutModel checkout;
	@Mock
	private PayoneCommerceCaseModel commerceCase;
	@Mock
	private OrderModel order;
	@Mock
	private PaymentTransactionModel transaction;
	@Mock
	private CurrencyModel euro;

	private PayonePaymentWebhookHandler paymentHandler;

	@BeforeEach
	void setUp()
	{
		paymentHandler = new PayonePaymentWebhookHandler();
		wire(paymentHandler);
		lenient().when(commonI18NService.getCurrency("EUR")).thenReturn(euro);
	}

	private void wire(final AbstractPayoneTransactionWebhookHandler handler)
	{
		handler.setModelService(modelService);
		handler.setPayoneTransactionService(payoneTransactionService);
		handler.setCommonI18NService(commonI18NService);
	}

	private static PayoneWebhookAmountOfMoneyDto amount(final Long minorUnits, final String currencyCode)
	{
		final PayoneWebhookAmountOfMoneyDto amountOfMoney = new PayoneWebhookAmountOfMoneyDto();
		amountOfMoney.setAmount(minorUnits);
		amountOfMoney.setCurrencyCode(currencyCode);
		return amountOfMoney;
	}

	private static PayoneWebhookStatusObjectDto statusObject(final String id,
			final PayoneWebhookAmountOfMoneyDto amountOfMoney)
	{
		final PayoneWebhookOutputDto output = new PayoneWebhookOutputDto();
		output.setAmountOfMoney(amountOfMoney);

		final PayoneWebhookStatusObjectDto statusObject = new PayoneWebhookStatusObjectDto();
		statusObject.setId(id);
		statusObject.setPaymentOutput(output);
		return statusObject;
	}

	/** A payment.* envelope reporting {@code minorUnits} EUR for payment {@code paymentId}. */
	private static PayoneWebhookEventDto paymentEnvelope(final String eventId, final String paymentId,
			final Long minorUnits)
	{
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setId(eventId);
		envelope.setPayment(statusObject(paymentId, amount(minorUnits, "EUR")));
		return envelope;
	}

	private void givenAnOrderedCheckout(final String checkoutId, final String commerceCaseId)
	{
		when(checkout.getOrder()).thenReturn(order);
		when(checkout.getCheckoutId()).thenReturn(checkoutId);
		when(payoneTransactionService.getOrCreatePaymentTransaction(order, commerceCaseId, checkoutId))
				.thenReturn(transaction);
	}

	// handler / domain coverage

	@Test
	void testSupports_whenCheckedAcrossAllHandlers_thenExactlyOneHandlerSupportsEachDomain()
	{
		final List<PayoneWebhookEventHandler> handlers = Arrays.asList(
				paymentHandler,
				new PayoneRefundWebhookHandler(),
				new PayonePayoutWebhookHandler(),
				new PayoneCommerceCaseWebhookHandler(),
				new PayoneCheckoutStatusWebhookHandler(),
				new PayonePaymentExecutionWebhookHandler(),
				new PayonePaymentInformationWebhookHandler());

		for (final PayoneWebhookDomain domain : PayoneWebhookDomain.values())
		{
			final PayoneWebhookEventType eventType = Arrays.stream(PayoneWebhookEventType.values())
					.filter(candidate -> candidate.getDomain() == domain)
					.findFirst()
					.orElseThrow();
			assertThat(handlers.stream().filter(handler -> handler.supports(eventType))).hasSize(1);
		}
	}

	@Test
	void testHandle_whenPaymentEventReportsAPartialAmount_thenBooksThatAmountNotTheCheckoutTotal()
	{
		// A partial capture: PCP reports 400, the checkout authorised 1000.
		// The total is stubbed leniently precisely because it must NOT be read.
		when(checkout.getPaymentId()).thenReturn("PP1AA7KKLSFB9MBG");
		lenient().when(checkout.getAmount()).thenReturn(1000L);
		givenAnOrderedCheckout("checkout-1", null);

		paymentHandler.handle(paymentEnvelope("webhook-1", "PP1AA7KKLSFB9MBG", 400L),
				PayoneWebhookEventType.PAYMENT_CAPTURED, new PayoneWebhookTarget(checkout, null),
				new PayoneWebhookEventModel());

		verify(checkout).setStatus(PayonePaymentStatus.CAPTURED);
		verify(payoneTransactionService).createPaymentTransactionEntry(transaction, "PP1AA7KKLSFB9MBG", order,
				StatusValue.CAPTURED, 400L, euro, PaymentTransactionType.CAPTURE);
	}

	@Test
	void testHandle_whenEnvelopeHasNoAmount_thenFallsBackToTheCheckoutTotalAndTheOrderCurrency()
	{
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setId("webhook-2");
		final PayoneWebhookStatusObjectDto payment = new PayoneWebhookStatusObjectDto();
		payment.setId("payment-2");
		envelope.setPayment(payment);

		when(checkout.getPaymentId()).thenReturn("payment-2");
		when(checkout.getAmount()).thenReturn(1000L);
		when(order.getCurrency()).thenReturn(euro);
		givenAnOrderedCheckout("checkout-2", null);

		paymentHandler.handle(envelope, PayoneWebhookEventType.PAYMENT_CAPTURED,
				new PayoneWebhookTarget(checkout, null), new PayoneWebhookEventModel());

		verify(payoneTransactionService).createPaymentTransactionEntry(transaction, "payment-2", order,
				StatusValue.CAPTURED, 1000L, euro, PaymentTransactionType.CAPTURE);
	}

	@Test
	void testHandle_whenCurrencyCodeIsUnknown_thenFallsBackToTheOrderCurrency()
	{
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setId("webhook-3");
		envelope.setPayment(statusObject("payment-3", amount(700L, "XTS")));

		when(commonI18NService.getCurrency("XTS")).thenReturn(null);
		when(checkout.getPaymentId()).thenReturn("payment-3");
		when(order.getCurrency()).thenReturn(euro);
		givenAnOrderedCheckout("checkout-3", null);

		paymentHandler.handle(envelope, PayoneWebhookEventType.PAYMENT_CAPTURED,
				new PayoneWebhookTarget(checkout, null), new PayoneWebhookEventModel());

		verify(payoneTransactionService).createPaymentTransactionEntry(transaction, "payment-3", order,
				StatusValue.CAPTURED, 700L, euro, PaymentTransactionType.CAPTURE);
	}

	/** The refund's own amount, not the payment's, and its own operation id. */
	@Test
	void testHandle_whenRefundEventIsReceived_thenBooksTheRefundedAmountAgainstTheRefundId()
	{
		final PayoneRefundWebhookHandler refundHandler = new PayoneRefundWebhookHandler();
		wire(refundHandler);

		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setId("webhook-4");
		final PayoneWebhookOutputDto refundOutput = new PayoneWebhookOutputDto();
		refundOutput.setAmountOfMoney(amount(250L, "EUR"));
		final PayoneWebhookStatusObjectDto refund = new PayoneWebhookStatusObjectDto();
		refund.setId("3066019730_1");
		refund.setRefundOutput(refundOutput);
		envelope.setRefund(refund);

		when(checkout.getPaymentId()).thenReturn("3066019730");
		givenAnOrderedCheckout("checkout-4", null);

		refundHandler.handle(envelope, PayoneWebhookEventType.REFUND_REFUNDED,
				new PayoneWebhookTarget(checkout, null), new PayoneWebhookEventModel());

		verify(payoneTransactionService).createPaymentTransactionEntry(transaction, "3066019730_1", order,
				StatusValue.REFUNDED, 250L, euro, PaymentTransactionType.REFUND_FOLLOW_ON);
	}

	/**
	 * A payment_execution.* envelope prices the transition in its event history,
	 * not at the root, and the entry must key on the paymentId so it deduplicates
	 * against the payment.* event for the same transition.
	 */
	@Test
	void testHandle_whenPaymentExecutionEventIsReceived_thenReadsTheAmountFromTheMatchingHistoryEntry()
	{
		final PayonePaymentExecutionWebhookHandler executionHandler = new PayonePaymentExecutionWebhookHandler();
		wire(executionHandler);

		final PayoneWebhookExecutionEventDto created = new PayoneWebhookExecutionEventDto();
		created.setType("SALE");
		created.setPaymentStatus("CREATED");
		created.setAmountOfMoney(amount(1000L, "EUR"));

		final PayoneWebhookExecutionEventDto captured = new PayoneWebhookExecutionEventDto();
		captured.setType("CAPTURE");
		captured.setPaymentStatus("CAPTURED");
		captured.setAmountOfMoney(amount(600L, "EUR"));

		final PayoneWebhookExecutionDto execution = new PayoneWebhookExecutionDto();
		execution.setId("4f0c512e-f12c-11ec-8ea0-0242ac120002");
		execution.setPaymentId("3066019730");
		execution.setEvents(List.of(created, captured));

		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setId("webhook-5");
		envelope.setPaymentExecution(execution);

		when(checkout.getPaymentId()).thenReturn("3066019730");
		when(checkout.getPaymentExecutionId()).thenReturn("4f0c512e-f12c-11ec-8ea0-0242ac120002");
		givenAnOrderedCheckout("checkout-5", null);

		executionHandler.handle(envelope, PayoneWebhookEventType.PAYMENT_EXECUTION_PAYMENT_CAPTURED,
				new PayoneWebhookTarget(checkout, null), new PayoneWebhookEventModel());

		verify(payoneTransactionService).createPaymentTransactionEntry(transaction, "3066019730", order,
				StatusValue.CAPTURED, 600L, euro, PaymentTransactionType.CAPTURE);
	}

	// backfilling the local ids the synchronous path never records

	@Test
	void testHandle_whenCheckoutIsMissingItsPcpIds_thenBackfillsThemFromTheEnvelope()
	{
		final PayoneWebhookExecutionDto execution = new PayoneWebhookExecutionDto();
		execution.setId("exec-6");
		execution.setPaymentId("payment-6");

		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setId("webhook-6");
		envelope.setPaymentExecution(execution);

		final PayonePaymentExecutionWebhookHandler executionHandler = new PayonePaymentExecutionWebhookHandler();
		wire(executionHandler);

		when(checkout.getPaymentId()).thenReturn(null);
		when(checkout.getPaymentExecutionId()).thenReturn(null);
		when(order.getCurrency()).thenReturn(euro);
		givenAnOrderedCheckout("checkout-6", null);

		executionHandler.handle(envelope, PayoneWebhookEventType.PAYMENT_EXECUTION_PAYMENT_CAPTURED,
				new PayoneWebhookTarget(checkout, null), new PayoneWebhookEventModel());

		verify(checkout).setPaymentId("payment-6");
		verify(checkout).setPaymentExecutionId("exec-6");
		verify(transaction).setPayonePaymentId("payment-6");
		verify(transaction).setPayonePaymentExecutionId("exec-6");
	}

	@Test
	void testHandle_whenCheckoutIsAlreadyIdentified_thenIdsAreNotRewritten()
	{
		when(checkout.getPaymentId()).thenReturn("PP1AA7KKLSFB9MBG");
		when(transaction.getPayonePaymentId()).thenReturn("PP1AA7KKLSFB9MBG");
		givenAnOrderedCheckout("checkout-7", null);

		paymentHandler.handle(paymentEnvelope("webhook-7", "PP1AA7KKLSFB9MBG", 1000L),
				PayoneWebhookEventType.PAYMENT_CAPTURED, new PayoneWebhookTarget(checkout, null),
				new PayoneWebhookEventModel());

		verify(checkout, never()).setPaymentId(any());
		verify(transaction, never()).setPayonePaymentId(any());
	}

	// resolution failures degrade, they do not throw

	@Test
	void testHandle_whenNoLocalTargetIsResolved_thenEventIsAcknowledgedWithoutMutation()
	{
		paymentHandler.handle(paymentEnvelope("webhook-8", "payment-8", 1000L),
				PayoneWebhookEventType.PAYMENT_REDIRECTED, PayoneWebhookTarget.NONE, new PayoneWebhookEventModel());

		verify(modelService, never()).save(any());
		verify(payoneTransactionService, never()).getOrCreatePaymentTransaction(any(), any(), any());
	}

	@Test
	void testHandle_whenNoLocalOrderIsResolved_thenTransactionSideEffectsAreSkipped()
	{
		when(checkout.getOrder()).thenReturn(null);
		when(checkout.getPaymentId()).thenReturn("payment-9");

		paymentHandler.handle(paymentEnvelope("webhook-9", "payment-9", 1000L),
				PayoneWebhookEventType.PAYMENT_CAPTURED, new PayoneWebhookTarget(checkout, null),
				new PayoneWebhookEventModel());

		// The checkout status still converges even without a resolvable order;
		// only the SAP transaction/order-status side effects are skipped.
		verify(checkout).setStatus(PayonePaymentStatus.CAPTURED);
		verify(modelService).save(checkout);
		verify(payoneTransactionService, never()).getOrCreatePaymentTransaction(any(), any(), any());
	}

	/** A checkout created before its order exists still resolves through its case. */
	@Test
	void testHandle_whenCheckoutHasNoOrder_thenOrderIsResolvedThroughACommerceCaseSibling()
	{
		final PayoneCheckoutModel sibling = new PayoneCheckoutModel();
		sibling.setOrder(order);

		when(checkout.getOrder()).thenReturn(null);
		when(checkout.getPaymentId()).thenReturn("payment-10");
		when(checkout.getCheckoutId()).thenReturn("checkout-10");
		when(commerceCase.getCheckouts()).thenReturn(List.of(sibling));
		when(commerceCase.getCommerceCaseId()).thenReturn("case-10");
		when(payoneTransactionService.getOrCreatePaymentTransaction(order, "case-10", "checkout-10"))
				.thenReturn(transaction);

		paymentHandler.handle(paymentEnvelope("webhook-10", "payment-10", 1000L),
				PayoneWebhookEventType.PAYMENT_CAPTURED, new PayoneWebhookTarget(checkout, commerceCase),
				new PayoneWebhookEventModel());

		verify(payoneTransactionService).createPaymentTransactionEntry(transaction, "payment-10", order,
				StatusValue.CAPTURED, 1000L, euro, PaymentTransactionType.CAPTURE);
	}

	@Test
	void testHandle_whenCheckoutStatusAlreadyMatches_thenCheckoutSaveIsSkipped()
	{
		when(checkout.getStatus()).thenReturn(PayonePaymentStatus.CAPTURED);
		when(checkout.getPaymentId()).thenReturn("payment-11");
		givenAnOrderedCheckout("checkout-11", null);

		paymentHandler.handle(paymentEnvelope("webhook-11", "payment-11", 200L),
				PayoneWebhookEventType.PAYMENT_CAPTURED, new PayoneWebhookTarget(checkout, null),
				new PayoneWebhookEventModel());

		verify(checkout, never()).setStatus(any());
		verify(modelService, never()).save(checkout);
		verify(payoneTransactionService).createPaymentTransactionEntry(transaction, "payment-11", order,
				StatusValue.CAPTURED, 200L, euro, PaymentTransactionType.CAPTURE);
	}

	// transaction type mapping

	@Test
	void testHandle_whenEventIsPaymentCancelled_thenMapsToTheCancelTransactionType()
	{
		when(checkout.getPaymentId()).thenReturn("payment-12");
		givenAnOrderedCheckout("checkout-12", null);

		paymentHandler.handle(paymentEnvelope("webhook-12", "payment-12", 400L),
				PayoneWebhookEventType.PAYMENT_CANCELLED, new PayoneWebhookTarget(checkout, null),
				new PayoneWebhookEventModel());

		verify(payoneTransactionService).createPaymentTransactionEntry(transaction, "payment-12", order,
				StatusValue.CANCELLED, 400L, euro, PaymentTransactionType.CANCEL);
	}

	@Test
	void testHandle_whenEventIsPaymentCreated_thenDefaultsToTheAuthorizationTransactionType()
	{
		when(checkout.getPaymentId()).thenReturn("payment-13");
		givenAnOrderedCheckout("checkout-13", null);

		paymentHandler.handle(paymentEnvelope("webhook-13", "payment-13", 700L),
				PayoneWebhookEventType.PAYMENT_CREATED, new PayoneWebhookTarget(checkout, null),
				new PayoneWebhookEventModel());

		verify(payoneTransactionService).createPaymentTransactionEntry(transaction, "payment-13", order,
				StatusValue.CREATED, 700L, euro, PaymentTransactionType.AUTHORIZATION);
	}

	@Test
	void testHandle_whenPaymentStatusIsRecognisedButUnmapped_thenDeclinesWithoutAskingForRedelivery()
	{
		assertThatThrownBy(() -> paymentHandler.handle(new PayoneWebhookEventDto(),
				PayoneWebhookEventType.PAYMENT_PAID, PayoneWebhookTarget.NONE, new PayoneWebhookEventModel()))
				.isInstanceOf(PayoneWebhookNotSupportedException.class)
				.hasMessageContaining("payment.paid")
				.hasMessageContaining("merchant must define");
	}

	@Test
	void testHandle_whenPayoutDomainIsUnimplemented_thenDeclinesTheSameWay()
	{
		assertThatThrownBy(() -> new PayonePayoutWebhookHandler().handle(new PayoneWebhookEventDto(),
				PayoneWebhookEventType.PAYOUT_CREATED, PayoneWebhookTarget.NONE, new PayoneWebhookEventModel()))
				.isInstanceOf(PayoneWebhookNotSupportedException.class)
				.hasMessageContaining("payout.created");
	}
}
