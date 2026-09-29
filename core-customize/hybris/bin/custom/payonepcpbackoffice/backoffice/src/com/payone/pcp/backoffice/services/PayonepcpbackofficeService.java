/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.backoffice.services;

import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;


/**
 * Supplies lightweight operational data to PAYONE Backoffice widgets.
 */
public class PayonepcpbackofficeService
{
	private static final String COUNT_CONFIGURATIONS = countQuery(PayoneConfigurationModel._TYPECODE);
	private static final String COUNT_ACTIVE_CONFIGURATIONS = COUNT_CONFIGURATIONS + " WHERE {"
			+ PayoneConfigurationModel.ACTIVE + "} = ?active";
	private static final String COUNT_COMMERCE_CASES = countQuery(PayoneCommerceCaseModel._TYPECODE);
	private static final String COUNT_RECENT_CHECKOUTS = countQuery(PayoneCheckoutModel._TYPECODE)
			+ " WHERE {creationtime} >= ?since";
	private static final String COUNT_PENDING_WEBHOOKS = countQuery(PayoneWebhookEventModel._TYPECODE) + " WHERE {"
			+ PayoneWebhookEventModel.PROCESSED + "} = ?processed";
	private static final String COUNT_RETRYING_WEBHOOKS = COUNT_PENDING_WEBHOOKS + " AND {"
			+ PayoneWebhookEventModel.ATTEMPTS + "} > ?attempts";
	private static final String FIND_LATEST_WEBHOOK_TIME = "SELECT {" + PayoneWebhookEventModel.RECEIVEDTIME + "} FROM {"
			+ PayoneWebhookEventModel._TYPECODE + "} WHERE {" + PayoneWebhookEventModel.RECEIVEDTIME
			+ "} IS NOT NULL ORDER BY {" + PayoneWebhookEventModel.RECEIVEDTIME + "} DESC";

	private FlexibleSearchService flexibleSearchService;
	private Clock clock = Clock.systemUTC();

	public PayoneOperationsSummary getOperationsSummary()
	{
		final Date since = Date.from(Instant.now(clock).minus(24, ChronoUnit.HOURS));
		return new PayoneOperationsSummary(
				count(COUNT_ACTIVE_CONFIGURATIONS, Map.of("active", Boolean.TRUE)),
				count(COUNT_CONFIGURATIONS, Map.of()),
				count(COUNT_COMMERCE_CASES, Map.of()),
				count(COUNT_RECENT_CHECKOUTS, Map.of("since", since)),
				count(COUNT_PENDING_WEBHOOKS, Map.of("processed", Boolean.FALSE)),
				count(COUNT_RETRYING_WEBHOOKS, Map.of("processed", Boolean.FALSE, "attempts", Integer.valueOf(0))),
				findLatestWebhookReceivedTime());
	}

	protected long count(final String statement, final Map<String, Object> parameters)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(statement, parameters);
		query.setResultClassList(List.of(Long.class));
		final SearchResult<Long> result = flexibleSearchService.search(query);
		return result.getResult().isEmpty() ? 0L : result.getResult().get(0).longValue();
	}

	protected Date findLatestWebhookReceivedTime()
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(FIND_LATEST_WEBHOOK_TIME);
		query.setResultClassList(List.of(Date.class));
		query.setCount(1);
		final SearchResult<Date> result = flexibleSearchService.search(query);
		return result.getResult().isEmpty() ? null : result.getResult().get(0);
	}

	private static String countQuery(final String typeCode)
	{
		return "SELECT COUNT({pk}) FROM {" + typeCode + "}";
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}

	void setClock(final Clock clock)
	{
		this.clock = clock;
	}
}
