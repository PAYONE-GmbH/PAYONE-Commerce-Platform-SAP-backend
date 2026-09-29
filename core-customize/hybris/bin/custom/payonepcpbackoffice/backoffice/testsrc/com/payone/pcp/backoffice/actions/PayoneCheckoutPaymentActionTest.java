/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.backoffice.actions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.hybris.backoffice.widgets.notificationarea.NotificationService;
import com.hybris.backoffice.widgets.notificationarea.event.NotificationEvent;
import com.hybris.cockpitng.actions.ActionContext;
import com.hybris.cockpitng.actions.ActionResult;
import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.facades.PayoneCheckoutOperation;
import com.payone.pcp.facades.PayoneCheckoutOperationException;
import com.payone.pcp.facades.PayoneCheckoutOperationException.Kind;
import com.payone.pcp.facades.PayoneCheckoutOperationsFacade;
import com.payone.pcp.facades.data.PaymentExecutionResultData;


@UnitTest
@RunWith(MockitoJUnitRunner.Silent.class)
public class PayoneCheckoutPaymentActionTest
{
	@Mock
	private PayoneCheckoutOperationsFacade service;
	@Mock
	private NotificationService notificationService;
	@Mock
	private ActionContext<PayoneCheckoutModel> ctx;
	@Mock
	private PayoneCheckoutModel checkout;

	@InjectMocks
	private PayoneCheckoutRefundAction refundAction;
	@InjectMocks
	private PayoneCheckoutCaptureAction captureAction;

	private void givenCheckout()
	{
		when(ctx.getData()).thenReturn(checkout);
		when(checkout.getCheckoutId()).thenReturn("co-1");
	}

	@Test
	public void alwaysNeedsConfirmation()
	{
		assertTrue(refundAction.needsConfirmation(ctx));
		assertTrue(captureAction.needsConfirmation(ctx));
	}

	@Test
	public void canPerform_followsServiceAvailability()
	{
		givenCheckout();
		when(service.isAvailable(checkout, PayoneCheckoutOperation.REFUND)).thenReturn(false);
		assertFalse(refundAction.canPerform(ctx));
		when(service.isAvailable(checkout, PayoneCheckoutOperation.CAPTURE)).thenReturn(true);
		assertTrue(captureAction.canPerform(ctx));
	}

	@Test
	public void canPerform_falseWithoutData()
	{
		assertFalse(refundAction.canPerform(ctx));
		assertFalse(refundAction.canPerform(null));
	}

	@Test
	public void refund_success_notifiesAndDelegatesRefund()
	{
		givenCheckout();
		final PaymentExecutionResultData data = new PaymentExecutionResultData();
		data.setStatus("PENDING");
		when(service.execute(checkout, PayoneCheckoutOperation.REFUND)).thenReturn(data);

		final ActionResult<Object> result = refundAction.perform(ctx);

		assertEquals(ActionResult.SUCCESS, result.getResultCode());
		verify(notificationService).notifyUser(eq(ctx), eq(AbstractPayoneCheckoutPaymentAction.EVENT_SUCCESS),
				eq(NotificationEvent.Level.SUCCESS), eq("REFUND"), eq("co-1"), eq("PENDING"));
	}

	@Test
	public void capture_delegatesCapture()
	{
		givenCheckout();
		when(service.execute(checkout, PayoneCheckoutOperation.CAPTURE)).thenReturn(new PaymentExecutionResultData());

		assertEquals(ActionResult.SUCCESS, captureAction.perform(ctx).getResultCode());
		verify(service).execute(checkout, PayoneCheckoutOperation.CAPTURE);
	}

	@Test
	public void rejected_reportsFailure()
	{
		givenCheckout();
		when(service.execute(any(), any())).thenThrow(new PayoneCheckoutOperationException(Kind.REJECTED, "no"));

		assertEquals(ActionResult.ERROR, refundAction.perform(ctx).getResultCode());
		verify(notificationService).notifyUser(eq(ctx), eq(AbstractPayoneCheckoutPaymentAction.EVENT_REJECTED),
				eq(NotificationEvent.Level.FAILURE), any(Object[].class));
	}

	@Test
	public void indeterminate_reportsWarningNotFailure()
	{
		givenCheckout();
		when(service.execute(any(), any())).thenThrow(new PayoneCheckoutOperationException(Kind.INDETERMINATE, "timeout"));

		assertEquals(ActionResult.ERROR, captureAction.perform(ctx).getResultCode());
		verify(notificationService).notifyUser(eq(ctx), eq(AbstractPayoneCheckoutPaymentAction.EVENT_INDETERMINATE),
				eq(NotificationEvent.Level.WARNING), any(Object[].class));
	}

	@Test
	public void formatsMinorUnitsForConfirmation()
	{
		assertEquals("19.99", AbstractPayoneCheckoutPaymentAction.formatMinorUnits(1999L));
		assertEquals("0.05", AbstractPayoneCheckoutPaymentAction.formatMinorUnits(5L));
		assertEquals("-", AbstractPayoneCheckoutPaymentAction.formatMinorUnits(null));
	}
}
