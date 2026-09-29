package com.payone.pcp.occ.controllers;

import com.payone.pcp.facades.PayoneCheckoutFacade;
import com.payone.pcp.facades.PayoneCheckoutMismatchException;
import com.payone.pcp.facades.PayoneConfigurationNotFoundException;
import com.payone.pcp.facades.data.AuthenticationTokenResultData;
import com.payone.pcp.facades.data.CommerceCaseResultData;
import com.payone.pcp.facades.data.PaymentDetailsData;
import com.payone.pcp.facades.data.PayoneAuthorizationResultData;
import com.payone.pcp.facades.data.PayoneCheckoutData;
import com.payone.pcp.facades.data.PaymentRequestData;
import com.payone.pcp.facades.data.PaymentResultData;

import com.payone.pcp.occ.dto.AuthenticationTokenResultWsDTO;
import com.payone.pcp.occ.dto.CommerceCaseResultWsDTO;
import com.payone.pcp.occ.dto.ErrorResultWsDTO;
import com.payone.pcp.occ.dto.PaymentDetailsWsDTO;
import com.payone.pcp.occ.dto.PaymentRequestWsDTO;
import com.payone.pcp.occ.dto.PaymentResultWsDTO;
import com.payone.pcp.occ.dto.PayoneAuthorizationResultWsDTO;
import com.payone.pcp.occ.dto.PayoneCheckoutResultWsDTO;

import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.order.CartService;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.function.Supplier;


/**
 * OCC endpoints for the PCP checkout flow: create a commerce case/checkout for the cart, mint an
 * authentication token for the hosted card iframe, and authorize/place the order. Binds requests
 * and maps results to a {@link ResponseEntity}; all orchestration lives in
 * {@link PayoneCheckoutFacade}. capturePayment/cancelPayment/refundPayment/completePayment/
 * pausePayment/refreshPayment are not exposed here: they are merchant/back-office operations,
 * available only through the facade for Backoffice callers.
 */
@Controller
@RequestMapping(value = "/{baseSiteId}/users/{userId}/carts/{cartId}/payone")
public class PayoneCheckoutController {
    private static final Logger LOG = LoggerFactory.getLogger(PayoneCheckoutController.class);

    @Resource(name = "cartService")
    private CartService cartService;

    @Resource(name = "payoneCheckoutFacade")
    private PayoneCheckoutFacade payoneCheckoutFacade;

    @ResponseBody
    @PostMapping("/commerce-case")
    public ResponseEntity<?> createCommerceCase() {
        final CartModel cart = cartService.getSessionCart();
        return handleIllegalState(cart, "PAYONE commerce case/checkout creation failed for cart={}",
                () -> toDto(payoneCheckoutFacade.createOrGetCommerceCase(cart)));
    }

    @ResponseBody
    @PostMapping("/authentication-token")
    public ResponseEntity<?> createAuthenticationToken() {
        final CartModel cart = cartService.getSessionCart();
        return handleIllegalState(cart, "PAYONE authentication token creation failed for cart={}",
                () -> toDto(payoneCheckoutFacade.createAuthenticationToken(cart)));
    }

    /**
     * @deprecated the direct-SDK path, wired to {@link PayoneCheckoutFacade#executePaymentAndPlaceOrder}.
     *             Still the only path the storefront calls; do not remove until migrated to
     *             /payments/authorize + /orders.
     */
    @Deprecated
    @ResponseBody
    @PostMapping("/placeorder")
    public ResponseEntity<?> executePayment(@RequestBody final PaymentRequestWsDTO body) {
        final CartModel cart = cartService.getSessionCart();
        final PaymentRequestData request = toData(body);

        return handleValidatedCall(cart, "PAYONE payment execution failed for cart={}", () -> {
            final PaymentResultData data = payoneCheckoutFacade.executePaymentAndPlaceOrder(cart, request);
            final PaymentResultWsDTO result = toDto(data);

            // No orderCode means the payment was rejected/errored before an order could be placed.
            if (result.getOrderCode() == null) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(result);
            }
            return ResponseEntity.ok(result);
        });
    }

    // Strategy-routed endpoints: dispatch via PaymentStrategyRegistry, cover every registered
    // payment family, enforce per-store paymentModes.

    @ResponseBody
    @PostMapping("/payments/authorize")
    public ResponseEntity<?> authorizePayment(
            @RequestParam("paymentProductId") final int paymentProductId,
            @RequestBody(required = false) final PaymentDetailsWsDTO body) {
        final CartModel cart = cartService.getSessionCart();
        final PaymentDetailsData paymentDetails = toData(body);

        return handleValidatedCall(cart, "PAYONE authorize (strategy path) failed for cart={}, product=" + paymentProductId,
                () -> ResponseEntity.ok(toDto(payoneCheckoutFacade.authorizePayment(cart, paymentProductId, paymentDetails))));
    }

    @ResponseBody
    @PostMapping("/payments/redirect-callback")
    public ResponseEntity<?> handleRedirectCallback(@RequestParam("checkoutId") final String checkoutId) {
        final CartModel cart = cartService.getSessionCart();
        return handleIllegalState(cart, "PAYONE redirect callback (strategy path) failed for cart={}, checkoutId=" + checkoutId,
                () -> toDto(payoneCheckoutFacade.handleRedirectCallback(cart, checkoutId)));
    }

    @ResponseBody
    @PostMapping("/orders")
    public ResponseEntity<?> placeOrder() {
        final CartModel cart = cartService.getSessionCart();
        return handleIllegalState(cart, "PAYONE order placement (strategy path) failed for cart={}",
                () -> toDto(payoneCheckoutFacade.placeOrder(cart)));
    }

    // Shared error-handling wrappers

    /**
     * Wraps an endpoint whose facade call only ever throws IllegalStateException (business
     * config / PCP-side errors, never request validation).
     *
     * @param logMessage caller-built log message; cart.getCode() is appended as the sole SLF4J {} arg
     */
    private ResponseEntity<?> handleIllegalState(
            final CartModel cart, final String logMessage, final Supplier<Object> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (final IllegalStateException e) {
            LOG.error(logMessage, cart.getCode(), e);
            return errorResponse(resolveStatus(e), e.getMessage());
        }
    }

    /**
     * Wraps an endpoint that also validates frontend-supplied request shape:
     * IllegalArgumentException maps to 400 without logging (client error, not worth a stack
     * trace), IllegalStateException maps and logs like {@link #handleIllegalState}. action builds
     * the ResponseEntity itself since executePayment needs to choose between 200 and 402.
     *
     * @param logMessage see {@link #handleIllegalState}
     */
    private ResponseEntity<?> handleValidatedCall(
            final CartModel cart, final String logMessage, final Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (final IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (final IllegalStateException e) {
            LOG.error(logMessage, cart.getCode(), e);
            return errorResponse(resolveStatus(e), e.getMessage());
        }
    }

    /**
     * Missing configuration or a checkout-id mismatch is a conflict (409); every other
     * IllegalStateException from the facade is a PCP-side or config failure, mapped to 502.
     */
    private static HttpStatus resolveStatus(final IllegalStateException e) {
        if (e instanceof PayoneConfigurationNotFoundException || e instanceof PayoneCheckoutMismatchException) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private static ResponseEntity<ErrorResultWsDTO> errorResponse(
            final HttpStatus status,
            final String message) {
        final ErrorResultWsDTO error = new ErrorResultWsDTO();
        error.setMessage(message);
        return ResponseEntity.status(status).body(error);
    }

    // DTO <-> facade data mapping

    private static CommerceCaseResultWsDTO toDto(final CommerceCaseResultData data) {
        final CommerceCaseResultWsDTO result = new CommerceCaseResultWsDTO();
        result.setCommerceCaseId(data.getCommerceCaseId());
        result.setCheckoutId(data.getCheckoutId());
        result.setAmount(data.getAmount());
        result.setCurrencyIsoCode(data.getCurrencyIsoCode());
        result.setAllowedPaymentActions(data.getAllowedPaymentActions());
        return result;
    }

    private static AuthenticationTokenResultWsDTO toDto(final AuthenticationTokenResultData data) {
        final AuthenticationTokenResultWsDTO result = new AuthenticationTokenResultWsDTO();
        result.setToken(data.getToken());
        result.setId(data.getId());
        result.setExpirationDate(data.getExpirationDate());
        return result;
    }

    private static PaymentRequestData toData(final PaymentRequestWsDTO body) {
        final PaymentRequestData request = new PaymentRequestData();
        if (body != null) {
            request.setCommerceCaseId(body.getCommerceCaseId());
            request.setCheckoutId(body.getCheckoutId());
            request.setPaymentMethod(body.getPaymentMethod());
            request.setPaymentProcessingToken(body.getPaymentProcessingToken());
            request.setPaymentProductId(body.getPaymentProductId());
            request.setIban(body.getIban());
            request.setAccountHolder(body.getAccountHolder());
            request.setCreditorId(body.getCreditorId());
            request.setMandateReference(body.getMandateReference());
            request.setDateOfSignature(body.getDateOfSignature());
        }
        return request;
    }

    private static PaymentResultWsDTO toDto(final PaymentResultData data) {
        final PaymentResultWsDTO result = new PaymentResultWsDTO();
        result.setOrderCode(data.getOrderCode());
        result.setPaymentStatus(data.getPaymentStatus());
        result.setRedirectUrl(data.getRedirectUrl());
        return result;
    }

    private static PaymentDetailsData toData(final PaymentDetailsWsDTO body) {
        final PaymentDetailsData paymentDetails = new PaymentDetailsData();
        if (body != null) {
            paymentDetails.setPaymentProcessingToken(body.getPaymentProcessingToken());
            paymentDetails.setIban(body.getIban());
            paymentDetails.setAccountHolder(body.getAccountHolder());
            paymentDetails.setCreditorId(body.getCreditorId());
            paymentDetails.setMandateReference(body.getMandateReference());
            paymentDetails.setDateOfSignature(body.getDateOfSignature());
        }
        return paymentDetails;
    }

    private static PayoneAuthorizationResultWsDTO toDto(final PayoneAuthorizationResultData data) {
        final PayoneAuthorizationResultWsDTO result = new PayoneAuthorizationResultWsDTO();
        result.setRedirect(data.isRedirect());
        result.setRedirectUrl(data.getRedirectUrl());
        result.setPaymentId(data.getPaymentId());
        result.setStatus(data.getStatus());
        return result;
    }

    private static PayoneCheckoutResultWsDTO toDto(final PayoneCheckoutData data) {
        final PayoneCheckoutResultWsDTO result = new PayoneCheckoutResultWsDTO();
        result.setCommerceCaseId(data.getCommerceCaseId());
        result.setCheckoutId(data.getCheckoutId());
        result.setPaymentExecutionId(data.getPaymentExecutionId());
        result.setPaymentId(data.getPaymentId());
        result.setStatus(data.getStatus());
        return result;
    }
}
