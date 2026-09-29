package com.payone.pcp.core.converters;

import com.payone.commerce.platform.lib.models.RefundRequest;
import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;


@UnitTest
public class RefundPaymentRequestConverterTest {
    private PcpRefundPaymentRequestConverter converter;

    @Before
    public void setUp() {
        converter = new PcpRefundPaymentRequestConverter();
    }

    @Test
    public void shouldConvertWithAmountOnly() {
        final RefundRequest result = converter.convert(2000L);

        assertNotNull(result);
        assertNotNull(result.getAmountOfMoney());
        assertEquals(Long.valueOf(2000L), result.getAmountOfMoney().getAmount());
    }

    @Test
    public void shouldFailForNullAmount() {
        assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void shouldConvertZeroAmount() {
        final RefundRequest result = converter.convert(0L);

        assertNotNull(result);
        assertEquals(Long.valueOf(0L), result.getAmountOfMoney().getAmount());
    }
}
