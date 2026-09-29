package com.payone.pcp.core.converters;

import com.payone.commerce.platform.lib.models.CancelPaymentRequest;
import de.hybris.bootstrap.annotations.UnitTest;

import com.payone.commerce.platform.lib.models.CancellationReason;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;


@UnitTest
public class CancelPaymentRequestConverterTest
{
	private PcpCancelPaymentRequestConverter converter;

	@Before
	public void setUp()
	{
		converter = new PcpCancelPaymentRequestConverter();
	}

	@Test
	public void shouldConvertWithConsumerRequestReason()
	{
		final CancelPaymentRequest result =
				converter.convert(CancellationReason.CONSUMER_REQUEST);

		assertNotNull(result);
		assertEquals(CancellationReason.CONSUMER_REQUEST, result.getCancellationReason());
	}

	@Test
	public void shouldFailForNullReason()
	{
		assertThrows(NullPointerException.class, () -> converter.convert(null));
	}
}
