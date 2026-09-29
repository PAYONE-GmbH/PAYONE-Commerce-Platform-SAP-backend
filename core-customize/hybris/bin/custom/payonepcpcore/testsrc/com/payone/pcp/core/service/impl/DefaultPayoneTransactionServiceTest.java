package com.payone.pcp.core.service.impl;

import com.payone.commerce.platform.lib.models.StatusValue;
import com.payone.pcp.core.service.PayoneTransactionIdentityConflictException;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.enums.PaymentStatus;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@UnitTest
public class DefaultPayoneTransactionServiceTest
{
	private static final String ORDER_CODE = "ORDER-001";
	private static final String COMMERCE_CASE_ID = "case-001";
	private static final String CHECKOUT_ID = "checkout-001";
	private static final String CART_CODE = "cart-abc-123";

	@Mock
	private ModelService modelService;

	@Mock
	private AbstractOrderModel order;

	@Mock
	private CurrencyModel currency;

	private AutoCloseable mocks;

	private DefaultPayoneTransactionService service;

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);

		when(modelService.create(PaymentTransactionEntryModel.class)).thenReturn(new PaymentTransactionEntryModel());
		when(modelService.create(PaymentTransactionModel.class)).thenReturn(new PaymentTransactionModel());

		when(order.getCode()).thenReturn(ORDER_CODE);
		lenient().when(order.getPaymentTransactions()).thenReturn(Collections.emptyList());

		// let save() hand the model back with a code, like the real persistence layer would
		doAnswer(invocation -> {
			final PaymentTransactionModel tx = invocation.getArgument(0);
			tx.setCode(tx.getCode() != null ? tx.getCode() : "TX-001");
			return tx;
		}).when(modelService).save(any(PaymentTransactionModel.class));

		service = new DefaultPayoneTransactionService();
		service.setModelService(modelService);
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	// PaymentTransaction lifecycle

	@Test
	public void shouldCreatePaymentTransaction()
	{
		final PaymentTransactionModel tx = service.getOrCreatePaymentTransaction(
				order, COMMERCE_CASE_ID, null);

		assertNotNull(tx);
		assertEquals(ORDER_CODE + "_PAYONE", tx.getCode());
		assertEquals(COMMERCE_CASE_ID, tx.getPayoneCommerceCaseId());
		verify(modelService).save(any(PaymentTransactionModel.class));
	}

	@Test
	public void shouldReuseExistingPaymentTransaction()
	{
		final PaymentTransactionModel existingTx = new PaymentTransactionModel();
		existingTx.setCode(ORDER_CODE + "_PAYONE");
		existingTx.setOrder(order);
		when(order.getPaymentTransactions()).thenReturn(Collections.singletonList(existingTx));

		final PaymentTransactionModel result = service.getOrCreatePaymentTransaction(
				order, COMMERCE_CASE_ID, null);

		assertEquals(existingTx, result);
		assertEquals(COMMERCE_CASE_ID, result.getPayoneCommerceCaseId());
	}

	@Test
	public void shouldRejectNullOrderOnGetOrCreate()
	{
		assertThrows(IllegalArgumentException.class,
				() -> service.getOrCreatePaymentTransaction(null, null, null));
	}

	// PCP identity-based reuse

	@Test
	public void shouldReuseCartClonedTransactionByPcpIdentityOnWebhookLookup()
	{
		// placeOrder cloned the cart's transaction onto the Order; its code
		// remains cart-derived (NOT order.getCode() + "_PAYONE"), but it carries
		// the real PCP identity set by the authorization step.
		final PaymentTransactionModel cartClonedTx = new PaymentTransactionModel();
		cartClonedTx.setCode(CART_CODE + "_PAYONE");
		cartClonedTx.setOrder(order);
		cartClonedTx.setPayoneCommerceCaseId(COMMERCE_CASE_ID);
		cartClonedTx.setPayoneCheckoutId(CHECKOUT_ID);
		when(order.getPaymentTransactions()).thenReturn(Collections.singletonList(cartClonedTx));

		// Webhook resolves by order + exact PCP ids (no code match against
		// order.getCode() + "_PAYONE").
		final PaymentTransactionModel result = service.getOrCreatePaymentTransaction(
				order, COMMERCE_CASE_ID, CHECKOUT_ID);

		assertSame("must reuse the existing cart-cloned row, not create a duplicate", cartClonedTx, result);
		assertEquals(CART_CODE + "_PAYONE", result.getCode());
		// Reused row's code must never be rewritten to the order-derived code.
		verify(modelService, never()).create(PaymentTransactionModel.class);
	}

	@Test
	public void shouldRejectAmbiguousIdentityMatchWithoutCreatingNewRow()
	{
		final PaymentTransactionModel candidateA = new PaymentTransactionModel();
		candidateA.setCode("cart-a_PAYONE");
		candidateA.setOrder(order);
		candidateA.setPayoneCommerceCaseId(COMMERCE_CASE_ID);

		final PaymentTransactionModel candidateB = new PaymentTransactionModel();
		candidateB.setCode("cart-b_PAYONE");
		candidateB.setOrder(order);
		candidateB.setPayoneCheckoutId(CHECKOUT_ID);

		when(order.getPaymentTransactions()).thenReturn(java.util.Arrays.asList(candidateA, candidateB));

		assertThrows(PayoneTransactionIdentityConflictException.class,
				() -> service.getOrCreatePaymentTransaction(order, COMMERCE_CASE_ID, CHECKOUT_ID));

		// Fail closed: no new PaymentTransaction row created for the ambiguous match.
		verify(modelService, never()).create(PaymentTransactionModel.class);
	}

	@Test
	public void shouldRejectAmbiguousWhenTwoCandidatesBothFullyMatchRequestedIdentity()
	{
		// Both candidates fully match on both PCP-id dimensions; must fail closed
		// rather than pick either one or create a new row.
		final PaymentTransactionModel candidateA = new PaymentTransactionModel();
		candidateA.setCode("cart-a_PAYONE");
		candidateA.setOrder(order);
		candidateA.setPayoneCommerceCaseId(COMMERCE_CASE_ID);
		candidateA.setPayoneCheckoutId(CHECKOUT_ID);

		final PaymentTransactionModel candidateB = new PaymentTransactionModel();
		candidateB.setCode("cart-b_PAYONE");
		candidateB.setOrder(order);
		candidateB.setPayoneCommerceCaseId(COMMERCE_CASE_ID);
		candidateB.setPayoneCheckoutId(CHECKOUT_ID);

		when(order.getPaymentTransactions()).thenReturn(java.util.Arrays.asList(candidateA, candidateB));

		assertThrows(PayoneTransactionIdentityConflictException.class,
				() -> service.getOrCreatePaymentTransaction(order, COMMERCE_CASE_ID, CHECKOUT_ID));

		verify(modelService, never()).create(PaymentTransactionModel.class);
	}

	@Test
	public void shouldFailClosedOnPartiallyOverlappingConflictingCandidateInsteadOfCreatingDuplicate()
	{
		// Candidate has a different, non-blank commerceCaseId - a partial identity
		// overlap. Must fail closed rather than skip it and create a possibly
		// duplicate row.
		final PaymentTransactionModel conflictingTx = new PaymentTransactionModel();
		conflictingTx.setCode("cart-other_PAYONE");
		conflictingTx.setOrder(order);
		conflictingTx.setPayoneCommerceCaseId("different-case-999");
		when(order.getPaymentTransactions()).thenReturn(Collections.singletonList(conflictingTx));

		assertThrows(PayoneTransactionIdentityConflictException.class,
				() -> service.getOrCreatePaymentTransaction(order, COMMERCE_CASE_ID, CHECKOUT_ID));

		verify(modelService, never()).create(PaymentTransactionModel.class);
	}

	@Test
	public void shouldFailClosedOnIncompleteCandidateMatchingOnlyOneDimension()
	{
		// One matching dimension is not enough to reuse a row - full reuse needs
		// both ids non-blank and equal on both dimensions. A partial overlap like
		// this must fail closed, not be silently skipped or duplicated.
		final PaymentTransactionModel incompleteTx = new PaymentTransactionModel();
		incompleteTx.setCode("cart-incomplete_PAYONE");
		incompleteTx.setOrder(order);
		incompleteTx.setPayoneCommerceCaseId(COMMERCE_CASE_ID);
		when(order.getPaymentTransactions()).thenReturn(Collections.singletonList(incompleteTx));

		assertThrows(PayoneTransactionIdentityConflictException.class,
				() -> service.getOrCreatePaymentTransaction(order, COMMERCE_CASE_ID, CHECKOUT_ID));

		verify(modelService, never()).save(incompleteTx);
		verify(modelService, never()).create(PaymentTransactionModel.class);
	}

	@Test
	public void shouldIgnoreFullyUnrelatedCandidateAndCreateNewRow()
	{
		// Candidate shares no non-blank dimension with the request at all - genuinely
		// unrelated, so it's ignored and a new row is created.
		final PaymentTransactionModel unrelatedTx = new PaymentTransactionModel();
		unrelatedTx.setCode("cart-unrelated_PAYONE");
		unrelatedTx.setOrder(order);
		when(order.getPaymentTransactions()).thenReturn(Collections.singletonList(unrelatedTx));

		final PaymentTransactionModel result = service.getOrCreatePaymentTransaction(
				order, COMMERCE_CASE_ID, CHECKOUT_ID);

		assertNotEquals("must not reuse a fully unrelated row", unrelatedTx, result);
		assertEquals(ORDER_CODE + "_PAYONE", result.getCode());
		assertEquals(COMMERCE_CASE_ID, result.getPayoneCommerceCaseId());
		assertEquals(CHECKOUT_ID, result.getPayoneCheckoutId());
		verify(modelService).create(PaymentTransactionModel.class);
	}

	@Test
	public void shouldRejectCodeMatchedTransactionWithConflictingCommerceCaseId()
	{
		// Code-matched row already carries a different, non-blank commerceCaseId;
		// must fail closed rather than merge PCP identities.
		final PaymentTransactionModel existingTx = new PaymentTransactionModel();
		existingTx.setCode(ORDER_CODE + "_PAYONE");
		existingTx.setOrder(order);
		existingTx.setPayoneCommerceCaseId("different-case-999");
		when(order.getPaymentTransactions()).thenReturn(Collections.singletonList(existingTx));

		assertThrows(PayoneTransactionIdentityConflictException.class,
				() -> service.getOrCreatePaymentTransaction(order, COMMERCE_CASE_ID, null));

		verify(modelService, never()).save(any(PaymentTransactionModel.class));
		verify(modelService, never()).create(PaymentTransactionModel.class);
	}

	@Test
	public void shouldRejectCodeMatchedTransactionWithConflictingCheckoutId()
	{
		// Same as above, but the conflict is on payoneCheckoutId instead.
		final PaymentTransactionModel existingTx = new PaymentTransactionModel();
		existingTx.setCode(ORDER_CODE + "_PAYONE");
		existingTx.setOrder(order);
		existingTx.setPayoneCheckoutId("different-checkout-999");
		when(order.getPaymentTransactions()).thenReturn(Collections.singletonList(existingTx));

		assertThrows(PayoneTransactionIdentityConflictException.class,
				() -> service.getOrCreatePaymentTransaction(order, COMMERCE_CASE_ID, CHECKOUT_ID));

		verify(modelService, never()).save(any(PaymentTransactionModel.class));
		verify(modelService, never()).create(PaymentTransactionModel.class);
	}

	@Test
	public void shouldNotReuseTransactionOnDifferentOrderEvenWithMatchingIdentity()
	{
		// Matching is scoped to order.getPaymentTransactions() only; matching ids
		// on an empty list still create a fresh row, never reach across orders.
		when(order.getPaymentTransactions()).thenReturn(Collections.emptyList());

		final PaymentTransactionModel result = service.getOrCreatePaymentTransaction(
				order, COMMERCE_CASE_ID, CHECKOUT_ID);

		assertNotNull(result);
		assertEquals(ORDER_CODE + "_PAYONE", result.getCode());
		verify(modelService).create(PaymentTransactionModel.class);
	}

	// PaymentTransactionEntry creation

	@Test
	public void shouldCreatePaymentTransactionEntry()
	{
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		final PaymentTransactionEntryModel entry = service.createPaymentTransactionEntry(
				tx, "pay-001", order, StatusValue.CAPTURED, 9999L, currency,
				PaymentTransactionType.AUTHORIZATION);

		assertNotNull(entry);
		assertEquals("ACCEPTED", entry.getTransactionStatus());
		assertEquals("CAPTURED", entry.getTransactionStatusDetails());
		assertEquals("pay-001", entry.getRequestId());
		assertEquals(PaymentTransactionType.AUTHORIZATION, entry.getType());

		verify(modelService).save(any(PaymentTransactionEntryModel.class));
	}

	@Test
	public void shouldNotInferPcpIdsFromEntryRequestId()
	{
		// requestId is a per-entry PCP operation id (payment/capture/cancel/refund),
		// never the source for the transaction-level PCP identity fields.
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		service.createPaymentTransactionEntry(
				tx, "some-pcp-operation-request-id", order, StatusValue.CAPTURED, 9999L, currency,
				PaymentTransactionType.CAPTURE);

		assertNull("payonePaymentId must not be inferred from entry requestId", tx.getPayonePaymentId());
		assertNull("payonePaymentExecutionId must not be inferred from entry requestId",
				tx.getPayonePaymentExecutionId());
	}

	@Test
	public void shouldPersistExplicitPcpIdsSetIndependentlyOfEntryRequestId()
	{
		// Explicit IDs (set by CardPaymentStrategy from the auth response, or by
		// AbstractPayoneTransactionWebhookHandler#recordPcpIds from the webhook) must
		// survive entry creation with a different requestId - the entry path must not
		// overwrite them.
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());
		tx.setPayonePaymentId("explicit-payment-id");
		tx.setPayonePaymentExecutionId("explicit-execution-id");

		service.createPaymentTransactionEntry(
				tx, "some-other-pcp-operation-request-id", order, StatusValue.CAPTURED, 9999L, currency,
				PaymentTransactionType.CAPTURE);

		assertEquals("explicit-payment-id", tx.getPayonePaymentId());
		assertEquals("explicit-execution-id", tx.getPayonePaymentExecutionId());
	}

	@Test
	public void shouldReturnNullForUpdatedStatus()
	{
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		final PaymentTransactionEntryModel result = service.createPaymentTransactionEntry(
				tx, "pay-001", order, StatusValue.UPDATED, null, null,
				PaymentTransactionType.AUTHORIZATION);

		assertNull("UPDATED should produce no entry", result);
		verify(modelService, never()).save(any(PaymentTransactionEntryModel.class));
	}

	@Test
	public void shouldSkipDuplicateEntry()
	{
		final PaymentTransactionEntryModel existingEntry = new PaymentTransactionEntryModel();
		existingEntry.setRequestId("pay-001");
		existingEntry.setTransactionStatusDetails("CAPTURED");

		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>(Collections.singletonList(existingEntry)));

		final PaymentTransactionEntryModel result = service.createPaymentTransactionEntry(
				tx, "pay-001", order, StatusValue.CAPTURED, 9999L, currency,
				PaymentTransactionType.AUTHORIZATION);

		assertEquals(existingEntry, result);
		// dedup: no new entry saved for a duplicate requestId
		verify(modelService, never()).save(any(PaymentTransactionEntryModel.class));
	}

	@Test
	public void shouldMapRejectedStatus()
	{
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		final PaymentTransactionEntryModel entry = service.createPaymentTransactionEntry(
				tx, "pay-002", order, StatusValue.REJECTED, null, null,
				PaymentTransactionType.AUTHORIZATION);

		assertNotNull(entry);
		assertEquals("REJECTED", entry.getTransactionStatus());
		assertEquals("REJECTED", entry.getTransactionStatusDetails());
	}

	@Test
	public void shouldMapCreatedToRequested()
	{
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		final PaymentTransactionEntryModel entry = service.createPaymentTransactionEntry(
				tx, "pay-003", order, StatusValue.CREATED, null, null,
				PaymentTransactionType.AUTHORIZATION);

		assertNotNull(entry);
		assertEquals("REQUESTED", entry.getTransactionStatus());
		assertEquals("CREATED", entry.getTransactionStatusDetails());
	}

	@Test
	public void shouldMapRedirectedToWaiting()
	{
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		final PaymentTransactionEntryModel entry = service.createPaymentTransactionEntry(
				tx, "pay-004", order, StatusValue.REDIRECTED, null, null,
				PaymentTransactionType.AUTHORIZATION);

		assertNotNull(entry);
		assertEquals("WAITING", entry.getTransactionStatus());
		assertEquals("REDIRECTED", entry.getTransactionStatusDetails());
	}

	// null parameter rejection

	@Test
	public void shouldRejectNullTransactionOnCreateEntry()
	{
		assertThrows(IllegalArgumentException.class,
				() -> service.createPaymentTransactionEntry(null, "req-1", order,
						StatusValue.CAPTURED, null, null, PaymentTransactionType.AUTHORIZATION));
	}

	@Test
	public void shouldRejectNullRequestId()
	{
		assertThrows(IllegalArgumentException.class,
				() -> service.createPaymentTransactionEntry(new PaymentTransactionModel(), null, order,
						StatusValue.CAPTURED, null, null, PaymentTransactionType.AUTHORIZATION));
	}

	// order-level payment status - amount-aware CAPTURED

	@Test
	public void shouldSetPaidForFullCapture()
	{
		final Double orderTotal = 99.99d;
		when(order.getTotalPrice()).thenReturn(orderTotal);

		when(order.getPaymentTransactions()).thenReturn(createTransactionList("CAPTURED",
				BigDecimal.valueOf(9999L).movePointLeft(2)));

		service.setOrderPaymentStatus(order);

		verify(order).setPaymentStatus(PaymentStatus.PAID);
		verify(modelService).save(order);
	}

	@Test
	public void shouldSetPaidForFullCaptureViaTrigger()
	{
		final Double orderTotal = 99.99d;
		when(order.getTotalPrice()).thenReturn(orderTotal);

		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		final PaymentTransactionEntryModel priorCapture = new PaymentTransactionEntryModel();
		priorCapture.setTransactionStatus("ACCEPTED");
		priorCapture.setTransactionStatusDetails("CAPTURED");
		priorCapture.setAmount(BigDecimal.valueOf(9999L).movePointLeft(2));
		tx.getEntries().add(priorCapture);

		when(order.getPaymentTransactions()).thenReturn(Collections.singletonList(tx));

		// When the trigger-based path runs from createPaymentTransactionEntry for a
		// new CAPTURED entry, it evaluates ALL existing CAPTURED amounts including this one
		service.createPaymentTransactionEntry(
				tx, "pay-cap-001", order, StatusValue.CAPTURED, 9999L, currency,
				PaymentTransactionType.CAPTURE);

		verify(order).setPaymentStatus(PaymentStatus.PAID);
		verify(modelService).save(order);
	}

	@Test
	@Ignore // TODO arch review see service method
	public void shouldSetPartpaidForPartialCapture()
	{
		final Double orderTotal = 100.00d;
		when(order.getTotalPrice()).thenReturn(orderTotal);

		when(order.getPaymentTransactions()).thenReturn(createTransactionList("CAPTURED",
				BigDecimal.valueOf(5000L).movePointLeft(2)));

		service.setOrderPaymentStatus(order);

		verify(order).setPaymentStatus(PaymentStatus.PARTPAID);
		verify(modelService).save(order);
	}

	// order-level payment status - amount-aware REFUNDED

	@Test
	@Ignore // TODO arch review see service method
	public void shouldSetNotpaidForFullRefund()
	{
		final Double orderTotal = 99.99d;
		when(order.getTotalPrice()).thenReturn(orderTotal);

		when(order.getPaymentTransactions()).thenReturn(createTransactionList("REFUNDED",
				BigDecimal.valueOf(9999L).movePointLeft(2)));

		service.setOrderPaymentStatus(order);

		verify(order).setPaymentStatus(PaymentStatus.NOTPAID);
		verify(modelService).save(order);
	}

	@Test
	@Ignore // TODO arch review see service method
	public void shouldSetPartpaidForPartialRefund()
	{
		final Double orderTotal = 100.00d;
		when(order.getTotalPrice()).thenReturn(orderTotal);

		when(order.getPaymentTransactions()).thenReturn(createTransactionList("REFUNDED",
				BigDecimal.valueOf(3000L).movePointLeft(2)));

		service.setOrderPaymentStatus(order);

		verify(order).setPaymentStatus(PaymentStatus.PARTPAID);
		verify(modelService).save(order);
	}

	// order-level payment status - CHARGEBACKED -> NOTPAID

	@Test
	public void shouldSetNotpaidForChargebackViaTrigger()
	{
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		when(order.getPaymentTransactions()).thenReturn(Collections.singletonList(tx));

		service.createPaymentTransactionEntry(
				tx, "cb-001", order, StatusValue.CHARGEBACKED, null, null,
				PaymentTransactionType.AUTHORIZATION);

		verify(order).setPaymentStatus(PaymentStatus.NOTPAID);
		verify(modelService).save(order);
	}

	// operation outcomes - do not change Order.paymentStatus

	@Test
	public void shouldNotChangeOrderStatusForRejectedRefund()
	{
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		service.createPaymentTransactionEntry(
				tx, "pay-005", order, StatusValue.REJECTED_REFUND, null, null,
				PaymentTransactionType.REFUND_FOLLOW_ON);

		// Order.paymentStatus must NOT have been touched (operation outcome)
		verify(order, never()).setPaymentStatus(any());
	}

	@Test
	public void shouldNotChangeOrderStatusForCancellationRequested()
	{
		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		service.createPaymentTransactionEntry(
				tx, "pay-006", order, StatusValue.CANCELLATION_REQUESTED, null, null,
				PaymentTransactionType.CANCEL);

		verify(order, never()).setPaymentStatus(any());
	}

	@Test
	public void shouldSetNotpaidForFullAccountCredited()
	{
		final Double orderTotal = 99.99d;
		when(order.getTotalPrice()).thenReturn(orderTotal);

		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>());

		when(order.getPaymentTransactions()).thenReturn(createTransactionList("ACCOUNT_CREDITED",
				BigDecimal.valueOf(9999L).movePointLeft(2)));

		service.createPaymentTransactionEntry(
				tx, "pay-007", order, StatusValue.ACCOUNT_CREDITED, 9999L, currency,
				PaymentTransactionType.REFUND_STANDALONE);

		verify(order).setPaymentStatus(PaymentStatus.NOTPAID);
		verify(modelService).save(order);
	}

	// helpers

	/**
	 * Creates a mock order with a single PaymentTransaction containing one entry
	 * with the given statusDetails (transactionStatus is always ACCEPTED).
	 */
	private List<PaymentTransactionModel> createTransactionList(final String statusDetails,
																final BigDecimal amount)
	{
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setTransactionStatus("ACCEPTED");
		entry.setTransactionStatusDetails(statusDetails);
		entry.setAmount(amount);

		final PaymentTransactionModel tx = new PaymentTransactionModel();
		tx.setCode(ORDER_CODE + "_PAYONE");
		tx.setOrder(order);
		tx.setEntries(new ArrayList<>(Collections.singletonList(entry)));
		return Collections.singletonList(tx);
	}
}
