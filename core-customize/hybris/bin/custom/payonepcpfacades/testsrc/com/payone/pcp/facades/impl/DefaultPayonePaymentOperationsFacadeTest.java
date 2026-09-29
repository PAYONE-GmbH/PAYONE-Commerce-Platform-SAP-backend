package com.payone.pcp.facades.impl;

import com.payone.pcp.core.converters.PcpAmountOfMoneyConverter;
import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;
import com.payone.pcp.core.service.PayoneConfigurationService;
import com.payone.pcp.core.service.PayoneTransactionIdentityConflictException;
import com.payone.pcp.core.service.PayoneTransactionService;
import com.payone.pcp.core.strategy.PayonePaymentStrategy;
import com.payone.pcp.core.strategy.PaymentStrategyRegistry;
import com.payone.pcp.facades.data.PaymentExecutionResultData;

import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.commerce.platform.lib.models.StatusValue;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.store.BaseStoreModel;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@UnitTest
public class DefaultPayonePaymentOperationsFacadeTest
{
	private static final String COMMERCE_CASE_ID = "cc-1";
	private static final String CHECKOUT_ID = "co-1";
	private static final String PAYMENT_EXECUTION_ID = "exec-1";

	@Mock
	private PayoneConfigurationService payoneConfigurationService;
	@Mock
	private PayoneTransactionService payoneTransactionService;
	@Mock
	private PaymentStrategyRegistry paymentStrategyRegistry;
	@Mock
	private PcpAmountOfMoneyConverter pcpAmountOfMoneyConverter;

	@Mock
	private BaseStoreModel store;
	@Mock
	private PayoneConfigurationModel configuration;
	@Mock
	private PaymentTransactionModel transaction;
	@Mock
	private PayonePaymentStrategy strategy;
	@Mock
	private AmountOfMoney amountOfMoney;

	private DefaultPayonePaymentOperationsFacade facade;

	@Before
	public void setUp()
	{
		MockitoAnnotations.openMocks(this);

		facade = new DefaultPayonePaymentOperationsFacade();
		facade.setPayoneConfigurationService(payoneConfigurationService);
		facade.setPayoneTransactionService(payoneTransactionService);
		facade.setPaymentStrategyRegistry(paymentStrategyRegistry);
		facade.setPcpAmountOfMoneyConverter(pcpAmountOfMoneyConverter);
	}

	/**
	 * Wires an OrderModel with a PCP PaymentTransaction (found via
	 * order.getPaymentTransactions(), not getOrCreatePaymentTransaction - see
	 * findPaymentTransaction's own Javadoc) and a PayonePaymentInfo carrying
	 * paymentProductId, the precondition every capture/cancel/refund test needs.
	 */
	private OrderModel givenOrderReadyForPostAuthorizationOperation(final int paymentProductId)
	{
		final OrderModel order = mock(OrderModel.class);
		when(order.getStore()).thenReturn(store);
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);

		when(transaction.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(transaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(transaction.getPayonePaymentExecutionId()).thenReturn(PAYMENT_EXECUTION_ID);
		when(order.getPaymentTransactions()).thenReturn(List.of(transaction));

		final PayonePaymentInfoModel paymentInfo = mock(PayonePaymentInfoModel.class);
		when(paymentInfo.getPaymentProductId()).thenReturn(paymentProductId);
		when(order.getPaymentInfo()).thenReturn(paymentInfo);

		when(payoneConfigurationService.getAllowedPaymentProductIds(store)).thenReturn(Collections.emptyList());
		when(paymentStrategyRegistry.getStrategy(paymentProductId, Collections.emptyList())).thenReturn(strategy);

		when(amountOfMoney.getAmount()).thenReturn(1000L);
		when(pcpAmountOfMoneyConverter.convert(order)).thenReturn(amountOfMoney);

		return order;
	}

	@Test
	public void capturePayment_throwsIllegalState_whenOrderHasNoPcpTransaction()
	{
		final OrderModel order = mock(OrderModel.class);
		when(order.getStore()).thenReturn(store);
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(order.getPaymentTransactions()).thenReturn(Collections.emptyList());

		assertThrows(IllegalStateException.class, () -> facade.capturePayment(order));
	}

	@Test
	public void capturePayment_throwsIllegalState_whenOrderHasNoPaymentProductId()
	{
		final OrderModel order = mock(OrderModel.class);
		when(order.getStore()).thenReturn(store);
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(transaction.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(transaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(transaction.getPayonePaymentExecutionId()).thenReturn(PAYMENT_EXECUTION_ID);
		when(order.getPaymentTransactions()).thenReturn(List.of(transaction));
		when(order.getPaymentInfo()).thenReturn(null);

		assertThrows(IllegalStateException.class, () -> facade.capturePayment(order));
	}

	@Test
	public void capturePayment_throwsIllegalState_whenNoStrategyForProduct()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(999);
		when(paymentStrategyRegistry.getStrategy(999, Collections.emptyList())).thenReturn(null);

		assertThrows(IllegalStateException.class, () -> facade.capturePayment(order));
	}

	@Test
	public void capturePayment_returnsMappedResult_onSuccess()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentExecutionResponse response =
				new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.CAPTURED, null);
		when(strategy.capture(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, null)).thenReturn(response);

		final PaymentExecutionResultData result = facade.capturePayment(order);

		assertEquals("pay-1", result.getPaymentId());
		assertEquals("exec-2", result.getPaymentExecutionId());
		verify(payoneTransactionService).createPaymentTransactionEntry(
				transaction, "pay-1", order, StatusValue.CAPTURED, 1000L, order.getCurrency(),
				PaymentTransactionType.CAPTURE);
	}

	@Test
	public void cancelPayment_returnsMappedResult_onSuccess()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentExecutionResponse response =
				new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.CANCELLED, null);
		when(strategy.cancel(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID)).thenReturn(response);

		final PaymentExecutionResultData result = facade.cancelPayment(order);

		assertEquals("pay-1", result.getPaymentId());
		verify(payoneTransactionService).createPaymentTransactionEntry(
				transaction, "pay-1", order, StatusValue.CANCELLED, 1000L, order.getCurrency(),
				PaymentTransactionType.CANCEL);
	}

	@Test
	public void refundPayment_returnsMappedResult_onSuccess()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentExecutionResponse response =
				new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.REFUNDED, null);
		when(strategy.refund(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, amountOfMoney)).thenReturn(response);

		final PaymentExecutionResultData result = facade.refundPayment(order);

		assertEquals("pay-1", result.getPaymentId());
		verify(payoneTransactionService).createPaymentTransactionEntry(
				transaction, "pay-1", order, StatusValue.REFUNDED, 1000L, order.getCurrency(),
				PaymentTransactionType.REFUND_FOLLOW_ON);
	}

	@Test
	public void refundPayment_throwsIllegalState_whenPcpCallFails()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		when(strategy.refund(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("PCP down"));

		assertThrows(IllegalStateException.class, () -> facade.refundPayment(order));
	}

	@Test
	public void completePayment_returnsMappedResult_onSuccess()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentExecutionResponse response =
				new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.CAPTURED, null);
		when(strategy.complete(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID)).thenReturn(response);

		final PaymentExecutionResultData result = facade.completePayment(order);

		assertEquals("pay-1", result.getPaymentId());
		verify(payoneTransactionService).createPaymentTransactionEntry(
				transaction, "pay-1", order, StatusValue.CAPTURED, 1000L, order.getCurrency(),
				PaymentTransactionType.CAPTURE);
	}

	@Test
	public void pausePayment_returnsMappedResult_andWritesNoTransactionEntry()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentExecutionResponse response =
				new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.PAUSED, null);
		when(strategy.pause(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID)).thenReturn(response);

		final PaymentExecutionResultData result = facade.pausePayment(order);

		assertEquals("pay-1", result.getPaymentId());
		Mockito.verifyNoInteractions(payoneTransactionService);
	}

	@Test
	public void refreshPayment_returnsMappedResult_andWritesNoTransactionEntry()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentExecutionResponse response =
				new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.CAPTURED, null);
		when(strategy.refresh(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID)).thenReturn(response);

		final PaymentExecutionResultData result = facade.refreshPayment(order);

		assertEquals("pay-1", result.getPaymentId());
		Mockito.verifyNoInteractions(payoneTransactionService);
	}

	@Test
	public void pausePayment_throwsIllegalState_whenNoStrategyForProduct()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(999);
		when(paymentStrategyRegistry.getStrategy(999, Collections.emptyList())).thenReturn(null);

		assertThrows(IllegalStateException.class, () -> facade.pausePayment(order));
	}

	/** Builds a PCP transaction row mock with the given identity, modifiedtime and PK. */
	private static PaymentTransactionModel pcpRow(final String commerceCaseId, final String checkoutId,
			final String paymentExecutionId, final long modifiedMillis, final long pk)
	{
		final PaymentTransactionModel row = mock(PaymentTransactionModel.class);
		when(row.getPayoneCommerceCaseId()).thenReturn(commerceCaseId);
		when(row.getPayoneCheckoutId()).thenReturn(checkoutId);
		when(row.getPayonePaymentExecutionId()).thenReturn(paymentExecutionId);
		when(row.getModifiedtime()).thenReturn(new Date(modifiedMillis));
		when(row.getPk()).thenReturn(PK.fromLong(pk));
		return row;
	}

	private void verifyNoStrategyCall()
	{
		verify(strategy, never()).refund(any(), anyString(), anyString(), anyString(), any());
		verify(strategy, never()).capture(any(), anyString(), anyString(), anyString(), any());
		Mockito.verifyNoInteractions(payoneTransactionService);
	}

	@Test
	public void refundPayment_rejects_whenAnyPcpRowIsIncomplete()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentTransactionModel incomplete = pcpRow(COMMERCE_CASE_ID, null, PAYMENT_EXECUTION_ID, 1L, 2L);
		when(order.getPaymentTransactions()).thenReturn(List.of(transaction, incomplete));

		assertThrows(IllegalStateException.class, () -> facade.refundPayment(order));
		verifyNoStrategyCall();
	}

	@Test
	public void capturePayment_rejects_whenPcpRowsCarryConflictingTuples()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentTransactionModel other = pcpRow(COMMERCE_CASE_ID, "co-2", PAYMENT_EXECUTION_ID, 1L, 2L);
		when(order.getPaymentTransactions()).thenReturn(List.of(transaction, other));

		assertThrows(PayoneTransactionIdentityConflictException.class, () -> facade.capturePayment(order));
		verifyNoStrategyCall();
	}

	@Test
	public void refundPayment_ignoresNonPcpRows()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentTransactionModel nonPcp = mock(PaymentTransactionModel.class);
		when(order.getPaymentTransactions()).thenReturn(List.of(nonPcp, transaction));
		when(strategy.refund(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, amountOfMoney))
				.thenReturn(new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.REFUNDED, null));

		facade.refundPayment(order);

		verify(strategy).refund(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, amountOfMoney);
	}

	@Test
	public void refundPayment_usesNewestRow_amongIdenticalTupleDuplicates()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentTransactionModel older = pcpRow(COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, 100L, 9L);
		final PaymentTransactionModel newer = pcpRow(COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, 200L, 1L);
		when(order.getPaymentTransactions()).thenReturn(List.of(newer, older));
		when(strategy.refund(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, amountOfMoney))
				.thenReturn(new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.REFUNDED, null));

		facade.refundPayment(order);

		verify(payoneTransactionService).createPaymentTransactionEntry(
				newer, "pay-1", order, StatusValue.REFUNDED, 1000L, order.getCurrency(),
				PaymentTransactionType.REFUND_FOLLOW_ON);
	}

	@Test
	public void refundPayment_breaksModifiedtimeTie_byHighestPk()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final PaymentTransactionModel lowPk = pcpRow(COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, 100L, 3L);
		final PaymentTransactionModel highPk = pcpRow(COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, 100L, 7L);
		when(order.getPaymentTransactions()).thenReturn(List.of(highPk, lowPk));
		when(strategy.refund(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, amountOfMoney))
				.thenReturn(new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.REFUNDED, null));

		facade.refundPayment(order);

		verify(payoneTransactionService).createPaymentTransactionEntry(
				highPk, "pay-1", order, StatusValue.REFUNDED, 1000L, order.getCurrency(),
				PaymentTransactionType.REFUND_FOLLOW_ON);
	}

	@Test
	public void capturePayment_withAmount_passesAmountAndRecordsIt()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		when(strategy.capture(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, 500L))
				.thenReturn(new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.CAPTURE_REQUESTED, null));

		facade.capturePayment(order, 500L);

		verify(payoneTransactionService).createPaymentTransactionEntry(
				transaction, "pay-1", order, StatusValue.CAPTURE_REQUESTED, 500L, order.getCurrency(),
				PaymentTransactionType.CAPTURE);
	}

	@Test
	public void refundPayment_withAmount_passesAmountAndRecordsIt()
	{
		final OrderModel order = givenOrderReadyForPostAuthorizationOperation(1);
		final AmountOfMoney partial = new AmountOfMoney().amount(700L).currencyCode("EUR");
		when(strategy.refund(configuration, COMMERCE_CASE_ID, CHECKOUT_ID, PAYMENT_EXECUTION_ID, partial))
				.thenReturn(new PaymentExecutionResponse("pay-1", "exec-2", StatusValue.REFUND_REQUESTED, null));

		facade.refundPayment(order, partial);

		verify(payoneTransactionService).createPaymentTransactionEntry(
				transaction, "pay-1", order, StatusValue.REFUND_REQUESTED, 700L, order.getCurrency(),
				PaymentTransactionType.REFUND_FOLLOW_ON);
	}
}
