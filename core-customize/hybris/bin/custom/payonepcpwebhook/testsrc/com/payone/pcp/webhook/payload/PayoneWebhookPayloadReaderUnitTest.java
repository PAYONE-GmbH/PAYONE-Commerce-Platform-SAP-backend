package com.payone.pcp.webhook.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.enums.PayoneWebhookDomain;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.service.impl.DefaultPayoneWebhookPayloadParser;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PayoneWebhookPayloadUtil}, driven by the reference sample from the
 * Commerce Webhook API (1.11.0), which populates every root member (payment, refund, payout,
 * commerceCase, checkout, paymentExecution, paymentInformation) with different ids and amounts
 * so a reader that just grabs "the first member present" gets caught.
 */
@UnitTest
class PayoneWebhookPayloadReaderUnitTest
{
	/** The reference sample, trimmed to the members this extension reads. */
	private static final String REFERENCE_SAMPLE = """
			{
			  "apiVersion": "v1",
			  "created": "2023-02-14T13:21:40.3744722+01:00",
			  "id": "686e5823-1ffd-42f7-9ba3-42b41b57d8dd",
			  "merchantId": "P1_TestMerchant",
			  "payment": {
			    "paymentOutput": {
			      "amountOfMoney": { "amount": 1000, "currencyCode": "EUR" },
			      "references": { "merchantReference": "your-order-6372" },
			      "cardPaymentMethodSpecificOutput": { "paymentProductId": 840 },
			      "paymentMethod": "string"
			    },
			    "status": "CREATED",
			    "statusOutput": {
			      "isCancellable": true,
			      "statusCategory": "CREATED",
			      "isAuthorized": true,
			      "isRefundable": true
			    },
			    "hostedCheckoutSpecificOutput": { "hostedCheckoutId": 3066019730 },
			    "id": "PP1AA7KKLSFB9MBG"
			  },
			  "refund": {
			    "refundOutput": {
			      "amountOfMoney": { "amount": 400, "currencyCode": "EUR" },
			      "references": { "merchantReference": "your-order-6372" }
			    },
			    "status": "CREATED",
			    "id": "3066019730_1"
			  },
			  "payout": {
			    "payoutOutput": {
			      "amountOfMoney": { "amount": 250, "currencyCode": "EUR" }
			    },
			    "status": "CREATED",
			    "statusCategory": "CREATED",
			    "id": "PP1AA7KKLSFB9MBG"
			  },
			  "commerceCase": {
			    "id": "0c3ab9d7-19ed-40da-9a0e-1f96f4cfb8ae",
			    "merchantReference": "customer-commerce-case-123",
			    "merchantCustomerId": "1234"
			  },
			  "checkout": {
			    "id": "4f0c512e-f12c-11ec-8ea0-0242ac120002",
			    "merchantReference": "customer-order-1234",
			    "checkoutStatus": "OPEN",
			    "statusOutput": {
			      "paymentStatus": "WAITING_FOR_PAYMENT",
			      "isModifiable": true,
			      "openAmount": 999999999999,
			      "collectedAmount": 999999999999,
			      "cancelledAmount": 999999999999,
			      "refundedAmount": 999999999999,
			      "chargebackAmount": 999999999999
			    }
			  },
			  "paymentExecution": {
			    "id": "4f0c512e-f12c-11ec-8ea0-0242ac120002",
			    "paymentId": "3066019730",
			    "merchantReference": "5a891df0b8cf11edaeb2af87d8ff0b2f",
			    "paymentChannel": "ECOMMERCE",
			    "events": [
			      {
			        "type": "SALE",
			        "amountOfMoney": { "amount": 1000, "currencyCode": "EUR" },
			        "paymentStatus": "CREATED",
			        "cancellationReason": "CONSUMER_REQUEST",
			        "returnReason": "Customer complained"
			      },
			      {
			        "type": "CAPTURE",
			        "amountOfMoney": { "amount": 600, "currencyCode": "EUR" },
			        "paymentStatus": "CAPTURED"
			      }
			    ],
			    "paymentProductId": 840
			  },
			  "paymentInformation": {
			    "id": "4f0c512e-f12c-11ec-8ea0-0242ac120002",
			    "paymentId": "3066019730",
			    "paymentChannel": "ECOMMERCE",
			    "paymentProductId": 840,
			    "events": [
			      {
			        "type": "SALE",
			        "amountOfMoney": { "amount": 900, "currencyCode": "EUR" },
			        "paymentStatus": "CREATED"
			      }
			    ]
			  },
			  "type": "payment.created"
			}
			""";

	private PayoneWebhookEventDto envelope;

	@BeforeEach
	void setUp()
	{
		envelope = new DefaultPayoneWebhookPayloadParser().parse(REFERENCE_SAMPLE);
	}

	@Test
	void testParse_whenReferenceSampleHasAllMembersPopulated_thenAllMembersAreReadCorrectly()
	{
		assertThat(envelope.getPayment().getPaymentOutput().getAmountOfMoney().getAmount()).isEqualTo(1000L);
		assertThat(envelope.getPayment().getPaymentOutput().getAmountOfMoney().getCurrencyCode()).isEqualTo("EUR");
		assertThat(envelope.getPayment().getPaymentOutput().getReferences().getMerchantReference())
				.isEqualTo("your-order-6372");
		assertThat(envelope.getPayment().getStatusOutput().getIsAuthorized()).isTrue();
		assertThat(envelope.getRefund().getRefundOutput().getAmountOfMoney().getAmount()).isEqualTo(400L);
		assertThat(envelope.getPayout().getPayoutOutput().getAmountOfMoney().getAmount()).isEqualTo(250L);
		assertThat(envelope.getCheckout().getStatusOutput().getPaymentStatus()).isEqualTo("WAITING_FOR_PAYMENT");
		assertThat(envelope.getPaymentExecution().getEvents()).hasSize(2);
		assertThat(envelope.getPaymentInformation().getEvents()).hasSize(1);
	}

	// domain, not member presence, decides which object is read
	@Test
	void testGetAmount_whenAllDomainsArePopulated_thenEachEventTypeReturnsItsOwnDomainAmount()
	{
		assertThat(amountFor(PayoneWebhookEventType.PAYMENT_CREATED)).isEqualTo(1000L);
		assertThat(amountFor(PayoneWebhookEventType.REFUND_REFUNDED)).isEqualTo(400L);
		assertThat(amountFor(PayoneWebhookEventType.PAYOUT_CREATED)).isEqualTo(250L);
		assertThat(amountFor(PayoneWebhookEventType.PAYMENT_INFORMATION_CREATED)).isEqualTo(900L);
	}

	@Test
	void testResolveOperationId_whenDomainIsGiven_thenReturnsOperationIdForThatDomain()
	{
		assertThat(PayoneWebhookPayloadUtil.resolveOperationId(envelope, PayoneWebhookDomain.PAYMENT))
				.contains("PP1AA7KKLSFB9MBG");
		assertThat(PayoneWebhookPayloadUtil.resolveOperationId(envelope, PayoneWebhookDomain.REFUND))
				.contains("3066019730_1");
		// The payment id, so a payment_execution.* and a payment.* event for the
		// same transition produce the same PaymentTransactionEntry requestId.
		assertThat(PayoneWebhookPayloadUtil.resolveOperationId(envelope, PayoneWebhookDomain.PAYMENT_EXECUTION))
				.contains("3066019730");
	}

	/** A payment.* envelope names no paymentExecutionId; it must not invent one. */
	@Test
	void testGetPaymentExecutionId_whenDomainLacksAnExecutionSnapshot_thenReturnsEmpty()
	{
		assertThat(PayoneWebhookPayloadUtil.getPaymentExecutionId(envelope, PayoneWebhookDomain.PAYMENT)).isEmpty();
		assertThat(PayoneWebhookPayloadUtil.getPaymentExecutionId(envelope, PayoneWebhookDomain.PAYMENT_EXECUTION))
				.contains("4f0c512e-f12c-11ec-8ea0-0242ac120002");
	}

	/** PCP always delivers the whole event history; pick the entry matching the announced transition, not the last or first one. */
	@Test
	void testGetAmount_whenExecutionEventTypeMatchesAHistoryEntry_thenReturnsThatEntrysAmount()
	{
		assertThat(amountFor(PayoneWebhookEventType.PAYMENT_EXECUTION_PAYMENT_CAPTURED)).isEqualTo(600L);
		assertThat(amountFor(PayoneWebhookEventType.PAYMENT_EXECUTION_PAYMENT_CREATED)).isEqualTo(1000L);
	}

	/** No history entry matches payment_execution.payment_paused; use the latest priced one. */
	@Test
	void testGetAmount_whenNoHistoryEntryMatchesTheEventType_thenFallsBackToTheMostRecentPricedEvent()
	{
		assertThat(amountFor(PayoneWebhookEventType.PAYMENT_EXECUTION_PAYMENT_PAUSED)).isEqualTo(600L);
	}

	@Test
	void testGetMerchantReference_whenDomainIsGiven_thenReturnsThePayoneEchoedMerchantReference()
	{
		assertThat(PayoneWebhookPayloadUtil.getMerchantReference(envelope, PayoneWebhookDomain.PAYMENT))
				.contains("your-order-6372");
		assertThat(PayoneWebhookPayloadUtil.getMerchantReference(envelope, PayoneWebhookDomain.PAYMENT_EXECUTION))
				.contains("5a891df0b8cf11edaeb2af87d8ff0b2f");
	}

	@Test
	void testAllAccessors_whenEnvelopeIsEmptyOrNull_thenReturnEmpty()
	{
		final PayoneWebhookEventDto empty = new PayoneWebhookEventDto();

		for (final PayoneWebhookDomain domain : PayoneWebhookDomain.values())
		{
			assertThat(PayoneWebhookPayloadUtil.resolveOperationId(empty, domain)).isEmpty();
			assertThat(PayoneWebhookPayloadUtil.paymentIdOf(empty, domain)).isEmpty();
			assertThat(PayoneWebhookPayloadUtil.getPaymentExecutionId(empty, domain)).isEmpty();
			assertThat(PayoneWebhookPayloadUtil.getMerchantReference(empty, domain)).isEmpty();
		}
		assertThat(PayoneWebhookPayloadUtil.getAmount(empty, PayoneWebhookEventType.PAYMENT_CAPTURED)).isEmpty();
		assertThat(PayoneWebhookPayloadUtil.getAmount(null, PayoneWebhookEventType.PAYMENT_CAPTURED)).isEmpty();
		assertThat(PayoneWebhookPayloadUtil.getAmount(empty, null)).isEmpty();
	}

	private Long amountFor(final PayoneWebhookEventType eventType)
	{
		return PayoneWebhookPayloadUtil.getAmount(envelope, eventType)
				.map(amountOfMoney -> amountOfMoney.getAmount())
				.orElse(null);
	}
}
