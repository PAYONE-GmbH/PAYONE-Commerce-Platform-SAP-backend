/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.backoffice.services;

import java.util.Date;


/**
 * Read-only operational figures displayed on the PAYONE Backoffice dashboard.
 */
public final class PayoneOperationsSummary
{
	private final long activeConfigurations;
	private final long totalConfigurations;
	private final long commerceCases;
	private final long recentCheckouts;
	private final long pendingWebhooks;
	private final long retryingWebhooks;
	private final Date latestWebhookReceivedTime;

	public PayoneOperationsSummary(final long activeConfigurations, final long totalConfigurations,
			final long commerceCases, final long recentCheckouts, final long pendingWebhooks,
			final long retryingWebhooks, final Date latestWebhookReceivedTime)
	{
		this.activeConfigurations = activeConfigurations;
		this.totalConfigurations = totalConfigurations;
		this.commerceCases = commerceCases;
		this.recentCheckouts = recentCheckouts;
		this.pendingWebhooks = pendingWebhooks;
		this.retryingWebhooks = retryingWebhooks;
		this.latestWebhookReceivedTime = latestWebhookReceivedTime == null
				? null
				: new Date(latestWebhookReceivedTime.getTime());
	}

	public long getActiveConfigurations()
	{
		return activeConfigurations;
	}

	public long getTotalConfigurations()
	{
		return totalConfigurations;
	}

	public long getCommerceCases()
	{
		return commerceCases;
	}

	public long getRecentCheckouts()
	{
		return recentCheckouts;
	}

	public long getPendingWebhooks()
	{
		return pendingWebhooks;
	}

	public long getRetryingWebhooks()
	{
		return retryingWebhooks;
	}

	public Date getLatestWebhookReceivedTime()
	{
		return latestWebhookReceivedTime == null
				? null
				: new Date(latestWebhookReceivedTime.getTime());
	}
}
