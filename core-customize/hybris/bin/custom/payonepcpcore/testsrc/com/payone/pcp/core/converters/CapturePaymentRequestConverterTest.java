package com.payone.pcp.core.converters;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;


@UnitTest
public class CapturePaymentRequestConverterTest
{
	private PcpCapturePaymentRequestConverter converter;

	@Before
	public void setUp()
	{
		converter = new PcpCapturePaymentRequestConverter();
	}

	@Test
	public void shouldConvertWithAmountAndFinal()
	{
		final com.payone.commerce.platform.lib.models.CapturePaymentRequest result =
				converter.convert(1000L, Boolean.TRUE);

		assertNotNull(result);
		assertEquals(Long.valueOf(1000L), result.getAmount());
		assertEquals(Boolean.TRUE, result.getIsFinal());
	}

	@Test
	public void shouldDefaultIsFinalToFalse()
	{
		final com.payone.commerce.platform.lib.models.CapturePaymentRequest result =
				converter.convert(500L, null);

		assertNotNull(result);
		assertEquals(Long.valueOf(500L), result.getAmount());
		assertEquals(Boolean.FALSE, result.getIsFinal());
	}

	@Test
	public void shouldFaillForNullAmount()
	{
		assertThrows(NullPointerException.class, () -> converter.convert(null, Boolean.TRUE));
	}

	@Test
	public void shouldConvertZeroAmount()
	{
		final com.payone.commerce.platform.lib.models.CapturePaymentRequest result =
				converter.convert(0L, Boolean.FALSE);

		assertNotNull(result);
		assertEquals(Long.valueOf(0L), result.getAmount());
		assertEquals(Boolean.FALSE, result.getIsFinal());
	}
}
