package com.payone.pcp.core.converters.paymentmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.payone.commerce.platform.lib.models.MandateRecurrenceType;
import com.payone.commerce.platform.lib.models.SepaDirectDebitPaymentMethodSpecificInput;
import com.payone.commerce.platform.lib.models.SepaDirectDebitPaymentProduct771SpecificInput;

import de.hybris.bootstrap.annotations.UnitTest;

import com.payone.pcp.core.model.PayoneConfigurationModel;
import com.payone.pcp.core.model.PayoneMandateModel;
import com.payone.pcp.core.model.PayonePaymentInfoModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@UnitTest
public class SepaDirectDebitPaymentMethodSpecificInputBuilderTest
{
	@Mock
	private PayonePaymentInfoModel paymentInfo;

	@Mock
	private PayoneMandateModel mandate;

	private PcpSepaDirectDebitPaymentMethodSpecificInputBuilder builder;

	private AutoCloseable mocks;

	@Before
	public void setUp()
	{
		mocks = MockitoAnnotations.openMocks(this);
		builder = new PcpSepaDirectDebitPaymentMethodSpecificInputBuilder();
	}

	@After
	public void tearDown() throws Exception
	{
		mocks.close();
	}

	@Test
	public void shouldBuildSepaInputFromMandate()
	{
		when(paymentInfo.getMandate()).thenReturn(mandate);
		when(mandate.getIban()).thenReturn("DE89370400440532013000");
		when(mandate.getAccountHolder()).thenReturn("Jane Doe");
		when(mandate.getDateOfSignature()).thenReturn("20260101");
		when(mandate.getUniqueMandateReference()).thenReturn("MANDATE-001");
		when(mandate.getCreditorId()).thenReturn("DE98ZZZ09999999999");

		final SepaDirectDebitPaymentMethodSpecificInput result =
				builder.build(paymentInfo, new PayoneConfigurationModel());

		assertNotNull(result);
		assertEquals(Integer.valueOf(771), result.getPaymentProductId().getValue());

		final SepaDirectDebitPaymentProduct771SpecificInput productInput = result.getPaymentProduct771SpecificInput();
		assertNotNull(productInput);
		assertNotNull(productInput.getMandate());
		assertEquals("DE89370400440532013000", productInput.getMandate().getBankAccountIban().getIban());
		assertEquals("Jane Doe", productInput.getMandate().getBankAccountIban().getAccountHolder());
		assertEquals("20260101", productInput.getMandate().getDateOfSignature());
		assertEquals("MANDATE-001", productInput.getMandate().getUniqueMandateReference());
		assertEquals("DE98ZZZ09999999999", productInput.getMandate().getCreditorId());
		assertEquals(MandateRecurrenceType.UNIQUE, productInput.getMandate().getRecurrenceType());
	}

	@Test
	public void shouldFailForNullPaymentInfo()
	{
		assertThrows(NullPointerException.class, () -> builder.build(null, new PayoneConfigurationModel()));
	}

	@Test
	public void shouldFailForNullMandate()
	{
		when(paymentInfo.getMandate()).thenReturn(null);

		assertThrows(NullPointerException.class, () -> builder.build(paymentInfo, new PayoneConfigurationModel()));
	}
}
