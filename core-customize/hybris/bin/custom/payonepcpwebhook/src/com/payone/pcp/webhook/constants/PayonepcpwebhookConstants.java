/*
 * Copyright (c) 2020 SAP SE or an SAP affiliate company. All rights reserved.
 */
package com.payone.pcp.webhook.constants;

/**
 * Global class for all Payonepcpwebhook constants. You can add global constants for your extension into this class.
 */
public final class PayonepcpwebhookConstants extends GeneratedPayonepcpwebhookConstants
{
	public static final String EXTENSIONNAME = "payonepcpwebhook";

	// --- PCP webhook receiver ---

	/**
	 * Path of the receiver, relative to this webapp's root. Full URL registered
	 * in the PAYONE Portal (Configuration &gt; Webhooks):
	 * {@code https://<host>/payonepcpwebhook/webhooks}.
	 */
	public static final String WEBHOOK_PATH = "/webhooks";

	/** Header carrying the Primary API key id. The {@code x-gcs-} prefix is PAYONE's own, not a typo. */
	public static final String HEADER_KEY_ID = "X-GCS-KeyId";

	/** Header carrying the Base64 SHA-256 HMAC of the exact raw request body. */
	public static final String HEADER_SIGNATURE = "X-GCS-Signature";

	/** MAC algorithm mandated by the PCP webhook guide ("an SHA-256 HMAC"). */
	public static final String HMAC_ALGORITHM = "HmacSHA256";

	/**
	 * Comma-separated list of {@code apiVersion} values this build knows how to
	 * interpret. An unlisted version is still verified, persisted and
	 * acknowledged, just not processed. See {@link #DEFAULT_SUPPORTED_API_VERSIONS}.
	 */
	public static final String SUPPORTED_API_VERSIONS = "payonepcpwebhook.apiversion.supported";

	/** Default for {@link #SUPPORTED_API_VERSIONS} — the only version PCP publishes today. */
	public static final String DEFAULT_SUPPORTED_API_VERSIONS = "v1";

	/**
	 * Uid of the user the receiver switches to before it writes anything. The
	 * endpoint is unauthenticated (PAYONE is not a Commerce principal), so
	 * writes run as this named technical user instead of anonymous.
	 */
	public static final String PROCESS_USER = "payonepcpwebhook.process.user";

	/** Default for {@link #PROCESS_USER}; use a dedicated, narrowly-scoped user in production. */
	public static final String DEFAULT_PROCESS_USER = "admin";

	/**
	 * Maximum accepted length of the {@code X-GCS-KeyId} header. Attacker-controlled
	 * and used to build a property lookup key, so constrained to the shape PCP
	 * actually issues (a UUID-like token) before use.
	 */
	public static final int KEY_ID_MAX_LENGTH = 128;

	/** Allowed characters in a key id — see {@link #KEY_ID_MAX_LENGTH}. */
	public static final String KEY_ID_PATTERN = "[A-Za-z0-9._-]+";

	/**
	 * Largest request body the receiver will buffer, in bytes. The HMAC needs the
	 * whole body in memory, and this endpoint is unauthenticated, so an unbounded
	 * read would be a memory-exhaustion vector.
	 */
	public static final String MAX_BODY_BYTES = "payonepcpwebhook.max.body.bytes";

	/** Default for {@link #MAX_BODY_BYTES}: 1 MiB, well above the largest realistic PCP envelope. */
	public static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

	private PayonepcpwebhookConstants()
	{
		// no instances
	}

	// implement here constants used by this extension
}
