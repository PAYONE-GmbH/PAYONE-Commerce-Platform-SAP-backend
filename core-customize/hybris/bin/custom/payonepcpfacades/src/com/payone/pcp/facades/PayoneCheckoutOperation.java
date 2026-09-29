/*
 * Copyright (c) 2026 PAYONE GmbH
 */
package com.payone.pcp.facades;

/**
 * Payment operations an operator may trigger on a single PayoneCheckout
 * (Backoffice actions). Cancel is out of scope.
 */
public enum PayoneCheckoutOperation
{
	CAPTURE,
	REFUND
}
