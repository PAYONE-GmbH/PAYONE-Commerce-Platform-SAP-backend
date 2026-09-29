package com.payone.pcp.webhook.dao.impl;

import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.webhook.dao.PayoneWebhookEventDao;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;

import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link PayoneWebhookEventDao}. All queries are parameterised: the values come from
 * an external, signature-verified payload, so string concatenation here would be a
 * FlexibleSearch injection vector. paymentExecutionId and paymentId are only {@code search=true}
 * (not unique), so those two lookups take the first row and log if more than one matched,
 * rather than rejecting the webhook over inconsistent local data.
 */
public class DefaultPayoneWebhookEventDao implements PayoneWebhookEventDao
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneWebhookEventDao.class);

	private static final String QUERY_EVENT_BY_EVENT_ID = "SELECT {" + PayoneWebhookEventModel.PK + "} FROM {"
			+ PayoneWebhookEventModel._TYPECODE + "} WHERE {" + PayoneWebhookEventModel.EVENTID + "} = ?eventId";

	private static final String QUERY_CASE_BY_CASE_ID = "SELECT {" + PayoneCommerceCaseModel.PK + "} FROM {"
			+ PayoneCommerceCaseModel._TYPECODE + "} WHERE {" + PayoneCommerceCaseModel.COMMERCECASEID + "} = ?commerceCaseId";

	private static final String QUERY_CHECKOUT_BY_CHECKOUT_ID = "SELECT {" + PayoneCheckoutModel.PK + "} FROM {"
			+ PayoneCheckoutModel._TYPECODE + "} WHERE {" + PayoneCheckoutModel.CHECKOUTID + "} = ?checkoutId";

	private static final String QUERY_CHECKOUT_BY_EXECUTION_ID = "SELECT {" + PayoneCheckoutModel.PK + "} FROM {"
			+ PayoneCheckoutModel._TYPECODE + "} WHERE {" + PayoneCheckoutModel.PAYMENTEXECUTIONID + "} = ?paymentExecutionId";

	private static final String QUERY_CHECKOUT_BY_PAYMENT_ID = "SELECT {" + PayoneCheckoutModel.PK + "} FROM {"
			+ PayoneCheckoutModel._TYPECODE + "} WHERE {" + PayoneCheckoutModel.PAYMENTID + "} = ?paymentId";

	private FlexibleSearchService flexibleSearchService;

	@Override
	public Optional<PayoneWebhookEventModel> findEventByEventId(final String eventId)
	{
		return findUniqueByParam(QUERY_EVENT_BY_EVENT_ID, "eventId", eventId, PayoneWebhookEventModel.class);
	}

	@Override
	public Optional<PayoneCommerceCaseModel> findCommerceCaseById(final String commerceCaseId)
	{
		return findUniqueByParam(QUERY_CASE_BY_CASE_ID, "commerceCaseId", commerceCaseId, PayoneCommerceCaseModel.class);
	}

	@Override
	public Optional<PayoneCheckoutModel> findCheckoutById(final String checkoutId)
	{
		return findUniqueByParam(QUERY_CHECKOUT_BY_CHECKOUT_ID, "checkoutId", checkoutId, PayoneCheckoutModel.class);
	}

	@Override
	public Optional<PayoneCheckoutModel> findCheckoutByPaymentExecutionId(final String paymentExecutionId)
	{
		return findUniqueByParam(QUERY_CHECKOUT_BY_EXECUTION_ID, "paymentExecutionId", paymentExecutionId, PayoneCheckoutModel.class);
	}

	@Override
	public Optional<PayoneCheckoutModel> findCheckoutByPaymentId(final String paymentId)
	{
		return findUniqueByParam(QUERY_CHECKOUT_BY_PAYMENT_ID, "paymentId", paymentId, PayoneCheckoutModel.class);
	}

	/**
	 * Runs a single-parameter lookup and returns at most one result.
	 *
	 * @param query      FlexibleSearch statement with exactly one named parameter
	 * @param param      the parameter name used in {@code query}
	 * @param value      the value to bind; blank short-circuits to empty
	 * @param resultType the expected model type
	 */
	private <T> Optional<T> findUniqueByParam(final String query, final String param, final String value,
			final Class<T> resultType)
	{
		if (StringUtils.isBlank(value))
		{
			return Optional.empty();
		}

		final FlexibleSearchQuery searchQuery = new FlexibleSearchQuery(query, Map.of(param, value));
		searchQuery.setResultClassList(List.of(resultType));

		final SearchResult<T> result = getFlexibleSearchService().search(searchQuery);
		final List<T> rows = result.getResult();

		if (rows.isEmpty())
		{
			return Optional.empty();
		}
		if (rows.size() > 1)
		{
			// Expected only for the non-unique paymentId / paymentExecutionId
			// lookups. Logged, but proceeds with the first row rather than
			// rejecting the webhook over inconsistent local data.
			LOG.warn("[PAYONE] Webhook lookup on {}={} matched {} rows of {}; using the first",
					param, value, rows.size(), resultType.getSimpleName());
		}
		return Optional.ofNullable(rows.getFirst());
	}

	public FlexibleSearchService getFlexibleSearchService()
	{
		return flexibleSearchService;
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
