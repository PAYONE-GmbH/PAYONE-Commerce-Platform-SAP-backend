package com.payone.pcp.facades.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.payone.commerce.platform.lib.models.ActionType;
import com.payone.commerce.platform.lib.models.CreatePaymentResponse;
import com.payone.commerce.platform.lib.models.MerchantAction;
import com.payone.commerce.platform.lib.models.RedirectData;
import com.payone.commerce.platform.lib.models.StatusValue;

import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.service.impl.PayonePaymentStatusMapper;
import com.payone.pcp.facades.data.PayoneAuthorizationResultData;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.Before;
import org.junit.Test;


@UnitTest
public class PayoneAuthorizationResultTransformerTest
{
	private PayoneAuthorizationResultTransformer transformer;

	@Before
	public void setUp()
	{
		transformer = new PayoneAuthorizationResultTransformer();
	}

	@Test
	public void convertMapsRedirectOutcome()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse()
				.merchantAction(new MerchantAction()
						.actionType(ActionType.REDIRECT)
						.redirectData(new RedirectData().redirectURL("https://redirect.test/3ds")));
		final PaymentExecutionResponse source = new PaymentExecutionResponse(
				"pay-1", "exec-1", StatusValue.REDIRECTED, raw);

		final PayoneAuthorizationResultData result = transformer.convert(source);

		assertTrue(result.isRedirect());
		assertEquals("https://redirect.test/3ds", result.getRedirectUrl());
		assertEquals("pay-1", result.getPaymentId());
		assertEquals(PayonePaymentStatusMapper.WAITING, result.getStatus());
	}

	@Test
	public void convertMapsDirectSuccessOutcome()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse();
		final PaymentExecutionResponse source = new PaymentExecutionResponse(
				"pay-2", "exec-2", StatusValue.CAPTURED, raw);

		final PayoneAuthorizationResultData result = transformer.convert(source);

		assertFalse(result.isRedirect());
		assertNull(result.getRedirectUrl());
		assertEquals("pay-2", result.getPaymentId());
		assertEquals(PayonePaymentStatusMapper.ACCEPTED, result.getStatus());
	}

	@Test
	public void convertMapsPendingOutcome()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse();
		final PaymentExecutionResponse source = new PaymentExecutionResponse(
				"pay-3", "exec-3", StatusValue.PENDING_PAYMENT, raw);

		final PayoneAuthorizationResultData result = transformer.convert(source);

		assertFalse(result.isRedirect());
		assertEquals(PayonePaymentStatusMapper.WAITING, result.getStatus());
	}

	@Test
	public void convertMapsRejectedOutcome()
	{
		final CreatePaymentResponse raw = new CreatePaymentResponse();
		final PaymentExecutionResponse source = new PaymentExecutionResponse(
				null, "exec-4", StatusValue.REJECTED, raw);

		final PayoneAuthorizationResultData result = transformer.convert(source);

		assertFalse(result.isRedirect());
		assertNull(result.getPaymentId());
		assertEquals(PayonePaymentStatusMapper.REJECTED, result.getStatus());
	}

	@Test
	public void convertThrowsForNullSource()
	{
		assertThrows(NullPointerException.class, () -> transformer.convert(null));
	}
}
