/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.facades.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.user.UserGroupModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.store.BaseStoreModel;

import java.util.List;

import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.payone.commerce.platform.lib.errors.ApiErrorResponseException;
import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.commerce.platform.lib.models.CheckoutResponse;
import com.payone.commerce.platform.lib.models.StatusCheckout;
import com.payone.commerce.platform.lib.models.StatusOutput;
import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayoneCheckoutService;
import com.payone.pcp.core.service.PayoneConfigurationService;
import com.payone.pcp.facades.PayoneCheckoutOperation;
import com.payone.pcp.facades.PayoneCheckoutOperationException;
import com.payone.pcp.facades.PayoneCheckoutOperationException.Kind;
import com.payone.pcp.facades.PayonePaymentOperationsFacade;
import com.payone.pcp.facades.data.PaymentExecutionResultData;


/**
 * Tests the checks of DefaultPayoneCheckoutOperationsFacade. executeLocked is
 * exercised directly: the transaction/row-lock wrapper in execute() needs a
 * running platform and is covered by the Backoffice end-to-end check.
 */
@UnitTest
@RunWith(MockitoJUnitRunner.Silent.class)
public class DefaultPayoneCheckoutOperationsFacadeTest
{
	private static final String CC = "cc-1";
	private static final String CO = "co-1";
	private static final String EX = "exec-1";

	@Mock
	private PayonePaymentOperationsFacade paymentOperationsFacade;
	@Mock
	private PayoneCheckoutService checkoutService;
	@Mock
	private PayoneConfigurationService configurationServicePcp;
	@Mock
	private ModelService modelService;
	@Mock
	private UserService userService;
	@Mock
	private ConfigurationService configurationService;
	@Mock
	private UserModel user;
	@Mock
	private UserGroupModel group;
	@Mock
	private PayoneCheckoutModel checkout;
	@Mock
	private PayoneCommerceCaseModel commerceCase;
	@Mock
	private OrderModel order;
	@Mock
	private BaseStoreModel store;
	@Mock
	private PayoneConfigurationModel pcpConfig;

	private final BaseConfiguration configuration = new BaseConfiguration();
	private final CheckoutResponse live = new CheckoutResponse();
	private DefaultPayoneCheckoutOperationsFacade facade;

	@Before
	public void setUp()
	{
		facade = new DefaultPayoneCheckoutOperationsFacade();
		facade.setPayonePaymentOperationsFacade(paymentOperationsFacade);
		facade.setPayoneCheckoutService(checkoutService);
		facade.setPayoneConfigurationService(configurationServicePcp);
		facade.setModelService(modelService);
		facade.setUserService(userService);
		facade.setConfigurationService(configurationService);

		when(configurationService.getConfiguration()).thenReturn(configuration);
		configuration.setProperty(DefaultPayoneCheckoutOperationsFacade.ENABLED_PROPERTY, "true");
		when(userService.getCurrentUser()).thenReturn(user);
		when(user.getUid()).thenReturn("operator");
		when(userService.getUserGroupForUID(DefaultPayoneCheckoutOperationsFacade.DEFAULT_USER_GROUP)).thenReturn(group);
		when(userService.isMemberOfGroup(user, group)).thenReturn(true);

		when(checkout.getOrder()).thenReturn(order);
		when(checkout.getCommerceCase()).thenReturn(commerceCase);
		when(commerceCase.getCommerceCaseId()).thenReturn(CC);
		when(checkout.getCheckoutId()).thenReturn(CO);
		when(checkout.getPaymentExecutionId()).thenReturn(EX);
		when(order.getCode()).thenReturn("order-1");
		when(order.getStore()).thenReturn(store);
		final List<PaymentTransactionModel> rows = List.of(row(CC, CO, EX));
		when(order.getPaymentTransactions()).thenReturn(rows);

		when(configurationServicePcp.getActiveConfigurationForStore(store)).thenReturn(pcpConfig);
		live.setAmountOfMoney(new AmountOfMoney().amount(1999L).currencyCode("EUR"));
		live.setCheckoutStatus(StatusCheckout.COMPLETED);
		live.statusOutput(new StatusOutput().openAmount(1999L).collectedAmount(0L).refundedAmount(0L));
		when(checkoutService.getCheckout(pcpConfig, CC, CO)).thenReturn(live);
	}

	private static PaymentTransactionModel row(final String cc, final String co, final String ex)
	{
		final PaymentTransactionModel tx = mock(PaymentTransactionModel.class);
		when(tx.getPayoneCommerceCaseId()).thenReturn(cc);
		when(tx.getPayoneCheckoutId()).thenReturn(co);
		when(tx.getPayonePaymentExecutionId()).thenReturn(ex);
		return tx;
	}

	private void givenBilled(final long collected, final long refunded)
	{
		live.setCheckoutStatus(StatusCheckout.BILLED);
		live.statusOutput(new StatusOutput().openAmount(0L).collectedAmount(collected).refundedAmount(refunded));
	}

	private void assertRejected(final PayoneCheckoutOperation operation)
	{
		final PayoneCheckoutOperationException e = assertThrows(PayoneCheckoutOperationException.class,
				() -> facade.executeLocked(checkout, operation));
		assertEquals(Kind.REJECTED, e.getKind());
		verifyNoInteractions(paymentOperationsFacade);
	}

	@Test
	public void capture_sendsOpenAmountFromPcp()
	{
		live.statusOutput(new StatusOutput().openAmount(1500L));
		final PaymentExecutionResultData result = new PaymentExecutionResultData();
		when(paymentOperationsFacade.capturePayment(order, 1500L)).thenReturn(result);

		assertSame(result, facade.executeLocked(checkout, PayoneCheckoutOperation.CAPTURE));
	}

	@Test
	public void refund_sendsCollectedMinusRefundedWithCurrency()
	{
		givenBilled(1999L, 499L);
		final PaymentExecutionResultData result = new PaymentExecutionResultData();
		when(paymentOperationsFacade.refundPayment(eq(order), any(AmountOfMoney.class))).thenReturn(result);

		assertSame(result, facade.executeLocked(checkout, PayoneCheckoutOperation.REFUND));
		verify(paymentOperationsFacade).refundPayment(eq(order),
				argThat(money -> money.getAmount() == 1500L && "EUR".equals(money.getCurrencyCode())));
	}

	@Test
	public void refund_rejectedWhenAlreadyFullyRefunded()
	{
		givenBilled(1999L, 1999L);
		assertRejected(PayoneCheckoutOperation.REFUND);
	}

	@Test
	public void capture_rejectedWhenNothingOpen()
	{
		live.statusOutput(new StatusOutput().openAmount(0L));
		assertRejected(PayoneCheckoutOperation.CAPTURE);
	}

	@Test
	public void refund_rejectedForCompletedButNotBilled()
	{
		assertRejected(PayoneCheckoutOperation.REFUND);
	}

	@Test
	public void capture_rejectedForOpenCheckout()
	{
		live.setCheckoutStatus(StatusCheckout.OPEN);
		assertRejected(PayoneCheckoutOperation.CAPTURE);
	}

	@Test
	public void capture_allowedForBilledWithOpenRemainder()
	{
		live.setCheckoutStatus(StatusCheckout.BILLED);
		live.statusOutput(new StatusOutput().openAmount(500L));
		when(paymentOperationsFacade.capturePayment(order, 500L)).thenReturn(new PaymentExecutionResultData());

		facade.executeLocked(checkout, PayoneCheckoutOperation.CAPTURE);
		verify(paymentOperationsFacade).capturePayment(order, 500L);
	}

	@Test
	public void rejectedWhenPcpReturnsNoAmounts()
	{
		live.statusOutput(null);
		assertRejected(PayoneCheckoutOperation.CAPTURE);
	}

	@Test
	public void rejectedWhenPcpReadFails_nothingExecuted()
	{
		when(checkoutService.getCheckout(pcpConfig, CC, CO)).thenThrow(new IllegalStateException("timeout"));
		assertRejected(PayoneCheckoutOperation.CAPTURE);
	}

	@Test
	public void rejectedWhenStoreHasNoPayoneConfiguration()
	{
		when(configurationServicePcp.getActiveConfigurationForStore(store)).thenReturn(null);
		assertRejected(PayoneCheckoutOperation.REFUND);
		verifyNoInteractions(checkoutService);
	}

	@Test
	public void pcpErrorResponse_isRejected()
	{
		when(paymentOperationsFacade.capturePayment(eq(order), anyLong()))
				.thenThrow(new IllegalStateException("bad request", mock(ApiErrorResponseException.class)));

		final PayoneCheckoutOperationException e = assertThrows(PayoneCheckoutOperationException.class,
				() -> facade.executeLocked(checkout, PayoneCheckoutOperation.CAPTURE));
		assertEquals(Kind.REJECTED, e.getKind());
	}

	@Test
	public void ioFailure_isIndeterminate()
	{
		when(paymentOperationsFacade.capturePayment(eq(order), anyLong())).thenThrow(new IllegalStateException("read timed out"));

		final PayoneCheckoutOperationException e = assertThrows(PayoneCheckoutOperationException.class,
				() -> facade.executeLocked(checkout, PayoneCheckoutOperation.CAPTURE));
		assertEquals(Kind.INDETERMINATE, e.getKind());
	}

	@Test
	public void disabledByDefault()
	{
		configuration.clearProperty(DefaultPayoneCheckoutOperationsFacade.ENABLED_PROPERTY);
		assertFalse(facade.isAvailable(checkout, PayoneCheckoutOperation.REFUND));
		assertThrows(PayoneCheckoutOperationException.class, () -> facade.execute(checkout, PayoneCheckoutOperation.REFUND));
		verifyNoInteractions(paymentOperationsFacade, checkoutService, modelService);
	}

	@Test
	public void rejectedWhenUserNotInGroup()
	{
		when(userService.isMemberOfGroup(user, group)).thenReturn(false);
		assertFalse(facade.isAvailable(checkout, PayoneCheckoutOperation.CAPTURE));
		assertThrows(PayoneCheckoutOperationException.class, () -> facade.execute(checkout, PayoneCheckoutOperation.CAPTURE));
		verify(modelService, never()).lock(any(de.hybris.platform.core.PK.class));
	}

	@Test
	public void rejectedWhenConfiguredGroupUnknown()
	{
		configuration.setProperty(DefaultPayoneCheckoutOperationsFacade.USER_GROUP_PROPERTY, "nosuchgroup");
		when(userService.getUserGroupForUID("nosuchgroup")).thenThrow(new UnknownIdentifierException("x"));
		assertFalse(facade.isAvailable(checkout, PayoneCheckoutOperation.CAPTURE));
	}

	@Test
	public void rejectedWhenCheckoutHasNoOrder()
	{
		when(checkout.getOrder()).thenReturn(null);
		assertRejected(PayoneCheckoutOperation.CAPTURE);
	}

	@Test
	public void rejectedWhenCheckoutIdentifiersIncomplete()
	{
		when(checkout.getPaymentExecutionId()).thenReturn(" ");
		assertRejected(PayoneCheckoutOperation.CAPTURE);
	}

	@Test
	public void rejectedWhenOrderHasNoPcpTransaction()
	{
		final List<PaymentTransactionModel> rows = List.of(row(null, null, null));
		when(order.getPaymentTransactions()).thenReturn(rows);
		assertRejected(PayoneCheckoutOperation.CAPTURE);
	}

	@Test
	public void rejectedWhenOrderBelongsToDifferentCheckout()
	{
		final List<PaymentTransactionModel> rows = List.of(row(CC, CO, EX), row(CC, "co-other", EX));
		when(order.getPaymentTransactions()).thenReturn(rows);
		assertRejected(PayoneCheckoutOperation.REFUND);
	}

	@Test
	public void isAvailable_trueWhenLocalChecksPass()
	{
		assertTrue(facade.isAvailable(checkout, PayoneCheckoutOperation.CAPTURE));
		verifyNoInteractions(checkoutService);
	}
}
