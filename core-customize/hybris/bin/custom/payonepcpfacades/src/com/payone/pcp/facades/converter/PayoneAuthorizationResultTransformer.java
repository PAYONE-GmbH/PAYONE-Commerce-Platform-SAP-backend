package com.payone.pcp.facades.converter;

import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.service.impl.PayonePaymentStatusMapper;
import com.payone.pcp.facades.data.PayoneAuthorizationResultData;

import java.util.Objects;


/**
 * Maps the core PaymentExecutionResponse to the frontend-facing PayoneAuthorizationResultData.
 * Reads only the normalized helpers core exposes, never SDK types; raw PCP status is mapped by
 * PayonePaymentStatusMapper. Not an AbstractConverter: the source is a service response, not a
 * model. Stateless, safe as a singleton bean.
 */
public class PayoneAuthorizationResultTransformer
{
	/**
	 * @param source the normalized PCP payment-execution response; must not be null.
	 * @return the frontend-facing authorization result.
	 */
	public PayoneAuthorizationResultData convert(final PaymentExecutionResponse source)
	{
		Objects.requireNonNull(source, "source must not be null");

		final PayoneAuthorizationResultData result = new PayoneAuthorizationResultData();
		result.setRedirect(source.hasRedirectAction());
		result.setRedirectUrl(source.getRedirectUrl());
		result.setPaymentId(source.getPaymentId());
		result.setStatus(PayonePaymentStatusMapper.toTransactionStatus(source.getStatus()));
		return result;
	}
}
