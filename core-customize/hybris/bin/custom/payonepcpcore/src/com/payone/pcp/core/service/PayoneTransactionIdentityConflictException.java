package com.payone.pcp.core.service;

/**
 * Thrown by {@code PayoneTransactionService.getOrCreatePaymentTransaction()} when the
 * commerce case ID / checkout ID cannot be resolved to a single existing PaymentTransaction:
 * either more than one candidate matches, or a candidate matches on one PCP identity dimension
 * but conflicts on the other. Fails closed rather than merging distinct PCP identities or
 * creating a duplicate PaymentTransaction row.
 */
public class PayoneTransactionIdentityConflictException extends IllegalStateException
{
	public PayoneTransactionIdentityConflictException(final String message)
	{
		super(message);
	}
}
