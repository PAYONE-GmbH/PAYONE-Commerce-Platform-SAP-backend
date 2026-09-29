package com.payone.pcp.webhook.service;

import com.payone.pcp.webhook.exception.PayoneWebhookSignatureException;

/**
 * Proves that an inbound delivery really came from PAYONE. Called first, before
 * the body is deserialised or persisted, per the PCP guide's "verify first"
 * rule. {@code PCP-ServerSDK-java} ships no verifier, hence this class.
 */
public interface PayoneWebhookSignatureService
{
	/**
     * Verifies the {@code X-GCS-Signature} of a delivery.
     *
     * @param rawBody    the exact, unparsed request body bytes (the signature is
     *                   computed over the raw payload)
     * @param keyId      value of the {@code X-GCS-KeyId} header, identifying which
     *                   configured secret to verify against
     * @param signature  value of the {@code X-GCS-Signature} header (Base64)
     * @param merchantId
     * @throws PayoneWebhookSignatureException on any failure — absent/malformed
     *                                         signature, mismatch, or no secret
     *                                         configured for {@code keyId}. The
     *                                         caller must not be able to tell
     *                                         these apart.
     */
	void verify(byte[] rawBody, String keyId, String signature, String merchantId);
}
