package com.payone.pcp.webhook.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payone.pcp.core.model.PayoneCheckoutModel;
import com.payone.pcp.core.model.PayoneCommerceCaseModel;
import com.payone.pcp.webhook.dao.PayoneWebhookEventDao;
import com.payone.pcp.webhook.dto.PayoneWebhookCheckoutDto;
import com.payone.pcp.webhook.dto.PayoneWebhookCommerceCaseDto;
import com.payone.pcp.webhook.dto.PayoneWebhookEventDto;
import com.payone.pcp.webhook.dto.PayoneWebhookExecutionDto;
import com.payone.pcp.webhook.dto.PayoneWebhookStatusObjectDto;
import com.payone.pcp.webhook.enums.PayoneWebhookEventType;
import com.payone.pcp.webhook.service.data.PayoneWebhookTarget;

import de.hybris.bootstrap.annotations.UnitTest;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins which id each event domain resolves a local checkout by, the refund/payout
 * full-id-then-stripped-suffix fallback, the paymentExecutionId-then-paymentId fallback, and
 * that the envelope's own commerceCase always wins over one inherited from a checkout.
 */
@ExtendWith(MockitoExtension.class)
@UnitTest
class DefaultPayoneWebhookTargetResolverUnitTest
{
	@Mock
	private PayoneWebhookEventDao payoneWebhookEventDao;

	private DefaultPayoneWebhookTargetResolver resolver;

	@BeforeEach
	void setUp()
	{
		resolver = new DefaultPayoneWebhookTargetResolver();
		resolver.setPayoneWebhookEventDao(payoneWebhookEventDao);

		lenient().when(payoneWebhookEventDao.findCommerceCaseById(org.mockito.ArgumentMatchers.any()))
				.thenReturn(Optional.empty());
	}

	// resolveCheckout by domain

	@Test
	void testResolve_whenDomainIsCommerceCase_thenReadsTheCommerceCaseDirectlyWithoutResolvingACheckout()
	{
		final PayoneWebhookCommerceCaseDto caseDto = new PayoneWebhookCommerceCaseDto();
		caseDto.setId("case-1");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setCommerceCase(caseDto);

		final PayoneCommerceCaseModel commerceCase = new PayoneCommerceCaseModel();
		when(payoneWebhookEventDao.findCommerceCaseById("case-1")).thenReturn(Optional.of(commerceCase));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.COMMERCE_CASE_CREATED);

		assertThat(target.hasCheckout()).isFalse();
		assertThat(target.getCommerceCase()).isSameAs(commerceCase);
	}

	@Test
	void testResolve_whenDomainIsCheckout_thenResolvesByCheckoutId()
	{
		final PayoneWebhookCheckoutDto checkoutDto = new PayoneWebhookCheckoutDto();
		checkoutDto.setId("checkout-1");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setCheckout(checkoutDto);

		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		when(payoneWebhookEventDao.findCheckoutById("checkout-1")).thenReturn(Optional.of(checkout));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.CHECKOUT_UPDATED);

		assertThat(target.getCheckout()).isSameAs(checkout);
	}

	@Test
	void testResolve_whenDomainIsPayment_thenResolvesByPaymentId()
	{
		final PayoneWebhookStatusObjectDto payment = new PayoneWebhookStatusObjectDto();
		payment.setId("payment-1");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setPayment(payment);

		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		when(payoneWebhookEventDao.findCheckoutByPaymentId("payment-1")).thenReturn(Optional.of(checkout));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.PAYMENT_CAPTURED);

		assertThat(target.getCheckout()).isSameAs(checkout);
	}

	// domain present but the envelope carries no matching member

	@Test
	void testResolve_whenCheckoutDomainHasNoCheckoutMember_thenResolvesNothingWithoutQuerying()
	{
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.CHECKOUT_UPDATED);

		assertThat(target.hasCheckout()).isFalse();
		verify(payoneWebhookEventDao, never()).findCheckoutById(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void testResolve_whenPaymentDomainHasNoPaymentMember_thenResolvesNothingWithoutQuerying()
	{
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.PAYMENT_CAPTURED);

		assertThat(target.hasCheckout()).isFalse();
		verify(payoneWebhookEventDao, never()).findCheckoutByPaymentId(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void testResolve_whenRefundDomainHasNoRefundMember_thenResolvesNothingWithoutQuerying()
	{
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.REFUND_REFUNDED);

		assertThat(target.hasCheckout()).isFalse();
		verify(payoneWebhookEventDao, never()).findCheckoutByPaymentId(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void testResolve_whenPaymentExecutionDomainHasNoExecutionMember_thenResolvesNothingWithoutQuerying()
	{
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.PAYMENT_EXECUTION_PAYMENT_CAPTURED);

		assertThat(target.hasCheckout()).isFalse();
		verify(payoneWebhookEventDao, never()).findCheckoutByPaymentExecutionId(org.mockito.ArgumentMatchers.any());
		verify(payoneWebhookEventDao, never()).findCheckoutByPaymentId(org.mockito.ArgumentMatchers.any());
	}

	// refund/payout: full id first, then stripped suffix

	@Test
	void testResolve_whenDomainIsRefund_thenMatchesTheFullIdFirst()
	{
		final PayoneWebhookStatusObjectDto refund = new PayoneWebhookStatusObjectDto();
		refund.setId("3066019730_1");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setRefund(refund);

		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		when(payoneWebhookEventDao.findCheckoutByPaymentId("3066019730_1")).thenReturn(Optional.of(checkout));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.REFUND_REFUNDED);

		assertThat(target.getCheckout()).isSameAs(checkout);
		verify(payoneWebhookEventDao, never()).findCheckoutByPaymentId("3066019730");
	}

	@Test
	void testResolve_whenRefundFullIdDoesNotMatch_thenFallsBackToTheIdBeforeTheLastUnderscore()
	{
		final PayoneWebhookStatusObjectDto refund = new PayoneWebhookStatusObjectDto();
		refund.setId("3066019730_1");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setRefund(refund);

		when(payoneWebhookEventDao.findCheckoutByPaymentId("3066019730_1")).thenReturn(Optional.empty());
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		when(payoneWebhookEventDao.findCheckoutByPaymentId("3066019730")).thenReturn(Optional.of(checkout));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.REFUND_REFUNDED);

		assertThat(target.getCheckout()).isSameAs(checkout);
	}

	@Test
	void testResolve_whenPayoutIdHasNoUnderscoreSuffix_thenDoesNotRetry()
	{
		final PayoneWebhookStatusObjectDto payout = new PayoneWebhookStatusObjectDto();
		payout.setId("3066019730");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setPayout(payout);

		when(payoneWebhookEventDao.findCheckoutByPaymentId("3066019730")).thenReturn(Optional.empty());

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.PAYOUT_CREATED);

		assertThat(target.hasCheckout()).isFalse();
		verify(payoneWebhookEventDao, org.mockito.Mockito.times(1)).findCheckoutByPaymentId("3066019730");
	}

	// payment_execution / payment_information: execution id first, then payment id

	@Test
	void testResolve_whenDomainIsPaymentExecution_thenTriesExecutionIdBeforePaymentId()
	{
		final PayoneWebhookExecutionDto execution = new PayoneWebhookExecutionDto();
		execution.setId("exec-1");
		execution.setPaymentId("payment-1");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setPaymentExecution(execution);

		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		when(payoneWebhookEventDao.findCheckoutByPaymentExecutionId("exec-1")).thenReturn(Optional.of(checkout));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.PAYMENT_EXECUTION_PAYMENT_CAPTURED);

		assertThat(target.getCheckout()).isSameAs(checkout);
		verify(payoneWebhookEventDao, never()).findCheckoutByPaymentId(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void testResolve_whenPaymentInformationExecutionIdMisses_thenFallsBackToPaymentId()
	{
		final PayoneWebhookExecutionDto execution = new PayoneWebhookExecutionDto();
		execution.setId("exec-1");
		execution.setPaymentId("payment-1");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setPaymentInformation(execution);

		when(payoneWebhookEventDao.findCheckoutByPaymentExecutionId("exec-1")).thenReturn(Optional.empty());
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		when(payoneWebhookEventDao.findCheckoutByPaymentId("payment-1")).thenReturn(Optional.of(checkout));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.PAYMENT_INFORMATION_PAYMENT_CAPTURED);

		assertThat(target.getCheckout()).isSameAs(checkout);
	}

	/**
	 * PayoneCheckout.paymentId isn't written until order placement, so the domain lookup
	 * misses and the event would otherwise resolve to nothing and change no local state.
	 */
	@Test
	void testResolve_whenPaymentIdIsUnknown_thenFallsBackToTheEnvelopeCheckoutId()
	{
		final PayoneWebhookStatusObjectDto payment = new PayoneWebhookStatusObjectDto();
		payment.setId("PP1AA7KKLSFB9MBG");
		final PayoneWebhookCheckoutDto checkoutDto = new PayoneWebhookCheckoutDto();
		checkoutDto.setId("4f0c512e-f12c-11ec-8ea0-0242ac120002");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setPayment(payment);
		envelope.setCheckout(checkoutDto);

		when(payoneWebhookEventDao.findCheckoutByPaymentId("PP1AA7KKLSFB9MBG")).thenReturn(Optional.empty());
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		when(payoneWebhookEventDao.findCheckoutById("4f0c512e-f12c-11ec-8ea0-0242ac120002"))
				.thenReturn(Optional.of(checkout));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.PAYMENT_CREATED);

		assertThat(target.getCheckout()).isSameAs(checkout);
	}

	@Test
	void testResolve_whenRefundIdsDoNotMatch_thenAlsoFallsBackToTheEnvelopeCheckoutId()
	{
		final PayoneWebhookStatusObjectDto refund = new PayoneWebhookStatusObjectDto();
		refund.setId("3066019730_1");
		final PayoneWebhookCheckoutDto checkoutDto = new PayoneWebhookCheckoutDto();
		checkoutDto.setId("checkout-1");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setRefund(refund);
		envelope.setCheckout(checkoutDto);

		when(payoneWebhookEventDao.findCheckoutByPaymentId("3066019730_1")).thenReturn(Optional.empty());
		when(payoneWebhookEventDao.findCheckoutByPaymentId("3066019730")).thenReturn(Optional.empty());
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		when(payoneWebhookEventDao.findCheckoutById("checkout-1")).thenReturn(Optional.of(checkout));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.REFUND_REFUNDED);

		assertThat(target.getCheckout()).isSameAs(checkout);
	}

	// resolveCommerceCase precedence

	@Test
	void testResolve_whenEnvelopeCarriesItsOwnCommerceCase_thenItWinsOverTheResolvedCheckoutsCase()
	{
		final PayoneWebhookCheckoutDto checkoutDto = new PayoneWebhookCheckoutDto();
		checkoutDto.setId("checkout-1");
		final PayoneWebhookCommerceCaseDto caseDto = new PayoneWebhookCommerceCaseDto();
		caseDto.setId("case-from-envelope");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setCheckout(checkoutDto);
		envelope.setCommerceCase(caseDto);

		final PayoneCommerceCaseModel checkoutsCase = new PayoneCommerceCaseModel();
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		checkout.setCommerceCase(checkoutsCase);
		when(payoneWebhookEventDao.findCheckoutById("checkout-1")).thenReturn(Optional.of(checkout));

		final PayoneCommerceCaseModel fromEnvelope = new PayoneCommerceCaseModel();
		when(payoneWebhookEventDao.findCommerceCaseById("case-from-envelope")).thenReturn(Optional.of(fromEnvelope));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.CHECKOUT_UPDATED);

		assertThat(target.getCommerceCase()).isSameAs(fromEnvelope);
	}

	@Test
	void testResolve_whenEnvelopeCarriesNoCommerceCase_thenFallsBackToTheCheckoutsCommerceCase()
	{
		final PayoneWebhookCheckoutDto checkoutDto = new PayoneWebhookCheckoutDto();
		checkoutDto.setId("checkout-1");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setCheckout(checkoutDto);

		final PayoneCommerceCaseModel checkoutsCase = new PayoneCommerceCaseModel();
		final PayoneCheckoutModel checkout = new PayoneCheckoutModel();
		checkout.setCommerceCase(checkoutsCase);
		when(payoneWebhookEventDao.findCheckoutById("checkout-1")).thenReturn(Optional.of(checkout));

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.CHECKOUT_UPDATED);

		assertThat(target.getCommerceCase()).isSameAs(checkoutsCase);
	}

	@Test
	void testResolve_whenNeitherCheckoutNorCommerceCaseResolve_thenReturnsNone()
	{
		final PayoneWebhookStatusObjectDto payment = new PayoneWebhookStatusObjectDto();
		payment.setId("unknown-payment");
		final PayoneWebhookEventDto envelope = new PayoneWebhookEventDto();
		envelope.setPayment(payment);

		when(payoneWebhookEventDao.findCheckoutByPaymentId("unknown-payment")).thenReturn(Optional.empty());

		final PayoneWebhookTarget target = resolver.resolve(envelope, PayoneWebhookEventType.PAYMENT_CAPTURED);

		assertThat(target.isEmpty()).isTrue();
	}
}
