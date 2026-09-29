package com.payone.pcp.webhook.payload;

import com.payone.pcp.core.enums.PayonePaymentStatus;
import com.payone.pcp.webhook.dto.*;
import com.payone.pcp.webhook.enums.PayoneWebhookDomain;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

/**
 * Domain-aware accessors for an inbound PCP webhook envelope. A real delivery populates
 * several root members at once (payment, refund, payout, commerceCase, checkout,
 * paymentExecution and paymentInformation can all be present together), so every accessor
 * here branches on the event's domain rather than "first non-null member wins". All
 * accessors return {@code Optional.empty()} instead of throwing: a missing member is
 * malformed PCP output and must degrade to "nothing applied", not fail the delivery into
 * an endless redelivery loop.
 */
public final class PayoneWebhookPayloadUtil
{
	private PayoneWebhookPayloadUtil()
	{
		// static utility
	}

	/**
	 * The {@code payment} / {@code refund} / {@code payout} snapshot for the
	 * domain, or empty for the domains that do not use one.
	 */
	public static Optional<PayoneWebhookStatusObjectDto> resolveStatusObjectByDomain(final PayoneWebhookEventDto envelope,
                                                                                     final PayoneWebhookDomain domain)
	{
		if (envelope == null || domain == null)
		{
			return Optional.empty();
		}
		return switch (domain)
		{
			case PAYMENT -> Optional.ofNullable(envelope.getPayment());
			case REFUND -> Optional.ofNullable(envelope.getRefund());
			case PAYOUT -> Optional.ofNullable(envelope.getPayout());
			default -> Optional.empty();
		};
	}

	/**
	 * The {@code paymentExecution} / {@code paymentInformation} snapshot for the
	 * domain, or empty for the domains that do not use one.
	 */
	public static Optional<PayoneWebhookExecutionDto> getPaymentExecution(final PayoneWebhookEventDto envelope,
                                                                          final PayoneWebhookDomain domain)
	{
		if (envelope == null || domain == null)
		{
			return Optional.empty();
		}
		return switch (domain)
		{
			case PAYMENT_EXECUTION -> Optional.ofNullable(envelope.getPaymentExecution());
			case PAYMENT_INFORMATION -> Optional.ofNullable(envelope.getPaymentInformation());
			default -> Optional.empty();
		};
	}

	/**
	 * Whichever of {@code paymentOutput} / {@code refundOutput} /
	 * {@code payoutOutput} the snapshot carries — see the bean comment on why one
	 * DTO declares all three.
	 */
	public static Optional<PayoneWebhookOutputDto> getPaymentOutput(final PayoneWebhookStatusObjectDto statusObject)
	{
		if (statusObject == null)
		{
			return Optional.empty();
		}
		if (statusObject.getPaymentOutput() != null)
		{
			return Optional.of(statusObject.getPaymentOutput());
		}
		if (statusObject.getRefundOutput() != null)
		{
			return Optional.of(statusObject.getRefundOutput());
		}
		return Optional.ofNullable(statusObject.getPayoutOutput());
	}

	/**
	 * The id of the PCP operation the event is about — {@code payment.id},
	 * {@code refund.id}, {@code payout.id}, or the payment id of an execution
	 * snapshot. Used as {@code requestId} on a {@code PaymentTransactionEntry}
	 * ({@code DefaultPayoneTransactionService.createPaymentTransactionEntry} keys
	 * idempotency on requestId + status). Refund and payout ids are their own
	 * operations (a refund of payment {@code 3066019730} is {@code 3066019730_1})
	 * and are not collapsed onto the payment id, so a refund can't dedupe against
	 * the capture it reverses.
	 */
	public static Optional<String> resolveOperationId(final PayoneWebhookEventDto envelope, final PayoneWebhookDomain domain)
	{
		final Optional<String> fromStatusObject = resolveStatusObjectByDomain(envelope, domain)
				.map(PayoneWebhookStatusObjectDto::getId)
				.filter(StringUtils::isNotBlank);
		if (fromStatusObject.isPresent())
		{
			return fromStatusObject;
		}

		// paymentId first, so a payment_execution.* event and the payment.* event
		// for the same transition produce the same requestId.
		return getPaymentExecution(envelope, domain)
				.map(execution -> StringUtils.defaultIfBlank(execution.getPaymentId(), execution.getId()))
				.filter(StringUtils::isNotBlank);
	}

	/** The PCP {@code paymentId}, from whichever snapshot the domain uses. */
	public static Optional<String> paymentIdOf(final PayoneWebhookEventDto envelope, final PayoneWebhookDomain domain)
	{
		if (domain == PayoneWebhookDomain.PAYMENT)
		{
			return resolveStatusObjectByDomain(envelope, domain)
					.map(PayoneWebhookStatusObjectDto::getId)
					.filter(StringUtils::isNotBlank);
		}
		return getPaymentExecution(envelope, domain)
				.map(PayoneWebhookExecutionDto::getPaymentId)
				.filter(StringUtils::isNotBlank);
	}

	/**
	 * The PCP {@code paymentExecutionId}. Only an execution snapshot carries one;
	 * a {@code payment.*} envelope does not, which is why it must never be
	 * derived from {@link #resolveOperationId}.
	 */
	public static Optional<String> getPaymentExecutionId(final PayoneWebhookEventDto envelope,
                                                         final PayoneWebhookDomain domain)
	{
		return getPaymentExecution(envelope, domain)
				.map(PayoneWebhookExecutionDto::getId)
				.filter(StringUtils::isNotBlank);
	}

	/**
	 * The amount this transition moved, in MINOR units, as PCP reported it.
	 *
	 * <p>The authoritative figure for a partial capture or partial refund: the
	 * local {@code PayoneCheckout.amount} is the total that was authorised, so
	 * using it would book every partial movement at full value.
	 *
	 * @param eventType drives both which snapshot is read and, for an execution
	 *                  snapshot, which of its events is the relevant one
	 */
	public static Optional<PayoneWebhookAmountOfMoneyDto> getAmount(final PayoneWebhookEventDto envelope,
                                                                    final PayoneWebhookEventType eventType)
	{
		if (eventType == null)
		{
			return Optional.empty();
		}
		final PayoneWebhookDomain domain = eventType.getDomain();

		final Optional<PayoneWebhookAmountOfMoneyDto> fromOutput = resolveStatusObjectByDomain(envelope, domain)
				.flatMap(PayoneWebhookPayloadUtil::getPaymentOutput)
				.map(PayoneWebhookOutputDto::getAmountOfMoney);
		if (fromOutput.isPresent())
		{
			return fromOutput;
		}

		return getPaymentExecution(envelope, domain)
				.flatMap(execution -> resolveAmountOfExecution(execution, eventType.getStatus().orElse(null)));
	}

	/**
	 * Picks the amount out of an execution snapshot's event history.
	 *
	 * <p>PCP delivers the WHOLE history on every delivery, so the last entry is
	 * not necessarily the transition this event announces. The event whose
	 * {@code paymentStatus} matches the type's mapped status is preferred; the
	 * most recent priced event is the fallback for a history that does not say.
	 */
	private static Optional<PayoneWebhookAmountOfMoneyDto> resolveAmountOfExecution(final PayoneWebhookExecutionDto execution,
                                                                                    final PayonePaymentStatus status)
	{
		final List<PayoneWebhookExecutionEventDto> events = execution.getEvents() == null
				? Collections.emptyList()
				: execution.getEvents();

		PayoneWebhookAmountOfMoneyDto lastPriced = null;
		PayoneWebhookAmountOfMoneyDto lastMatching = null;

		for (final PayoneWebhookExecutionEventDto event : events)
		{
			if (event == null || event.getAmountOfMoney() == null)
			{
				continue;
			}
			lastPriced = event.getAmountOfMoney();
			if (status != null && StringUtils.equalsIgnoreCase(event.getPaymentStatus(), status.getCode()))
			{
				lastMatching = event.getAmountOfMoney();
			}
		}

		return Optional.ofNullable(lastMatching == null ? lastPriced : lastMatching);
	}

	/**
	 * The merchant reference PCP echoes back — the SAP order code or guid this
	 * plugin sent (see {@code PcpReferencesConverter}). Traceability only; local
	 * entities are always resolved by PCP id.
	 */
	public static Optional<String> getMerchantReference(final PayoneWebhookEventDto envelope,
                                                        final PayoneWebhookDomain domain)
	{
		final Optional<String> fromOutput = resolveStatusObjectByDomain(envelope, domain)
				.flatMap(PayoneWebhookPayloadUtil::getPaymentOutput)
				.map(PayoneWebhookOutputDto::getReferences)
				.map(PayoneWebhookReferencesDto::getMerchantReference)
				.filter(StringUtils::isNotBlank);
		if (fromOutput.isPresent())
		{
			return fromOutput;
		}

		return getPaymentExecution(envelope, domain)
				.map(PayoneWebhookExecutionDto::getMerchantReference)
				.filter(StringUtils::isNotBlank);
	}
}
