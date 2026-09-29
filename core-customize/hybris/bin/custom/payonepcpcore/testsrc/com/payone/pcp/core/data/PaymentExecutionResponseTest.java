package com.payone.pcp.core.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.payone.commerce.platform.lib.models.ActionType;
import com.payone.commerce.platform.lib.models.CapturePaymentResponse;
import com.payone.commerce.platform.lib.models.CreatePaymentResponse;
import com.payone.commerce.platform.lib.models.MerchantAction;
import com.payone.commerce.platform.lib.models.RedirectData;
import com.payone.commerce.platform.lib.models.StatusValue;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.Test;


@UnitTest
public class PaymentExecutionResponseTest
{
	private static final String REDIRECT_URL = "https://payone.redirect.test/3ds";
	private static final String PAYMENT_ID = "pay_123";
	private static final String EXECUTION_ID = "exec_456";

	// hasRedirectAction

	@Test
	public void hasRedirectActionReturnsTrueWhenCreatePaymentHasRedirect()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.merchantAction(new MerchantAction()
						.actionType(ActionType.REDIRECT)
						.redirectData(new RedirectData().redirectURL(REDIRECT_URL)));
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.REDIRECTED, raw);
		assertTrue(resp.hasRedirectAction());
	}

	@Test
	public void hasRedirectActionReturnsFalseForNonCreatePayment()
	{
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CAPTURED, new CapturePaymentResponse());
		assertFalse(resp.hasRedirectAction());
	}

	@Test
	public void hasRedirectActionReturnsFalseWhenRawResponseIsNull()
	{
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CREATED, null);
		assertFalse(resp.hasRedirectAction());
	}

	@Test
	public void hasRedirectActionReturnsFalseWhenMerchantActionIsNull()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse();
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CREATED, raw);
		assertFalse(resp.hasRedirectAction());
	}

	@Test
	public void hasRedirectActionReturnsFalseWhenActionTypeIsNotRedirect()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.merchantAction(new MerchantAction()
						.actionType(ActionType.SHOW_FORM)
						.redirectData(new RedirectData().redirectURL(REDIRECT_URL)));
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CREATED, raw);
		assertFalse(resp.hasRedirectAction());
	}

	@Test
	public void hasRedirectActionReturnsFalseWhenRedirectDataIsNull()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.merchantAction(new MerchantAction()
						.actionType(ActionType.REDIRECT));
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.REDIRECTED, raw);
		assertFalse(resp.hasRedirectAction());
	}

	@Test
	public void hasRedirectActionReturnsFalseWhenRedirectUrlIsEmpty()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.merchantAction(new MerchantAction()
						.actionType(ActionType.REDIRECT)
						.redirectData(new RedirectData().redirectURL("")));
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CREATED, raw);
		assertFalse(resp.hasRedirectAction());
	}

	@Test
	public void hasRedirectActionReturnsFalseWhenRedirectUrlIsBlank()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.merchantAction(new MerchantAction()
						.actionType(ActionType.REDIRECT)
						.redirectData(new RedirectData().redirectURL("   ")));
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CREATED, raw);
		assertFalse(resp.hasRedirectAction());
	}

	// getRedirectUrl

	@Test
	public void getRedirectUrlReturnsUrlWhenHasRedirect()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.merchantAction(new MerchantAction()
						.actionType(ActionType.REDIRECT)
						.redirectData(new RedirectData().redirectURL(REDIRECT_URL)));
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.REDIRECTED, raw);
		assertEquals(REDIRECT_URL, resp.getRedirectUrl());
	}

	@Test
	public void getRedirectUrlReturnsNullForNonCreatePayment()
	{
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CAPTURED, new CapturePaymentResponse());
		assertNull(resp.getRedirectUrl());
	}

	@Test
	public void getRedirectUrlReturnsNullWhenNoRedirectAction()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse();
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CREATED, raw);
		assertNull(resp.getRedirectUrl());
	}

	@Test
	public void getRedirectUrlReturnsNullWhenRawResponseIsNull()
	{
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CREATED, null);
		assertNull(resp.getRedirectUrl());
	}

	// isDirectSuccess

	@Test
	public void isDirectSuccessReturnsTrueForCapturedWithoutRedirect()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.paymentExecutionId(java.util.UUID.randomUUID());
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CAPTURED, raw);
		assertTrue(resp.isDirectSuccess());
	}

	@Test
	public void isDirectSuccessReturnsTrueForAuthorizationRequested()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.paymentExecutionId(java.util.UUID.randomUUID());
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.AUTHORIZATION_REQUESTED, raw);
		assertTrue(resp.isDirectSuccess());
	}

	@Test
	public void isDirectSuccessReturnsFalseForCreatedStatus()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse();
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CREATED, raw);
		assertFalse(resp.isDirectSuccess());
	}

	@Test
	public void isDirectSuccessReturnsFalseForCaptureRequested()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse();
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CAPTURE_REQUESTED, raw);
		assertFalse(resp.isDirectSuccess());
	}

	@Test
	public void isDirectSuccessReturnsFalseWhenRedirectNeeded()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.merchantAction(new MerchantAction()
						.actionType(ActionType.REDIRECT)
						.redirectData(new RedirectData().redirectURL(REDIRECT_URL)));
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.REDIRECTED, raw);
		assertFalse(resp.isDirectSuccess());
	}

	@Test
	public void isDirectSuccessReturnsFalseForPendingStatus()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse();
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.PENDING_PAYMENT, raw);
		assertFalse(resp.isDirectSuccess());
	}

	@Test
	public void isDirectSuccessReturnsFalseForNullStatus()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse();
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, null, raw);
		assertFalse(resp.isDirectSuccess());
	}

	@Test
	public void isDirectSuccessReturnsFalseForNonCreatePayment()
	{
		final PaymentExecutionResponse resp = new PaymentExecutionResponse(
				PAYMENT_ID, EXECUTION_ID, StatusValue.CAPTURED, new CapturePaymentResponse());
		assertFalse(resp.isDirectSuccess());
	}
}
