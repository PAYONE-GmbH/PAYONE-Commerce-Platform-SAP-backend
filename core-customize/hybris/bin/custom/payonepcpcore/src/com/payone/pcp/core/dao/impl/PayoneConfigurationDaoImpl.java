package com.payone.pcp.core.dao.impl;

import com.payone.pcp.core.dao.PayoneConfigurationDao;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;

import com.payone.pcp.core.model.PayoneConfigurationModel;

import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/** FlexibleSearch-based data access for PayoneConfigurationModel. */
public class PayoneConfigurationDaoImpl implements PayoneConfigurationDao {
    private static final Logger LOG = LoggerFactory.getLogger(PayoneConfigurationDaoImpl.class);

    private static final String QUERY_BY_MERCHANT_ID = "SELECT {" + PayoneConfigurationModel.PK + "} FROM {" + PayoneConfigurationModel._TYPECODE + "} WHERE {" + PayoneConfigurationModel.MERCHANTID + "} = ?merchantId";

    private FlexibleSearchService flexibleSearchService;

    @Override
    public PayoneConfigurationModel findPayoneConfigurationByMerchantId(final String merchantId) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        if (StringUtils.isBlank(merchantId)) {
            throw new IllegalArgumentException("merchantId must not be blank");
        }

        final FlexibleSearchQuery query = new FlexibleSearchQuery(QUERY_BY_MERCHANT_ID);
        query.addQueryParameter(PayoneConfigurationModel.MERCHANTID, merchantId);
        return singleResult(query, PayoneConfigurationModel.MERCHANTID, merchantId);
    }

    /**
     * Not {@code searchUnique}: that throws on empty/ambiguous results, forcing every caller
     * into a try/catch for what is here an ordinary "not found" outcome. More than one row is
     * a data defect, not a caller error, so it's logged and the first row returned.
     */
    private PayoneConfigurationModel singleResult(final FlexibleSearchQuery query, final String attribute, final String value) {
        final SearchResult<PayoneConfigurationModel> result = flexibleSearchService.search(query);
        final List<PayoneConfigurationModel> rows = result.getResult();

        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            LOG.warn("Found {} PayoneConfiguration rows for {} [{}]; expected at most one. Using the first.", rows.size(), attribute, value);
        }
        return rows.getFirst();
    }

    public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService) {
        this.flexibleSearchService = flexibleSearchService;
    }
}
