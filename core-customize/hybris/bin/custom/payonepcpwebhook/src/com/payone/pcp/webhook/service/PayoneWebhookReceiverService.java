package com.payone.pcp.webhook.service;

import com.payone.pcp.webhook.exception.PayoneWebhookPayloadException;
import com.payone.pcp.webhook.exception.PayoneWebhookSignatureException;
import com.payone.pcp.webhook.service.data.PayoneWebhookReceipt;

/**
 * The whole inbound webhook flow behind one call: verify, parse, dedup, store,
 * process. Keeps the controller a transport adapter and lets the trust boundary
 * be unit-tested without a DispatcherServlet.
 */
public interface PayoneWebhookReceiverService
{
	/**
	 * Takes delivery of one webhook.
	 *
	 * @param rawBody   the exact, unparsed request body bytes (the signature is
	 *                  computed over the raw payload)
	 * @param keyId     the {@code X-GCS-KeyId} header
	 * @param signature the {@code X-GCS-Signature} header
	 * @return what was done with the delivery; every outcome is answerable with 204
	 * @throws PayoneWebhookSignatureException if the delivery cannot be proven to
	 *                                         come from PAYONE (→ 401)
	 * @throws PayoneWebhookPayloadException   if the verified body is unusable (→ 400)
	 */
	PayoneWebhookReceipt receive(byte[] rawBody, String keyId, String signature);
}
