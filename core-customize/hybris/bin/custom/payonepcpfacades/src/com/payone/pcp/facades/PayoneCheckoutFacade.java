package com.payone.pcp.facades;

import com.payone.pcp.facades.data.AuthenticationTokenResultData;
import com.payone.pcp.facades.data.CommerceCaseResultData;
import com.payone.pcp.facades.data.PaymentDetailsData;
import com.payone.pcp.facades.data.PayoneAuthorizationResultData;
import com.payone.pcp.facades.data.PayoneCheckoutData;
import com.payone.pcp.facades.data.PaymentRequestData;
import com.payone.pcp.facades.data.PaymentResultData;

import de.hybris.platform.core.model.order.CartModel;


public interface PayoneCheckoutFacade
{
	/**
	 * Create a PCP CommerceCase + Checkout for the current cart, or return the
	 * existing one if the cart already carries PCP identifiers (idempotent
	 * short-circuit).
	 *
	 * @param cart the SAP Commerce cart; must not be null.
	 * @return the checkout state including the PCP identifiers needed for the
	 *         hosted tokenizer / redirect flow.
	 * @throws IllegalStateException if the store has no active PAYONE
	 *                                configuration or the PCP API call fails.
	 */
	CommerceCaseResultData createOrGetCommerceCase(CartModel cart);

	/**
	 * Mint a merchant JWT for the hosted card iframe for the current cart's store.
	 *
	 * @param cart the SAP Commerce cart; must not be null.
	 * @return the authentication token result (token, id, expirationDate).
	 * @throws IllegalStateException if the store has no active PAYONE
	 *                                configuration or the PCP API call fails.
	 */
	AuthenticationTokenResultData createAuthenticationToken(CartModel cart);

	/**
	 * Execute a CARD or SEPA payment against an already-initialized PCP checkout
	 * and, on success, place the SAP Commerce order.
	 *
	 * @param cart    the SAP Commerce cart; must not be null.
	 * @param request the frontend-supplied payment request; must not be null and
	 *                must reference the cart's active commerceCaseId/checkoutId.
	 * @return the payment result (order code + mapped status, or a redirect URL).
	 * @throws IllegalStateException if the store has no active PAYONE
	 *                                configuration, the request does not match
	 *                                the cart's active checkout, or order
	 *                                placement fails after a successful payment.
	 * @deprecated the older, direct-SDK path, limited to
	 *             CARD/SEPA and with no per-store paymentModes enforcement.
	 *             Still the only path wired to the storefront (OCC
	 *             {@code /placeorder}) — do not remove until that is migrated.
	 *             Use {@link #authorizePayment(CartModel, int, PaymentDetailsData)}
	 *             + {@link #placeOrder(CartModel)} for new callers.
	 */
	@Deprecated
	PaymentResultData executePaymentAndPlaceOrder(CartModel cart, PaymentRequestData request);

	/**
	 * Strategy-routed path (additive — see class Javadoc). Authorizes a payment
	 * for the given PCP payment product via {@code PaymentStrategyRegistry},
	 * subject to the store's allowed payment modes. Does not place the order —
	 * call {@link #placeOrder(CartModel)} afterward once the result indicates
	 * success (or after {@link #handleRedirectCallback} for a redirect flow).
	 *
	 * @param cart             the SAP Commerce cart; must not be null.
	 * @param paymentProductId the PCP payment product ID to authorize.
	 * @param paymentDetails   CARD/SEPA-specific fields (token, or iban/mandate);
	 *                         may be null for methods that need none.
	 * @return the normalized authorization result (redirect / direct success / pending).
	 * @throws IllegalArgumentException if required fields for the resolved
	 *                                   payment family are missing.
	 * @throws IllegalStateException    if the store has no active PAYONE
	 *                                   configuration, the checkout was not
	 *                                   initialized, no strategy is registered
	 *                                   (or allowed) for the product, or the
	 *                                   PCP API call fails.
	 */
	PayoneAuthorizationResultData authorizePayment(CartModel cart, int paymentProductId, PaymentDetailsData paymentDetails);

	/**
	 * Strategy-routed path (additive — see class Javadoc). Handles the
	 * return-URL callback after a frontend redirect (3DS, PayPal, Wero, ...):
	 * validates the checkout and reflects the current PCP checkout status.
	 *
	 * @param cart       the SAP Commerce cart; must not be null.
	 * @param checkoutId the PCP checkout ID the frontend redirected back from.
	 * @return the current authorization result reflecting the post-redirect state.
	 * @throws IllegalStateException if no active configuration, the checkout
	 *                                was not initialized, or the checkoutId
	 *                                does not match the cart's.
	 */
	PayoneAuthorizationResultData handleRedirectCallback(CartModel cart, String checkoutId);

	/**
	 * Strategy-routed path (additive — see class Javadoc). Finalizes the SAP
	 * order via {@code CommerceCheckoutService} after a successful
	 * {@link #authorizePayment} (or post-redirect callback), and re-links the
	 * cart's PCP-tracking {@code PaymentTransaction} to the resulting order.
	 *
	 * @param cart the SAP Commerce cart to convert into an order; must not be null.
	 * @return the checkout state of the resulting order.
	 * @throws IllegalStateException if the cart has no PaymentInfo (authorize
	 *                                first) or order placement fails.
	 */
	PayoneCheckoutData placeOrder(CartModel cart);
}
