/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.facades;

/**
 * Raised when a payment operation on a PayoneCheckout is refused
 * ({@link Kind#REJECTED}: nothing was executed at PCP, safe to retry once the
 * cause is fixed) or when the PCP call ended without a readable answer
 * ({@link Kind#INDETERMINATE}: the outcome at PCP is unknown and must be
 * reconciled before any retry).
 */
public class PayoneCheckoutOperationException extends RuntimeException
{
	public enum Kind
	{
		REJECTED,
		INDETERMINATE
	}

	private final Kind kind;

	public PayoneCheckoutOperationException(final Kind kind, final String message)
	{
		super(message);
		this.kind = kind;
	}

	public PayoneCheckoutOperationException(final Kind kind, final String message, final Throwable cause)
	{
		super(message, cause);
		this.kind = kind;
	}

	public Kind getKind()
	{
		return kind;
	}
}
