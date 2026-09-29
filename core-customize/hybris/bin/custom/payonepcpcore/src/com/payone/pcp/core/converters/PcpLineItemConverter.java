package com.payone.pcp.core.converters;

import com.payone.commerce.platform.lib.models.CartItemInput;
import com.payone.commerce.platform.lib.models.OrderLineDetailsInput;
import com.payone.commerce.platform.lib.models.ProductType;
import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.util.TaxValue;

import java.math.BigDecimal;
import java.util.Objects;


/** Converts SAP AbstractOrderEntryModel to PCP SDK CartItemInput. */
public class PcpLineItemConverter
{
	/**
	 * Converts a single SAP order entry to a PCP CartItemInput. Entries without a ProductModel
	 * (e.g. shipping cost entries) return null; the caller must filter these out and log a warning.
	 *
	 * @param entry the SAP order entry; must not be null
	 * @return PCP CartItemInput, or null if the entry has no product
	 */
	public CartItemInput convert(final AbstractOrderEntryModel entry)
	{
		Objects.requireNonNull(entry, "entry must not be null");

		final ProductModel product = entry.getProduct();
		if (product == null)
		{
			return null;
		}

		final long productPrice = BigDecimal.valueOf(entry.getBasePrice())
				.movePointRight(2)
				.longValue();

		final long taxAmount = entry.getTaxValues() != null
				? BigDecimal.valueOf(entry.getTaxValues().stream()
						.mapToDouble(TaxValue::getAppliedValue)
						.sum())
						.movePointRight(2)
						.longValue()
				: 0L;

		final OrderLineDetailsInput details = new OrderLineDetailsInput()
				.productCode(PcpFieldTrimmer.trim(product.getCode(), 50))
				.productName(PcpFieldTrimmer.trim(product.getName(), 116))
				.quantity(entry.getQuantity() != null ? entry.getQuantity() : 0L)
				.productPrice(productPrice)
				.taxAmount(taxAmount)
				.productType(ProductType.GOODS);

		return new CartItemInput()
				.orderLineDetails(details);
	}
}
