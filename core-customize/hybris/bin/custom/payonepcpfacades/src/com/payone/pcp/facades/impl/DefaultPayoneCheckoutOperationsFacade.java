/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.facades.impl;

import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.user.UserGroupModel;
import de.hybris.platform.core.model.user.UserModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.tx.Transaction;
import de.hybris.platform.tx.TransactionBody;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.payone.commerce.platform.lib.errors.ApiErrorResponseException;
import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.commerce.platform.lib.models.CheckoutResponse;
import com.payone.commerce.platform.lib.models.StatusCheckout;
import com.payone.commerce.platform.lib.models.StatusOutput;
import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayoneCheckoutService;
import com.payone.pcp.core.service.PayoneConfigurationService;
import com.payone.pcp.facades.PayoneCheckoutOperation;
import com.payone.pcp.facades.PayoneCheckoutOperationException;
import com.payone.pcp.facades.PayoneCheckoutOperationException.Kind;
import com.payone.pcp.facades.PayoneCheckoutOperationsFacade;
import com.payone.pcp.facades.PayonePaymentOperationsFacade;
import com.payone.pcp.facades.data.PaymentExecutionResultData;


/**
 * Backoffice Capture/Refund for one PayoneCheckout (technical
 * feasibility; clean-core integration, fine-grained permissions and further
 * operations are out of scope).
 * <p>
 * Every {@link #execute} call, in this order:
 * <ol>
 *   <li>checks the feature flag {@value #ENABLED_PROPERTY} (default off) and
 *       that the current user belongs to {@value #USER_GROUP_PROPERTY};</li>
 *   <li>takes a database row lock on the Checkout inside a transaction, so a
 *       second click on any cluster node waits and then sees the first
 *       result instead of running in parallel;</li>
 *   <li>checks that every PCP PaymentTransaction on the linked Order carries
 *       exactly the selected Checkout's CommerceCase/Checkout/PaymentExecution
 *       ids (the Order-keyed facade could otherwise act on another Checkout);</li>
 *   <li>reads the Checkout live from PCP and only proceeds if PCP's own
 *       status allows the operation (Capture: COMPLETED/BILLED/CHARGEBACKED,
 *       Refund: BILLED/CHARGEBACKED - https://docs.commerce.payone.com/api-reference)
 *       and there is an amount left: {@code openAmount} for Capture,
 *       {@code collectedAmount - refundedAmount} for Refund. An operation
 *       that already ran therefore leaves nothing to capture/refund and is
 *       refused - that is the double-execution guard;</li>
 *   <li>executes exactly that amount through {@link PayonePaymentOperationsFacade}.</li>
 * </ol>
 * No local status is set optimistically; the webhook path owns status.
 */
public class DefaultPayoneCheckoutOperationsFacade implements PayoneCheckoutOperationsFacade
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneCheckoutOperationsFacade.class);

	static final String ENABLED_PROPERTY = "payonepcp.backoffice.paymentactions.enabled";
	static final String USER_GROUP_PROPERTY = "payonepcp.backoffice.paymentactions.usergroup";
	static final String DEFAULT_USER_GROUP = "admingroup";

	private static final Set<StatusCheckout> CAPTURABLE = EnumSet.of(StatusCheckout.COMPLETED, StatusCheckout.BILLED,
			StatusCheckout.CHARGEBACKED);
	private static final Set<StatusCheckout> REFUNDABLE = EnumSet.of(StatusCheckout.BILLED, StatusCheckout.CHARGEBACKED);

	private PayonePaymentOperationsFacade payonePaymentOperationsFacade;
	private PayoneCheckoutService payoneCheckoutService;
	private PayoneConfigurationService payoneConfigurationService;
	private ModelService modelService;
	private UserService userService;
	private ConfigurationService configurationService;

	@Override
	public boolean isAvailable(final PayoneCheckoutModel checkout, final PayoneCheckoutOperation operation)
	{
		try
		{
			validateLocally(checkout);
			return true;
		}
		catch (final PayoneCheckoutOperationException e)
		{
			return false;
		}
	}

	@Override
	public PaymentExecutionResultData execute(final PayoneCheckoutModel checkout, final PayoneCheckoutOperation operation)
	{
		Objects.requireNonNull(operation, "operation must not be null");
		validateAccess();
		if (checkout == null)
		{
			throw rejected("No checkout selected");
		}
		try
		{
			return (PaymentExecutionResultData) Transaction.current().execute(new TransactionBody()
			{
				@Override
				public Object execute()
				{
					lockCheckout(checkout);
					return executeLocked(checkout, operation);
				}
			});
		}
		catch (final PayoneCheckoutOperationException e)
		{
			throw e;
		}
		catch (final Exception e)
		{
			// callFacade/readLiveCheckout wrap every path that reaches PCP; anything else
			// failed before a request was sent (transaction setup, lock commit).
			throw new PayoneCheckoutOperationException(Kind.REJECTED, e.getMessage(), e);
		}
	}

	/** Row lock (SELECT ... FOR UPDATE) serialises concurrent clicks across cluster nodes. */
	private void lockCheckout(final PayoneCheckoutModel checkout)
	{
		try
		{
			modelService.lock(checkout.getPk());
			modelService.refresh(checkout);
		}
		catch (final RuntimeException e)
		{
			throw new PayoneCheckoutOperationException(Kind.REJECTED,
					"Could not lock checkout [" + checkout.getCheckoutId() + "]: " + e.getMessage(), e);
		}
	}

	/** Runs inside the transaction, with the Checkout row locked. */
	PaymentExecutionResultData executeLocked(final PayoneCheckoutModel checkout, final PayoneCheckoutOperation operation)
	{
		final OrderModel order = validateLocally(checkout);
		final PayoneConfigurationModel config = payoneConfigurationService.getActiveConfigurationForStore(order.getStore());
		if (config == null)
		{
			throw rejected("PAYONE is not configured for the order's store");
		}

		final CheckoutResponse live = readLiveCheckout(config, checkout);
		final StatusCheckout status = live.getCheckoutStatus();
		final StatusOutput amounts = live.getStatusOutput();
		final String currency = live.getAmountOfMoney() != null ? live.getAmountOfMoney().getCurrencyCode() : null;
		if (amounts == null || StringUtils.isBlank(currency))
		{
			throw rejected("PCP returned no amounts for checkout [" + checkout.getCheckoutId() + "]");
		}

		final PaymentExecutionResultData result;
		final long amount;
		switch (operation)
		{
			case CAPTURE ->
			{
				requireStatus(operation, status, CAPTURABLE);
				amount = positive(amounts.getOpenAmount(), "No open amount left to capture");
				LOG.info("PAYONE Backoffice CAPTURE by [{}] for checkout [{}], order [{}], amount [{} {}]", currentUid(),
						checkout.getCheckoutId(), order.getCode(), amount, currency);
				result = callFacade(() -> payonePaymentOperationsFacade.capturePayment(order, amount));
			}
			case REFUND ->
			{
				requireStatus(operation, status, REFUNDABLE);
				amount = positive(value(amounts.getCollectedAmount()) - value(amounts.getRefundedAmount()),
						"Nothing left to refund");
				LOG.info("PAYONE Backoffice REFUND by [{}] for checkout [{}], order [{}], amount [{} {}]", currentUid(),
						checkout.getCheckoutId(), order.getCode(), amount, currency);
				result = callFacade(() -> payonePaymentOperationsFacade.refundPayment(order,
						new AmountOfMoney().amount(amount).currencyCode(currency)));
			}
			default -> throw rejected("Unsupported operation " + operation);
		}
		return result;
	}

	private CheckoutResponse readLiveCheckout(final PayoneConfigurationModel config, final PayoneCheckoutModel checkout)
	{
		try
		{
			final CheckoutResponse live = payoneCheckoutService.getCheckout(config,
					checkout.getCommerceCase().getCommerceCaseId(), checkout.getCheckoutId());
			if (live == null)
			{
				throw rejected("PCP returned no data for checkout [" + checkout.getCheckoutId() + "]");
			}
			return live;
		}
		catch (final RuntimeException e)
		{
			if (e instanceof PayoneCheckoutOperationException operationException)
			{
				throw operationException;
			}
			// Read-only call: nothing was executed, retrying is safe.
			throw new PayoneCheckoutOperationException(Kind.REJECTED,
					"Could not read checkout [" + checkout.getCheckoutId() + "] from PCP: " + e.getMessage(), e);
		}
	}

	private static PaymentExecutionResultData callFacade(final java.util.function.Supplier<PaymentExecutionResultData> call)
	{
		try
		{
			return call.get();
		}
		catch (final RuntimeException e)
		{
			// PCP answered with an HTTP error response: rejected, nothing happened.
			// Anything else (IO, timeout, unreadable response) leaves the PCP outcome unknown.
			throw new PayoneCheckoutOperationException(hasApiErrorResponse(e) ? Kind.REJECTED : Kind.INDETERMINATE,
					e.getMessage(), e);
		}
	}

	private static void requireStatus(final PayoneCheckoutOperation operation, final StatusCheckout status,
			final Set<StatusCheckout> allowed)
	{
		if (status == null || !allowed.contains(status))
		{
			throw rejected(operation + " is not allowed for PCP checkout status [" + status + "], allowed: " + allowed);
		}
	}

	private static long positive(final Long amount, final String message)
	{
		if (amount == null || amount <= 0)
		{
			throw rejected(message);
		}
		return amount;
	}

	private static long value(final Long amount)
	{
		return amount != null ? amount : 0L;
	}

	private void validateAccess()
	{
		if (!configurationService.getConfiguration().getBoolean(ENABLED_PROPERTY, false))
		{
			throw rejected("PAYONE Backoffice payment actions are disabled (" + ENABLED_PROPERTY + ")");
		}
		if (!currentUserIsAuthorized())
		{
			throw rejected("Current user is not allowed to trigger PAYONE payment operations");
		}
	}

	private OrderModel validateLocally(final PayoneCheckoutModel checkout)
	{
		validateAccess();
		if (checkout == null)
		{
			throw rejected("No checkout selected");
		}
		final OrderModel order = checkout.getOrder();
		final PayoneCommerceCaseModel commerceCase = checkout.getCommerceCase();
		if (order == null || commerceCase == null)
		{
			throw rejected("Checkout [" + checkout.getCheckoutId() + "] is not linked to an order and commerce case");
		}
		final CheckoutIdentity selected = new CheckoutIdentity(commerceCase.getCommerceCaseId(), checkout.getCheckoutId(),
				checkout.getPaymentExecutionId());
		if (!selected.isComplete())
		{
			throw rejected("Checkout [" + checkout.getCheckoutId() + "] has incomplete PCP identifiers");
		}
		final List<CheckoutIdentity> orderIdentities = order.getPaymentTransactions().stream()
				.filter(DefaultPayoneCheckoutOperationsFacade::isPcpRow)
				.map(CheckoutIdentity::of)
				.collect(Collectors.toList());
		if (orderIdentities.isEmpty())
		{
			throw rejected("Order [" + order.getCode() + "] has no PCP payment transaction");
		}
		if (orderIdentities.stream().anyMatch(id -> !id.equals(selected)))
		{
			throw rejected("Order [" + order.getCode() + "] PCP payment transactions do not all match checkout ["
					+ checkout.getCheckoutId() + "]");
		}
		return order;
	}

	private boolean currentUserIsAuthorized()
	{
		final UserModel user = userService.getCurrentUser();
		final String groupUid = configurationService.getConfiguration().getString(USER_GROUP_PROPERTY, DEFAULT_USER_GROUP);
		if (user == null || StringUtils.isBlank(groupUid))
		{
			return false;
		}
		try
		{
			final UserGroupModel group = userService.getUserGroupForUID(groupUid);
			return userService.isMemberOfGroup(user, group);
		}
		catch (final UnknownIdentifierException e)
		{
			return false;
		}
	}

	private String currentUid()
	{
		final UserModel user = userService.getCurrentUser();
		return user != null ? user.getUid() : null;
	}

	private static boolean hasApiErrorResponse(final Throwable throwable)
	{
		for (Throwable t = throwable; t != null; t = t.getCause() == t ? null : t.getCause())
		{
			if (t instanceof ApiErrorResponseException)
			{
				return true;
			}
		}
		return false;
	}

	private static PayoneCheckoutOperationException rejected(final String message)
	{
		return new PayoneCheckoutOperationException(Kind.REJECTED, message);
	}

	/** Same "is a PCP row" convention as DefaultPayonePaymentOperationsFacade. */
	private static boolean isPcpRow(final PaymentTransactionModel tx)
	{
		return StringUtils.isNotBlank(tx.getPayoneCommerceCaseId()) || StringUtils.isNotBlank(tx.getPayoneCheckoutId())
				|| StringUtils.isNotBlank(tx.getPayonePaymentExecutionId()) || StringUtils.isNotBlank(tx.getPayonePaymentId());
	}

	private record CheckoutIdentity(String commerceCaseId, String checkoutId, String paymentExecutionId)
	{
		static CheckoutIdentity of(final PaymentTransactionModel tx)
		{
			return new CheckoutIdentity(tx.getPayoneCommerceCaseId(), tx.getPayoneCheckoutId(), tx.getPayonePaymentExecutionId());
		}

		boolean isComplete()
		{
			return StringUtils.isNotBlank(commerceCaseId) && StringUtils.isNotBlank(checkoutId)
					&& StringUtils.isNotBlank(paymentExecutionId);
		}
	}

	public void setPayonePaymentOperationsFacade(final PayonePaymentOperationsFacade payonePaymentOperationsFacade)
	{
		this.payonePaymentOperationsFacade = payonePaymentOperationsFacade;
	}

	public void setPayoneCheckoutService(final PayoneCheckoutService payoneCheckoutService)
	{
		this.payoneCheckoutService = payoneCheckoutService;
	}

	public void setPayoneConfigurationService(final PayoneConfigurationService payoneConfigurationService)
	{
		this.payoneConfigurationService = payoneConfigurationService;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setUserService(final UserService userService)
	{
		this.userService = userService;
	}

	public void setConfigurationService(final ConfigurationService configurationService)
	{
		this.configurationService = configurationService;
	}
}
