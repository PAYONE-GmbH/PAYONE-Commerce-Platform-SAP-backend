package com.payone.pcp.facades.impl;

import com.payone.pcp.core.converters.CreateCheckoutRequestConverter;
import com.payone.pcp.core.converters.CreateCommerceCaseRequestConverter;
import com.payone.pcp.core.converters.PaymentExecutionRequestConverter;
import com.payone.pcp.core.converters.PcpAmountOfMoneyConverter;
import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.service.PayoneAuthenticationService;
import com.payone.pcp.core.service.PayoneCheckoutService;
import com.payone.pcp.core.service.PayoneCommerceCaseService;
import com.payone.pcp.core.service.PayoneConfigurationService;
import com.payone.pcp.core.service.PayonePcpClientFactory;
import com.payone.pcp.core.service.PayoneTransactionService;
import com.payone.pcp.core.strategy.PayonePaymentStrategy;
import com.payone.pcp.core.strategy.PaymentStrategyRegistry;
import com.payone.pcp.facades.PayoneCheckoutMismatchException;
import com.payone.pcp.facades.PayoneConfigurationNotFoundException;
import com.payone.pcp.facades.converter.PayoneAuthorizationResultTransformer;
import com.payone.pcp.facades.data.CommerceCaseResultData;
import com.payone.pcp.facades.data.PaymentDetailsData;
import com.payone.pcp.facades.data.PayoneAuthorizationResultData;
import com.payone.pcp.facades.data.PayoneCheckoutData;
import com.payone.pcp.facades.data.PaymentRequestData;

import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.commerce.platform.lib.models.CheckoutResponse;
import com.payone.commerce.platform.lib.models.CreateCommerceCaseRequest;
import com.payone.commerce.platform.lib.models.CreateCommerceCaseResponse;
import com.payone.commerce.platform.lib.models.CreateCheckoutRequest;
import com.payone.commerce.platform.lib.models.CreateCheckoutResponse;
import com.payone.commerce.platform.lib.models.CheckoutReferences;
import com.payone.commerce.platform.lib.models.StatusCheckout;
import com.payone.commerce.platform.lib.models.StatusValue;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.commerceservices.order.CommerceCheckoutService;
import de.hybris.platform.commerceservices.service.data.CommerceCheckoutParameter;
import de.hybris.platform.commerceservices.service.data.CommerceOrderResult;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.order.CartService;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.store.BaseStoreModel;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@UnitTest
public class DefaultPayoneCheckoutFacadeTest
{
	private static final String COMMERCE_CASE_ID = "cc-1";
	private static final String CHECKOUT_ID = "co-1";

	@Mock
	private ModelService modelService;
	@Mock
	private CartService cartService;
	@Mock
	private CommerceCheckoutService commerceCheckoutService;
	@Mock
	private PayoneConfigurationService payoneConfigurationService;
	@Mock
	private PayoneCommerceCaseService payoneCommerceCaseService;
	@Mock
	private PayoneAuthenticationService payoneAuthenticationService;
	@Mock
	private PayoneTransactionService payoneTransactionService;
	@Mock
	private PaymentExecutionRequestConverter paymentExecutionRequestConverter;
	@Mock
	private PcpAmountOfMoneyConverter pcpAmountOfMoneyConverter;
	@Mock
	private PayonePcpClientFactory payonePcpClientFactory;
	@Mock
	private CreateCommerceCaseRequestConverter createCommerceCaseRequestConverter;
	@Mock
	private CreateCheckoutRequestConverter createCheckoutRequestConverter;
	@Mock
	private PaymentStrategyRegistry paymentStrategyRegistry;
	@Mock
	private PayoneCheckoutService payoneCheckoutService;
	@Mock
	private PayoneAuthorizationResultTransformer payoneAuthorizationResultTransformer;

	@Mock
	private CartModel cart;
	@Mock
	private BaseStoreModel store;
	@Mock
	private PayoneConfigurationModel configuration;
	@Mock
	private CurrencyModel currency;
	@Mock
	private PaymentTransactionModel transaction;
	@Mock
	private PayonePaymentStrategy strategy;

	private DefaultPayoneCheckoutFacade facade;

	@Before
	public void setUp()
	{
		MockitoAnnotations.openMocks(this);

		facade = new DefaultPayoneCheckoutFacade();
		facade.setModelService(modelService);
		facade.setCartService(cartService);
		facade.setCommerceCheckoutService(commerceCheckoutService);
		facade.setPayoneConfigurationService(payoneConfigurationService);
		facade.setPayoneCommerceCaseService(payoneCommerceCaseService);
		facade.setPayoneAuthenticationService(payoneAuthenticationService);
		facade.setPayoneTransactionService(payoneTransactionService);
		facade.setPaymentExecutionRequestConverter(paymentExecutionRequestConverter);
		facade.setPcpAmountOfMoneyConverter(pcpAmountOfMoneyConverter);
		facade.setPayonePcpClientFactory(payonePcpClientFactory);
		facade.setCreateCommerceCaseRequestConverter(createCommerceCaseRequestConverter);
		facade.setCreateCheckoutRequestConverter(createCheckoutRequestConverter);
		facade.setPaymentStrategyRegistry(paymentStrategyRegistry);
		facade.setPayoneCheckoutService(payoneCheckoutService);
		facade.setPayoneAuthorizationResultTransformer(payoneAuthorizationResultTransformer);

		// requireActiveConfiguration(cart) resolves via cart.getStore(), not the session's
		// "current" store, so the facade also works from a Groovy console script with no
		// session state.
		when(cart.getStore()).thenReturn(store);

		// modelService is a mock, so create() returns null unless stubbed; the CARD/SEPA and
		// strategy-routed transient-model paths need a real instance to call setters on.
		when(modelService.create(com.payone.pcp.core.model.PayonePaymentInfoModel.class))
				.thenAnswer(invocation -> mock(com.payone.pcp.core.model.PayonePaymentInfoModel.class));
		when(modelService.create(com.payone.pcp.core.model.PayoneMandateModel.class))
				.thenAnswer(invocation -> mock(com.payone.pcp.core.model.PayoneMandateModel.class));
	}

	/**
	 * Wires cart+transaction as an already-initialized PCP checkout, the precondition every
	 * strategy-routed test (authorizePayment/handleRedirectCallback) needs.
	 */
	private void givenInitializedCheckout()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(payoneTransactionService.getOrCreatePaymentTransaction(cart, null, null)).thenReturn(transaction);
		when(transaction.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(transaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
	}

	private static PaymentRequestData cardRequest()
	{
		final PaymentRequestData request = new PaymentRequestData();
		request.setCommerceCaseId(COMMERCE_CASE_ID);
		request.setCheckoutId(CHECKOUT_ID);
		request.setPaymentMethod("CARD");
		request.setPaymentProcessingToken("token-1");
		return request;
	}

	private static PaymentRequestData sepaRequest()
	{
		final PaymentRequestData request = new PaymentRequestData();
		request.setCommerceCaseId(COMMERCE_CASE_ID);
		request.setCheckoutId(CHECKOUT_ID);
		request.setPaymentMethod("SEPA");
		request.setIban("DE00000000000000000000");
		request.setAccountHolder("Holder");
		request.setCreditorId("creditor");
		request.setMandateReference("mandate");
		request.setDateOfSignature("2026-01-01");
		return request;
	}

	@Test
	public void createOrGetCommerceCase_returnsExistingIds_whenCartAlreadyInitialized()
	{
		when(cart.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(cart.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(cart.getCurrency()).thenReturn(currency);
		when(currency.getIsocode()).thenReturn("EUR");

		final AmountOfMoney amount = new AmountOfMoney().amount(1000L).currencyCode("EUR");
		when(pcpAmountOfMoneyConverter.convert(cart)).thenReturn(amount);

		final CommerceCaseResultData result = facade.createOrGetCommerceCase(cart);

		assertEquals(COMMERCE_CASE_ID, result.getCommerceCaseId());
		assertEquals(CHECKOUT_ID, result.getCheckoutId());
		assertEquals(Long.valueOf(1000L), result.getAmount());
		assertEquals("EUR", result.getCurrencyIsoCode());

		// Short-circuit must not attempt to create a new commerce case.
		verify(payoneCommerceCaseService, org.mockito.Mockito.never())
				.createCommerceCase(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	public void createOrGetCommerceCase_throwsConfigurationNotFound_whenNoActiveConfiguration()
	{
		when(cart.getPayoneCommerceCaseId()).thenReturn(null);
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(null);

		assertThrows(PayoneConfigurationNotFoundException.class, () -> facade.createOrGetCommerceCase(cart));
	}

	@Test
	public void createOrGetCommerceCase_throwsIllegalState_whenCartHasNoDeliveryAddress()
	{
		when(cart.getPayoneCommerceCaseId()).thenReturn(null);
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(cart.getDeliveryAddress()).thenReturn(null);

		assertThrows(IllegalStateException.class, () -> facade.createOrGetCommerceCase(cart));

		// The request converters must not be invoked without a delivery address.
		verify(createCommerceCaseRequestConverter, org.mockito.Mockito.never())
				.convert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	/**
	 * PCP requires merchantReference unique per Checkout, not just per commerce case. The
	 * commerce-case reference gets a random suffix to survive retries, but
	 * CreateCheckoutRequestConverter defaults the checkout's own reference to the raw cart code
	 * (PcpReferencesConverter.resolveMerchantReference), which collides on retry (CCv2 errorCode
	 * 50305001 "checkout-error-colliding-merchant-reference"). The facade overrides the
	 * checkout's reference with the same suffixed value used for the commerce case.
	 */
	@Test
	public void createOrGetCommerceCase_usesSameMerchantReference_forCommerceCaseAndCheckout()
	{
		when(cart.getPayoneCommerceCaseId()).thenReturn(null);
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(cart.getCode()).thenReturn("00002000");
		when(cart.getDeliveryAddress())
				.thenReturn(mock(de.hybris.platform.core.model.user.AddressModel.class));
		when(cart.getUser())
				.thenReturn(mock(de.hybris.platform.core.model.user.CustomerModel.class));
		when(cart.getCurrency()).thenReturn(currency);
		when(currency.getIsocode()).thenReturn("EUR");
		when(pcpAmountOfMoneyConverter.convert(cart))
				.thenReturn(new AmountOfMoney().amount(1000L).currencyCode("EUR"));

		final CreateCommerceCaseRequest commerceCaseRequest = new CreateCommerceCaseRequest();
		when(createCommerceCaseRequestConverter.convert(any(), any(), any())).thenReturn(commerceCaseRequest);

		final CreateCheckoutRequest checkoutRequest = new CreateCheckoutRequest()
				.references(new CheckoutReferences().merchantReference("00002000"));
		when(createCheckoutRequestConverter.convert(any(), any(), any(), any())).thenReturn(checkoutRequest);

		final CreateCommerceCaseResponse commerceCaseResponse = new CreateCommerceCaseResponse()
				.commerceCaseId(java.util.UUID.randomUUID())
				.checkout(new CreateCheckoutResponse().checkoutId(java.util.UUID.randomUUID()));
		when(payoneCommerceCaseService.createCommerceCase(any(), any())).thenReturn(commerceCaseResponse);

		when(modelService.create(com.payone.pcp.core.model.PayoneCheckoutModel.class))
				.thenReturn(mock(com.payone.pcp.core.model.PayoneCheckoutModel.class));
		when(modelService.create(com.payone.pcp.core.model.PayoneCommerceCaseModel.class))
				.thenReturn(mock(com.payone.pcp.core.model.PayoneCommerceCaseModel.class));

		facade.createOrGetCommerceCase(cart);

		final org.mockito.ArgumentCaptor<CreateCommerceCaseRequest> captor =
				org.mockito.ArgumentCaptor.forClass(CreateCommerceCaseRequest.class);
		verify(payoneCommerceCaseService).createCommerceCase(any(), captor.capture());

		final CreateCommerceCaseRequest sentRequest = captor.getValue();
		assertEquals(sentRequest.getMerchantReference(),
				sentRequest.getCheckout().getReferences().getMerchantReference());
		org.junit.Assert.assertNotEquals("00002000", sentRequest.getCheckout().getReferences().getMerchantReference());
	}

	@Test
	public void createAuthenticationToken_throwsConfigurationNotFound_whenNoActiveConfiguration()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(null);

		assertThrows(PayoneConfigurationNotFoundException.class, () -> facade.createAuthenticationToken(cart));
	}

	@Test
	public void executePaymentAndPlaceOrder_throwsIllegalArgument_whenRequiredFieldsMissing()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);

		final PaymentRequestData request = new PaymentRequestData();
		// commerceCaseId/checkoutId/paymentMethod all blank.

		assertThrows(IllegalArgumentException.class, () -> facade.executePaymentAndPlaceOrder(cart, request));
	}

	@Test
	public void executePaymentAndPlaceOrder_throwsMismatch_whenIdsDoNotMatchCart()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(cart.getPayoneCommerceCaseId()).thenReturn("other-cc");
		when(cart.getPayoneCheckoutId()).thenReturn("other-co");

		assertThrows(PayoneCheckoutMismatchException.class,
				() -> facade.executePaymentAndPlaceOrder(cart, cardRequest()));
	}

	@Test
	public void executePaymentAndPlaceOrder_throwsIllegalArgument_whenCardTokenMissing()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(cart.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(cart.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);

		final PaymentRequestData request = cardRequest();
		request.setPaymentProcessingToken(null);

		assertThrows(IllegalArgumentException.class, () -> facade.executePaymentAndPlaceOrder(cart, request));
	}

	@Test
	public void executePaymentAndPlaceOrder_throwsIllegalArgument_whenSepaFieldsMissing()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(cart.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(cart.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);

		final PaymentRequestData request = new PaymentRequestData();
		request.setCommerceCaseId(COMMERCE_CASE_ID);
		request.setCheckoutId(CHECKOUT_ID);
		request.setPaymentMethod("SEPA");
		// iban/accountHolder/creditorId/mandateReference/dateOfSignature all missing.

		assertThrows(IllegalArgumentException.class, () -> facade.executePaymentAndPlaceOrder(cart, request));
	}

	@Test
	public void executePaymentAndPlaceOrder_throwsIllegalArgument_whenSepaCartIsNotEur()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(cart.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(cart.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(cart.getCurrency()).thenReturn(currency);
		when(currency.getIsocode()).thenReturn("USD");

		assertThrows(IllegalArgumentException.class,
				() -> facade.executePaymentAndPlaceOrder(cart, sepaRequest()));
	}

	/**
	 * PCP-side failures (ApiErrorResponseException, e.g. missing paymentChannel) must surface as
	 * IllegalStateException, same as a generic RuntimeException from the PCP client, so the
	 * controller can map it to a meaningful HTTP status (was an uncaught 400 on CCv2 test).
	 */
	@Test
	public void executePaymentAndPlaceOrder_throwsIllegalState_whenPcpRejectsRequest() throws Exception
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(cart.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(cart.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);

		final PaymentRequestData request = cardRequest();

		final com.payone.commerce.platform.lib.models.PaymentExecutionRequest execRequest =
				new com.payone.commerce.platform.lib.models.PaymentExecutionRequest()
						.paymentExecutionSpecificInput(
								new com.payone.commerce.platform.lib.models.PaymentExecutionSpecificInput()
										.paymentReferences(new com.payone.commerce.platform.lib.models.References()));
		when(paymentExecutionRequestConverter.convert(any(), any(), any())).thenReturn(execRequest);

		final com.payone.commerce.platform.lib.endpoints.PaymentExecutionApiClient apiClient =
				mock(com.payone.commerce.platform.lib.endpoints.PaymentExecutionApiClient.class);
		when(payonePcpClientFactory.createPaymentExecutionApiClient(configuration)).thenReturn(apiClient);
		// getMessage() null matches what PCP returns when its error body carries no message
		// string; real detail lives only in the errors list. getRootCauseMessage() must surface
		// it rather than falling back to "PAYONE request failed".
		final com.payone.commerce.platform.lib.models.APIError apiError =
				new com.payone.commerce.platform.lib.models.APIError().errorCode("50000000");
		when(apiClient.createPayment(any(), any(), any(), any()))
				.thenThrow(new com.payone.commerce.platform.lib.errors.ApiErrorResponseException(
						500, null, java.util.List.of(apiError)));

		final IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> facade.executePaymentAndPlaceOrder(cart, request));

		org.junit.Assert.assertNotEquals("PAYONE request failed", thrown.getMessage());
		org.junit.Assert.assertTrue(thrown.getMessage().contains("50000000"));

		// Same error-recovery as the RuntimeException catch: a dead checkout
		// must not be reused on the next attempt.
		verify(cart).setPayoneCommerceCaseId(null);
		verify(cart).setPayoneCheckoutId(null);
	}

	@Test
	public void executePaymentAndPlaceOrder_throwsIllegalArgument_whenPaymentMethodUnsupported()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(cart.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(cart.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);

		final PaymentRequestData request = new PaymentRequestData();
		request.setCommerceCaseId(COMMERCE_CASE_ID);
		request.setCheckoutId(CHECKOUT_ID);
		request.setPaymentMethod("PAYPAL");

		assertThrows(IllegalArgumentException.class, () -> facade.executePaymentAndPlaceOrder(cart, request));
	}

	// authorizePayment

	@Test
	public void authorizePayment_throwsIllegalState_whenCheckoutNotInitialized()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(payoneTransactionService.getOrCreatePaymentTransaction(cart, null, null)).thenReturn(transaction);
		when(transaction.getPayoneCommerceCaseId()).thenReturn(null);

		assertThrows(IllegalStateException.class, () -> facade.authorizePayment(cart, 1, null));
	}

	@Test
	public void authorizePayment_throwsIllegalState_whenNoStrategyForProduct()
	{
		givenInitializedCheckout();
		when(payoneConfigurationService.getAllowedPaymentProductIds(store)).thenReturn(Collections.emptyList());
		when(paymentStrategyRegistry.getStrategy(999, Collections.emptyList())).thenReturn(null);

		assertThrows(IllegalStateException.class, () -> facade.authorizePayment(cart, 999, null));
	}

	@Test
	public void authorizePayment_returnsTransformedResult_onDirectSuccess()
	{
		givenInitializedCheckout();
		when(payoneConfigurationService.getAllowedPaymentProductIds(store)).thenReturn(Collections.emptyList());
		when(paymentStrategyRegistry.getStrategy(1, Collections.emptyList())).thenReturn(strategy);

		// isDirectSuccess() requires the raw response to be a CreatePaymentResponse.
		final PaymentExecutionResponse response = new PaymentExecutionResponse(
				"pay-1", "exec-1", StatusValue.CAPTURED,
				new com.payone.commerce.platform.lib.models.CreatePaymentResponse());
		when(strategy.authorize(any(), any(), any(), any(), any())).thenReturn(response);

		final PayoneAuthorizationResultData expected = new PayoneAuthorizationResultData();
		expected.setStatus("ACCEPTED");
		when(payoneAuthorizationResultTransformer.convert(response)).thenReturn(expected);

		final PaymentDetailsData paymentDetails = new PaymentDetailsData();
		paymentDetails.setPaymentProcessingToken("tok-1");

		final PayoneAuthorizationResultData result = facade.authorizePayment(cart, 1, paymentDetails);

		assertEquals("ACCEPTED", result.getStatus());
		verify(cart).setPaymentInfo(any());
	}

	@Test
	public void authorizePayment_returnsRedirectResult_forPayPal()
	{
		// PayPal (840, REDIRECT family) needs no paymentDetails - unlike CARD/SEPA,
		// PcpRedirectPaymentMethodSpecificInputBuilder reads only paymentProductId.
		givenInitializedCheckout();
		when(payoneConfigurationService.getAllowedPaymentProductIds(store)).thenReturn(Collections.emptyList());
		when(paymentStrategyRegistry.getStrategy(840, Collections.emptyList())).thenReturn(strategy);

		final PaymentExecutionResponse response = new PaymentExecutionResponse(
				null, "exec-1", StatusValue.REDIRECTED,
				new com.payone.commerce.platform.lib.models.CreatePaymentResponse()
						.merchantAction(new com.payone.commerce.platform.lib.models.MerchantAction()
								.actionType(com.payone.commerce.platform.lib.models.ActionType.REDIRECT)
								.redirectData(new com.payone.commerce.platform.lib.models.RedirectData()
										.redirectURL("https://paypal.test/approve"))));
		when(strategy.authorize(any(), any(), any(), any(), any())).thenReturn(response);

		final PayoneAuthorizationResultData expected = new PayoneAuthorizationResultData();
		expected.setRedirect(true);
		expected.setRedirectUrl("https://paypal.test/approve");
		when(payoneAuthorizationResultTransformer.convert(response)).thenReturn(expected);

		final PayoneAuthorizationResultData result = facade.authorizePayment(cart, 840, null);

		assertEquals("https://paypal.test/approve", result.getRedirectUrl());
		// Direct-success side effect must not fire for a redirect outcome.
		verify(cart, never()).setPaymentInfo(any());
	}

	@Test
	public void authorizePayment_preservesExistingExecutionId_whenResponseOmitsIt()
	{
		// A response carrying only paymentId (pending/async outcome) must not clear a
		// previously-recorded execution id - the old OR-guarded dual-set wiped it unconditionally.
		givenInitializedCheckout();
		when(transaction.getPayonePaymentExecutionId()).thenReturn("exec-existing");
		when(payoneConfigurationService.getAllowedPaymentProductIds(store)).thenReturn(Collections.emptyList());
		when(paymentStrategyRegistry.getStrategy(1, Collections.emptyList())).thenReturn(strategy);

		final PaymentExecutionResponse response = new PaymentExecutionResponse(
				"pay-new", null, StatusValue.PENDING_PAYMENT,
				new com.payone.commerce.platform.lib.models.CreatePaymentResponse());
		when(strategy.authorize(any(), any(), any(), any(), any())).thenReturn(response);
		when(payoneAuthorizationResultTransformer.convert(response)).thenReturn(new PayoneAuthorizationResultData());

		facade.authorizePayment(cart, 1, null);

		verify(transaction).setPayonePaymentId("pay-new");
		verify(transaction, never()).setPayonePaymentExecutionId(any());
	}

	@Test
	public void authorizePayment_throwsIllegalArgument_whenSepaCartIsNotEur()
	{
		givenInitializedCheckout();
		when(cart.getCurrency()).thenReturn(currency);
		when(currency.getIsocode()).thenReturn("USD");
		when(payoneConfigurationService.getAllowedPaymentProductIds(store)).thenReturn(Collections.emptyList());
		when(paymentStrategyRegistry.getStrategy(771, Collections.emptyList())).thenReturn(strategy);

		final PaymentDetailsData paymentDetails = new PaymentDetailsData();
		paymentDetails.setIban("DE00000000000000000000");

		assertThrows(IllegalArgumentException.class, () -> facade.authorizePayment(cart, 771, paymentDetails));
	}

	// handleRedirectCallback

	@Test
	public void handleRedirectCallback_throwsMismatch_whenCheckoutIdDoesNotMatch()
	{
		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		when(payoneTransactionService.getOrCreatePaymentTransaction(cart, null, null)).thenReturn(transaction);
		when(transaction.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(transaction.getPayoneCheckoutId()).thenReturn("other-co");

		assertThrows(PayoneCheckoutMismatchException.class,
				() -> facade.handleRedirectCallback(cart, CHECKOUT_ID));
	}

	@Test
	public void handleRedirectCallback_returnsCurrentStatus_whenCheckoutMatches()
	{
		givenInitializedCheckout();
		when(transaction.getPayonePaymentId()).thenReturn("pay-1");

		final CheckoutResponse checkoutResponse = mock(CheckoutResponse.class);
		when(checkoutResponse.getCheckoutStatus()).thenReturn(StatusCheckout.PENDING_COMPLETION);
		when(payoneCheckoutService.getCheckout(configuration, COMMERCE_CASE_ID, CHECKOUT_ID)).thenReturn(checkoutResponse);

		final PayoneAuthorizationResultData result = facade.handleRedirectCallback(cart, CHECKOUT_ID);

		assertEquals("pay-1", result.getPaymentId());
		assertEquals(StatusCheckout.PENDING_COMPLETION.name(), result.getStatus());
		verify(payoneTransactionService).setOrderPaymentStatus(cart);
	}

	// placeOrder

	@Test
	public void placeOrder_throwsIllegalState_whenCartHasNoPaymentInfo() throws de.hybris.platform.order.InvalidCartException
	{
		when(cart.getPaymentInfo()).thenReturn(null);

		assertThrows(IllegalStateException.class, () -> facade.placeOrder(cart));

		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void placeOrder_usesOrderClonedTransaction_onSuccess() throws de.hybris.platform.order.InvalidCartException
	{
		when(cart.getPaymentInfo()).thenReturn(mock(de.hybris.platform.core.model.order.payment.PaymentInfoModel.class));
		when(payoneTransactionService.getOrCreatePaymentTransaction(cart, null, null)).thenReturn(transaction);
		when(transaction.getCode()).thenReturn("cart-1_PAYONE");
		when(transaction.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(transaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		// Complete identity (execution id already present) - no backfill needed.
		when(transaction.getPayonePaymentExecutionId()).thenReturn("exec-1");

		final OrderModel order = mock(OrderModel.class);
		final CommerceOrderResult orderResult = mock(CommerceOrderResult.class);
		when(orderResult.getOrder()).thenReturn(order);
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class))).thenReturn(orderResult);

		// OrderService.createOrderFromCart deep-clones `transaction` onto the order
		// (Order2PaymentTransaction.paymentTransactions is partof="true"). placeOrder() must find
		// and use that clone, not call setOrder() on the original - that collides with the clone
		// on PaymentTransactions' unique (code, order, versionID) index (real DuplicateKeyException
		// in HAC testing).
		final PaymentTransactionModel orderTransaction = mock(PaymentTransactionModel.class);
		when(orderTransaction.getCode()).thenReturn("cart-1_PAYONE");
		when(orderTransaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(order.getPaymentTransactions()).thenReturn(java.util.List.of(orderTransaction));

		final com.payone.pcp.core.model.PayoneCheckoutModel checkoutModel =
				mock(com.payone.pcp.core.model.PayoneCheckoutModel.class);
		when(modelService.getByExample(any())).thenReturn(checkoutModel);

		final PayoneCheckoutData result = facade.placeOrder(cart);

		verify(transaction, never()).setOrder(any());
		verify(order).setPayoneCommerceCaseId(COMMERCE_CASE_ID);
		verify(cartService).removeSessionCart();
		assertEquals(COMMERCE_CASE_ID, result.getCommerceCaseId());
		assertEquals(CHECKOUT_ID, result.getCheckoutId());
	}

	@Test
	public void placeOrder_backfillsExecutionId_whenGetCheckoutResolvesUniqueMatch()
			throws de.hybris.platform.order.InvalidCartException
	{
		when(cart.getPaymentInfo()).thenReturn(mock(de.hybris.platform.core.model.order.payment.PaymentInfoModel.class));
		when(payoneTransactionService.getOrCreatePaymentTransaction(cart, null, null)).thenReturn(transaction);
		when(transaction.getCode()).thenReturn("cart-1_PAYONE");
		when(transaction.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(transaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(transaction.getPayonePaymentExecutionId()).thenReturn(null);
		when(transaction.getPayonePaymentId()).thenReturn("pay-1");

		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		final java.util.UUID executionUuid = java.util.UUID.randomUUID();
		final com.payone.commerce.platform.lib.models.PaymentExecution execution =
				new com.payone.commerce.platform.lib.models.PaymentExecution()
						.paymentId("pay-1")
						.paymentExecutionId(executionUuid);
		final CheckoutResponse checkoutResponse = mock(CheckoutResponse.class);
		when(checkoutResponse.getPaymentExecutions()).thenReturn(java.util.List.of(execution));
		when(payoneCheckoutService.getCheckout(configuration, COMMERCE_CASE_ID, CHECKOUT_ID)).thenReturn(checkoutResponse);

		final OrderModel order = mock(OrderModel.class);
		final CommerceOrderResult orderResult = mock(CommerceOrderResult.class);
		when(orderResult.getOrder()).thenReturn(order);
		when(commerceCheckoutService.placeOrder(any(CommerceCheckoutParameter.class))).thenReturn(orderResult);

		final PaymentTransactionModel orderTransaction = mock(PaymentTransactionModel.class);
		when(orderTransaction.getCode()).thenReturn("cart-1_PAYONE");
		when(orderTransaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(order.getPaymentTransactions()).thenReturn(java.util.List.of(orderTransaction));

		final com.payone.pcp.core.model.PayoneCheckoutModel checkoutModel =
				mock(com.payone.pcp.core.model.PayoneCheckoutModel.class);
		when(modelService.getByExample(any())).thenReturn(checkoutModel);

		final PayoneCheckoutData result = facade.placeOrder(cart);

		verify(transaction).setPayonePaymentExecutionId(executionUuid.toString());
		verify(modelService).save(transaction);
		assertEquals(COMMERCE_CASE_ID, result.getCommerceCaseId());
	}

	@Test
	public void placeOrder_throwsIllegalState_whenNoExecutionMatchesPaymentId() throws de.hybris.platform.order.InvalidCartException
	{
		when(cart.getPaymentInfo()).thenReturn(mock(de.hybris.platform.core.model.order.payment.PaymentInfoModel.class));
		when(payoneTransactionService.getOrCreatePaymentTransaction(cart, null, null)).thenReturn(transaction);
		when(transaction.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(transaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(transaction.getPayonePaymentExecutionId()).thenReturn(null);
		when(transaction.getPayonePaymentId()).thenReturn("pay-1");

		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		final CheckoutResponse checkoutResponse = mock(CheckoutResponse.class);
		when(checkoutResponse.getPaymentExecutions()).thenReturn(java.util.List.of());
		when(payoneCheckoutService.getCheckout(configuration, COMMERCE_CASE_ID, CHECKOUT_ID)).thenReturn(checkoutResponse);

		assertThrows(IllegalStateException.class, () -> facade.placeOrder(cart));

		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void placeOrder_throwsIllegalState_whenMultipleExecutionsMatchPaymentId()
			throws de.hybris.platform.order.InvalidCartException
	{
		when(cart.getPaymentInfo()).thenReturn(mock(de.hybris.platform.core.model.order.payment.PaymentInfoModel.class));
		when(payoneTransactionService.getOrCreatePaymentTransaction(cart, null, null)).thenReturn(transaction);
		when(transaction.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(transaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(transaction.getPayonePaymentExecutionId()).thenReturn(null);
		when(transaction.getPayonePaymentId()).thenReturn("pay-1");

		when(payoneConfigurationService.getActiveConfigurationForStore(store)).thenReturn(configuration);
		final com.payone.commerce.platform.lib.models.PaymentExecution execution1 =
				new com.payone.commerce.platform.lib.models.PaymentExecution()
						.paymentId("pay-1")
						.paymentExecutionId(java.util.UUID.randomUUID());
		final com.payone.commerce.platform.lib.models.PaymentExecution execution2 =
				new com.payone.commerce.platform.lib.models.PaymentExecution()
						.paymentId("pay-1")
						.paymentExecutionId(java.util.UUID.randomUUID());
		final CheckoutResponse checkoutResponse = mock(CheckoutResponse.class);
		when(checkoutResponse.getPaymentExecutions()).thenReturn(java.util.List.of(execution1, execution2));
		when(payoneCheckoutService.getCheckout(configuration, COMMERCE_CASE_ID, CHECKOUT_ID)).thenReturn(checkoutResponse);

		assertThrows(IllegalStateException.class, () -> facade.placeOrder(cart));

		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

	@Test
	public void placeOrder_throwsIllegalState_whenPaymentIdBlank_withoutCallingGetCheckout()
			throws de.hybris.platform.order.InvalidCartException
	{
		when(cart.getPaymentInfo()).thenReturn(mock(de.hybris.platform.core.model.order.payment.PaymentInfoModel.class));
		when(payoneTransactionService.getOrCreatePaymentTransaction(cart, null, null)).thenReturn(transaction);
		when(transaction.getPayoneCommerceCaseId()).thenReturn(COMMERCE_CASE_ID);
		when(transaction.getPayoneCheckoutId()).thenReturn(CHECKOUT_ID);
		when(transaction.getPayonePaymentExecutionId()).thenReturn(null);
		when(transaction.getPayonePaymentId()).thenReturn(null);

		assertThrows(IllegalStateException.class, () -> facade.placeOrder(cart));

		verify(payoneCheckoutService, never()).getCheckout(any(), any(), any());
		verify(commerceCheckoutService, never()).placeOrder(any(CommerceCheckoutParameter.class));
	}

}
