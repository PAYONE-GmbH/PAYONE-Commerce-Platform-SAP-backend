package com.payone.pcp.core.service;

import de.hybris.platform.core.model.order.payment.PaymentModeModel;
import de.hybris.platform.store.BaseStoreModel;

import com.payone.pcp.core.model.PayoneConfigurationModel;

import java.util.Collection;


/**
 * Loads the per-store {@link PayoneConfigurationModel} with apiSecret resolved from the runtime
 * environment (CCv2 secret store / system properties) and set on the model.
 * <p>
 * Error contract, uniform across every method here: "nothing configured" is a null return, not
 * an exception. {@link IllegalStateException} means something IS configured but its secrets
 * could not be resolved - an operator error, never swallowed into a null.
 */
public interface PayoneConfigurationService
{
	/**
	 * May return a configuration for a deactivated store - active is resolved, not enforced.
	 * Payment paths should use {@link #getActiveConfigurationForStore(BaseStoreModel)} instead.
	 *
	 * @param baseStore the store whose PayoneConfiguration to load
	 * @return configuration with secrets populated, or {@code null} if absent
	 * @throws IllegalStateException if a configuration is attached but its apiSecret cannot be resolved
	 */
	PayoneConfigurationModel getConfigurationForStore(BaseStoreModel baseStore);

	/**
	 * @param baseStoreUid the uid of the store whose configuration to load
	 * @return configuration with secrets populated, or {@code null} if absent
	 * @throws IllegalStateException if a configuration is attached but its secrets cannot be resolved
	 */
	PayoneConfigurationModel getConfigurationForStoreUid(String baseStoreUid);

	/**
	 * @return configuration for the current store (session/context), or {@code null} if absent
	 * @throws IllegalStateException if a configuration is attached but its secrets cannot be resolved
	 */
	PayoneConfigurationModel getCurrentConfiguration();

	/**
	 * As {@link #getConfigurationForStore(BaseStoreModel)}, but {@code null} for a deactivated
	 * configuration. Use this on payment paths; the plain getters exist for backoffice/diagnostics,
	 * where seeing a deactivated configuration is the point.
	 *
	 * @return configuration, or {@code null} if absent or inactive
	 * @throws IllegalStateException if an active configuration's secrets cannot be resolved
	 */
	PayoneConfigurationModel getActiveConfigurationForStore(BaseStoreModel baseStore);

	/**
	 * As {@link #getCurrentConfiguration()}, but {@code null} when deactivated.
	 *
	 * @return configuration for the current store, or {@code null} if absent or inactive
	 * @throws IllegalStateException if an active configuration's secrets cannot be resolved
	 */
	PayoneConfigurationModel getCurrentActiveConfiguration();

	/**
	 * Used by the webhook handler to look up the store configuration that owns an incoming event.
	 *
	 * @param merchantId the PCP merchant identifier
	 * @return configuration, or {@code null} if none matches
	 * @throws IllegalStateException if a configuration matches but its secrets are missing
	 */
	PayoneConfigurationModel getConfigurationByMerchantId(String merchantId);


	/**
	 * @return the merchantId, or {@code null} if the store is {@code null} or has no configuration
	 */
	String getMerchantId(BaseStoreModel baseStore);

	/**
	 * @return the current merchantId, or {@code null} if there is no current store or configuration
	 */
	String getCurrentMerchantId();


	/**
	 * Resolves the PCP payment product IDs enabled for a store from its
	 * {@code PayoneConfiguration.paymentModes} (OOTB {@code PaymentMode} items). Feeds
	 * {@code PaymentStrategyRegistry.getStrategy(productId, allowedProductIds)}.
	 *
	 * @return the enabled PCP payment modes, or empty if none configured
	 * @throws IllegalStateException if the store's configuration secrets cannot be resolved
	 */
	Collection<PaymentModeModel> getAllowedPaymentProductIds(BaseStoreModel baseStore);
}
