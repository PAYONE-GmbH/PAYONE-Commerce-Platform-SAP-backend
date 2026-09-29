/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.backoffice.widgets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hybris.backoffice.navigation.TreeNodeSelector;
import com.hybris.cockpitng.engine.WidgetInstanceManager;

import de.hybris.bootstrap.annotations.UnitTest;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.zkoss.zul.Label;

import com.payone.pcp.backoffice.services.PayoneOperationsSummary;
import com.payone.pcp.backoffice.services.PayonepcpbackofficeService;


@UnitTest
@RunWith(MockitoJUnitRunner.class)
public class PayonepcpbackofficeControllerTest
{
	@Mock
	private PayonepcpbackofficeService payonepcpbackofficeService;
	@Mock
	private WidgetInstanceManager widgetInstanceManager;
	@Mock
	private Label configurationsValue;
	@Mock
	private Label commerceCasesValue;
	@Mock
	private Label recentCheckoutsValue;
	@Mock
	private Label webhookBacklogValue;
	@Mock
	private Label retryingWebhooksValue;
	@Mock
	private Label latestWebhookValue;
	@Mock
	private Label lastUpdatedValue;
	@Mock
	private Label errorLabel;

	@Captor
	private ArgumentCaptor<Object> outputCaptor;

	@InjectMocks
	private PayonepcpbackofficeController controller;

	@Before
	public void setUp()
	{
		controller.setWidgetInstanceManager(widgetInstanceManager);
	}

	@Test
	public void shouldRenderOperationsSummary()
	{
		final Date latestWebhook = Date.from(Instant.parse("2026-09-03T07:45:00Z"));
		final PayoneOperationsSummary summary = new PayoneOperationsSummary(1, 2, 3, 4, 5, 6, latestWebhook);
		when(payonepcpbackofficeService.getOperationsSummary()).thenReturn(summary);

		controller.refresh();

		verify(configurationsValue).setValue("1 / 2");
		verify(commerceCasesValue).setValue("3");
		verify(recentCheckoutsValue).setValue("4");
		verify(webhookBacklogValue).setValue("5");
		verify(retryingWebhooksValue).setValue("6");
		verify(latestWebhookValue).setValue(controller.formatDate(latestWebhook));
		verify(lastUpdatedValue).setValue(anyString());
		verify(errorLabel).setVisible(false);
	}

	@Test
	public void shouldShowErrorWhenSummaryCannotBeLoaded()
	{
		doThrow(new IllegalStateException("search unavailable"))
				.when(payonepcpbackofficeService).getOperationsSummary();

		controller.refresh();

		verify(errorLabel).setVisible(true);
	}

	@Test
	public void shouldNavigateEveryQuickLinkToItsExplorerTreeNode()
	{
		controller.openConfigurations();
		controller.openCommerceCases();
		controller.openCheckouts();
		controller.openWebhooks();

		verify(widgetInstanceManager, times(4)).sendOutput(eq("navigateToNode"), outputCaptor.capture());
		final List<Object> outputs = outputCaptor.getAllValues();
		assertNode(outputs.get(0), "payonepcpbackoffice.typenode.configuration");
		assertNode(outputs.get(1), "payonepcpbackoffice.typenode.commercecases");
		assertNode(outputs.get(2), "payonepcpbackoffice.typenode.checkouts");
		assertNode(outputs.get(3), "payonepcpbackoffice.typenode.webhooks");
	}

	@Test
	public void shouldUseDashWhenNoWebhookHasBeenReceived()
	{
		assertEquals("\u2014", controller.formatDate(null));
	}

	private static void assertNode(final Object output, final String expectedNodeId)
	{
		assertTrue(output instanceof TreeNodeSelector);
		final TreeNodeSelector selector = (TreeNodeSelector) output;
		assertEquals(expectedNodeId, selector.getNodeId());
		assertTrue(selector.isTriggerSelectionEvents());
	}
}
