package com.payone.pcp.facades.impl;

import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;
import com.payone.pcp.core.service.PayoneConfigurationService;
import com.payone.pcp.core.service.PayoneTransactionIdentityConflictException;
import com.payone.pcp.core.service.PayoneTransactionService;
import com.payone.pcp.core.service.impl.PayoneApiErrorMapper;
import com.payone.pcp.core.service.impl.PayonePaymentStatusMapper;
import com.payone.pcp.core.strategy.PayonePaymentStrategy;
import com.payone.pcp.core.strategy.PaymentStrategyRegistry;
import com.payone.pcp.core.converters.PcpAmountOfMoneyConverter;
import com.payone.pcp.facades.PayoneConfigurationNotFoundException;
import com.payone.pcp.facades.PayonePaymentOperationsFacade;
import com.payone.pcp.facades.data.PaymentExecutionResultData;

import com.payone.commerce.platform.lib.models.AmountOfMoney;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/** Post-authorization capture/cancel/refund/complete/pause/refresh on a placed order's PCP payment. */
public class DefaultPayonePaymentOperationsFacade implements PayonePaymentOperationsFacade
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayonePaymentOperationsFacade.class);

	private PayoneConfigurationService payoneConfigurationService;
	private PayoneTransactionService payoneTransactionService;
	private PaymentStrategyRegistry paymentStrategyRegistry;
	private PcpAmountOfMoneyConverter pcpAmountOfMoneyConverter;

	@Override
	public PaymentExecutionResultData capturePayment(final OrderModel order)
	{
		return executePostAuthorizationOperation(order, "capture", null,
				(strategy, config, commerceCaseId, checkoutId, paymentExecutionId) ->
						strategy.capture(config, commerceCaseId, checkoutId, paymentExecutionId, null));
	}

	@Override
	public PaymentExecutionResultData cancelPayment(final OrderModel order)
	{
		return executePostAuthorizationOperation(order, "cancel",
				(strategy, config, commerceCaseId, checkoutId, paymentExecutionId) ->
						strategy.cancel(config, commerceCaseId, checkoutId, paymentExecutionId));
	}

	@Override
	public PaymentExecutionResultData refundPayment(final OrderModel order)
	{
		Objects.requireNonNull(order, "order must not be null");
		// PCP rejects a refund without amountOfMoney; default to the full order total.
		return refundPayment(order, pcpAmountOfMoneyConverter.convert(order));
	}

	@Override
	public PaymentExecutionResultData capturePayment(final OrderModel order, final long amount)
	{
		return executePostAuthorizationOperation(order, "capture", Long.valueOf(amount),
				(strategy, config, commerceCaseId, checkoutId, paymentExecutionId) ->
						strategy.capture(config, commerceCaseId, checkoutId, paymentExecutionId, Long.valueOf(amount)));
	}

	@Override
	public PaymentExecutionResultData refundPayment(final OrderModel order, final AmountOfMoney amountOfMoney)
	{
		Objects.requireNonNull(amountOfMoney, "amountOfMoney must not be null");
		return executePostAuthorizationOperation(order, "refund", amountOfMoney.getAmount(),
				(strategy, config, commerceCaseId, checkoutId, paymentExecutionId) ->
						strategy.refund(config, commerceCaseId, checkoutId, paymentExecutionId, amountOfMoney));
	}

	@Override
	public PaymentExecutionResultData completePayment(final OrderModel order)
	{
		return executePostAuthorizationOperation(order, "complete",
				(strategy, config, commerceCaseId, checkoutId, paymentExecutionId) ->
						strategy.complete(config, commerceCaseId, checkoutId, paymentExecutionId));
	}

	@Override
	public PaymentExecutionResultData pausePayment(final OrderModel order)
	{
		return executePostAuthorizationOperation(order, "pause",
				(strategy, config, commerceCaseId, checkoutId, paymentExecutionId) ->
						strategy.pause(config, commerceCaseId, checkoutId, paymentExecutionId));
	}

	@Override
	public PaymentExecutionResultData refreshPayment(final OrderModel order)
	{
		return executePostAuthorizationOperation(order, "refresh",
				(strategy, config, commerceCaseId, checkoutId, paymentExecutionId) ->
						strategy.refresh(config, commerceCaseId, checkoutId, paymentExecutionId));
	}

	/**
	 * Shared resolution and error handling for all six operations: finds the order's PCP
	 * PaymentTransaction (see {@link #findPaymentTransaction}), resolves the payment strategy
	 * from the productId on the order's PayonePaymentInfo, and invokes the SDK call.
	 *
	 * @param operationName used only for log/exception messages (capture/cancel/refund/complete/pause/refresh)
	 */
	private PaymentExecutionResultData executePostAuthorizationOperation(final OrderModel order,
			final String operationName, final PostAuthorizationOperation operation)
	{
		return executePostAuthorizationOperation(order, operationName, null, operation);
	}

	/**
	 * @param entryAmount amount (minor units) recorded on the PaymentTransactionEntry; null
	 *                    records the full order total
	 */
	private PaymentExecutionResultData executePostAuthorizationOperation(final OrderModel order,
			final String operationName, final Long entryAmount, final PostAuthorizationOperation operation)
	{
		Objects.requireNonNull(order, "order must not be null");
		final PayoneConfigurationModel config = requireActiveConfiguration(order);

		final PaymentTransactionModel transaction = findPaymentTransaction(order);
		final String commerceCaseId = transaction.getPayoneCommerceCaseId();
		final String checkoutId = transaction.getPayoneCheckoutId();
		final String paymentExecutionId = transaction.getPayonePaymentExecutionId();
		if (StringUtils.isBlank(commerceCaseId) || StringUtils.isBlank(checkoutId) || StringUtils.isBlank(paymentExecutionId))
		{
			throw new IllegalStateException(
					"PCP payment was never authorized for order [" + order.getCode() + "] - cannot " + operationName);
		}

		final Integer paymentProductId = order.getPaymentInfo() instanceof PayonePaymentInfoModel payonePaymentInfo
				? payonePaymentInfo.getPaymentProductId()
				: null;
		if (paymentProductId == null)
		{
			throw new IllegalStateException(
					"Order [" + order.getCode() + "] has no PCP paymentProductId on its PaymentInfo - cannot " + operationName);
		}

		final PayonePaymentStrategy strategy = resolveStrategy(order, paymentProductId);

		LOG.info("PAYONE {} (strategy path) for order [{}], product [{}], commerceCase [{}], checkout [{}]",
				operationName, order.getCode(), paymentProductId, commerceCaseId, checkoutId);

		final PaymentExecutionResponse response;
		try
		{
			response = operation.execute(strategy, config, commerceCaseId, checkoutId, paymentExecutionId);
		}
		catch (final RuntimeException e)
		{
			LOG.error("PAYONE {} failed for order [{}], product [{}]: {}",
					operationName, order.getCode(), paymentProductId, PayoneApiErrorMapper.describe(e), e);
			throw new IllegalStateException(PayoneApiErrorMapper.rootCauseMessage(e), e);
		}
		if (response == null)
		{
			throw new IllegalStateException(
					"PCP " + operationName + " returned no response for order [" + order.getCode() + "], product [" + paymentProductId + "]");
		}

		final String mappedStatus = PayonePaymentStatusMapper.toTransactionStatus(response.getStatus());
		final PaymentTransactionType transactionType = operationTransactionType(operationName);
		if (transactionType != null)
		{
			payoneTransactionService.createPaymentTransactionEntry(
					transaction,
					response.getPaymentId() != null ? response.getPaymentId() : paymentExecutionId,
					order,
					response.getStatus(),
					entryAmount != null ? entryAmount : pcpAmountOfMoneyConverter.convert(order).getAmount(),
					order.getCurrency(),
					transactionType);
		}

		final PaymentExecutionResultData result = new PaymentExecutionResultData();
		result.setPaymentId(response.getPaymentId());
		result.setPaymentExecutionId(response.getPaymentExecutionId());
		result.setStatus(mappedStatus);
		return result;
	}

	/**
	 * SAP transaction type to record; null for pause/refresh, which move no money (pause holds
	 * an in-flight operation, refresh re-authorizes without capturing). complete finalizes a
	 * delayed capture, so it maps to CAPTURE like the direct capture operation.
	 */
	private static PaymentTransactionType operationTransactionType(final String operationName)
	{
		return switch (operationName)
		{
			case "capture", "complete" -> PaymentTransactionType.CAPTURE;
			case "cancel" -> PaymentTransactionType.CANCEL;
			case "refund" -> PaymentTransactionType.REFUND_FOLLOW_ON;
			case "pause", "refresh" -> null;
			default -> throw new IllegalArgumentException("Unknown operation [" + operationName + "]");
		};
	}

	/**
	 * Finds the order's single, authoritative PCP PaymentTransaction. Not
	 * {@code getOrCreatePaymentTransaction(order, null, null)}: that derives its lookup code from
	 * {@code order.getCode()}, but the transaction cloned onto the order by
	 * {@code DefaultPayoneCheckoutFacade.placeOrder()} keeps the cart's code (partof clone
	 * preserves it), which never matches {@code order.getCode() + "_PAYONE"} - getOrCreate would
	 * silently create a second, empty transaction instead of finding the real one.
	 * <p>
	 * Fails closed before any PCP call: every PCP row (one with any PCP id populated) must carry
	 * all three ids, and all complete rows must agree on the same
	 * (commerceCaseId, checkoutId, paymentExecutionId) tuple - a mismatch is an identity conflict,
	 * never guessed at. Duplicate rows with the identical tuple are allowed; the newest by
	 * {@code modifiedtime} wins, with PK as a deterministic tie-breaker.
	 */
	private PaymentTransactionModel findPaymentTransaction(final OrderModel order)
	{
		final List<PaymentTransactionModel> pcpRows = order.getPaymentTransactions().stream()
				.filter(DefaultPayonePaymentOperationsFacade::isPcpRow)
				.collect(Collectors.toList());

		if (pcpRows.isEmpty())
		{
			throw new IllegalStateException(
					"Order [" + order.getCode() + "] has no PCP PaymentTransaction - was it ever authorized via PAYONE?");
		}

		final PaymentTransactionModel incomplete = pcpRows.stream()
				.filter(tx -> !isCompletePcpRow(tx))
				.findFirst()
				.orElse(null);
		if (incomplete != null)
		{
			throw new IllegalStateException(
					"Order [" + order.getCode() + "] has an incomplete PCP PaymentTransaction [" + incomplete.getCode()
							+ "] (commerceCaseId=[" + incomplete.getPayoneCommerceCaseId() + "], checkoutId=["
							+ incomplete.getPayoneCheckoutId() + "], paymentExecutionId=["
							+ incomplete.getPayonePaymentExecutionId()
							+ "]) - refusing to execute any PCP operation until every PCP PaymentTransaction on this "
							+ "order carries a complete commerceCaseId/checkoutId/paymentExecutionId identity");
		}

		final Map<PcpIdentity, List<PaymentTransactionModel>> byIdentity = pcpRows.stream()
				.collect(Collectors.groupingBy(PcpIdentity::of));
		if (byIdentity.size() > 1)
		{
			throw new PayoneTransactionIdentityConflictException(
					"Order [" + order.getCode() + "] carries " + byIdentity.size()
							+ " distinct, fully-populated PCP identities across its PaymentTransactions - refusing to "
							+ "guess which one is authoritative");
		}

		return byIdentity.values().iterator().next().stream()
				.max(Comparator.comparing(PaymentTransactionModel::getModifiedtime,
						Comparator.nullsFirst(Comparator.naturalOrder()))
						.thenComparing(PaymentTransactionModel::getPk, Comparator.nullsFirst(Comparator.naturalOrder())))
				.orElseThrow();
	}

	/** A row is "PCP" once any PCP identifier is populated (see {@link #findPaymentTransaction}). */
	private static boolean isPcpRow(final PaymentTransactionModel tx)
	{
		return StringUtils.isNotBlank(tx.getPayoneCommerceCaseId())
				|| StringUtils.isNotBlank(tx.getPayoneCheckoutId())
				|| StringUtils.isNotBlank(tx.getPayonePaymentExecutionId())
				|| StringUtils.isNotBlank(tx.getPayonePaymentId());
	}

	/** A PCP row is "complete" only when all three ids are populated. */
	private static boolean isCompletePcpRow(final PaymentTransactionModel tx)
	{
		return StringUtils.isNotBlank(tx.getPayoneCommerceCaseId())
				&& StringUtils.isNotBlank(tx.getPayoneCheckoutId())
				&& StringUtils.isNotBlank(tx.getPayonePaymentExecutionId());
	}

	/** Identity tuple every complete PCP row on an order must agree on (see {@link #findPaymentTransaction}). */
	private record PcpIdentity(String commerceCaseId, String checkoutId, String paymentExecutionId)
	{
		static PcpIdentity of(final PaymentTransactionModel tx)
		{
			return new PcpIdentity(tx.getPayoneCommerceCaseId(), tx.getPayoneCheckoutId(), tx.getPayonePaymentExecutionId());
		}
	}

	/** Dispatches a single-strategy-method call (capture/cancel/refund/complete/pause/refresh all share this shape). */
	@FunctionalInterface
	private interface PostAuthorizationOperation
	{
		PaymentExecutionResponse execute(PayonePaymentStrategy strategy, PayoneConfigurationModel config,
				String commerceCaseId, String checkoutId, String paymentExecutionId);
	}

	/**
	 * Resolves the configuration for the order's own store, not the session's "current" store -
	 * callers include Backoffice actions and scheduled jobs, which have no web session.
	 */
	private PayoneConfigurationModel requireActiveConfiguration(final AbstractOrderModel order)
	{
		final PayoneConfigurationModel config = payoneConfigurationService.getActiveConfigurationForStore(order.getStore());
		if (config == null)
		{
			throw new PayoneConfigurationNotFoundException("PAYONE is not configured for the current store");
		}
		return config;
	}

	/**
	 * Resolves the strategy for productId, filtered by the store's allowed
	 * payment modes (PayoneConfiguration.paymentModes).
	 */
	private PayonePaymentStrategy resolveStrategy(final AbstractOrderModel order, final int paymentProductId)
	{
		final Collection<Integer> allowedProductIds = payoneConfigurationService.getAllowedPaymentProductIds(order.getStore())
				.stream()
				.map(mode -> Integer.valueOf(mode.getCode()))
				.collect(Collectors.toList());
		final PayonePaymentStrategy strategy = paymentStrategyRegistry.getStrategy(paymentProductId, allowedProductIds);
		if (strategy == null)
		{
			throw new IllegalStateException("No PAYONE payment strategy for product [" + paymentProductId
					+ "] in store [" + (order.getStore() != null ? order.getStore().getUid() : null) + "]");
		}
		return strategy;
	}

	public void setPayoneConfigurationService(final PayoneConfigurationService payoneConfigurationService)
	{
		this.payoneConfigurationService = payoneConfigurationService;
	}

	public void setPayoneTransactionService(final PayoneTransactionService payoneTransactionService)
	{
		this.payoneTransactionService = payoneTransactionService;
	}

	public void setPaymentStrategyRegistry(final PaymentStrategyRegistry paymentStrategyRegistry)
	{
		this.paymentStrategyRegistry = paymentStrategyRegistry;
	}

	public void setPcpAmountOfMoneyConverter(final PcpAmountOfMoneyConverter pcpAmountOfMoneyConverter)
	{
		this.pcpAmountOfMoneyConverter = pcpAmountOfMoneyConverter;
	}
}
