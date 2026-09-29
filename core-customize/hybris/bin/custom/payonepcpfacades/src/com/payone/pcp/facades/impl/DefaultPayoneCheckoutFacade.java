package com.payone.pcp.facades.impl;

import com.payone.commerce.platform.lib.errors.ApiErrorResponseException;
import com.payone.commerce.platform.lib.errors.ApiResponseRetrievalException;
import com.payone.commerce.platform.lib.models.*;
import com.payone.pcp.core.converters.CreateCheckoutRequestConverter;
import com.payone.pcp.core.converters.CreateCommerceCaseRequestConverter;
import com.payone.pcp.core.converters.PaymentExecutionRequestConverter;
import com.payone.pcp.core.converters.PcpAmountOfMoneyConverter;
import com.payone.pcp.core.converters.PcpFieldTrimmer;
import com.payone.pcp.core.converters.PcpMerchantReferenceGenerator;
import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayoneMandateModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;
import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.service.PayoneAuthenticationService;
import com.payone.pcp.core.service.PayoneCheckoutService;
import com.payone.pcp.core.service.PayoneCommerceCaseService;
import com.payone.pcp.core.service.PayoneConfigurationService;
import com.payone.pcp.core.service.PayonePcpClientFactory;
import com.payone.pcp.core.service.PayoneTransactionService;
import com.payone.pcp.core.service.impl.PayoneApiErrorMapper;
import com.payone.pcp.core.service.impl.PayonePaymentStatusMapper;
import com.payone.pcp.core.strategy.PayonePaymentStrategy;
import com.payone.pcp.core.strategy.PaymentStrategyRegistry;
import com.payone.pcp.facades.PayoneCheckoutFacade;
import com.payone.pcp.facades.PayoneCheckoutMismatchException;
import com.payone.pcp.facades.PayoneConfigurationNotFoundException;
import com.payone.pcp.facades.converter.PayoneAuthorizationResultTransformer;
import com.payone.pcp.facades.data.AuthenticationTokenResultData;
import com.payone.pcp.facades.data.CommerceCaseResultData;
import com.payone.pcp.facades.data.PaymentDetailsData;
import com.payone.pcp.facades.data.PayoneAuthorizationResultData;
import com.payone.pcp.facades.data.PayoneCheckoutData;
import com.payone.pcp.facades.data.PaymentRequestData;
import com.payone.pcp.facades.data.PaymentResultData;

import de.hybris.platform.commerceservices.order.CommerceCheckoutService;
import de.hybris.platform.commerceservices.service.data.CommerceCheckoutParameter;
import de.hybris.platform.commerceservices.service.data.CommerceOrderResult;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.user.AddressModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.order.CartService;
import de.hybris.platform.order.InvalidCartException;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;

import java.util.Collection;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * Two checkout paths: {@link #executePaymentAndPlaceOrder} is the deprecated direct-SDK path
 * (CARD/SEPA only, no paymentModes enforcement); {@link #authorizePayment}/
 * {@link #handleRedirectCallback}/{@link #placeOrder} is the additive strategy-routed path,
 * dispatching by paymentProductId through {@link PaymentStrategyRegistry} and enforcing
 * {@code PayoneConfiguration.paymentModes}. Business-configuration failures throw
 * {@link IllegalStateException}; malformed request input throws {@link IllegalArgumentException}.
 */
public class DefaultPayoneCheckoutFacade implements PayoneCheckoutFacade
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultPayoneCheckoutFacade.class);

	private ModelService modelService;
	private CartService cartService;
	private CommerceCheckoutService commerceCheckoutService;
	private PayoneConfigurationService payoneConfigurationService;
	private PayoneCommerceCaseService payoneCommerceCaseService;
	private PayoneAuthenticationService payoneAuthenticationService;
	private PayoneTransactionService payoneTransactionService;
	private PaymentExecutionRequestConverter paymentExecutionRequestConverter;
	private PcpAmountOfMoneyConverter pcpAmountOfMoneyConverter;
	private PayonePcpClientFactory payonePcpClientFactory;
	private CreateCommerceCaseRequestConverter createCommerceCaseRequestConverter;
	private CreateCheckoutRequestConverter createCheckoutRequestConverter;

	// Strategy-routed path (additive — see class Javadoc).
	private PaymentStrategyRegistry paymentStrategyRegistry;
	private PayoneCheckoutService payoneCheckoutService;
	private PayoneAuthorizationResultTransformer payoneAuthorizationResultTransformer;

	// -----------------------------------------------------------------------
	// createOrGetCommerceCase
	// -----------------------------------------------------------------------

	@Override
	public CommerceCaseResultData createOrGetCommerceCase(final CartModel cart)
	{
		Objects.requireNonNull(cart, "cart must not be null");

		if (StringUtils.isNotBlank(cart.getPayoneCommerceCaseId())
				&& StringUtils.isNotBlank(cart.getPayoneCheckoutId()))
		{
			return commerceCaseResultFromCart(cart);
		}

		final PayoneConfigurationModel config = requireActiveConfiguration(cart);
		final AddressModel deliveryAddress = cart.getDeliveryAddress();
		if (deliveryAddress == null)
		{
			throw new IllegalStateException(
					"Cannot initialize PCP checkout: cart [" + cart.getCode() + "] has no delivery address");
		}

		// A cart code is reused verbatim across a failed-payment retry (the
		// error-recovery path below clears the cart's PCP ids, not its code),
		// but PCP requires merchantReference to be unique per commerce case
		// (docs.commerce.payone.com/api-reference). The converter's default
		// (order.getCode()) would collide on retry, so a fresh suffix is
		// applied on top of it, same as the mock this replaces.
		// CreateCommerceCaseRequest.merchantReference / CheckoutReferences.merchantReference
		// share the same maxLength 40 (api-reference).
		final String merchantReference = PcpMerchantReferenceGenerator.unique(cart.getCode(), 40);

		try
		{
			final CreateCommerceCaseRequest commerceCaseRequest =
					buildCreateCommerceCaseRequest(cart, deliveryAddress, merchantReference);
			final CreateCommerceCaseResponse commerceCaseResponse = payoneCommerceCaseService
					.createCommerceCase(config, commerceCaseRequest);

			final String commerceCaseId = commerceCaseResponse.getCommerceCaseId().toString();
			final CreateCheckoutResponse checkoutResponse = commerceCaseResponse.getCheckout();
			if (checkoutResponse == null || checkoutResponse.getCheckoutId() == null)
			{
				throw new IllegalStateException("PAYONE created the commerce case without a checkout");
			}
			final String checkoutId = checkoutResponse.getCheckoutId().toString();

			persistNewCommerceCase(cart, commerceCaseId, checkoutId, merchantReference);

			return commerceCaseResultFromCart(cart, checkoutResponse.getAllowedPaymentActions());
		}
		catch (final IllegalStateException e)
		{
			throw e;
		}
		catch (final RuntimeException e)
		{
			LOG.error("PAYONE commerce case/checkout creation failed for cart={}", cart.getCode(), e);
			throw new IllegalStateException(PayoneApiErrorMapper.rootCauseMessage(e), e);
		}
	}

	/**
	 * Builds the CreateCommerceCaseRequest for a fresh commerce case, with
	 * the checkout's own merchantReference overridden to match the commerce
	 * case's (CreateCheckoutRequestConverter defaults it to the raw order
	 * code via PcpReferencesConverter.resolveMerchantReference - the same
	 * value the commerce case would collide on without the suffix). PCP
	 * requires merchantReference to be unique per Checkout too (api-reference:
	 * "Unique reference ...").
	 */
	private CreateCommerceCaseRequest buildCreateCommerceCaseRequest(final CartModel cart,
			final AddressModel deliveryAddress, final String merchantReference)
	{
		final CustomerModel customer = (CustomerModel) cart.getUser();
		final AddressModel billingAddress = cart.getPaymentAddress() != null
				? cart.getPaymentAddress()
				: cart.getDeliveryAddress();
		final String merchantShopReference = cart.getStore() != null ? cart.getStore().getUid() : null;

		final CreateCheckoutRequest checkoutRequest =
				createCheckoutRequestConverter.convert(cart, deliveryAddress, customer, merchantShopReference);
		if (checkoutRequest.getReferences() != null)
		{
			checkoutRequest.getReferences().merchantReference(merchantReference);
		}

		return createCommerceCaseRequestConverter.convert(cart, customer, billingAddress)
				.merchantReference(merchantReference)
				.checkout(checkoutRequest);
	}

	/** Persists the newly created commerce case/checkout locally and stamps their ids onto the cart. */
	private void persistNewCommerceCase(final CartModel cart, final String commerceCaseId, final String checkoutId,
			final String merchantReference)
	{
		final PayoneCheckoutModel payoneCheckoutModel = modelService.create(PayoneCheckoutModel.class);
		payoneCheckoutModel.setCheckoutId(checkoutId);
		final AmountOfMoney checkoutAmount = pcpAmountOfMoneyConverter.convert(cart);
		payoneCheckoutModel.setAmount(checkoutAmount.getAmount());
		payoneCheckoutModel.setCurrencyIsoCode(checkoutAmount.getCurrencyCode());
		modelService.save(payoneCheckoutModel);

		final PayoneCommerceCaseModel commerceCaseModel = modelService.create(PayoneCommerceCaseModel.class);
		commerceCaseModel.setCommerceCaseId(commerceCaseId);
		commerceCaseModel.setMerchantReference(merchantReference);
		commerceCaseModel.setCheckouts(Collections.singletonList(payoneCheckoutModel));

		cart.setPayoneCommerceCaseId(commerceCaseId);
		cart.setPayoneCheckoutId(checkoutId);
		modelService.saveAll(commerceCaseModel, payoneCheckoutModel, cart);
	}

	private CommerceCaseResultData commerceCaseResultFromCart(final CartModel cart)
	{
		return commerceCaseResultFromCart(cart, null);
	}

	private CommerceCaseResultData commerceCaseResultFromCart(final CartModel cart,
			final List<AllowedPaymentActions> allowedPaymentActions)
	{
		final CommerceCaseResultData result = new CommerceCaseResultData();
		result.setCommerceCaseId(cart.getPayoneCommerceCaseId());
		result.setCheckoutId(cart.getPayoneCheckoutId());
		result.setAmount(pcpAmountOfMoneyConverter.convert(cart).getAmount());
		result.setCurrencyIsoCode(cart.getCurrency() != null ? cart.getCurrency().getIsocode() : null);
		result.setAllowedPaymentActions(allowedPaymentActions == null
				? null
				: allowedPaymentActions.stream()
						.map(action -> action.getValue())
						.collect(Collectors.toList()));
		return result;
	}

	// -----------------------------------------------------------------------
	// createAuthenticationToken
	// -----------------------------------------------------------------------

	@Override
	public AuthenticationTokenResultData createAuthenticationToken(final CartModel cart)
	{
		Objects.requireNonNull(cart, "cart must not be null");
		final PayoneConfigurationModel config = requireActiveConfiguration(cart);

		try
		{
			final AuthenticationToken token = payoneAuthenticationService
					.createAuthenticationToken(config, cart.getCode());

			final AuthenticationTokenResultData result = new AuthenticationTokenResultData();
			result.setToken(token.getToken());
			result.setId(token.getId() == null ? null : token.getId().toString());
			result.setExpirationDate(token.getExpirationDate() == null
					? null
					: token.getExpirationDate().toString());

			return result;
		}
		catch (final RuntimeException e)
		{
			LOG.error("PAYONE authentication token creation failed for cart={}", cart.getCode(), e);
			throw new IllegalStateException(PayoneApiErrorMapper.rootCauseMessage(e), e);
		}
	}

	// -----------------------------------------------------------------------
	// executePaymentAndPlaceOrder (deprecated — see PayoneCheckoutFacade Javadoc)
	// -----------------------------------------------------------------------

	@Override
	@Deprecated
	public PaymentResultData executePaymentAndPlaceOrder(final CartModel cart, final PaymentRequestData request)
	{
		Objects.requireNonNull(cart, "cart must not be null");
		final PayoneConfigurationModel config = requireActiveConfiguration(cart);
		final LegacyPaymentMethod paymentMethod = validateExecutePaymentRequest(cart, request);

		final PaymentExecutionRequest execRequest = buildExecutionRequest(cart, request, config, paymentMethod);
		final String paymentReference = finalizeExecutionInput(execRequest);

		final CreatePaymentResponse execResponse =
				executePcpPayment(config, cart, request, execRequest, paymentReference);

		final PayoneCheckoutModel payoneCheckoutModel = recordPaymentIdsOnCheckout(request, execResponse);

		final StatusValue pcpStatus = execResponse.getPayment().getStatus();
		final String mappedStatus = PayonePaymentStatusMapper.toTransactionStatus(pcpStatus);
		if (PayonePaymentStatusMapper.REJECTED.equals(mappedStatus)
				|| PayonePaymentStatusMapper.ERROR.equals(mappedStatus))
		{
			final PaymentResultData result = new PaymentResultData();
			result.setPaymentStatus(mappedStatus);
			return result;
		}

		attachPaymentInfo(cart, request, paymentMethod);
		final OrderModel order = placeOrderAndClearCart(cart);
		linkOrderToCheckout(order, request, payoneCheckoutModel);
		recordPaymentTransaction(order, request, execResponse, pcpStatus);

		return buildPaymentResult(order, mappedStatus, execResponse);
	}

	/**
	 * Validates the incoming request shape and that it targets the cart's
	 * active PCP checkout.
	 *
	 * @return the parsed payment method; never null (throws instead)
	 * @throws IllegalArgumentException    if required fields are missing/blank
	 *                                       or paymentMethod is not CARD/SEPA
	 * @throws PayoneCheckoutMismatchException if the ids don't match the cart
	 */
	private LegacyPaymentMethod validateExecutePaymentRequest(final CartModel cart, final PaymentRequestData request)
	{
		if (request == null
				|| StringUtils.isBlank(request.getCommerceCaseId())
				|| StringUtils.isBlank(request.getCheckoutId())
				|| StringUtils.isBlank(request.getPaymentMethod()))
		{
			throw new IllegalArgumentException("commerceCaseId, checkoutId and paymentMethod are required");
		}

		if (!StringUtils.equals(request.getCommerceCaseId(), cart.getPayoneCommerceCaseId())
				|| !StringUtils.equals(request.getCheckoutId(), cart.getPayoneCheckoutId()))
		{
			throw new PayoneCheckoutMismatchException("The PAYONE commerceCaseId and checkoutId must belong to the active cart");
		}

		final LegacyPaymentMethod paymentMethod = LegacyPaymentMethod.fromString(request.getPaymentMethod());
		if (paymentMethod == null)
		{
			throw new IllegalArgumentException("paymentMethod must be CARD or SEPA");
		}
		return paymentMethod;
	}

	/**
	 * Builds the family-specific PaymentExecutionRequest (CARD token or SEPA
	 * mandate), validating the fields each family requires.
	 */
	private PaymentExecutionRequest buildExecutionRequest(final CartModel cart, final PaymentRequestData request,
			final PayoneConfigurationModel config, final LegacyPaymentMethod paymentMethod)
	{
		if (paymentMethod == LegacyPaymentMethod.CARD)
		{
			if (StringUtils.isBlank(request.getPaymentProcessingToken()))
			{
				throw new IllegalArgumentException("paymentProcessingToken is required for CARD");
			}

			final int paymentProductId = request.getPaymentProductId() != null ? request.getPaymentProductId() : 1;
			final PayonePaymentInfoModel transientPaymentInfo = newTransientPaymentInfo(cart, paymentProductId);
			transientPaymentInfo.setPaymentProcessingToken(request.getPaymentProcessingToken());

			return paymentExecutionRequestConverter.convert(cart, transientPaymentInfo, config);
		}

		// paymentMethod == SEPA (validateExecutePaymentRequest rejects anything else)
		if (StringUtils.isAnyBlank(
				request.getIban(),
				request.getAccountHolder(),
				request.getCreditorId(),
				request.getMandateReference(),
				request.getDateOfSignature()))
		{
			throw new IllegalArgumentException(
					"iban, accountHolder, creditorId, mandateReference and dateOfSignature are required for SEPA");
		}

		if (cart.getCurrency() == null || !"EUR".equalsIgnoreCase(cart.getCurrency().getIsocode()))
		{
			throw new IllegalArgumentException("SEPA Direct Debit requires an EUR cart");
		}

		// PaymentExecutionRequestConverter dispatches product 771 to
		// PcpSepaDirectDebitPaymentMethodSpecificInputBuilder via PaymentMode.
		// paymentFamily = SEPA (projectdata-paymentmodes.impex). SEPA never
		// reads bic - only Secured Direct Debit (3392) does - so null here.
		final PayonePaymentInfoModel transientPaymentInfo = newTransientPaymentInfo(cart, 771);
		transientPaymentInfo.setMandate(newTransientMandate(
				request.getIban(), null, request.getAccountHolder(), request.getCreditorId(),
				request.getMandateReference(), request.getDateOfSignature()));

		return paymentExecutionRequestConverter.convert(cart, transientPaymentInfo, config);
	}

	/**
	 * Strips the redundant shopping cart (already submitted at checkout
	 * creation) and stamps a fresh merchantReference onto the execution
	 * request's PaymentReferences.
	 *
	 * @return the merchantReference that was set, for logging/error context
	 */
	private String finalizeExecutionInput(final PaymentExecutionRequest execRequest)
	{
		final PaymentExecutionSpecificInput executionInput = execRequest.getPaymentExecutionSpecificInput();
		if (executionInput == null)
		{
			throw new IllegalStateException("PaymentExecutionSpecificInput was not created");
		}

		/*
		 * The shopping cart was already submitted when the checkout was created.
		 * Sending it again during PaymentExecution triggers additional Direct
		 * platform order validation and is not required for this flow.
		 */
		executionInput.setShoppingCart(null);

		if (executionInput.getPaymentReferences() == null)
		{
			throw new IllegalStateException("PaymentReferences were not created");
		}

		final String paymentReference = createPaymentReference();
		executionInput.getPaymentReferences().merchantReference(paymentReference);
		return paymentReference;
	}

	/**
	 * Calls PCP's PaymentExecution endpoint, logging the attempt and outcome.
	 * Failures are recovered via {@link #recoverFromFailedExecution} (drops
	 * the cart's dead checkout ids) and re-thrown as IllegalStateException.
	 */
	private CreatePaymentResponse executePcpPayment(final PayoneConfigurationModel config, final CartModel cart,
			final PaymentRequestData request, final PaymentExecutionRequest execRequest, final String paymentReference)
	{
		final CreatePaymentResponse execResponse;
		try
		{
			LOG.info(
					"Executing PAYONE payment: cart={}, merchantId={}, apiHost={}, "
							+ "commerceCaseId={}, checkoutId={}, paymentMethod={}, "
							+ "paymentProductId={}, paymentReference={}",
					cart.getCode(),
					config.getMerchantId(),
					config.getApiEndpointHost(),
					request.getCommerceCaseId(),
					request.getCheckoutId(),
					request.getPaymentMethod(),
					request.getPaymentProductId(),
					paymentReference);

			execResponse = payonePcpClientFactory.createPaymentExecutionApiClient(config)
					.createPayment(config.getMerchantId(), request.getCommerceCaseId(), request.getCheckoutId(), execRequest);
		}
		catch (final RuntimeException e)
		{
			throw recoverFromFailedExecution(cart, request, config, paymentReference, e);
		}
		catch (final ApiErrorResponseException | ApiResponseRetrievalException | IOException e)
		{
			throw recoverFromFailedExecution(cart, request, config, paymentReference, e);
		}

		if (execResponse == null)
		{
			LOG.error(
					"PAYONE returned an empty payment response: cart={}, commerceCaseId={}, "
							+ "checkoutId={}, paymentReference={}",
					cart.getCode(), request.getCommerceCaseId(), request.getCheckoutId(), paymentReference);
			throw new IllegalStateException("PAYONE returned an empty payment response");
		}

		LOG.info(
				"PAYONE payment execution succeeded: cart={}, commerceCaseId={}, checkoutId={}, "
						+ "paymentReference={}, paymentId={}, paymentExecutionId={}, status={}",
				cart.getCode(),
				request.getCommerceCaseId(),
				request.getCheckoutId(),
				paymentReference,
				execResponse.getPayment().getId(),
				execResponse.getPaymentExecutionId(),
				execResponse.getPayment().getStatus());

		return execResponse;
	}

	/**
	 * Records the payment/paymentExecution ids before placeOrder(), not after: PAYONE can
	 * deliver a webhook for this payment while order creation is still in flight, and
	 * PayoneWebhookTargetResolver matches events to a checkout only by these ids. Redirect
	 * methods (card + 3DS) rarely hit the window; synchronous ones (SEPA) land in it every time.
	 * paymentExecutionId is what every later capture/refund/cancel keys on, and this response
	 * is the only place it's available.
	 */
	private PayoneCheckoutModel recordPaymentIdsOnCheckout(final PaymentRequestData request,
			final CreatePaymentResponse execResponse)
	{
		final PayoneCheckoutModel payoneCheckoutModel = findPayoneCheckout(request.getCheckoutId());
		payoneCheckoutModel.setPaymentId(execResponse.getPayment().getId());
		if (execResponse.getPaymentExecutionId() != null)
		{
			payoneCheckoutModel.setPaymentExecutionId(execResponse.getPaymentExecutionId().toString());
		}
		modelService.save(payoneCheckoutModel);
		return payoneCheckoutModel;
	}

	/** Builds the (this time persisted) PayonePaymentInfo and attaches it to the cart via the checkout service. */
	private void attachPaymentInfo(final CartModel cart, final PaymentRequestData request,
			final LegacyPaymentMethod paymentMethod)
	{
		final PayonePaymentInfoModel paymentInfo = modelService.create(PayonePaymentInfoModel.class);
		paymentInfo.setCode(cart.getCode());
		paymentInfo.setUser(cart.getUser());
		paymentInfo.setDuplicate(Boolean.FALSE);
		paymentInfo.setPaymentMethod(request.getPaymentMethod());

		if (paymentMethod == LegacyPaymentMethod.CARD)
		{
			paymentInfo.setPaymentProcessingToken(request.getPaymentProcessingToken());
			if (request.getPaymentProductId() != null)
			{
				paymentInfo.setPaymentProductId(request.getPaymentProductId());
			}
		}

		modelService.save(paymentInfo);

		final CommerceCheckoutParameter setPaymentInfoParameter = new CommerceCheckoutParameter();
		setPaymentInfoParameter.setEnableHooks(true);
		setPaymentInfoParameter.setCart(cart);
		setPaymentInfoParameter.setPaymentInfo(paymentInfo);
		commerceCheckoutService.setPaymentInfo(setPaymentInfoParameter);
	}

	/** Places the order for the cart (post successful PCP payment) and clears the session cart. */
	private OrderModel placeOrderAndClearCart(final CartModel cart)
	{
		final CommerceCheckoutParameter placeOrderParameter = new CommerceCheckoutParameter();
		placeOrderParameter.setEnableHooks(true);
		placeOrderParameter.setCart(cart);

		final CommerceOrderResult orderResult;
		try
		{
			orderResult = commerceCheckoutService.placeOrder(placeOrderParameter);
		}
		catch (final InvalidCartException e)
		{
			LOG.error("Order placement failed after successful PAYONE payment for cart={}", cart.getCode(), e);
			throw new IllegalStateException(e.getMessage(), e);
		}

		final OrderModel order = orderResult.getOrder();
		cartService.removeSessionCart();
		return order;
	}

	/** Links the placed order back to its PCP checkout (the PCP ids themselves were already recorded earlier). */
	private void linkOrderToCheckout(final OrderModel order, final PaymentRequestData request,
			final PayoneCheckoutModel payoneCheckoutModel)
	{
		order.setPayoneCommerceCaseId(request.getCommerceCaseId());
		payoneCheckoutModel.setOrder(order);

		modelService.save(order);
		modelService.save(payoneCheckoutModel);
	}

	/** Records the authorization PaymentTransactionEntry against the placed order. */
	private void recordPaymentTransaction(final OrderModel order, final PaymentRequestData request,
			final CreatePaymentResponse execResponse, final StatusValue pcpStatus)
	{
		final PaymentTransactionModel transaction = payoneTransactionService.getOrCreatePaymentTransaction(
				order,
				request.getCommerceCaseId(),
				request.getCheckoutId());

		payoneTransactionService.createPaymentTransactionEntry(
				transaction,
				execResponse.getPayment().getId(),
				order,
				pcpStatus,
				pcpAmountOfMoneyConverter.convert(order).getAmount(),
				order.getCurrency(),
				PaymentTransactionType.AUTHORIZATION);
	}

	private static PaymentResultData buildPaymentResult(final OrderModel order, final String mappedStatus,
			final CreatePaymentResponse execResponse)
	{
		final PaymentResultData result = new PaymentResultData();
		result.setOrderCode(order.getCode());
		result.setPaymentStatus(mappedStatus);
		if (StatusValue.REDIRECTED.equals(execResponse.getPayment().getStatus()))
		{
			result.setPaymentStatus("REDIRECTED");
			result.setRedirectUrl(execResponse.getMerchantAction().getRedirectData().getRedirectURL());
		}
		return result;
	}

	/**
	 * The two methods executePaymentAndPlaceOrder (deprecated path) supports. Replaces
	 * scattered "CARD"/"SEPA" string comparisons with a compile-time-checked dispatch.
	 * PaymentRequestData.paymentMethod stays a String on the wire; this enum is parsed
	 * once per call, facade-internal only.
	 */
	private enum LegacyPaymentMethod
	{
		CARD, SEPA;

		private static LegacyPaymentMethod fromString(final String value)
		{
			for (final LegacyPaymentMethod method : values())
			{
				if (method.name().equalsIgnoreCase(value))
				{
					return method;
				}
			}
			return null;
		}
	}

	// -- authorizePayment (strategy-routed path) --

	@Override
	public PayoneAuthorizationResultData authorizePayment(final CartModel cart, final int paymentProductId,
			final PaymentDetailsData paymentDetails)
	{
		Objects.requireNonNull(cart, "cart must not be null");
		final PayoneConfigurationModel config = requireActiveConfiguration(cart);

		final PaymentTransactionModel transaction = requireTransaction(cart);
		final String commerceCaseId = transaction.getPayoneCommerceCaseId();
		final String checkoutId = transaction.getPayoneCheckoutId();
		if (StringUtils.isBlank(commerceCaseId) || StringUtils.isBlank(checkoutId))
		{
			throw new IllegalStateException(
					"PCP checkout not initialized for cart [" + cart.getCode() + "] - call createOrGetCommerceCase first");
		}

		final PayonePaymentStrategy strategy = resolveStrategy(cart, paymentProductId);
		final PayonePaymentInfoModel paymentInfoModel = buildTransientPaymentInfo(paymentProductId, paymentDetails, cart);

		LOG.info("Authorizing PCP payment (strategy path) for cart [{}], product [{}], checkout [{}]",
				cart.getCode(), paymentProductId, checkoutId);

		final PaymentExecutionResponse response;
		try
		{
			response = strategy.authorize(config, commerceCaseId, checkoutId, cart, paymentInfoModel);
		}
		catch (final RuntimeException e)
		{
			LOG.error("PAYONE strategy authorize failed for cart [{}], product [{}]: {}",
					cart.getCode(), paymentProductId, PayoneApiErrorMapper.describe(e), e);
			throw new IllegalStateException(PayoneApiErrorMapper.rootCauseMessage(e), e);
		}
		if (response == null)
		{
			throw new IllegalStateException(
					"PCP authorize returned no response for cart [" + cart.getCode() + "], product [" + paymentProductId + "]");
		}

		// Merge each id independently. A response carrying only one (e.g. paymentId set,
		// paymentExecutionId still null on a pending outcome) must not clear the other.
		boolean changed = false;
		if (response.getPaymentId() != null && !response.getPaymentId().equals(transaction.getPayonePaymentId()))
		{
			transaction.setPayonePaymentId(response.getPaymentId());
			changed = true;
		}
		if (response.getPaymentExecutionId() != null
				&& !response.getPaymentExecutionId().equals(transaction.getPayonePaymentExecutionId()))
		{
			transaction.setPayonePaymentExecutionId(response.getPaymentExecutionId());
			changed = true;
		}
		if (changed)
		{
			modelService.save(transaction);
		}

		if (response.isDirectSuccess())
		{
			cart.setPaymentInfo(paymentInfoModel);
			modelService.save(cart);
		}

		return payoneAuthorizationResultTransformer.convert(response);
	}

	// -- handleRedirectCallback (strategy-routed path) --

	@Override
	public PayoneAuthorizationResultData handleRedirectCallback(final CartModel cart, final String checkoutId)
	{
		Objects.requireNonNull(cart, "cart must not be null");
		Objects.requireNonNull(checkoutId, "checkoutId must not be null");
		final PayoneConfigurationModel config = requireActiveConfiguration(cart);

		final PaymentTransactionModel transaction = requireTransaction(cart);
		final String commerceCaseId = transaction.getPayoneCommerceCaseId();
		if (StringUtils.isBlank(commerceCaseId))
		{
			throw new IllegalStateException(
					"PCP checkout not initialized for cart [" + cart.getCode() + "] - call createOrGetCommerceCase first");
		}
		if (!StringUtils.equals(checkoutId, transaction.getPayoneCheckoutId()))
		{
			throw new PayoneCheckoutMismatchException("PCP redirect callback checkout [" + checkoutId
					+ "] does not match the cart's checkout [" + transaction.getPayoneCheckoutId() + "]");
		}

		final String checkoutStatusCode = payoneCheckoutService.getCheckout(config, commerceCaseId, checkoutId)
				.getCheckoutStatus().name();

		payoneTransactionService.setOrderPaymentStatus(cart);

		LOG.info("PCP redirect callback handled (strategy path) for cart [{}], checkout [{}], status [{}]",
				cart.getCode(), checkoutId, checkoutStatusCode);

		final PayoneAuthorizationResultData result = new PayoneAuthorizationResultData();
		result.setRedirect(false);
		result.setPaymentId(transaction.getPayonePaymentId());
		// The terminal ACCEPTED/REJECTED transition is completed by the webhook.
		result.setStatus(checkoutStatusCode);
		return result;
	}

	// -- placeOrder (strategy-routed path) --

	@Override
	public PayoneCheckoutData placeOrder(final CartModel cart)
	{
		Objects.requireNonNull(cart, "cart must not be null");
		if (cart.getPaymentInfo() == null)
		{
			throw new IllegalStateException("Cannot place PCP order: cart [" + cart.getCode() + "] has no PaymentInfo (authorize first)");
		}

		final PaymentTransactionModel transaction = requireTransaction(cart);
		final String commerceCaseId = transaction.getPayoneCommerceCaseId();
		final String checkoutId = transaction.getPayoneCheckoutId();
		if (StringUtils.isBlank(commerceCaseId) || StringUtils.isBlank(checkoutId))
		{
			throw new IllegalStateException(
					"PCP checkout not initialized for cart [" + cart.getCode() + "] - call createOrGetCommerceCase first");
		}

		// An Order must never carry a PCP transaction with no paymentExecutionId, or
		// capture/refund/webhook-reconciliation have nothing to match against. An
		// async/pending authorize can still leave it null here, so try one exact
		// backfill via getCheckout before refusing.
		if (StringUtils.isBlank(transaction.getPayonePaymentExecutionId()))
		{
			backfillPaymentExecutionId(cart, transaction, commerceCaseId, checkoutId);
		}

		final CommerceCheckoutParameter placeOrderParameter = new CommerceCheckoutParameter();
		placeOrderParameter.setEnableHooks(true);
		placeOrderParameter.setCart(cart);

		final CommerceOrderResult orderResult;
		try
		{
			orderResult = commerceCheckoutService.placeOrder(placeOrderParameter);
		}
		catch (final InvalidCartException e)
		{
			throw new IllegalStateException("PCP order placement failed for cart [" + cart.getCode() + "]: " + e.getMessage(), e);
		}

		final OrderModel order = orderResult.getOrder();
		cartService.removeSessionCart();

		// Find, not re-link: createOrderFromCart already deep-clones `transaction` onto the
		// new order, since Order2PaymentTransaction.paymentTransactions is partof="true" and
		// clone strategies carry partof relations (including the code) when cloning an
		// AbstractOrder. Calling transaction.setOrder(order) here would attach the old,
		// still-cart-linked transaction too, colliding with the clone on the unique
		// (code, order, versionID) index PaymentTransactions.transUniqueIdx - confirmed by a
		// real DuplicateKeyException from exactly that index. Use the clone directly instead;
		// it already has every field transaction had when placeOrder started.
		final PaymentTransactionModel orderTransaction = order.getPaymentTransactions().stream()
				.filter(tx -> tx.getCode().equals(transaction.getCode()))
				.findFirst()
				.orElse(transaction);

		order.setPayoneCommerceCaseId(commerceCaseId);
		modelService.save(order);

		final PayoneCheckoutModel payoneCheckoutModel = findPayoneCheckout(orderTransaction.getPayoneCheckoutId());
		payoneCheckoutModel.setOrder(order);
		modelService.save(payoneCheckoutModel);

		LOG.info("PCP order placed (strategy path) [{}], linked to commerceCase [{}]", order.getCode(), commerceCaseId);

		final PayoneCheckoutData data = new PayoneCheckoutData();
		data.setCommerceCaseId(commerceCaseId);
		data.setCheckoutId(orderTransaction.getPayoneCheckoutId());
		data.setPaymentExecutionId(orderTransaction.getPayonePaymentExecutionId());
		data.setPaymentId(orderTransaction.getPayonePaymentId());
		data.setStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().getCode() : null);
		return data;
	}

	/**
	 * Backfills a missing {@code payonePaymentExecutionId} by retrieving the checkout
	 * (side-effect-free, unlike {@code refreshPayment}) and matching its
	 * {@link PaymentExecution} list against the transaction's known {@code payonePaymentId}.
	 * Matches only by exact paymentId equality, never by list position. Throws
	 * {@link IllegalStateException} on zero or ambiguous matches rather than guessing -
	 * an incomplete PCP identity must never reach an Order.
	 */
	private void backfillPaymentExecutionId(final CartModel cart, final PaymentTransactionModel transaction,
			final String commerceCaseId, final String checkoutId)
	{
		final String paymentId = transaction.getPayonePaymentId();
		if (StringUtils.isBlank(paymentId))
		{
			throw new IllegalStateException("Cannot place PCP order: cart [" + cart.getCode()
					+ "] has no paymentExecutionId and no paymentId to resolve it from - authorize first");
		}

		final PayoneConfigurationModel config = requireActiveConfiguration(cart);
		final List<PaymentExecution> executions = payoneCheckoutService.getCheckout(config, commerceCaseId, checkoutId)
				.getPaymentExecutions();

		final List<PaymentExecution> matches = executions == null
				? Collections.emptyList()
				: executions.stream()
						.filter(execution -> paymentId.equals(execution.getPaymentId()))
						.collect(Collectors.toList());

		if (matches.size() != 1 || matches.get(0).getPaymentExecutionId() == null)
		{
			throw new IllegalStateException("Cannot place PCP order: cart [" + cart.getCode()
					+ "] has no paymentExecutionId, and checkout [" + checkoutId + "] does not resolve to a single "
					+ "unambiguous PaymentExecution for paymentId [" + paymentId + "] (found " + matches.size() + ")");
		}

		transaction.setPayonePaymentExecutionId(matches.get(0).getPaymentExecutionId().toString());
		modelService.save(transaction);
	}

	// -- Internal helpers --

	/**
	 * Resolves the active configuration from {@code order.getStore()}, not the session's
	 * current store. This facade must work from callers with no session - OCC request,
	 * scheduled job, Groovy console - so it can't depend on {@code BaseStoreService}.
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
	 * Loads the local {@code PayoneCheckout} for a PCP checkout id. Throws if absent: it's
	 * created together with the commerce case, so a miss means the caller quoted a
	 * checkout this system never made.
	 */
	private PayoneCheckoutModel findPayoneCheckout(final String checkoutId)
	{
		final PayoneCheckoutModel example = new PayoneCheckoutModel();
		example.setCheckoutId(checkoutId);
		return modelService.getByExample(example);
	}

	private static String createPaymentReference()
	{
		// PaymentExecution.References.merchantReference: maxLength 20 (api-reference).
		return PcpMerchantReferenceGenerator.unique("pay", 20);
	}

	/**
	 * Logs and recovers from a failed PCP payment-execution call (deprecated path). A checkout
	 * that failed execution keeps the failed PaymentExecution attached, so reusing it just
	 * reproduces the failure - drop the cached ids so the next attempt builds a fresh
	 * commerce case and checkout.
	 *
	 * @return an {@link IllegalStateException} for the caller to throw, so the controller's
	 *         existing catch maps it to a real HTTP status.
	 */
	private IllegalStateException recoverFromFailedExecution(final CartModel cart, final PaymentRequestData request,
			final PayoneConfigurationModel config, final String paymentReference, final Exception e)
	{
		LOG.error(
				"PAYONE payment execution failed: cart={}, merchantId={}, apiHost={}, "
						+ "commerceCaseId={}, checkoutId={}, paymentMethod={}, "
						+ "paymentProductId={}, paymentReference={}, errorDetails={}",
				cart.getCode(),
				config.getMerchantId(),
				config.getApiEndpointHost(),
				request.getCommerceCaseId(),
				request.getCheckoutId(),
				request.getPaymentMethod(),
				request.getPaymentProductId(),
				paymentReference,
				PayoneApiErrorMapper.describe(e),
				e);

		cart.setPayoneCommerceCaseId(null);
		cart.setPayoneCheckoutId(null);
		modelService.save(cart);

		return new IllegalStateException(PayoneApiErrorMapper.rootCauseMessage(e), e);
	}

	/**
	 * Returns the cart's PCP-linked PaymentTransaction, or creates one seeded from the
	 * cart's payoneCommerceCaseId/payoneCheckoutId. Those ids live on the cart, not on a
	 * transaction, so passing null/null here would create a transaction that looks
	 * uninitialized to every later call.
	 */
	private PaymentTransactionModel requireTransaction(final CartModel cart)
	{
		return payoneTransactionService.getOrCreatePaymentTransaction(cart, cart.getPayoneCommerceCaseId(),
				cart.getPayoneCheckoutId());
	}

	/**
	 * Strategy-routed path only (see class Javadoc). Resolves the strategy for
	 * productId, filtered by the store's allowed payment modes
	 * (PayoneConfiguration.paymentModes) - the enforcement
	 * executePaymentAndPlaceOrder's direct SDK call does not perform.
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

	/**
	 * Strategy-routed path only (see class Javadoc). Builds the transient
	 * (never persisted) PayonePaymentInfo the strategy dispatch reads from -
	 * same construction as executePaymentAndPlaceOrder's CARD/SEPA branches,
	 * generalized to any productId the registry resolves a strategy for.
	 *
	 * REDIRECT-family products (PayPal 840, Wero 900) need neither a token nor
	 * a mandate - PcpRedirectPaymentMethodSpecificInputBuilder reads only
	 * paymentProductId - so a null/empty paymentDetails already produces a
	 * usable PayonePaymentInfo for them without any extra branch here.
	 * MOBILE (Apple Pay 302, Google Pay 320) is NOT yet supported end-to-end:
	 * PcpMobilePaymentMethodSpecificInputBuilder is a hard stub. FINANCING
	 * (PAYONE Secured Invoice 3390, Secured Direct Debit 3392) is supported
	 * for B2C only - customerDevice ipAddress, plus dateOfBirth/phoneNumber
	 * sourced from the customer's payment address, are required by PCP for
	 * every BNPL payment (docs.commerce.payone.com/docs/payment-methods/
	 * payone-bnpl/) and must be supplied via paymentDetails/the customer's
	 * address respectively - callers using B2B still send an incomplete
	 * request. 3392 additionally reads paymentDetails' iban/bic/accountHolder,
	 * same mandate fields SEPA (771) already carries plus bic (SEPA never
	 * reads it).
	 */
	private PayonePaymentInfoModel buildTransientPaymentInfo(final int paymentProductId,
			final PaymentDetailsData paymentDetails, final CartModel cart)
	{
		final PayonePaymentInfoModel paymentInfo = newTransientPaymentInfo(cart, paymentProductId);

		if (paymentDetails == null)
		{
			return paymentInfo;
		}

		if (StringUtils.isNotBlank(paymentDetails.getPaymentProcessingToken()))
		{
			paymentInfo.setPaymentProcessingToken(paymentDetails.getPaymentProcessingToken());
		}

		if (StringUtils.isNotBlank(paymentDetails.getCustomerIpAddress()))
		{
			paymentInfo.setCustomerIpAddress(paymentDetails.getCustomerIpAddress());
		}

		if (StringUtils.isNotBlank(paymentDetails.getIban()))
		{
			// EUR is required by both SEPA (771) and PAYONE BNPL
			// (docs.commerce.payone.com/docs/payment-methods/payone-bnpl/ -
			// "General prerequisites": Germany/Austria, Euro).
			if (cart.getCurrency() == null || !"EUR".equalsIgnoreCase(cart.getCurrency().getIsocode()))
			{
				throw new IllegalArgumentException("SEPA Direct Debit / Secured Direct Debit requires an EUR cart");
			}
			paymentInfo.setMandate(newTransientMandate(
					paymentDetails.getIban(), paymentDetails.getBic(), paymentDetails.getAccountHolder(),
					paymentDetails.getCreditorId(), paymentDetails.getMandateReference(),
					paymentDetails.getDateOfSignature()));
		}

		return paymentInfo;
	}

	/**
	 * Builds a transient (never persisted) PayonePaymentInfo with the
	 * mandatory PaymentInfo (OOTB) fields set. Shared by
	 * executePaymentAndPlaceOrder (deprecated direct-SDK path) and
	 * buildTransientPaymentInfo (strategy-routed path) - both previously
	 * built this independently, and the deprecated path never set code/user,
	 * which would trip MandatoryAttributesValidator if that model were ever
	 * saved (the exact bug already found and fixed on the strategy-routed
	 * path's PaymentInfo this same cleanup pass avoids reintroducing).
	 *
	 * @param cart             the SAP Commerce cart; code/user are read from it
	 * @param paymentProductId the PCP payment product ID to set on the model
	 * @return a transient PayonePaymentInfoModel, not saved
	 */
	private PayonePaymentInfoModel newTransientPaymentInfo(final CartModel cart, final int paymentProductId)
	{
		final PayonePaymentInfoModel paymentInfo = modelService.create(PayonePaymentInfoModel.class);
		paymentInfo.setCode(cart.getCode());
		paymentInfo.setUser(cart.getUser());
		paymentInfo.setPaymentProductId(paymentProductId);
		return paymentInfo;
	}

	/**
	 * Builds a transient (never persisted) PayoneMandate from SEPA/Secured
	 * Direct Debit mandate fields. Shared by executePaymentAndPlaceOrder
	 * (deprecated direct-SDK path) and buildTransientPaymentInfo
	 * (strategy-routed path) - both previously built this independently with
	 * identical field mapping. bic is only read by Secured Direct Debit
	 * (3392) - SEPA (771) never sets it and passes null.
	 */
	private PayoneMandateModel newTransientMandate(final String iban, final String bic, final String accountHolder,
			final String creditorId, final String mandateReference, final String dateOfSignature)
	{
		final PayoneMandateModel mandate = modelService.create(PayoneMandateModel.class);
		mandate.setIban(iban);
		mandate.setBic(bic);
		mandate.setAccountHolder(accountHolder);
		mandate.setCreditorId(creditorId);
		mandate.setUniqueMandateReference(mandateReference);
		mandate.setDateOfSignature(dateOfSignature);
		return mandate;
	}

	// -----------------------------------------------------------------------
	// Spring setters
	// -----------------------------------------------------------------------

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setCartService(final CartService cartService)
	{
		this.cartService = cartService;
	}

	public void setCommerceCheckoutService(final CommerceCheckoutService commerceCheckoutService)
	{
		this.commerceCheckoutService = commerceCheckoutService;
	}

	public void setPayoneConfigurationService(final PayoneConfigurationService payoneConfigurationService)
	{
		this.payoneConfigurationService = payoneConfigurationService;
	}

	public void setPayoneCommerceCaseService(final PayoneCommerceCaseService payoneCommerceCaseService)
	{
		this.payoneCommerceCaseService = payoneCommerceCaseService;
	}

	public void setPayoneAuthenticationService(final PayoneAuthenticationService payoneAuthenticationService)
	{
		this.payoneAuthenticationService = payoneAuthenticationService;
	}

	public void setPayoneTransactionService(final PayoneTransactionService payoneTransactionService)
	{
		this.payoneTransactionService = payoneTransactionService;
	}

	public void setPaymentExecutionRequestConverter(final PaymentExecutionRequestConverter paymentExecutionRequestConverter)
	{
		this.paymentExecutionRequestConverter = paymentExecutionRequestConverter;
	}

	public void setPcpAmountOfMoneyConverter(final PcpAmountOfMoneyConverter pcpAmountOfMoneyConverter)
	{
		this.pcpAmountOfMoneyConverter = pcpAmountOfMoneyConverter;
	}

	public void setPayonePcpClientFactory(final PayonePcpClientFactory payonePcpClientFactory)
	{
		this.payonePcpClientFactory = payonePcpClientFactory;
	}

	public void setCreateCommerceCaseRequestConverter(final CreateCommerceCaseRequestConverter createCommerceCaseRequestConverter)
	{
		this.createCommerceCaseRequestConverter = createCommerceCaseRequestConverter;
	}

	public void setCreateCheckoutRequestConverter(final CreateCheckoutRequestConverter createCheckoutRequestConverter)
	{
		this.createCheckoutRequestConverter = createCheckoutRequestConverter;
	}

	public void setPaymentStrategyRegistry(final PaymentStrategyRegistry paymentStrategyRegistry)
	{
		this.paymentStrategyRegistry = paymentStrategyRegistry;
	}

	public void setPayoneCheckoutService(final PayoneCheckoutService payoneCheckoutService)
	{
		this.payoneCheckoutService = payoneCheckoutService;
	}

	public void setPayoneAuthorizationResultTransformer(final PayoneAuthorizationResultTransformer payoneAuthorizationResultTransformer)
	{
		this.payoneAuthorizationResultTransformer = payoneAuthorizationResultTransformer;
	}
}
