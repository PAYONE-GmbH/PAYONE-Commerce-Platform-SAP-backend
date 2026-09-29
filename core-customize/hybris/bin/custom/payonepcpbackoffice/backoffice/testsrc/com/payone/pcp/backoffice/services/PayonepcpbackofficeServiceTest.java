/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.backoffice.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;


@UnitTest
@RunWith(MockitoJUnitRunner.class)
public class PayonepcpbackofficeServiceTest
{
	private static final Instant NOW = Instant.parse("2026-09-03T08:00:00Z");
	private static final Date LATEST_WEBHOOK_TIME = Date.from(Instant.parse("2026-09-03T07:45:00Z"));

	@Mock
	private FlexibleSearchService flexibleSearchService;

	@Captor
	private ArgumentCaptor<FlexibleSearchQuery> queryCaptor;

	private PayonepcpbackofficeService service;

	@Before
	public void setUp()
	{
		service = new PayonepcpbackofficeService();
		service.setFlexibleSearchService(flexibleSearchService);
		service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	public void shouldLoadOperationsSummaryAndApplyOperationalFilters()
	{
		doReturn(result(2L), result(3L), result(13L), result(5L), result(4L), result(1L), result(LATEST_WEBHOOK_TIME))
				.when(flexibleSearchService).search(any(FlexibleSearchQuery.class));

		final PayoneOperationsSummary summary = service.getOperationsSummary();

		assertEquals(2L, summary.getActiveConfigurations());
		assertEquals(3L, summary.getTotalConfigurations());
		assertEquals(13L, summary.getCommerceCases());
		assertEquals(5L, summary.getRecentCheckouts());
		assertEquals(4L, summary.getPendingWebhooks());
		assertEquals(1L, summary.getRetryingWebhooks());
		assertEquals(LATEST_WEBHOOK_TIME, summary.getLatestWebhookReceivedTime());

		verify(flexibleSearchService, times(7)).search(queryCaptor.capture());
		final List<FlexibleSearchQuery> queries = queryCaptor.getAllValues();

		assertEquals(Boolean.TRUE, queries.get(0).getQueryParameters().get("active"));
		assertTrue(queries.get(3).getQuery().contains("creationtime"));
		assertEquals(Date.from(NOW.minus(24, ChronoUnit.HOURS)), queries.get(3).getQueryParameters().get("since"));
		assertEquals(Boolean.FALSE, queries.get(4).getQueryParameters().get("processed"));
		assertEquals(Boolean.FALSE, queries.get(5).getQueryParameters().get("processed"));
		assertEquals(Integer.valueOf(0), queries.get(5).getQueryParameters().get("attempts"));
		assertEquals(1, queries.get(6).getCount());
	}

	@Test
	public void shouldUseZeroAndNoTimestampWhenNoRowsExist()
	{
		final SearchResult<?> emptyResult = emptyResult();
		doReturn(emptyResult, emptyResult, emptyResult, emptyResult, emptyResult, emptyResult, emptyResult)
				.when(flexibleSearchService).search(any(FlexibleSearchQuery.class));

		final PayoneOperationsSummary summary = service.getOperationsSummary();

		assertEquals(0L, summary.getActiveConfigurations());
		assertEquals(0L, summary.getTotalConfigurations());
		assertEquals(0L, summary.getCommerceCases());
		assertEquals(0L, summary.getRecentCheckouts());
		assertEquals(0L, summary.getPendingWebhooks());
		assertEquals(0L, summary.getRetryingWebhooks());
		assertNull(summary.getLatestWebhookReceivedTime());
	}

	@Test
	public void shouldProtectLatestWebhookDateFromMutation()
	{
		final Date source = new Date(1_000L);
		final PayoneOperationsSummary summary = new PayoneOperationsSummary(1, 2, 3, 4, 5, 6, source);

		source.setTime(2_000L);
		final Date firstRead = summary.getLatestWebhookReceivedTime();
		assertEquals(1_000L, firstRead.getTime());

		firstRead.setTime(3_000L);
		assertEquals(1_000L, summary.getLatestWebhookReceivedTime().getTime());
		assertFalse(source.equals(summary.getLatestWebhookReceivedTime()));
	}

	@SuppressWarnings("unchecked")
	private static <T> SearchResult<T> result(final T value)
	{
		final SearchResult<T> result = mock(SearchResult.class);
		when(result.getResult()).thenReturn(Collections.singletonList(value));
		return result;
	}

	@SuppressWarnings("unchecked")
	private static <T> SearchResult<T> emptyResult()
	{
		final SearchResult<T> result = mock(SearchResult.class);
		when(result.getResult()).thenReturn(Collections.emptyList());
		return result;
	}
}
