package com.payone.pcp.core.service;

import com.payone.commerce.platform.lib.models.AuthenticationToken;

import com.payone.pcp.core.model.PayoneConfigurationModel;


/** Thin wrapper over the PCP SDK AuthenticationApiClient, for client-side hosted tokenizer tokens. */
public interface PayoneAuthenticationService
{
	/**
	 * @param config PAYONE configuration for the target merchant (secrets populated)
	 * @param referenceId opaque caller-chosen identifier (e.g. order code) that PCP echoes back
	 * @return the PCP AuthenticationToken containing the JWT
	 * @throws IllegalArgumentException if {@code config} is null
	 * @throws IllegalStateException if the SDK client cannot be created or the PCP API call fails
	 */
	AuthenticationToken createAuthenticationToken(
			PayoneConfigurationModel config, String referenceId);
}
