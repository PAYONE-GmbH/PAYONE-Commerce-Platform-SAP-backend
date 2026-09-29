package com.payone.pcp.webhook.service.impl;

import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.HEADER_KEY_ID;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.HMAC_ALGORITHM;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.KEY_ID_MAX_LENGTH;
import static com.payone.pcp.webhook.constants.PayonepcpwebhookConstants.KEY_ID_PATTERN;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayoneConfigurationService;
import com.payone.pcp.webhook.exception.PayoneWebhookSignatureException;
import com.payone.pcp.webhook.service.PayoneWebhookSignatureService;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link PayoneWebhookSignatureService}: Base64(HmacSHA256(key = apiSecret, message =
 * rawBody)), compared against X-GCS-Signature, with X-GCS-KeyId selecting which secret (PCP
 * webhook guide, "Step 3: Validate HMAC Credentials"). PCP-ServerSDK-java ships no verifier for
 * this, so it's in-house; a defect here lets a forged request update payment state. The MAC key is
 * the secret alone, per the Global Collect/Worldline lineage of the x-gcs-* headers — revisit if
 * verification fails against a real delivery with a confirmed-correct secret.
 *
 * <p>Both the expected key id and the HMAC secret come from the PayoneConfiguration owning the
 * delivered merchantId, never from flat ConfigurationService properties, so a correct key
 * id/secret pair for merchant A cannot verify a delivery claiming to be merchant B. Uses
 * {@link MessageDigest#isEqual} for constant-time comparison, and never logs the secret, digest or
 * body.
 */
public class DefaultPayoneWebhookSignatureService implements PayoneWebhookSignatureService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneWebhookSignatureService.class);

	private static final Pattern VALID_KEY_ID = Pattern.compile(KEY_ID_PATTERN);

	/** Single message for every rejection, so the response can't probe which key ids are configured. */
	private static final String REJECTION_MESSAGE = "Webhook signature verification failed";

	private PayoneConfigurationService payoneConfigurationService;

	@Override
	public void verify(final byte[] rawBody, final String keyId, final String signature, final String merchantId)
	{
		if (rawBody == null || rawBody.length == 0)
		{
			LOG.warn("[PAYONE] Webhook rejected: empty request body (keyId={})", sanitise(keyId));
			throw new PayoneWebhookSignatureException(REJECTION_MESSAGE);
		}
		if (StringUtils.isBlank(signature))
		{
			LOG.warn("[PAYONE] Webhook rejected: missing signature header (keyId={})", sanitise(keyId));
			throw new PayoneWebhookSignatureException(REJECTION_MESSAGE);
		}

		final String validKeyId = requireValidKeyId(keyId);
		final PayoneConfigurationModel configuration = resolveConfiguration(merchantId, validKeyId);
		requireMatchingKeyId(configuration, validKeyId);
		final String secret = requireSecret(configuration, validKeyId);

		final byte[] expected = computeHmac(rawBody, secret);
		final byte[] provided = decodeSignature(signature, validKeyId);

		if (!MessageDigest.isEqual(expected, provided))
		{
			LOG.warn("[PAYONE] Webhook rejected: signature mismatch (keyId={}, bodyBytes={})",
					sanitise(validKeyId), rawBody.length);
			throw new PayoneWebhookSignatureException(REJECTION_MESSAGE);
		}

		LOG.debug("[PAYONE] Webhook signature verified (keyId={}, bodyBytes={})", sanitise(validKeyId), rawBody.length);
	}

	/** Rejects a key id absent or outside the shape PCP issues, before it's compared or logged. */
	private String requireValidKeyId(final String keyId)
	{
		final String trimmed = StringUtils.trimToNull(keyId);
		if (trimmed == null || trimmed.length() > KEY_ID_MAX_LENGTH || !VALID_KEY_ID.matcher(trimmed).matches())
		{
			LOG.warn("[PAYONE] Webhook rejected: missing or malformed {} header (value={})",
					HEADER_KEY_ID, sanitise(keyId));
			throw new PayoneWebhookSignatureException(REJECTION_MESSAGE);
		}
		return trimmed;
	}

	/**
	 * Resolves the PayoneConfiguration that owns the delivered merchantId. Fails closed with the
	 * same rejection as everything else when the merchantId is blank, unknown, or its secrets can't
	 * be resolved — an operator misconfiguration must not be distinguishable from hostile traffic.
	 */
	private PayoneConfigurationModel resolveConfiguration(final String merchantId, final String keyId)
	{
		final PayoneConfigurationModel configuration;
		try
		{
			configuration = StringUtils.isBlank(merchantId)
					? null
					: getPayoneConfigurationService().getConfigurationByMerchantId(merchantId);
		}
		catch (final IllegalStateException | IllegalArgumentException e)
		{
			// ERROR, not WARN: a deployment fault (invalid or unresolved secret config), not hostile traffic.
			LOG.error("[PAYONE] Webhook rejected: PayoneConfiguration for merchantId={} could not be resolved: {}",
					sanitise(merchantId), e.getMessage());
			throw new PayoneWebhookSignatureException(REJECTION_MESSAGE, e);
		}

		if (configuration == null)
		{
			LOG.warn("[PAYONE] Webhook rejected: no PayoneConfiguration for merchantId={} (keyId={})",
					sanitise(merchantId), sanitise(keyId));
			throw new PayoneWebhookSignatureException(REJECTION_MESSAGE);
		}
		return configuration;
	}

	/**
	 * Verifies the delivered key id against the merchant's {@code apiKeyId} before the secret is
	 * even considered — a key id issued to a different merchant must not verify just because that
	 * merchant's secret is also known.
	 */
	private void requireMatchingKeyId(final PayoneConfigurationModel configuration, final String keyId)
	{
		if (!StringUtils.equals(keyId, configuration.getApiKeyId()))
		{
			LOG.warn("[PAYONE] Webhook rejected: keyId={} does not match the apiKeyId configured for merchantId={}",
					sanitise(keyId), sanitise(configuration.getMerchantId()));
			throw new PayoneWebhookSignatureException(REJECTION_MESSAGE);
		}
	}

	/**
	 * Resolves the HMAC secret from the merchant's configuration. Comes from the CCv2 environment /
	 * secret store, never from items.xml or ImpEx.
	 *
	 * @throws PayoneWebhookSignatureException when the secret is blank — fails closed
	 */
	private String requireSecret(final PayoneConfigurationModel configuration, final String keyId)
	{
		final String secret = configuration.getApiSecret();
		if (StringUtils.isNotBlank(secret))
		{
			return secret;
		}

		// ERROR, not WARN: this is a deployment fault, not hostile traffic.
		LOG.error("[PAYONE] Webhook rejected: no apiSecret configured for merchantId={} (keyId={}) — "
						+ "set payone.pcp.<merchantId>.apiSecret in the CCv2 secret store.",
				sanitise(configuration.getMerchantId()), sanitise(keyId));
		throw new PayoneWebhookSignatureException(REJECTION_MESSAGE);
	}

	private byte[] computeHmac(final byte[] rawBody, final String secret)
	{
		try
		{
			final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return mac.doFinal(rawBody);
		}
		catch (final GeneralSecurityException e)
		{
			// Unreachable short of a broken JCE provider; surfaced as a rejection, not a 500.
			LOG.error("[PAYONE] Webhook rejected: {} unavailable or key rejected by the JCE provider", HMAC_ALGORITHM, e);
			throw new PayoneWebhookSignatureException(REJECTION_MESSAGE, e);
		}
	}

	private byte[] decodeSignature(final String signature, final String keyId)
	{
		try
		{
			return Base64.getDecoder().decode(signature.trim());
		}
		catch (final IllegalArgumentException e)
		{
			LOG.warn("[PAYONE] Webhook rejected: signature header is not valid Base64 (keyId={})", sanitise(keyId));
			throw new PayoneWebhookSignatureException(REJECTION_MESSAGE, e);
		}
	}

	/** Strips CR/LF (log injection) and caps length (log flooding) before logging. */
	private String sanitise(final String headerValue)
	{
		if (headerValue == null)
		{
			return "<null>";
		}
		final String flattened = headerValue.replaceAll("[\\r\\n]", "");
		return flattened.length() > KEY_ID_MAX_LENGTH ? flattened.substring(0, KEY_ID_MAX_LENGTH) + "..." : flattened;
	}

	public PayoneConfigurationService getPayoneConfigurationService()
	{
		return payoneConfigurationService;
	}

	public void setPayoneConfigurationService(final PayoneConfigurationService payoneConfigurationService)
	{
		this.payoneConfigurationService = payoneConfigurationService;
	}
}
