/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.backoffice.actions;

import java.math.BigDecimal;
import java.util.EnumSet;

import jakarta.annotation.Resource;

import com.hybris.backoffice.widgets.notificationarea.NotificationService;
import com.hybris.backoffice.widgets.notificationarea.event.NotificationEvent;
import com.hybris.cockpitng.actions.ActionContext;
import com.hybris.cockpitng.actions.ActionResult;
import com.hybris.cockpitng.actions.CockpitAction;
import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.facades.PayoneCheckoutOperation;
import com.payone.pcp.facades.PayoneCheckoutOperationException;
import com.payone.pcp.facades.PayoneCheckoutOperationsFacade;
import com.payone.pcp.facades.data.PaymentExecutionResultData;


/**
 * Checkout-editor action for a PCP Capture/Refund. Thin adapter: all
 * checks (feature flag, user group, identity, live PCP state and amount,
 * row lock) live in {@link PayoneCheckoutOperationsFacade} and run on
 * {@link #perform}; {@link #canPerform} only drives button enablement.
 */
public abstract class AbstractPayoneCheckoutPaymentAction implements CockpitAction<PayoneCheckoutModel, Object>
{
	static final String EVENT_SUCCESS = "payonepcpbackoffice.paymentaction.success";
	static final String EVENT_REJECTED = "payonepcpbackoffice.paymentaction.rejected";
	static final String EVENT_INDETERMINATE = "payonepcpbackoffice.paymentaction.indeterminate";

	@Resource(name = "payoneCheckoutOperationsFacade")
	private PayoneCheckoutOperationsFacade payoneCheckoutOperationsFacade;

	@Resource(name = "notificationService")
	private NotificationService notificationService;

	protected abstract PayoneCheckoutOperation getOperation();

	@Override
	public boolean canPerform(final ActionContext<PayoneCheckoutModel> ctx)
	{
		return ctx != null && ctx.getData() != null && payoneCheckoutOperationsFacade.isAvailable(ctx.getData(), getOperation());
	}

	@Override
	public boolean needsConfirmation(final ActionContext<PayoneCheckoutModel> ctx)
	{
		return true;
	}

	@Override
	public String getConfirmationMessage(final ActionContext<PayoneCheckoutModel> ctx)
	{
		final PayoneCheckoutModel checkout = ctx.getData();
		return ctx.getLabel("confirmation.message", new Object[] {
				checkout.getCheckoutId(),
				checkout.getOrder() != null ? checkout.getOrder().getCode() : "-",
				formatMinorUnits(checkout.getAmount()),
				checkout.getCurrencyIsoCode() });
	}

	/** Checkout amounts are stored in minor units (cents); show 1999 as "19.99". */
	static String formatMinorUnits(final Long amount)
	{
		return amount != null ? BigDecimal.valueOf(amount).movePointLeft(2).toPlainString() : "-";
	}

	@Override
	public ActionResult<Object> perform(final ActionContext<PayoneCheckoutModel> ctx)
	{
		final PayoneCheckoutModel checkout = ctx.getData();
		final String operation = getOperation().name();
		try
		{
			final PaymentExecutionResultData result = payoneCheckoutOperationsFacade.execute(checkout, getOperation());
			notificationService.notifyUser(ctx, EVENT_SUCCESS, NotificationEvent.Level.SUCCESS, operation,
					checkout.getCheckoutId(), result.getStatus());
			final ActionResult<Object> actionResult = new ActionResult<>(ActionResult.SUCCESS, checkout);
			actionResult.setStatusFlags(EnumSet.of(ActionResult.StatusFlag.OBJECT_PERSISTED));
			return actionResult;
		}
		catch (final PayoneCheckoutOperationException e)
		{
			final boolean indeterminate = e.getKind() == PayoneCheckoutOperationException.Kind.INDETERMINATE;
			notificationService.notifyUser(ctx, indeterminate ? EVENT_INDETERMINATE : EVENT_REJECTED,
					indeterminate ? NotificationEvent.Level.WARNING : NotificationEvent.Level.FAILURE, operation,
					checkout.getCheckoutId(), e.getMessage());
			return new ActionResult<>(ActionResult.ERROR);
		}
	}
}
