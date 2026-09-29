package com.payone.pcp.core.converters;

import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;

import com.payone.commerce.platform.lib.models.AmountOfMoney;
import com.payone.commerce.platform.lib.models.CartItemInput;
import com.payone.commerce.platform.lib.models.OrderLineDetailsInput;
import com.payone.commerce.platform.lib.models.ProductType;
import com.payone.commerce.platform.lib.models.ShoppingCartInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;



/**
 * Converts SAP order entries to PCP ShoppingCartInput. Order entries carry only product lines -
 * delivery and other order-level charges aren't represented as entries - so a mismatch between
 * item total and order total is reconciled with a single SHIPMENT line. PCP requires the cart's
 * item total not to exceed Checkout.amountOfMoney (docs.commerce.payone.com/api-reference).
 * Stateless; null input yields null SDK fields.
 */
public class PcpShoppingCartConverter
{
	private static final Logger LOG = LoggerFactory.getLogger(PcpShoppingCartConverter.class);

	private final PcpLineItemConverter lineItemConverter;
	private final PcpAmountOfMoneyConverter amountOfMoneyConverter;

	public PcpShoppingCartConverter(final PcpLineItemConverter lineItemConverter,
			final PcpAmountOfMoneyConverter amountOfMoneyConverter)
	{
		this.lineItemConverter = lineItemConverter;
		this.amountOfMoneyConverter = amountOfMoneyConverter;
	}

	/**
	 * Converts order entries to a ShoppingCartInput. Entries without a product (e.g. shipping
	 * cost lines) are skipped - use the PCP SDK's own shipping fields instead. Appends a
	 * SHIPMENT line if the item total falls short of the order's amountOfMoney.
	 *
	 * @param order the SAP order, must not be null
	 * @return ShoppingCartInput with items
	 */
	public ShoppingCartInput convert(final AbstractOrderModel order)
	{
		Objects.requireNonNull(order, "order must not be null");

		final List<CartItemInput> items = new ArrayList<>(order.getEntries().stream()
				.filter(Objects::nonNull)
				.map(this::convertOrWarn)
				.filter(Objects::nonNull)
				.collect(Collectors.toList()));

		reconcileWithAmount(order, items);

		return new ShoppingCartInput()
				.items(items.isEmpty() ? Collections.emptyList() : items);
	}

	/** Converts one entry, or warns and returns null if it has no product (shipping/handling line). */
	private CartItemInput convertOrWarn(final AbstractOrderEntryModel entry)
	{
		final CartItemInput item = lineItemConverter.convert(entry);
		if (item == null)
		{
			LOG.warn("Skipping order entry [{}] - no ProductModel (shipping/handling cost). "
					+ "Use the PCP SDK shipping input fields instead.",
					entry.getEntryNumber());
		}
		return item;
	}

	/** Appends a SHIPMENT line for the gap between amountOfMoney and the summed item total. */
	private void reconcileWithAmount(final AbstractOrderModel order, final List<CartItemInput> items)
	{
		final AmountOfMoney amount = amountOfMoneyConverter.convert(order);
		if (amount == null)
		{
			return;
		}

		final long targetAmount = amount.getAmount();
		long itemsTotal = 0L;
		for (final CartItemInput item : items)
		{
			if (item == null || item.getOrderLineDetails() == null)
			{
				continue;
			}
			final OrderLineDetailsInput details = item.getOrderLineDetails();
			final long quantity = details.getQuantity() != null ? details.getQuantity() : 0L;
			final long price = details.getProductPrice() != null ? details.getProductPrice() : 0L;
			itemsTotal += quantity * price;
		}

		final long remainder = targetAmount - itemsTotal;
		if (remainder != 0L)
		{
			final OrderLineDetailsInput remainderDetails = new OrderLineDetailsInput()
					.productName("Shipping & other charges")
					.productPrice(remainder)
					.quantity(1L)
					.taxAmount(0L)
					.productType(ProductType.SHIPMENT);

			items.add(new CartItemInput().orderLineDetails(remainderDetails));
		}
	}
}
