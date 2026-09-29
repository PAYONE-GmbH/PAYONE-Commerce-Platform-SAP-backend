package com.payone.pcp.core.converters;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.c2l.CurrencyModel;

import com.payone.commerce.platform.lib.models.AmountOfMoney;

import java.math.BigDecimal;
import java.util.Objects;


/** Converts SAP AbstractOrderModel to PCP SDK AmountOfMoney. */
public class PcpAmountOfMoneyConverter
{
	/**
	 * Converts an order's total price and currency to a PCP AmountOfMoney.
	 *
	 * @param order the SAP order; must not be null, with a non-null currency
	 * @return PCP AmountOfMoney
	 */
	public AmountOfMoney convert(final AbstractOrderModel order)
	{
		Objects.requireNonNull(order, "order must not be null");

		final CurrencyModel currency = order.getCurrency();
		Objects.requireNonNull(currency, "currency must not be null");

		// PCP amount is in minor units (cents).
		final long amount = BigDecimal.valueOf(order.getTotalPrice())
				.movePointRight(2)
				.longValue();

		return new AmountOfMoney()
				.amount(amount)
				.currencyCode(currency.getIsocode());
	}
}
