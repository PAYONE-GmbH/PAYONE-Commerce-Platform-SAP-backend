package com.payone.pcp.core.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;


/**
 * Registry of {@link PayonePaymentStrategy} beans, indexed by PCP payment product ID.
 * Thread-safe after Spring init: the map is built once in {@link #afterPropertiesSet()} and
 * never mutated afterward.
 */
public class PaymentStrategyRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentStrategyRegistry.class);

    private List<PayonePaymentStrategy> strategyBeans;
    private Map<Integer, PayonePaymentStrategy> strategies = Collections.emptyMap();


    /**
     * Spring init-method. Indexes injected strategy beans by product ID; last one wins on a clash.
     */
    public void afterPropertiesSet() {
        final Map<Integer, PayonePaymentStrategy> map = new HashMap<>();
        if (strategyBeans != null) {
            for (final PayonePaymentStrategy strategy : strategyBeans) {
                final int id = strategy.getPaymentProductId();
                if (map.containsKey(id)) {
                    LOG.warn("Duplicate PaymentStrategy registered for product ID [{}] - " + "the last one wins", id);
                }
                map.put(id, strategy);
            }
        }
        strategies = Collections.unmodifiableMap(map);
        LOG.debug("PaymentStrategyRegistry initialised with [{}] strategies", map.size());
    }

    /**
     * Resolve a strategy by payment product ID.
     *
     * @param productId the PCP payment product ID.
     * @return the strategy, or null if not registered.
     */
    public PayonePaymentStrategy getStrategy(final int productId) {
        return strategies.get(productId);
    }

    /**
     * Resolve a strategy by product ID, subject to the store's configured
     * allowed payment modes.
     *
     * @param productId         the PCP payment product ID.
     * @param allowedProductIds the set of product IDs enabled for this store
     *                          (from PayoneConfiguration.paymentModes);
     *                          may be null or empty.
     * @return the strategy, or null if the product is not allowed or not registered.
     */
    public PayonePaymentStrategy getStrategy(final int productId, final Collection<Integer> allowedProductIds) {
        if (allowedProductIds != null && !allowedProductIds.isEmpty() && !allowedProductIds.contains(productId)) {
            return null;
        }
        return getStrategy(productId);
    }

    /**
     * Returns all registered strategies keyed by product ID.
     * The map is unmodifiable.
     */
    public Map<Integer, PayonePaymentStrategy> getAllStrategies() {
        return strategies;
    }

    public void setStrategyBeans(final List<PayonePaymentStrategy> strategyBeans) {
        this.strategyBeans = strategyBeans;
    }
}
