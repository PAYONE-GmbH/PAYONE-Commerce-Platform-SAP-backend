package com.payone.pcp.webhook.dao.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.webhook.model.PayoneWebhookEventModel;

import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultPayoneWebhookEventDao}.
 */
@ExtendWith(MockitoExtension.class)
class DefaultPayoneWebhookEventDaoUnitTest
{
	private static final String VALUE = "some-id";

	@Mock
	private FlexibleSearchService flexibleSearchService;

	@Captor
	private ArgumentCaptor<FlexibleSearchQuery> queryCaptor;

	private DefaultPayoneWebhookEventDao dao;

	@BeforeEach
	void setUp()
	{
		dao = new DefaultPayoneWebhookEventDao();
		dao.setFlexibleSearchService(flexibleSearchService);
	}

	@SuppressWarnings("unchecked")
	private <T> void stubResult(final List<T> rows)
	{
		final SearchResult<T> result = mock(SearchResult.class);
		when(result.getResult()).thenReturn(rows);
		when(flexibleSearchService.<T>search(any(FlexibleSearchQuery.class))).thenReturn(result);
	}

	private FlexibleSearchQuery capturedQuery()
	{
		verify(flexibleSearchService).search(queryCaptor.capture());
		return queryCaptor.getValue();
	}

	// findEventByEventId

	@Test
	void findEventByEventIdBindsTheEventIdParameter()
	{
		stubResult(Collections.singletonList(new PayoneWebhookEventModel()));

		final Optional<PayoneWebhookEventModel> result = dao.findEventByEventId(VALUE);

		assertThat(result).isPresent();
		assertThat(capturedQuery().getQueryParameters()).containsEntry("eventId", VALUE);
	}

	@Test
	void findEventByEventIdReturnsEmptyWhenNoRowsMatch()
	{
		stubResult(Collections.emptyList());

		assertThat(dao.findEventByEventId(VALUE)).isEmpty();
	}

	@Test
	void findEventByEventIdShortCircuitsOnBlankId()
	{
		assertThat(dao.findEventByEventId("   ")).isEmpty();
		assertThat(dao.findEventByEventId(null)).isEmpty();

		verify(flexibleSearchService, never()).search(any(FlexibleSearchQuery.class));
	}

	@Test
	void findEventByEventIdReturnsTheFirstRowWhenMultipleMatch()
	{
		final PayoneWebhookEventModel first = new PayoneWebhookEventModel();
		final PayoneWebhookEventModel second = new PayoneWebhookEventModel();
		stubResult(List.of(first, second));

		assertThat(dao.findEventByEventId(VALUE)).contains(first);
	}

	// findCommerceCaseById

	@Test
	void findCommerceCaseByIdBindsTheCommerceCaseIdParameter()
	{
		stubResult(Collections.singletonList(new PayoneCommerceCaseModel()));

		final Optional<PayoneCommerceCaseModel> result = dao.findCommerceCaseById(VALUE);

		assertThat(result).isPresent();
		assertThat(capturedQuery().getQueryParameters()).containsEntry("commerceCaseId", VALUE);
	}

	@Test
	void findCommerceCaseByIdReturnsEmptyWhenNoRowsMatch()
	{
		stubResult(Collections.emptyList());

		assertThat(dao.findCommerceCaseById(VALUE)).isEmpty();
	}

	@Test
	void findCommerceCaseByIdShortCircuitsOnBlankId()
	{
		assertThat(dao.findCommerceCaseById("")).isEmpty();

		verify(flexibleSearchService, never()).search(any(FlexibleSearchQuery.class));
	}

	// findCheckoutById

	@Test
	void findCheckoutByIdBindsTheCheckoutIdParameter()
	{
		stubResult(Collections.singletonList(new PayoneCheckoutModel()));

		final Optional<PayoneCheckoutModel> result = dao.findCheckoutById(VALUE);

		assertThat(result).isPresent();
		assertThat(capturedQuery().getQueryParameters()).containsEntry("checkoutId", VALUE);
	}

	@Test
	void findCheckoutByIdReturnsEmptyWhenNoRowsMatch()
	{
		stubResult(Collections.emptyList());

		assertThat(dao.findCheckoutById(VALUE)).isEmpty();
	}

	@Test
	void findCheckoutByIdShortCircuitsOnBlankId()
	{
		assertThat(dao.findCheckoutById(null)).isEmpty();

		verify(flexibleSearchService, never()).search(any(FlexibleSearchQuery.class));
	}

	// findCheckoutByPaymentExecutionId

	@Test
	void findCheckoutByPaymentExecutionIdBindsTheParameter()
	{
		stubResult(Collections.singletonList(new PayoneCheckoutModel()));

		final Optional<PayoneCheckoutModel> result = dao.findCheckoutByPaymentExecutionId(VALUE);

		assertThat(result).isPresent();
		assertThat(capturedQuery().getQueryParameters()).containsEntry("paymentExecutionId", VALUE);
	}

	@Test
	void findCheckoutByPaymentExecutionIdReturnsEmptyWhenNoRowsMatch()
	{
		stubResult(Collections.emptyList());

		assertThat(dao.findCheckoutByPaymentExecutionId(VALUE)).isEmpty();
	}

	@Test
	void findCheckoutByPaymentExecutionIdShortCircuitsOnBlankId()
	{
		assertThat(dao.findCheckoutByPaymentExecutionId("  ")).isEmpty();

		verify(flexibleSearchService, never()).search(any(FlexibleSearchQuery.class));
	}

	// findCheckoutByPaymentId

	@Test
	void findCheckoutByPaymentIdBindsTheParameter()
	{
		stubResult(Collections.singletonList(new PayoneCheckoutModel()));

		final Optional<PayoneCheckoutModel> result = dao.findCheckoutByPaymentId(VALUE);

		assertThat(result).isPresent();
		assertThat(capturedQuery().getQueryParameters()).containsEntry("paymentId", VALUE);
	}

	@Test
	void findCheckoutByPaymentIdReturnsEmptyWhenNoRowsMatch()
	{
		stubResult(Collections.emptyList());

		assertThat(dao.findCheckoutByPaymentId(VALUE)).isEmpty();
	}

	@Test
	void findCheckoutByPaymentIdReturnsTheFirstRowWhenMultipleMatch()
	{
		final PayoneCheckoutModel first = new PayoneCheckoutModel();
		final PayoneCheckoutModel second = new PayoneCheckoutModel();
		stubResult(List.of(first, second));

		assertThat(dao.findCheckoutByPaymentId(VALUE)).contains(first);
	}

	@Test
	void findCheckoutByPaymentIdShortCircuitsOnBlankId()
	{
		assertThat(dao.findCheckoutByPaymentId("   ")).isEmpty();

		verify(flexibleSearchService, never()).search(any(FlexibleSearchQuery.class));
	}
}
