/*
 * InMemorySecretAttributeHandler.java
 *
 * Dynamic attribute handler for PayoneConfiguration secrets (apiSecret).
 * Reads secrets from runtime environment properties on first
 * access and caches them in memory — NEVER persisted to the database.
 *
 * The handler resolves secrets by deriving the property key from the model's
 * merchantId and the configured attribute name. The property key convention is:
 *   payone.pcp.{merchantId}.{attributeName}
 *
 * Thread-safe: ConcurrentHashMap for the cache; ConfigurationService is
 * thread-safe by contract.
 */
package com.payone.pcp.core.dynamic;

import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.model.attribute.DynamicAttributeHandler;

import com.payone.pcp.core.constants.PayonepcpcoreConstants;
import com.payone.pcp.core.model.PayoneConfigurationModel;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;


public class InMemorySecretAttributeHandler implements DynamicAttributeHandler<String, ItemModel>
{
	private final String attributeName;
	private final Map<String, String> cache = new ConcurrentHashMap<>();
	private ConfigurationService hybrisConfigurationService;

	public InMemorySecretAttributeHandler(final String attributeName)
	{
		this.attributeName = Objects.requireNonNull(attributeName, "attributeName must not be null");
	}

	@Override
	public String get(final ItemModel model)
	{
		if (!(model instanceof PayoneConfigurationModel configModel))
		{
			return null;
		}

		final String merchantId = configModel.getMerchantId();
		if (StringUtils.isBlank(merchantId))
		{
			return null;
		}

		// Check cache first.
		final String cached = cache.get(merchantId);
		if (cached != null)
		{
			return cached;
		}

		// Resolve from runtime environment properties.
		final String key = PayonepcpcoreConstants.PROPERTY_PREFIX + merchantId + "." + attributeName;
		final Object raw = hybrisConfigurationService.getConfiguration().getProperty(key);
		final String value = raw == null ? null : raw.toString();

		if (StringUtils.isNotBlank(value))
		{
			cache.put(merchantId, value);
		}

		return value;
	}

	/**
	 * The setter is intentionally unimplemented - secrets are read-only from the
	 * runtime environment and must never be written through the model layer.
	 * Callers trying to set a secret via the model will get an
	 * UnsupportedOperationException.
	 */
	@Override
	public void set(final ItemModel model, final String value)
	{
		throw new UnsupportedOperationException(
				"Cannot set [" + attributeName + "] on PayoneConfiguration - "
				+ "secrets are read-only from the CCv2 runtime environment. "
				+ "Set the property [" + propertyKey(model) + "] in local.properties "
				+ "or the CCv2 environment instead.");
	}

	private String propertyKey(final ItemModel model)
	{
		if (model instanceof PayoneConfigurationModel configModel && StringUtils.isNotBlank(configModel.getMerchantId()))
		{
			return PayonepcpcoreConstants.PROPERTY_PREFIX + configModel.getMerchantId() + "." + attributeName;
		}
		return "";
	}


	// -----------------------------------------------------------------------
	// Spring setter
	// -----------------------------------------------------------------------

	public void setHybrisConfigurationService(final ConfigurationService hybrisConfigurationService)
	{
		this.hybrisConfigurationService = hybrisConfigurationService;
	}
}
