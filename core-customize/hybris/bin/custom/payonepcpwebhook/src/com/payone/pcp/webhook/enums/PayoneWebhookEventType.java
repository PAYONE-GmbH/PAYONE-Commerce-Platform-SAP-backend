package com.payone.pcp.webhook.enums;

import com.payone.pcp.core.enums.PayonePaymentStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A PCP webhook event type token, transcribed from the Commerce Webhook API (1.11.0) reference,
 * with its envelope domain and the {@link PayonePaymentStatus} it transitions that domain to,
 * where one exists.
 * <p>
 * {@code PayoneWebhookEvent.eventType} stays a raw string so a token PAYONE adds tomorrow is
 * still storable; this enum is the mapping layer on top, and {@link #fromCode} returns empty for
 * an unrecognised token rather than rejecting it. The status is not derived by string-munging the
 * suffix ({@code PayonePaymentStatus.valueOf(suffix)}): {@code PayonePaymentStatus} is
 * {@code dynamic="true"}, so {@code valueOf()} would manufacture an instance for any code, and
 * the mapping isn't total anyway.
 * <p>
 * 11 published event types have no {@code PayonePaymentStatus} counterpart and carry a null
 * status: {@code payment.account_verified}, {@code payment.pending_fraud_approval},
 * {@code payment.pending_approval}, {@code payment.paid}, {@code payment.chargeback_notification},
 * {@code refund.pending_approval}, {@code payout.pending_approval},
 * {@code payment_execution.payment_reserved}, {@code payment_execution.payment_completed},
 * {@code payment_execution.payment_refreshed}, {@code payment_information.payment_reserved}. A
 * configured domain handler fails explicitly for these until the merchant supplies a mapping.
 * {@code payment.paid} (terminal success of a redirect flow) and
 * {@code payment.chargeback_notification} (the guide's risk signal) are the consequential ones.
 * Extending {@code PayonePaymentStatus} is a payonepcpcore change, so
 * {@code DefaultPayoneWebhookEventProcessor} logs every unmapped-but-recognised token at WARN
 * before the relevant handler rejects it.
 *
 * @see <a href="https://docs.commerce.payone.com/webhook-api-reference">Commerce Webhook API reference</a>
 */
public enum PayoneWebhookEventType
{
	// --- payment.* — lifecycle of a payment transaction -----------------------
	PAYMENT_CREATED("payment.created", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.CREATED),
	PAYMENT_REDIRECTED("payment.redirected", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.REDIRECTED),
	PAYMENT_PENDING_PAYMENT("payment.pending_payment", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.PENDING_PAYMENT),
	PAYMENT_ACCOUNT_VERIFIED("payment.account_verified", PayoneWebhookDomain.PAYMENT, null),
	PAYMENT_PENDING_FRAUD_APPROVAL("payment.pending_fraud_approval", PayoneWebhookDomain.PAYMENT, null),
	PAYMENT_PENDING_APPROVAL("payment.pending_approval", PayoneWebhookDomain.PAYMENT, null),
	PAYMENT_PENDING_COMPLETION("payment.pending_completion", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.PENDING_COMPLETION),
	PAYMENT_PAID("payment.paid", PayoneWebhookDomain.PAYMENT, null),
	PAYMENT_REVERSED("payment.reversed", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.REVERSED),
	PAYMENT_CHARGEBACK_NOTIFICATION("payment.chargeback_notification", PayoneWebhookDomain.PAYMENT, null),
	PAYMENT_AUTHORIZATION_REQUESTED("payment.authorization_requested", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.AUTHORIZATION_REQUESTED),
	PAYMENT_PENDING_CAPTURE("payment.pending_capture", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.PENDING_CAPTURE),
	PAYMENT_CAPTURED("payment.captured", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.CAPTURED),
	PAYMENT_CAPTURE_REQUESTED("payment.capture_requested", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.CAPTURE_REQUESTED),
	PAYMENT_REFUNDED("payment.refunded", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.REFUNDED),
	PAYMENT_CANCELLED("payment.cancelled", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.CANCELLED),
	PAYMENT_CANCELLATION_REQUESTED("payment.cancellation_requested", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.CANCELLATION_REQUESTED),
	PAYMENT_REJECTED("payment.rejected", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.REJECTED),
	PAYMENT_REJECTED_CAPTURE("payment.rejected_capture", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.REJECTED_CAPTURE),
	PAYMENT_REJECTED_REFUND("payment.rejected_refund", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.REJECTED_REFUND),
	PAYMENT_CHARGEBACKED("payment.chargebacked", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.CHARGEBACKED),
	PAYMENT_CHARGEBACK_REVERSED("payment.chargeback_reversed", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.CHARGEBACK_REVERSED),
	PAYMENT_ACCOUNT_CREDITED("payment.account_credited", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.ACCOUNT_CREDITED),
	PAYMENT_ACCOUNT_DEBITED("payment.account_debited", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.ACCOUNT_DEBITED),
	PAYMENT_PAUSED("payment.paused", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.PAUSED),
	PAYMENT_REJECTED_PAUSE("payment.rejected_pause", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.REJECTED_PAUSE),
	PAYMENT_UPDATED("payment.updated", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.UPDATED),
	PAYMENT_REJECTED_UPDATE("payment.rejected_update", PayoneWebhookDomain.PAYMENT, PayonePaymentStatus.REJECTED_UPDATE),

	// --- refund.* — refund process states ------------------------------------
	REFUND_REFUND_REQUESTED("refund.refund_requested", PayoneWebhookDomain.REFUND, PayonePaymentStatus.REFUND_REQUESTED),
	REFUND_CREATED("refund.created", PayoneWebhookDomain.REFUND, PayonePaymentStatus.CREATED),
	REFUND_PENDING_APPROVAL("refund.pending_approval", PayoneWebhookDomain.REFUND, null),
	REFUND_REJECTED("refund.rejected", PayoneWebhookDomain.REFUND, PayonePaymentStatus.REJECTED),
	REFUND_CAPTURED("refund.captured", PayoneWebhookDomain.REFUND, PayonePaymentStatus.CAPTURED),
	REFUND_REFUNDED("refund.refunded", PayoneWebhookDomain.REFUND, PayonePaymentStatus.REFUNDED),
	REFUND_CANCELLED("refund.cancelled", PayoneWebhookDomain.REFUND, PayonePaymentStatus.CANCELLED),

	// --- payout.* — merchant outbound fund movement --------------------------
	PAYOUT_CREATED("payout.created", PayoneWebhookDomain.PAYOUT, PayonePaymentStatus.CREATED),
	PAYOUT_PENDING_APPROVAL("payout.pending_approval", PayoneWebhookDomain.PAYOUT, null),
	PAYOUT_REJECTED("payout.rejected", PayoneWebhookDomain.PAYOUT, PayonePaymentStatus.REJECTED),
	PAYOUT_PAYOUT_REQUESTED("payout.payout_requested", PayoneWebhookDomain.PAYOUT, PayonePaymentStatus.PAYOUT_REQUESTED),
	PAYOUT_ACCOUNT_CREDITED("payout.account_credited", PayoneWebhookDomain.PAYOUT, PayonePaymentStatus.ACCOUNT_CREDITED),
	PAYOUT_REJECTED_CREDIT("payout.rejected_credit", PayoneWebhookDomain.PAYOUT, PayonePaymentStatus.REJECTED_CREDIT),
	PAYOUT_CANCELLED("payout.cancelled", PayoneWebhookDomain.PAYOUT, PayonePaymentStatus.CANCELLED),
	PAYOUT_REVERSED("payout.reversed", PayoneWebhookDomain.PAYOUT, PayonePaymentStatus.REVERSED),

	// --- commerce_case.* — order/case context changes -------------------------
	// PayoneCommerceCase carries no status attribute (it is a container, and the
	// lifecycle lives on its checkouts), so both tokens map to a null status and
	// are handled purely as "resolve and link the case".
	COMMERCE_CASE_CREATED("commerce_case.created", PayoneWebhookDomain.COMMERCE_CASE, null),
	COMMERCE_CASE_UPDATED("commerce_case.updated", PayoneWebhookDomain.COMMERCE_CASE, null),

	// --- checkout.* — checkout progression -----------------------------------
	CHECKOUT_CREATED("checkout.created", PayoneWebhookDomain.CHECKOUT, PayonePaymentStatus.CREATED),
	CHECKOUT_UPDATED("checkout.updated", PayoneWebhookDomain.CHECKOUT, PayonePaymentStatus.UPDATED),
	// INTERPRETATION, not a 1:1 PCP StatusValue: PCP has no DELETED status value.
	// Mapping to CANCELLED is chosen over leaving the local status stale, since a
	// deleted checkout can never progress again. Revisit if PCP adds DELETED.
	CHECKOUT_DELETED("checkout.deleted", PayoneWebhookDomain.CHECKOUT, PayonePaymentStatus.CANCELLED),

	// --- payment_execution.* — payment-operation specific processes -----------
	PAYMENT_EXECUTION_PAYMENT_CREATED("payment_execution.payment_created", PayoneWebhookDomain.PAYMENT_EXECUTION, PayonePaymentStatus.CREATED),
	PAYMENT_EXECUTION_PAYMENT_CAPTURED("payment_execution.payment_captured", PayoneWebhookDomain.PAYMENT_EXECUTION, PayonePaymentStatus.CAPTURED),
	PAYMENT_EXECUTION_PAYMENT_RESERVED("payment_execution.payment_reserved", PayoneWebhookDomain.PAYMENT_EXECUTION, null),
	PAYMENT_EXECUTION_PAYMENT_REVERSED("payment_execution.payment_reversed", PayoneWebhookDomain.PAYMENT_EXECUTION, PayonePaymentStatus.REVERSED),
	PAYMENT_EXECUTION_PAYMENT_REFUNDED("payment_execution.payment_refunded", PayoneWebhookDomain.PAYMENT_EXECUTION, PayonePaymentStatus.REFUNDED),
	PAYMENT_EXECUTION_PAYMENT_COMPLETED("payment_execution.payment_completed", PayoneWebhookDomain.PAYMENT_EXECUTION, null),
	PAYMENT_EXECUTION_PAYMENT_PAUSED("payment_execution.payment_paused", PayoneWebhookDomain.PAYMENT_EXECUTION, PayonePaymentStatus.PAUSED),
	PAYMENT_EXECUTION_PAYMENT_REFRESHED("payment_execution.payment_refreshed", PayoneWebhookDomain.PAYMENT_EXECUTION, null),

	// --- payment_information.* — POS / terminal transactions ------------------
	PAYMENT_INFORMATION_CREATED("payment_information.created", PayoneWebhookDomain.PAYMENT_INFORMATION, PayonePaymentStatus.CREATED),
	PAYMENT_INFORMATION_PAYMENT_CAPTURED("payment_information.payment_captured", PayoneWebhookDomain.PAYMENT_INFORMATION, PayonePaymentStatus.CAPTURED),
	PAYMENT_INFORMATION_PAYMENT_RESERVED("payment_information.payment_reserved", PayoneWebhookDomain.PAYMENT_INFORMATION, null),
	PAYMENT_INFORMATION_PAYMENT_REVERSED("payment_information.payment_reversed", PayoneWebhookDomain.PAYMENT_INFORMATION, PayonePaymentStatus.REVERSED),
	PAYMENT_INFORMATION_PAYMENT_REFUNDED("payment_information.payment_refunded", PayoneWebhookDomain.PAYMENT_INFORMATION, PayonePaymentStatus.REFUNDED),
	PAYMENT_INFORMATION_PAYMENT_CHARGEBACKED("payment_information.payment_chargebacked", PayoneWebhookDomain.PAYMENT_INFORMATION, PayonePaymentStatus.CHARGEBACKED);

	/** Lookup by wire token. Built once; the enum is immutable. */
	private static final Map<String, PayoneWebhookEventType> BY_CODE = buildIndex();

	private final String code;
	private final PayoneWebhookDomain domain;
	private final PayonePaymentStatus status;

	PayoneWebhookEventType(final String code, final PayoneWebhookDomain domain, final PayonePaymentStatus status)
	{
		this.code = code;
		this.domain = domain;
		this.status = status;
	}

	private static Map<String, PayoneWebhookEventType> buildIndex()
	{
		final Map<String, PayoneWebhookEventType> index = new HashMap<>();
		for (final PayoneWebhookEventType type : values())
		{
			index.put(type.code, type);
		}
		return Collections.unmodifiableMap(index);
	}

	/**
	 * Resolves a wire token to its enum constant.
	 *
	 * @param code the raw {@code type} member of the envelope; may be null
	 * @return the matching type, or empty for null / blank / unrecognised input
	 *         (not an error — an event type newer than this build)
	 */
	public static Optional<PayoneWebhookEventType> fromCode(final String code)
	{
		if (code == null || code.isBlank())
		{
			return Optional.empty();
		}
		return Optional.ofNullable(BY_CODE.get(code.trim()));
	}

	/** @return the wire token, e.g. {@code payment.captured}. */
	public String getCode()
	{
		return code;
	}

	/** @return the envelope domain this event describes. */
	public PayoneWebhookDomain getDomain()
	{
		return domain;
	}

	/**
	 * @return the PayonePaymentStatus this event transitions its domain object
	 *         to, or empty when the published event type has no counterpart in
	 *         the enum (see UNMAPPED in the class comment).
	 */
	public Optional<PayonePaymentStatus> getStatus()
	{
		return Optional.ofNullable(status);
	}
}
