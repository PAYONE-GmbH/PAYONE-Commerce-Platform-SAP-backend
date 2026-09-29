package com.payone.pcp.core.service.impl;

import com.payone.pcp.core.constants.PayonepcpcoreConstants;
import com.payone.pcp.core.dao.PayoneConfigurationDao;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayoneConfigurationService;
import com.payone.pcp.core.validation.PayoneConfigurationValidator;
import de.hybris.platform.core.model.order.payment.PaymentModeModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.store.BaseStoreModel;
import de.hybris.platform.store.services.BaseStoreService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


/**
 * apiSecret is resolved lazily by {@code InMemorySecretAttributeHandler} on first access to
 * {@code PayoneConfigurationModel.getApiSecret()}, reading runtime environment properties under
 * {@code payone.pcp.{merchantId}.apiSecret}. This service loads the model, validates type
 * constraints, and fails fast if the required environment property is missing; it does not set
 * secrets on the model itself.
 */
public class DefaultPayoneConfigurationService implements PayoneConfigurationService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneConfigurationService.class);

	private ConfigurationService hybrisConfigurationService;
	private BaseStoreService baseStoreService;
	private PayoneConfigurationDao payoneConfigurationDao;

	@Override
	public PayoneConfigurationModel getConfigurationForStore(final BaseStoreModel baseStore)
	{
		if (baseStore == null)
		{
			return null;
		}

		final PayoneConfigurationModel configModel = baseStore.getPayoneConfiguration();
		if (configModel == null)
		{
			LOG.debug("No PayoneConfiguration attached to store [{}]", baseStore.getUid());
			return null;
		}

		return validateAndCheckSecrets(configModel);
	}

	@Override
	public PayoneConfigurationModel getConfigurationForStoreUid(final String baseStoreUid)
	{
		Objects.requireNonNull(baseStoreUid, "baseStoreUid must not be null");
		if (StringUtils.isBlank(baseStoreUid))
		{
			throw new IllegalArgumentException("baseStoreUid must not be blank");
		}

		final BaseStoreModel baseStore = baseStoreService.getBaseStoreForUid(baseStoreUid);
		if (baseStore == null)
		{
			LOG.warn("No BaseStore found for uid [{}]", baseStoreUid);
			return null;
		}

		return getConfigurationForStore(baseStore);
	}

	@Override
	public PayoneConfigurationModel getCurrentConfiguration()
	{
		final BaseStoreModel currentStore = baseStoreService.getCurrentBaseStore();
		if (currentStore == null)
		{
			LOG.warn("No current BaseStore available");
			return null;
		}

		return getConfigurationForStore(currentStore);
	}

	@Override
	public PayoneConfigurationModel getActiveConfigurationForStore(final BaseStoreModel baseStore)
	{
		return activeConfigurationOnly(getConfigurationForStore(baseStore));
	}

	@Override
	public PayoneConfigurationModel getCurrentActiveConfiguration()
	{
		return activeConfigurationOnly(getCurrentConfiguration());
	}

	@Override
	public PayoneConfigurationModel getConfigurationByMerchantId(final String merchantId)
	{
		if (StringUtils.isBlank(merchantId))
		{
			return null;
		}

		final PayoneConfigurationModel configModel = payoneConfigurationDao.findPayoneConfigurationByMerchantId(merchantId);
		if (configModel == null)
		{
			LOG.debug("No PayoneConfiguration found for merchantId [{}]", merchantId);
			return null;
		}

		return validateAndCheckSecrets(configModel);
	}


	@Override
	public String getMerchantId(final BaseStoreModel baseStore)
	{
		if (baseStore == null || baseStore.getPayoneConfiguration() == null)
		{
			return null;
		}
		return baseStore.getPayoneConfiguration().getMerchantId();
	}

	@Override
	public String getCurrentMerchantId()
	{
		final BaseStoreModel currentStore = baseStoreService.getCurrentBaseStore();
		return getMerchantId(currentStore);
	}

	@Override
	public Collection<PaymentModeModel> getAllowedPaymentProductIds(final BaseStoreModel baseStore)
	{
		final PayoneConfigurationModel config = getConfigurationForStore(baseStore);
		if (config == null || config.getPaymentModes() == null)
		{
			return Collections.emptyList();
		}
		return config.getPaymentModes();
	}

	private static PayoneConfigurationModel activeConfigurationOnly(final PayoneConfigurationModel config)
	{
		if (config == null)
		{
			return null;
		}
		if (config.getActive() == null || !config.getActive())
		{
			LOG.debug("PayoneConfiguration for merchantId [{}] is not active", config.getMerchantId());
			return null;
		}
		return config;
	}

	/**
	 * Validates type constraints, then checks apiSecret is configured so an operator gets a
	 * clear error at config-load time instead of a failure deep in the first API call.
	 */
	private PayoneConfigurationModel validateAndCheckSecrets(final PayoneConfigurationModel model)
	{
		final List<String> violations = PayoneConfigurationValidator.validate(model);
		if (!violations.isEmpty())
		{
			throw new IllegalArgumentException(
					"PayoneConfiguration validation failed: " + String.join("; ", violations));
		}

		final String merchantId = model.getMerchantId();

		checkSecretConfigured(merchantId);

		return model;
	}

	/**
	 * @throws IllegalStateException if the property is not configured
	 */
	private void checkSecretConfigured(final String merchantId)
	{
		final String key = PayonepcpcoreConstants.PROPERTY_PREFIX + merchantId + PayonepcpcoreConstants.API_SECRET_PROPERTY_SUFFIX;
		final Object raw = hybrisConfigurationService.getConfiguration().getProperty(key);
		if (raw == null || StringUtils.isBlank(raw.toString()))
		{
			throw new IllegalStateException(
					"Secret property [" + key + "] for merchantId [" + merchantId + "] "
					+ "is not configured. Set it in local.properties or the CCv2 environment.");
		}
	}

	public void setHybrisConfigurationService(final ConfigurationService hybrisConfigurationService)
	{
		this.hybrisConfigurationService = hybrisConfigurationService;
	}

	public void setBaseStoreService(final BaseStoreService baseStoreService)
	{
		this.baseStoreService = baseStoreService;
	}

	public void setPayoneConfigurationDao(final PayoneConfigurationDao payoneConfigurationDao)
	{
		this.payoneConfigurationDao = payoneConfigurationDao;
	}
}
