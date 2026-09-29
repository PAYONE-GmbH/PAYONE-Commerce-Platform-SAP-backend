package com.payone.pcp.webhook.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.lenient;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayoneConfigurationService;
import com.payone.pcp.webhook.exception.PayoneWebhookSignatureException;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests the class that makes an unauthenticated, CSRF-exempt endpoint safe.
 *
 * <p>EXPECTED_SIGNATURE is a known-answer vector, produced independently of this codebase:
 * {@code printf '%s' "$BODY" | openssl dgst -sha256 -hmac "test-secret-value" -binary | base64}.
 * Computing it with the same Mac calls as the implementation would only prove the code agrees
 * with itself; pinning a third-party output fixes the wire format PAYONE has to agree with.
 */
@ExtendWith(MockitoExtension.class)
class DefaultPayoneWebhookSignatureServiceUnitTest
{
	/** A realistic PCP envelope, byte-for-byte what the vector was computed over. */
	private static final String BODY = "{\"apiVersion\":\"v1\",\"created\":\"2023-02-14T13:21:40.3744722+01:00\","
			+ "\"id\":\"686e5823-1ffd-42f7-9ba3-42b41b57d8dd\",\"merchantId\":\"P1_TestMerchant\","
			+ "\"type\":\"payment.captured\"}";

	private static final String MERCHANT_ID = "P1_TestMerchant";
	private static final String KEY_ID = "aaaaaaaa-bbbb-cccc-dddd-bdfa6f87a1b2";
	private static final String SECRET = "test-secret-value";

	/** Computed by openssl — see the class comment. */
	private static final String EXPECTED_SIGNATURE = "JKYk/cbSZcPHbpOANrDTA3sawBqqWm+8ytEceea4X+w=";

	@Mock
	private PayoneConfigurationService payoneConfigurationService;

	@Mock
	private PayoneConfigurationModel configuration;

	private DefaultPayoneWebhookSignatureService service;

	@BeforeEach
	void setUp()
	{
		service = new DefaultPayoneWebhookSignatureService();
		service.setPayoneConfigurationService(payoneConfigurationService);

		// lenient: the early-rejection tests never reach configuration lookup.
		lenient().when(payoneConfigurationService.getConfigurationByMerchantId(MERCHANT_ID)).thenReturn(configuration);
		lenient().when(configuration.getMerchantId()).thenReturn(MERCHANT_ID);
	}

	private void givenApiKeyId(final String apiKeyId)
	{
		lenient().when(configuration.getApiKeyId()).thenReturn(apiKeyId);
	}

	private void givenApiSecret(final String secret)
	{
		lenient().when(configuration.getApiSecret()).thenReturn(secret);
	}

	private void verify(final String signature)
	{
		service.verify(BODY.getBytes(StandardCharsets.UTF_8), KEY_ID, signature, MERCHANT_ID);
	}

	private void assertRejected(final Runnable call)
	{
		assertThatExceptionOfType(PayoneWebhookSignatureException.class).isThrownBy(call::run);
	}

	// The wire format

	/** Our HMAC must equal what an independent implementation produces for the same input. */
	@Test
	void acceptsTheSignatureAnIndependentImplementationProduces()
	{
		givenApiKeyId(KEY_ID);
		givenApiSecret(SECRET);

		assertThatCode(() -> verify(EXPECTED_SIGNATURE)).doesNotThrowAnyException();
	}

	/** Base64 may arrive padded with whitespace after passing through proxies. */
	@Test
	void acceptsASignaturePaddedWithWhitespace()
	{
		givenApiKeyId(KEY_ID);
		givenApiSecret(SECRET);

		assertThatCode(() -> verify("  " + EXPECTED_SIGNATURE + "\n")).doesNotThrowAnyException();
	}

	// PayoneConfiguration resolution

	/** Fails closed: an unknown merchantId must reject every delivery. */
	@Test
	void rejectsWhenNoConfigurationExistsForTheMerchantId()
	{
		lenient().when(payoneConfigurationService.getConfigurationByMerchantId(MERCHANT_ID)).thenReturn(null);

		assertRejected(() -> verify(EXPECTED_SIGNATURE));
	}

	/** A configuration whose secrets can't be resolved from the environment is a deployment fault, not hostile traffic, but it must still reject. */
	@Test
	void rejectsWhenConfigurationResolutionFails()
	{
		lenient().when(payoneConfigurationService.getConfigurationByMerchantId(MERCHANT_ID))
				.thenThrow(new IllegalStateException("apiSecret not configured"));

		assertRejected(() -> verify(EXPECTED_SIGNATURE));
	}

	/** A key id issued to a different merchant must not verify, even against the right merchantId's secret. */
	@Test
	void rejectsWhenTheDeliveredKeyIdDoesNotMatchTheConfiguredApiKeyId()
	{
		givenApiKeyId("a-different-key-id");
		givenApiSecret(SECRET);

		assertRejected(() -> verify(EXPECTED_SIGNATURE));
	}

	/** Fails closed: an unconfigured apiSecret must reject every delivery. */
	@Test
	void rejectsWhenNoApiSecretIsConfigured()
	{
		givenApiKeyId(KEY_ID);
		givenApiSecret(null);

		assertRejected(() -> verify(EXPECTED_SIGNATURE));
	}

	@Test
	void rejectsWhenTheConfiguredApiSecretIsBlank()
	{
		givenApiKeyId(KEY_ID);
		givenApiSecret("   ");

		assertRejected(() -> verify(EXPECTED_SIGNATURE));
	}

	// Forgery and tampering

	@Test
	void rejectsASignatureComputedWithTheWrongSecret()
	{
		givenApiKeyId(KEY_ID);
		givenApiSecret("not-the-right-secret");

		assertRejected(() -> verify(EXPECTED_SIGNATURE));
	}

	/** The core anti-tampering property: one flipped character must invalidate the signature. */
	@Test
	void rejectsABodyAlteredByASingleCharacter()
	{
		givenApiKeyId(KEY_ID);
		givenApiSecret(SECRET);

		final String tampered = BODY.replace("payment.captured", "payment.refunded");

		assertRejected(() -> service.verify(tampered.getBytes(StandardCharsets.UTF_8), KEY_ID, EXPECTED_SIGNATURE, MERCHANT_ID));
	}

	// Malformed input — all rejected before any configuration lookup

	@Test
	void rejectsAMissingSignatureHeader()
	{
		assertRejected(() -> verify(null));
		assertRejected(() -> verify("   "));
	}

	@Test
	void rejectsASignatureThatIsNotValidBase64()
	{
		givenApiKeyId(KEY_ID);
		givenApiSecret(SECRET);

		assertRejected(() -> verify("this is not base64 !!!"));
	}

	@Test
	void rejectsAMissingKeyIdHeader()
	{
		assertRejected(() -> service.verify(BODY.getBytes(StandardCharsets.UTF_8), null, EXPECTED_SIGNATURE, MERCHANT_ID));
		assertRejected(() -> service.verify(BODY.getBytes(StandardCharsets.UTF_8), "  ", EXPECTED_SIGNATURE, MERCHANT_ID));
	}

	/** The key id is compared against the configured apiKeyId, so its shape is constrained up front. */
	@Test
	void rejectsAKeyIdOutsideTheAllowedCharacterSet()
	{
		assertRejected(() -> service.verify(BODY.getBytes(StandardCharsets.UTF_8), "key id with spaces",
				EXPECTED_SIGNATURE, MERCHANT_ID));
		assertRejected(() -> service.verify(BODY.getBytes(StandardCharsets.UTF_8), "key/../../etc",
				EXPECTED_SIGNATURE, MERCHANT_ID));
		assertRejected(() -> service.verify(BODY.getBytes(StandardCharsets.UTF_8), "key\r\nInjected: header",
				EXPECTED_SIGNATURE, MERCHANT_ID));
	}

	@Test
	void rejectsAnOverlongKeyId()
	{
		assertRejected(() -> service.verify(BODY.getBytes(StandardCharsets.UTF_8), "k".repeat(129),
				EXPECTED_SIGNATURE, MERCHANT_ID));
	}

	/** HmacSHA256 over zero bytes is a valid MAC, so an empty body must be rejected explicitly. */
	@Test
	void rejectsAnEmptyBody()
	{
		assertRejected(() -> service.verify(new byte[0], KEY_ID, EXPECTED_SIGNATURE, MERCHANT_ID));
		assertRejected(() -> service.verify(null, KEY_ID, EXPECTED_SIGNATURE, MERCHANT_ID));
	}

	/** No merchantId means no configuration to resolve a key id or secret from. */
	@Test
	void rejectsAMissingMerchantId()
	{
		assertRejected(() -> service.verify(BODY.getBytes(StandardCharsets.UTF_8), KEY_ID, EXPECTED_SIGNATURE, null));
		assertRejected(() -> service.verify(BODY.getBytes(StandardCharsets.UTF_8), KEY_ID, EXPECTED_SIGNATURE, "   "));
	}
}
