/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.backoffice.widgets;

import com.hybris.backoffice.navigation.TreeNodeSelector;
import com.hybris.cockpitng.annotations.ViewEvent;
import com.hybris.cockpitng.util.DefaultWidgetController;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zul.Label;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.payone.pcp.backoffice.services.PayoneOperationsSummary;
import com.payone.pcp.backoffice.services.PayonepcpbackofficeService;


public class PayonepcpbackofficeController extends DefaultWidgetController
{
	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(PayonepcpbackofficeController.class);
	private static final String NAVIGATE_TO_NODE = "navigateToNode";
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
			.withZone(ZoneId.systemDefault());

	@Wire
	private transient Label configurationsValue;
	@Wire
	private transient Label commerceCasesValue;
	@Wire
	private transient Label recentCheckoutsValue;
	@Wire
	private transient Label webhookBacklogValue;
	@Wire
	private transient Label retryingWebhooksValue;
	@Wire
	private transient Label latestWebhookValue;
	@Wire
	private transient Label lastUpdatedValue;
	@Wire
	private transient Label errorLabel;

	@WireVariable
	private transient PayonepcpbackofficeService payonepcpbackofficeService;

	@Override
	public void initialize(final Component comp)
	{
		super.initialize(comp);
		refresh();
	}

	@ViewEvent(componentID = "refreshButton", eventName = Events.ON_CLICK)
	public void refresh()
	{
		try
		{
			final PayoneOperationsSummary summary = payonepcpbackofficeService.getOperationsSummary();
			configurationsValue.setValue(summary.getActiveConfigurations() + " / " + summary.getTotalConfigurations());
			commerceCasesValue.setValue(Long.toString(summary.getCommerceCases()));
			recentCheckoutsValue.setValue(Long.toString(summary.getRecentCheckouts()));
			webhookBacklogValue.setValue(Long.toString(summary.getPendingWebhooks()));
			retryingWebhooksValue.setValue(Long.toString(summary.getRetryingWebhooks()));
			latestWebhookValue.setValue(formatDate(summary.getLatestWebhookReceivedTime()));
			lastUpdatedValue.setValue(DATE_TIME_FORMATTER.format(Instant.now()));
			errorLabel.setVisible(false);
		}
		catch (final RuntimeException exception)
		{
			LOG.warn("Could not refresh the PAYONE operations widget", exception);
			errorLabel.setVisible(true);
		}
	}

	@ViewEvent(componentID = "configurationsButton", eventName = Events.ON_CLICK)
	public void openConfigurations()
	{
		navigateTo("payonepcpbackoffice.typenode.configuration");
	}

	@ViewEvent(componentID = "commerceCasesButton", eventName = Events.ON_CLICK)
	public void openCommerceCases()
	{
		navigateTo("payonepcpbackoffice.typenode.commercecases");
	}

	@ViewEvent(componentID = "checkoutsButton", eventName = Events.ON_CLICK)
	public void openCheckouts()
	{
		navigateTo("payonepcpbackoffice.typenode.checkouts");
	}

	@ViewEvent(componentID = "webhooksButton", eventName = Events.ON_CLICK)
	public void openWebhooks()
	{
		navigateTo("payonepcpbackoffice.typenode.webhooks");
	}

	protected void navigateTo(final String nodeId)
	{
		sendOutput(NAVIGATE_TO_NODE, new TreeNodeSelector(nodeId, true));
	}

	protected String formatDate(final java.util.Date date)
	{
		return date == null ? "\u2014" : DATE_TIME_FORMATTER.format(date.toInstant());
	}
}
