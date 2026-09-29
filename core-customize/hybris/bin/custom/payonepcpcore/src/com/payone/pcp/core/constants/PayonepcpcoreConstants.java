package com.payone.pcp.core.constants;

/**
 * Global class for all payonepcpcore constants.
 */
public final class PayonepcpcoreConstants extends GeneratedPayonepcpcoreConstants
{
	/** Extension name - matches extensioninfo.xml. */
	public static final String EXTENSIONNAME = "payonepcpcore";

	/** Integrator name sent to PAYONE as SDK ServerMetaInfo. */
	public static final String PCP_INTEGRATOR = "PAYONE_SAP_COMMERCE_PLUGIN";

	// Secrets are never stored in items.xml or impex; resolved at runtime from
	// the CCv2 environment as payone.pcp.{merchantId}.apiSecret, or locally
	// via local.properties / PAYONE_PCP_{MERCHANTID}_APISECRET.

	/** Prefix for all PCP secret properties. */
	public static final String PROPERTY_PREFIX = "payone.pcp.";

	/** Suffix for the API secret property. */
	public static final String API_SECRET_PROPERTY_SUFFIX = ".apiSecret";

	private PayonepcpcoreConstants()
	{
		// empty to avoid instantiating this constant class
	}
}
