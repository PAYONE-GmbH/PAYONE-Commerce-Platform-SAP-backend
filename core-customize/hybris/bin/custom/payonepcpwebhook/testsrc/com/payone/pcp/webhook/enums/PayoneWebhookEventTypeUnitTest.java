package com.payone.pcp.webhook.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.payone.pcp.core.enums.PayonePaymentStatus;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PayoneWebhookEventType}.
 */
class PayoneWebhookEventTypeUnitTest
{
	// fromCode()

	@Test
	void fromCodeIsEmptyForNull()
	{
		assertThat(PayoneWebhookEventType.fromCode(null)).isEmpty();
	}

	@Test
	void fromCodeIsEmptyForAnEmptyString()
	{
		assertThat(PayoneWebhookEventType.fromCode("")).isEmpty();
	}

	@Test
	void fromCodeIsEmptyForBlankWhitespace()
	{
		assertThat(PayoneWebhookEventType.fromCode("   ")).isEmpty();
	}

	@Test
	void fromCodeIsEmptyForAnUnrecognisedToken()
	{
		assertThat(PayoneWebhookEventType.fromCode("not.a.real.type")).isEmpty();
	}

	@Test
	void fromCodeResolvesAKnownTokenAndRoundTripsThroughGetCode()
	{
		final Optional<PayoneWebhookEventType> resolved = PayoneWebhookEventType.fromCode("payment.captured");

		assertThat(resolved).contains(PayoneWebhookEventType.PAYMENT_CAPTURED);
		assertThat(resolved.get().getCode()).isEqualTo("payment.captured");
	}

	// getStatus()

	@Test
	void aMappedEventTypeHasThePayonePaymentStatusCounterpart()
	{
		assertThat(PayoneWebhookEventType.PAYMENT_CAPTURED.getStatus()).contains(PayonePaymentStatus.CAPTURED);
	}

	/** payment.paid is one of the 11 published tokens with no PayonePaymentStatus counterpart. */
	@Test
	void anUnmappedEventTypeHasNoStatus()
	{
		assertThat(PayoneWebhookEventType.PAYMENT_PAID.getStatus()).isEmpty();
	}
}
