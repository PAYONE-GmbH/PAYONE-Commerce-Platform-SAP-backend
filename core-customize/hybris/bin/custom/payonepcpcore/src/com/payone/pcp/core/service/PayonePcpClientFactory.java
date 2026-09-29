package com.payone.pcp.core.service;

import com.payone.commerce.platform.lib.endpoints.AuthenticationApiClient;
import com.payone.commerce.platform.lib.endpoints.CheckoutApiClient;
import com.payone.commerce.platform.lib.endpoints.CommerceCaseApiClient;
import com.payone.commerce.platform.lib.endpoints.PaymentExecutionApiClient;
import com.payone.commerce.platform.lib.endpoints.PaymentInformationApiClient;

import com.payone.pcp.core.model.PayoneConfigurationModel;


/**
 * Supplies the PCP SDK API clients for a configuration model. Interface-backed so payment
 * strategies can be unit-tested without real credentials, and to follow this extension's
 * alias + default* wiring convention in payonepcpcore-spring.xml.
 * <p>
 * Returned clients hold an HMAC key derived from the API secret and a shared HTTP connection
 * pool; implementations are expected to cache them, so callers should treat lookups as cheap
 * and not hold their own references.
 */
public interface PayonePcpClientFactory
{
	/**
	 * @param config PayoneConfigurationModel with secrets populated; must carry a merchantId.
	 * @return the AuthenticationApiClient for this merchant's current credentials.
	 * @throws IllegalArgumentException if the configuration has no merchantId, or
	 *                                  its API key/secret is not a usable HMAC key.
	 */
	AuthenticationApiClient createAuthenticationApiClient(PayoneConfigurationModel config);

	/**
	 * @param config PayoneConfigurationModel with secrets populated; must carry a merchantId.
	 * @return the CommerceCaseApiClient for this merchant's current credentials.
	 * @throws IllegalArgumentException as above.
	 */
	CommerceCaseApiClient createCommerceCaseApiClient(PayoneConfigurationModel config);

	/**
	 * @param config PayoneConfigurationModel with secrets populated; must carry a merchantId.
	 * @return the CheckoutApiClient for this merchant's current credentials.
	 * @throws IllegalArgumentException as above.
	 */
	CheckoutApiClient createCheckoutApiClient(PayoneConfigurationModel config);

	/**
	 * @param config PayoneConfigurationModel with secrets populated; must carry a merchantId.
	 * @return the PaymentExecutionApiClient for this merchant's current credentials.
	 * @throws IllegalArgumentException as above.
	 */
	PaymentExecutionApiClient createPaymentExecutionApiClient(PayoneConfigurationModel config);

	/**
	 * @param config PayoneConfigurationModel with secrets populated; must carry a merchantId.
	 * @return the PaymentInformationApiClient for this merchant's current credentials.
	 * @throws IllegalArgumentException as above.
	 */
	PaymentInformationApiClient createPaymentInformationApiClient(PayoneConfigurationModel config);
}
