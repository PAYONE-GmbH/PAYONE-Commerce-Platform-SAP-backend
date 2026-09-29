package com.payone.pcp.core.strategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.payone.pcp.core.data.PaymentExecutionResponse;
import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.order.AbstractOrderModel;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;


@UnitTest
public class PaymentStrategyRegistryTest
{
	private PaymentStrategyRegistry registry;
	private PayonePaymentStrategy cardStrategy;
	private PayonePaymentStrategy paypalStrategy;
	private PayonePaymentStrategy mastercardStrategy;

	@Before
	public void setUp()
	{
		registry = new PaymentStrategyRegistry();
		cardStrategy = newStub(1);
		paypalStrategy = newStub(840);
		mastercardStrategy = newStub(3);
	}

	@Test
	public void shouldResolveRegisteredStrategy()
	{
		registry.setStrategyBeans(Arrays.asList(cardStrategy, paypalStrategy));
		registry.afterPropertiesSet();

		assertEquals(cardStrategy, registry.getStrategy(1));
		assertEquals(paypalStrategy, registry.getStrategy(840));
	}

	@Test
	public void shouldReturnNullForUnknownProductId()
	{
		registry.setStrategyBeans(Collections.singletonList(cardStrategy));
		registry.afterPropertiesSet();

		assertNull(registry.getStrategy(99999));
	}

	@Test
	public void shouldReturnNullForEmptyRegistry()
	{
		registry.setStrategyBeans(Collections.emptyList());
		registry.afterPropertiesSet();

		assertNull(registry.getStrategy(1));
	}

	@Test
	public void shouldFilterByAllowedProductIds()
	{
		registry.setStrategyBeans(Arrays.asList(cardStrategy, paypalStrategy, mastercardStrategy));
		registry.afterPropertiesSet();

		final Collection<Integer> allowed = Arrays.asList(
				1,
				3);

		assertEquals(cardStrategy, registry.getStrategy(1, allowed));
		assertEquals(mastercardStrategy, registry.getStrategy(3, allowed));
		assertNull("PayPal should not be returned when filtered",
				registry.getStrategy(840, allowed));
	}

	@Test
	public void shouldNotFilterWhenAllowedListIsNull()
	{
		registry.setStrategyBeans(Collections.singletonList(cardStrategy));
		registry.afterPropertiesSet();

		assertEquals(cardStrategy, registry.getStrategy(1, null));
	}

	@Test
	public void shouldNotFilterWhenAllowedListIsEmpty()
	{
		registry.setStrategyBeans(Collections.singletonList(cardStrategy));
		registry.afterPropertiesSet();

		assertEquals(cardStrategy,
				registry.getStrategy(1,
						Collections.emptyList()));
	}

	@Test
	public void shouldReturnAllStrategies()
	{
		registry.setStrategyBeans(Arrays.asList(cardStrategy, paypalStrategy));
		registry.afterPropertiesSet();

		final Map<Integer, PayonePaymentStrategy> all = registry.getAllStrategies();
		assertNotNull(all);
		assertEquals(2, all.size());
		assertEquals(cardStrategy, all.get(1));
	}

	@Test
	public void shouldReturnEmptyMapForEmptyRegistry()
	{
		registry.setStrategyBeans(null);
		registry.afterPropertiesSet();

		assertNotNull(registry.getAllStrategies());
		assertEquals(0, registry.getAllStrategies().size());
	}

	@Test
	public void shouldHandleDuplicateRegistrationLastWins()
	{
		final PayonePaymentStrategy first = newStub(1);
		final PayonePaymentStrategy second = newStub(1);
		registry.setStrategyBeans(Arrays.asList(first, second));
		registry.afterPropertiesSet();

		assertEquals(second, registry.getStrategy(1));
	}

	@Test
	public void shouldHandleNullStrategyBeans()
	{
		registry.setStrategyBeans(null);
		registry.afterPropertiesSet();

		assertNull(registry.getStrategy(1));
	}

	/**
	 * Card scheme product IDs (Visa=1, Amex=2, Mastercard=3, Diners=132) must each resolve
	 * to a distinct strategy - the registry must never collapse them onto a single legacy "CARD" entry.
	 */
	@Test
	public void shouldResolveDistinctStrategiesPerCardScheme()
	{
		final PayonePaymentStrategy visa = newStub(1);
		final PayonePaymentStrategy amex = newStub(2);
		final PayonePaymentStrategy mastercard = newStub(3);
		final PayonePaymentStrategy diners = newStub(132);

		registry.setStrategyBeans(Arrays.asList(visa, amex, mastercard, diners));
		registry.afterPropertiesSet();

		assertSame(visa, registry.getStrategy(1));
		assertSame(amex, registry.getStrategy(2));
		assertSame(mastercard, registry.getStrategy(3));
		assertSame(diners, registry.getStrategy(132));
		assertEquals(4, registry.getAllStrategies().size());
	}

	private static PayonePaymentStrategy newStub(final int productId)
	{
		return new PayonePaymentStrategy()
		{
			@Override
			public int getPaymentProductId()
			{
				return productId;
			}

			@Override
			public PaymentExecutionResponse authorize(final PayoneConfigurationModel config,
					final String commerceCaseId, final String checkoutId,
					final AbstractOrderModel order, final PayonePaymentInfoModel paymentInfo)
			{
				return null;
			}

			@Override
			public PaymentExecutionResponse capture(final PayoneConfigurationModel config,
					final String commerceCaseId, final String checkoutId,
					final String paymentExecutionId, final Long amount)
			{
				return null;
			}

			@Override
			public PaymentExecutionResponse cancel(final PayoneConfigurationModel config,
					final String commerceCaseId, final String checkoutId,
					final String paymentExecutionId)
			{
				return null;
			}

			@Override
			public PaymentExecutionResponse refund(final PayoneConfigurationModel config,
					final String commerceCaseId, final String checkoutId,
					final String paymentExecutionId, final com.payone.commerce.platform.lib.models.AmountOfMoney amountOfMoney)
			{
				return null;
			}

			@Override
			public PaymentExecutionResponse complete(final PayoneConfigurationModel config,
					final String commerceCaseId, final String checkoutId,
					final String paymentExecutionId)
			{
				return null;
			}

			@Override
			public PaymentExecutionResponse pause(final PayoneConfigurationModel config,
					final String commerceCaseId, final String checkoutId,
					final String paymentExecutionId)
			{
				return null;
			}

			@Override
			public PaymentExecutionResponse refresh(final PayoneConfigurationModel config,
					final String commerceCaseId, final String checkoutId,
					final String paymentExecutionId)
			{
				return null;
			}
		};
	}

}
