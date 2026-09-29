package com.payone.pcp.core.data;

import com.payone.commerce.platform.lib.models.ActionType;
import com.payone.commerce.platform.lib.models.CreatePaymentResponse;
import com.payone.commerce.platform.lib.models.StatusValue;

import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;



/**
 * Normalizes the per-operation SDK response types (CreatePaymentResponse, CapturePaymentResponse,
 * CancelPaymentResponse, ...), which share no common supertype beyond Serializable, into one shape
 * for the facade and strategy layers.
 */
public class PaymentExecutionResponse implements Serializable
{
	private final String paymentId;
	private final String paymentExecutionId;
	private final StatusValue status;
	private final Serializable rawResponse;

	/** All parameters may be null: not every operation returns a paymentId, executionId, or status. */
	public PaymentExecutionResponse(
			final String paymentId,
			final String paymentExecutionId,
			final StatusValue status,
			final Serializable rawResponse)
	{
		this.paymentId = paymentId;
		this.paymentExecutionId = paymentExecutionId;
		this.status = status;
		this.rawResponse = rawResponse;
	}

	public String getPaymentId()
	{
		return paymentId;
	}

	public String getPaymentExecutionId()
	{
		return paymentExecutionId;
	}

	public StatusValue getStatus()
	{
		return status;
	}

	/** The raw SDK response object; cast to the specific type for fields not exposed here. */
	public Serializable getRawResponse()
	{
		return rawResponse;
	}

	/** True only for a CreatePaymentResponse with a REDIRECT MerchantAction and a non-blank URL. */
	public boolean hasRedirectAction()
	{
		return getRedirectUrl() != null;
	}

	/** The redirect URL when {@link #hasRedirectAction()} is true, else null. */
	public String getRedirectUrl()
	{
		if (!(rawResponse instanceof CreatePaymentResponse createPaymentResponse))
		{
			return null;
		}
		if (createPaymentResponse.getMerchantAction() == null
				|| createPaymentResponse.getMerchantAction().getActionType() != ActionType.REDIRECT
				|| createPaymentResponse.getMerchantAction().getRedirectData() == null)
		{
			return null;
		}
		final String url = createPaymentResponse.getMerchantAction().getRedirectData().getRedirectURL();
		return StringUtils.isNotBlank(url) ? url : null;
	}

	/** True for a create-payment outcome that settled directly: status CAPTURED or AUTHORIZATION_REQUESTED, no redirect. */
	public boolean isDirectSuccess()
	{
		if (!(rawResponse instanceof CreatePaymentResponse) || status == null || hasRedirectAction())
		{
			return false;
		}
		return status == StatusValue.CAPTURED || status == StatusValue.AUTHORIZATION_REQUESTED;
	}
}
