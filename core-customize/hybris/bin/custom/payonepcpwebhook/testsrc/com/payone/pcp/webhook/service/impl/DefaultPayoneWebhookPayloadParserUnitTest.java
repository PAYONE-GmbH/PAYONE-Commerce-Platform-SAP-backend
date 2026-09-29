package com.payone.pcp.webhook.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.exception.PayoneWebhookPayloadException;

import org.junit.jupiter.api.Test;

/**
 * Pins what the parser accepts, rejects outright, and tolerates (missing type, unknown
 * properties), since it is the first thing a raw, untrusted request body reaches.
 */
class DefaultPayoneWebhookPayloadParserUnitTest
{
	private static final String FULL_ENVELOPE = "{"
			+ "\"apiVersion\":\"v1\","
			+ "\"created\":\"2023-02-14T13:21:40.3744722+01:00\","
			+ "\"id\":\"686e5823-1ffd-42f7-9ba3-42b41b57d8dd\","
			+ "\"merchantId\":\"P1_TestMerchant\","
			+ "\"type\":\"payment.captured\","
			+ "\"payment\":{\"id\":\"3066019730\",\"status\":\"CAPTURED\",\"statusCategory\":\"SUCCESSFUL\"},"
			+ "\"commerceCase\":{\"id\":\"case-1\",\"merchantReference\":\"ref-1\",\"merchantCustomerId\":\"cust-1\"},"
			+ "\"checkout\":{\"id\":\"checkout-1\",\"merchantReference\":\"ref-2\",\"checkoutStatus\":\"COMPLETED\"},"
			+ "\"paymentExecution\":{\"id\":\"exec-1\",\"paymentId\":\"3066019730\",\"merchantReference\":\"ref-3\","
			+ "\"paymentChannel\":\"ECOMMERCE\",\"paymentProductId\":1}"
			+ "}";

	private final DefaultPayoneWebhookPayloadParser parser = new DefaultPayoneWebhookPayloadParser();

	// Rejected outright

	@Test
	void rejectsANullPayload()
	{
		assertThatExceptionOfType(PayoneWebhookPayloadException.class).isThrownBy(() -> parser.parse(null));
	}

	@Test
	void rejectsABlankPayload()
	{
		assertThatExceptionOfType(PayoneWebhookPayloadException.class).isThrownBy(() -> parser.parse("   "));
	}

	@Test
	void rejectsPayloadThatIsNotValidJson()
	{
		assertThatExceptionOfType(PayoneWebhookPayloadException.class)
				.isThrownBy(() -> parser.parse("{not json"));
	}

	@Test
	void rejectsJsonMissingTheEventId()
	{
		assertThatExceptionOfType(PayoneWebhookPayloadException.class)
				.isThrownBy(() -> parser.parse("{\"apiVersion\":\"v1\",\"type\":\"payment.captured\"}"));
	}

	@Test
	void rejectsJsonWhereTheEventIdIsBlank()
	{
		assertThatExceptionOfType(PayoneWebhookPayloadException.class)
				.isThrownBy(() -> parser.parse("{\"id\":\"   \",\"type\":\"payment.captured\"}"));
	}

	// Tolerated

	/** A missing type is only logged; the processor decides what to do with it. */
	@Test
	void parsesSuccessfullyWhenTypeIsMissing()
	{
		final PayoneWebhookEventDto envelope = parser.parse("{\"id\":\"686e5823-1ffd-42f7-9ba3-42b41b57d8dd\"}");

		assertThat(envelope.getId()).isEqualTo("686e5823-1ffd-42f7-9ba3-42b41b57d8dd");
		assertThat(envelope.getType()).isNull();
	}

	/** FAIL_ON_UNKNOWN_PROPERTIES is disabled so PCP can extend the payload freely. */
	@Test
	void toleratesAnUnknownExtraProperty()
	{
		final PayoneWebhookEventDto envelope = parser.parse(
				"{\"id\":\"686e5823-1ffd-42f7-9ba3-42b41b57d8dd\",\"type\":\"payment.captured\",\"somethingNew\":42}");

		assertThat(envelope.getId()).isEqualTo("686e5823-1ffd-42f7-9ba3-42b41b57d8dd");
		assertThat(envelope.getType()).isEqualTo("payment.captured");
	}

	// Full envelope

	@Test
	void parsesEveryMemberOfAFullEnvelope()
	{
		final PayoneWebhookEventDto envelope = parser.parse(FULL_ENVELOPE);

		assertThat(envelope.getApiVersion()).isEqualTo("v1");
		assertThat(envelope.getCreated()).isEqualTo("2023-02-14T13:21:40.3744722+01:00");
		assertThat(envelope.getId()).isEqualTo("686e5823-1ffd-42f7-9ba3-42b41b57d8dd");
		assertThat(envelope.getMerchantId()).isEqualTo("P1_TestMerchant");
		assertThat(envelope.getType()).isEqualTo("payment.captured");

		assertThat(envelope.getPayment().getId()).isEqualTo("3066019730");
		assertThat(envelope.getPayment().getStatus()).isEqualTo("CAPTURED");
		assertThat(envelope.getPayment().getStatusCategory()).isEqualTo("SUCCESSFUL");

		assertThat(envelope.getCommerceCase().getId()).isEqualTo("case-1");
		assertThat(envelope.getCommerceCase().getMerchantReference()).isEqualTo("ref-1");
		assertThat(envelope.getCommerceCase().getMerchantCustomerId()).isEqualTo("cust-1");

		assertThat(envelope.getCheckout().getId()).isEqualTo("checkout-1");
		assertThat(envelope.getCheckout().getMerchantReference()).isEqualTo("ref-2");
		assertThat(envelope.getCheckout().getCheckoutStatus()).isEqualTo("COMPLETED");

		assertThat(envelope.getPaymentExecution().getId()).isEqualTo("exec-1");
		assertThat(envelope.getPaymentExecution().getPaymentId()).isEqualTo("3066019730");
		assertThat(envelope.getPaymentExecution().getMerchantReference()).isEqualTo("ref-3");
		assertThat(envelope.getPaymentExecution().getPaymentChannel()).isEqualTo("ECOMMERCE");
		assertThat(envelope.getPaymentExecution().getPaymentProductId()).isEqualTo(1);
	}
}
