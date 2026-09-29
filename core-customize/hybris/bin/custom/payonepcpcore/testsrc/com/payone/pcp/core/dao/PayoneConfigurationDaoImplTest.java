package com.payone.pcp.core.dao;

import com.payone.pcp.core.dao.impl.PayoneConfigurationDaoImpl;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@UnitTest
public class PayoneConfigurationDaoImplTest {
    private static final String MERCHANT_ID = "TEST_MERCHANT";

    @Mock
    private FlexibleSearchService flexibleSearchService;

    @Captor
    private ArgumentCaptor<FlexibleSearchQuery> queryCaptor;

    private PayoneConfigurationDaoImpl dao;

    private AutoCloseable mocks;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        dao = new PayoneConfigurationDaoImpl();
        dao.setFlexibleSearchService(flexibleSearchService);
    }

    @After
    public void tearDown() throws Exception {
        mocks.close();
    }

    /**
     * Stubs {@code search()}, not {@code searchUnique()}: the platform's
     * {@code searchUnique} throws on empty/ambiguous results and never returns null.
     */
    private void stubResult(final List<PayoneConfigurationModel> rows) {
        final SearchResult<PayoneConfigurationModel> searchResult = mock(SearchResult.class);
        when(searchResult.getResult()).thenReturn(rows);
        when(flexibleSearchService.<PayoneConfigurationModel>search(any(FlexibleSearchQuery.class))).thenReturn(searchResult);
    }

    /** Captures on verify, not inside when() - a capturing matcher in the stub setup would grab the wrong invocation. */
    private FlexibleSearchQuery capturedQuery() {
        verify(flexibleSearchService).search(queryCaptor.capture());
        return queryCaptor.getValue();
    }

    @Test
    public void shouldFindByMerchantId() {
        final PayoneConfigurationModel expected = new PayoneConfigurationModel();
        expected.setMerchantId(MERCHANT_ID);

        stubResult(Collections.singletonList(expected));

        final PayoneConfigurationModel result = dao.findPayoneConfigurationByMerchantId(MERCHANT_ID);

        assertNotNull(result);
        assertEquals(MERCHANT_ID, result.getMerchantId());

        final FlexibleSearchQuery query = capturedQuery();
        assertEquals(MERCHANT_ID, query.getQueryParameters().get(PayoneConfigurationModel.MERCHANTID));
    }


    @Test
    public void shouldReturnNullForUnknownMerchantId() {
        stubResult(Collections.emptyList());
        assertNull(dao.findPayoneConfigurationByMerchantId("unknown"));
    }

    @Test
    public void shouldReturnNullWhenSearchResultIsNull() {
        stubResult(null);
        assertNull(dao.findPayoneConfigurationByMerchantId(MERCHANT_ID));
    }

}
