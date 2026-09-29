package com.payone.pcp.occ.controllers;

import com.payone.pcp.facades.PayoneCheckoutFacade;
import com.payone.pcp.facades.PayoneCheckoutMismatchException;
import com.payone.pcp.facades.PayoneConfigurationNotFoundException;
import com.payone.pcp.facades.data.AuthenticationTokenResultData;
import com.payone.pcp.facades.data.CommerceCaseResultData;
import com.payone.pcp.facades.data.PaymentDetailsData;
import com.payone.pcp.facades.data.PaymentRequestData;
import com.payone.pcp.facades.data.PaymentResultData;
import com.payone.pcp.facades.data.PayoneAuthorizationResultData;
import com.payone.pcp.facades.data.PayoneCheckoutData;

import com.payone.pcp.occ.dto.ErrorResultWsDTO;
import com.payone.pcp.occ.dto.PaymentDetailsWsDTO;
import com.payone.pcp.occ.dto.PaymentRequestWsDTO;
import com.payone.pcp.occ.dto.PaymentResultWsDTO;
import com.payone.pcp.occ.dto.PayoneAuthorizationResultWsDTO;
import com.payone.pcp.occ.dto.PayoneCheckoutResultWsDTO;
import com.payone.pcp.occ.dto.CommerceCaseResultWsDTO;
import com.payone.pcp.occ.dto.AuthenticationTokenResultWsDTO;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.order.CartService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;


/**
 * Unit tests for {@link PayoneCheckoutController}. No Spring context: facade and cartService are
 * mocked and injected via {@link ReflectionTestUtils}, matching the controller's field injection.
 */
@UnitTest
public class PayoneCheckoutControllerTest
{
	private static final String CART_CODE = "cart-1";
	private static final String COMMERCE_CASE_ID = "cc-1";
	private static final String CHECKOUT_ID = "co-1";

	@Mock
	private CartService cartService;
	@Mock
	private PayoneCheckoutFacade payoneCheckoutFacade;
	@Mock
	private CartModel cart;

	private PayoneCheckoutController controller;

	@Before
	public void setUp()
	{
		MockitoAnnotations.openMocks(this);

		controller = new PayoneCheckoutController();
		ReflectionTestUtils.setField(controller, "cartService", cartService);
		ReflectionTestUtils.setField(controller, "payoneCheckoutFacade", payoneCheckoutFacade);

		when(cartService.getSessionCart()).thenReturn(cart);
		when(cart.getCode()).thenReturn(CART_CODE);
	}

	// createCommerceCase

	@Test
	public void createCommerceCase_returns200WithMappedFields_onSuccess()
	{
		final CommerceCaseResultData data = new CommerceCaseResultData();
		data.setCommerceCaseId(COMMERCE_CASE_ID);
		data.setCheckoutId(CHECKOUT_ID);
		data.setAmount(1000L);
		data.setCurrencyIsoCode("EUR");
		data.setAllowedPaymentActions(List.of("PAYMENT_EXECUTION"));
		when(payoneCheckoutFacade.createOrGetCommerceCase(cart)).thenReturn(data);

		final ResponseEntity<?> response = controller.createCommerceCase();

		assertEquals(HttpStatus.OK, response.getStatusCode());
		final CommerceCaseResultWsDTO body = (CommerceCaseResultWsDTO) response.getBody();
		assertEquals(COMMERCE_CASE_ID, body.getCommerceCaseId());
		assertEquals(CHECKOUT_ID, body.getCheckoutId());
		assertEquals(Long.valueOf(1000L), body.getAmount());
		assertEquals("EUR", body.getCurrencyIsoCode());
		assertEquals(List.of("PAYMENT_EXECUTION"), body.getAllowedPaymentActions());
	}

	@Test
	public void createCommerceCase_returns409_whenConfigurationNotFound()
	{
		when(payoneCheckoutFacade.createOrGetCommerceCase(cart))
				.thenThrow(new PayoneConfigurationNotFoundException("no active configuration"));

		final ResponseEntity<?> response = controller.createCommerceCase();

		assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
		assertEquals("no active configuration", ((ErrorResultWsDTO) response.getBody()).getMessage());
	}

	@Test
	public void createCommerceCase_returns502_forOtherIllegalState()
	{
		when(payoneCheckoutFacade.createOrGetCommerceCase(cart))
				.thenThrow(new IllegalStateException("PCP call failed"));

		final ResponseEntity<?> response = controller.createCommerceCase();

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
		assertEquals("PCP call failed", ((ErrorResultWsDTO) response.getBody()).getMessage());
	}

	// createAuthenticationToken

	@Test
	public void createAuthenticationToken_returns200WithMappedFields_onSuccess()
	{
		final AuthenticationTokenResultData data = new AuthenticationTokenResultData();
		data.setToken("jwt-token");
		data.setId("token-id");
		data.setExpirationDate("2030-01-01");
		when(payoneCheckoutFacade.createAuthenticationToken(cart)).thenReturn(data);

		final ResponseEntity<?> response = controller.createAuthenticationToken();

		assertEquals(HttpStatus.OK, response.getStatusCode());
		final AuthenticationTokenResultWsDTO body = (AuthenticationTokenResultWsDTO) response.getBody();
		assertEquals("jwt-token", body.getToken());
		assertEquals("token-id", body.getId());
		assertEquals("2030-01-01", body.getExpirationDate());
	}

	@Test
	public void createAuthenticationToken_returns502_onIllegalState()
	{
		when(payoneCheckoutFacade.createAuthenticationToken(cart))
				.thenThrow(new IllegalStateException("token creation failed"));

		final ResponseEntity<?> response = controller.createAuthenticationToken();

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
	}

	// executePayment (deprecated direct-SDK path)

	@Test
	public void executePayment_returns200_whenOrderCodePresent()
	{
		final PaymentRequestWsDTO body = new PaymentRequestWsDTO();
		body.setCommerceCaseId(COMMERCE_CASE_ID);
		body.setCheckoutId(CHECKOUT_ID);
		body.setPaymentMethod("CARD");
		body.setPaymentProcessingToken("token-1");

		final PaymentResultData data = new PaymentResultData();
		data.setOrderCode("order-1");
		data.setPaymentStatus("PAID");
		when(payoneCheckoutFacade.executePaymentAndPlaceOrder(eq(cart), any())).thenReturn(data);

		final ResponseEntity<?> response = controller.executePayment(body);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		final PaymentResultWsDTO result = (PaymentResultWsDTO) response.getBody();
		assertEquals("order-1", result.getOrderCode());
		assertEquals("PAID", result.getPaymentStatus());
	}

	@Test
	public void executePayment_returns402_whenOrderCodeAbsent()
	{
		final PaymentRequestWsDTO body = new PaymentRequestWsDTO();
		final PaymentResultData data = new PaymentResultData();
		data.setPaymentStatus("REJECTED");
		when(payoneCheckoutFacade.executePaymentAndPlaceOrder(eq(cart), any())).thenReturn(data);

		final ResponseEntity<?> response = controller.executePayment(body);

		assertEquals(HttpStatus.PAYMENT_REQUIRED, response.getStatusCode());
		assertEquals("REJECTED", ((PaymentResultWsDTO) response.getBody()).getPaymentStatus());
	}

	@Test
	public void executePayment_passesAllRequestFieldsToFacade()
	{
		final PaymentRequestWsDTO body = new PaymentRequestWsDTO();
		body.setCommerceCaseId(COMMERCE_CASE_ID);
		body.setCheckoutId(CHECKOUT_ID);
		body.setPaymentMethod("SEPA");
		body.setIban("DE123");
		body.setAccountHolder("Jane Doe");
		body.setCreditorId("creditor-1");
		body.setMandateReference("mandate-1");
		body.setDateOfSignature("2026-01-01");
		when(payoneCheckoutFacade.executePaymentAndPlaceOrder(eq(cart), any())).thenReturn(new PaymentResultData());

		controller.executePayment(body);

		final org.mockito.ArgumentCaptor<PaymentRequestData> captor =
				org.mockito.ArgumentCaptor.forClass(PaymentRequestData.class);
		org.mockito.Mockito.verify(payoneCheckoutFacade).executePaymentAndPlaceOrder(eq(cart), captor.capture());
		final PaymentRequestData request = captor.getValue();
		assertEquals(COMMERCE_CASE_ID, request.getCommerceCaseId());
		assertEquals(CHECKOUT_ID, request.getCheckoutId());
		assertEquals("SEPA", request.getPaymentMethod());
		assertEquals("DE123", request.getIban());
		assertEquals("Jane Doe", request.getAccountHolder());
		assertEquals("creditor-1", request.getCreditorId());
		assertEquals("mandate-1", request.getMandateReference());
		assertEquals("2026-01-01", request.getDateOfSignature());
	}

	@Test
	public void executePayment_handlesNullBody()
	{
		when(payoneCheckoutFacade.executePaymentAndPlaceOrder(eq(cart), any()))
				.thenThrow(new IllegalArgumentException("commerceCaseId, checkoutId and paymentMethod are required"));

		final ResponseEntity<?> response = controller.executePayment(null);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
	}

	@Test
	public void executePayment_returns400_onIllegalArgument()
	{
		final PaymentRequestWsDTO body = new PaymentRequestWsDTO();
		when(payoneCheckoutFacade.executePaymentAndPlaceOrder(eq(cart), any()))
				.thenThrow(new IllegalArgumentException("paymentProcessingToken is required for CARD"));

		final ResponseEntity<?> response = controller.executePayment(body);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("paymentProcessingToken is required for CARD", ((ErrorResultWsDTO) response.getBody()).getMessage());
	}

	@Test
	public void executePayment_returns409_onCheckoutMismatch()
	{
		final PaymentRequestWsDTO body = new PaymentRequestWsDTO();
		when(payoneCheckoutFacade.executePaymentAndPlaceOrder(eq(cart), any()))
				.thenThrow(new PayoneCheckoutMismatchException("ids do not match"));

		final ResponseEntity<?> response = controller.executePayment(body);

		assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
	}

	// authorizePayment (strategy-routed path)

	@Test
	public void authorizePayment_returns200WithMappedFields_onSuccess()
	{
		final PaymentDetailsWsDTO body = new PaymentDetailsWsDTO();
		body.setIban("DE123");

		final PayoneAuthorizationResultData data = new PayoneAuthorizationResultData();
		data.setRedirect(true);
		data.setRedirectUrl("https://pcp/redirect");
		data.setPaymentId("payment-1");
		data.setStatus("REDIRECTED");
		when(payoneCheckoutFacade.authorizePayment(eq(cart), eq(771), any())).thenReturn(data);

		final ResponseEntity<?> response = controller.authorizePayment(771, body);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		final PayoneAuthorizationResultWsDTO result = (PayoneAuthorizationResultWsDTO) response.getBody();
		assertTrue(result.isRedirect());
		assertEquals("https://pcp/redirect", result.getRedirectUrl());
		assertEquals("payment-1", result.getPaymentId());
		assertEquals("REDIRECTED", result.getStatus());
	}

	@Test
	public void authorizePayment_handlesNullBody()
	{
		final PayoneAuthorizationResultData data = new PayoneAuthorizationResultData();
		data.setStatus("ACCEPTED");
		when(payoneCheckoutFacade.authorizePayment(eq(cart), eq(840), any())).thenReturn(data);

		final ResponseEntity<?> response = controller.authorizePayment(840, null);

		assertEquals(HttpStatus.OK, response.getStatusCode());
	}

	@Test
	public void authorizePayment_returns400_onIllegalArgument()
	{
		when(payoneCheckoutFacade.authorizePayment(eq(cart), anyInt(), any()))
				.thenThrow(new IllegalArgumentException("SEPA Direct Debit requires an EUR cart"));

		final ResponseEntity<?> response = controller.authorizePayment(771, new PaymentDetailsWsDTO());

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("SEPA Direct Debit requires an EUR cart", ((ErrorResultWsDTO) response.getBody()).getMessage());
	}

	@Test
	public void authorizePayment_returns502_onIllegalState()
	{
		when(payoneCheckoutFacade.authorizePayment(eq(cart), anyInt(), any()))
				.thenThrow(new IllegalStateException("No PAYONE payment strategy for product [999]"));

		final ResponseEntity<?> response = controller.authorizePayment(999, new PaymentDetailsWsDTO());

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
	}

	// handleRedirectCallback

	@Test
	public void handleRedirectCallback_returns200WithMappedFields_onSuccess()
	{
		final PayoneAuthorizationResultData data = new PayoneAuthorizationResultData();
		data.setStatus("ACCEPTED");
		data.setPaymentId("payment-1");
		when(payoneCheckoutFacade.handleRedirectCallback(cart, CHECKOUT_ID)).thenReturn(data);

		final ResponseEntity<?> response = controller.handleRedirectCallback(CHECKOUT_ID);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		final PayoneAuthorizationResultWsDTO result = (PayoneAuthorizationResultWsDTO) response.getBody();
		assertEquals("ACCEPTED", result.getStatus());
		assertEquals("payment-1", result.getPaymentId());
	}

	@Test
	public void handleRedirectCallback_returns409_onCheckoutMismatch()
	{
		when(payoneCheckoutFacade.handleRedirectCallback(cart, CHECKOUT_ID))
				.thenThrow(new PayoneCheckoutMismatchException("checkout mismatch"));

		final ResponseEntity<?> response = controller.handleRedirectCallback(CHECKOUT_ID);

		assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
	}

	// placeOrder (strategy-routed path)

	@Test
	public void placeOrder_returns200WithMappedFields_onSuccess()
	{
		final PayoneCheckoutData data = new PayoneCheckoutData();
		data.setCommerceCaseId(COMMERCE_CASE_ID);
		data.setCheckoutId(CHECKOUT_ID);
		data.setPaymentExecutionId("exec-1");
		data.setPaymentId("payment-1");
		data.setStatus("PAID");
		when(payoneCheckoutFacade.placeOrder(cart)).thenReturn(data);

		final ResponseEntity<?> response = controller.placeOrder();

		assertEquals(HttpStatus.OK, response.getStatusCode());
		final PayoneCheckoutResultWsDTO result = (PayoneCheckoutResultWsDTO) response.getBody();
		assertEquals(COMMERCE_CASE_ID, result.getCommerceCaseId());
		assertEquals(CHECKOUT_ID, result.getCheckoutId());
		assertEquals("exec-1", result.getPaymentExecutionId());
		assertEquals("payment-1", result.getPaymentId());
		assertEquals("PAID", result.getStatus());
	}

	@Test
	public void placeOrder_returns502_onIllegalState()
	{
		when(payoneCheckoutFacade.placeOrder(cart))
				.thenThrow(new IllegalStateException("order placement failed"));

		final ResponseEntity<?> response = controller.placeOrder();

		assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
		assertEquals("order placement failed", ((ErrorResultWsDTO) response.getBody()).getMessage());
	}
}
