/*
 * pcp-secured-invoice-preauth-test.groovy
 *
 * PAYONE PCP — a payment that is authorised but NOT captured ("capture step
 * missing"), WITHOUT any client-side token: PAYONE Secured Invoice (3390)
 * with requiresApproval = true.
 *
 * Why this product / this flag (docs.commerce.payone.com/docs/payment-methods/
 * payone-bnpl/payone-secured-invoice, financingPaymentMethodSpecificInput):
 *   "requiresApproval — Determines whether the amount is captured.
 *    true  - The payment requires approval before the funds will be captured
 *            using an Ordermanagement deliver or a Paymentexecution capture
 *    false - The funds will be captured automatically after the customer has
 *            confirmed the payment"
 * By contrast SEPA 771 ("the amount is automatically captured") and Wero 900
 * ("Currently only requiresApproval: false is supported") cannot be left
 * uncaptured, and CARD/Google/Apple Pay need a client-side token.
 *
 * Why the service layer and not PayoneCheckoutFacade:
 * PcpFinancingPaymentMethodSpecificInputBuilder hardcodes
 * requiresApproval(Boolean.FALSE), so the facade path always captures
 * immediately. This script therefore builds the PCP request itself and calls
 * payoneCommerceCaseService / payonePaymentExecutionService /
 * payoneCheckoutService directly. No hybris Order/Cart is created — the
 * "uncaptured" state is shown purely from PCP's side.
 *
 * FLOW:
 *   [1/5] resolve PayoneConfiguration by merchantId
 *   [2/5] create CommerceCase + Checkout (BNPL customer data: dateOfBirth,
 *         phone, name — all mandatory for PAYONE BNPL per the doc)
 *   [3/5] executePayment: financing 3390, requiresApproval = true,
 *         customerDevice.ipAddress set (mandatory for BNPL)
 *   [4/5] getCheckout -> show statusOutput (openAmount/collectedAmount) and
 *         execution events; expect an open, uncaptured amount
 *   [5/5] optional (doCapture = true): capturePayment for the full amount,
 *         then show the state again
 *
 * MERCHANT: works with ANY test merchant for which PAYONE has activated
 * Secured Invoice (3390) — set merchantId below. The merchant needs a
 * PayoneConfiguration row (impex / Backoffice) and its runtime secret
 * payone.pcp.<merchantId>.apiSecret (never stored in the database); no BaseStore is involved.
 * Check activation first with pcp-financing-raw-api-test.groovy
 * (paymentProductId = 3390, requiresApproval = true).
 *
 * If PCP returns errorCode 50090923 PAYMENT_METHOD_NOT_ALLOWED, 3390 is not
 * activated for the merchant - a PAYONE-side activation issue, not this
 * script. With an activated merchant, a REJECTED status means PCP's BNPL risk
 * check declined the synthetic customer.
 *
 * IMPORTANT: mutates PCP preprod (real CommerceCase/Payment). Test merchant
 * only. No secret is printed. No System.exit(). Nothing is written to the
 * hybris database, so HAC commit mode does not matter.
 */

// =======================================================================
// SETTINGS
// =======================================================================

// Any preprod merchant with Secured Invoice (3390) activated. Needs a
// PayoneConfiguration row + payone.pcp.<merchantId>.apiSecret.
def merchantId      = "REPLACE_WITH_MERCHANT_ID"
def amountCents     = 20000L
def testCustomerIp  = "127.0.0.1"
def doCapture       = false   // true = capture the full amount at the end

// PCP caps payment-execution merchantReference at 20 chars.
def shortRef   = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8)
def caseRef    = "si-pre-" + shortRef   // commerce-case merchantReference
def paymentRef = "sip-" + shortRef      // payment-execution merchantReference

// =======================================================================
// IMPORTS
// =======================================================================

import com.payone.commerce.platform.lib.models.*
import com.payone.commerce.platform.lib.errors.ApiErrorResponseException

import com.payone.pcp.core.data.PaymentExecutionResponse
import com.payone.pcp.core.service.PayoneCheckoutService
import com.payone.pcp.core.service.PayoneCommerceCaseService
import com.payone.pcp.core.service.PayoneConfigurationService
import com.payone.pcp.core.service.PayonePaymentExecutionService

// =======================================================================
// HELPERS
// =======================================================================

def passed = 0
def failed = 0
def errors = []

def printSdkError = { Throwable t ->
    def root = t
    def msg = t.message
    while (root != null) {
        if (root.message) { msg = root.message; break }
        root = root.cause
    }
    def sb = new StringBuilder(msg != null ? msg : t.class.simpleName)
    def cause = t.cause
    while (cause != null) {
        if (cause instanceof ApiErrorResponseException) {
            try { sb.append(" [HTTP ").append(cause.statusCode).append("]") } catch (Exception ignored) {}
            try { sb.append("\n      - ").append(cause.responseBody) } catch (Exception ignored) {}
            try { cause.errors?.each { sb.append("\n      - ").append(it.message) } } catch (Exception ignored) {}
            break
        }
        cause = cause.cause
    }
    return sb.toString()
}

def test = { String name, Closure block ->
    try {
        block()
        passed++
        println "  ✓ ${name}"
    } catch (AssertionError e) {
        failed++
        errors << "${name}: ${e.message}"
        println "  ✗ ${name}"
        println "    ${e.message}"
    } catch (Exception e) {
        failed++
        errors << "${name}: ${e.class.simpleName}: ${printSdkError(e)}"
        println "  ✗ ${name}"
        println "    ${printSdkError(e)}"
    }
}

def assertNotNull = { Object value, String label = "value" ->
    if (value == null) throw new AssertionError("${label} is null")
}

def assertTrue = { boolean condition, String msg ->
    if (!condition) throw new AssertionError(msg)
}

def getBean = { String name ->
    try {
        return spring.getBean(name)
    } catch (MissingPropertyException e) {
        return de.hybris.platform.core.Registry.getApplicationContext().getBean(name)
    }
}

// =======================================================================
// START
// =======================================================================

println ""
println "=" * 75
println "  PAYONE PCP — Secured Invoice 3390, requiresApproval=true (no capture yet)"
println "  Merchant: ${merchantId}   Ref: ${caseRef}"
println "  ⚠ Mutates PCP preprod."
println "=" * 75

// -----------------------------------------------------------------------
// [1/5] Configuration
// -----------------------------------------------------------------------

println ""
println "[1/5] Resolving PayoneConfiguration for [${merchantId}]..."

def config = null
try {
    config = ((PayoneConfigurationService) getBean("payoneConfigurationService"))
            .getConfigurationByMerchantId(merchantId)
} catch (Exception e) {
    println "  ✗ ${printSdkError(e)}"
}
if (config == null || config.apiSecret == null) {
    println "  ✗ No usable PayoneConfiguration for [${merchantId}] (row missing or apiSecret not set)."
    return [passed: 0, failed: 1]
}
println "  ✓ Resolved (host ${config.apiEndpointHost})"

def ccService       = (PayoneCommerceCaseService) getBean("payoneCommerceCaseService")
def paymentService  = (PayonePaymentExecutionService) getBean("payonePaymentExecutionService")
def checkoutService = (PayoneCheckoutService) getBean("payoneCheckoutService")

// -----------------------------------------------------------------------
// [2/5] CommerceCase + Checkout
// -----------------------------------------------------------------------

println ""
println "[2/5] Creating CommerceCase + Checkout..."

String commerceCaseId = null
String checkoutId = null

test("createCommerceCase") {
    // Customer data modelled on the doc's Secured Invoice example; BNPL
    // requires dateOfBirth, phoneNumber and name.
    def customer = new Customer()
        .merchantCustomerId("cust-" + shortRef)
        .businessRelation(BusinessRelation.B2C)
        .locale("de")
        .contactDetails(new ContactDetails()
                .emailAddress("pcp-si-${shortRef}@example.com")
                .phoneNumber("0188711725"))
        .personalInformation(new PersonalInformation()
                .dateOfBirth("19780101")
                .gender(PersonalInformation.GenderEnum.MALE)
                .name(new PersonalName().firstName("Ruediger").surname("Soerensen")))
        .billingAddress(new Address()
                .countryCode("DE").zip("22332").city("Bremervoerde")
                .street("Moritz-Guenther-Strasse").houseNumber("23a"))

    def checkout = new CreateCheckoutRequest()
        .amountOfMoney(new AmountOfMoney().amount(amountCents).currencyCode("EUR"))
        .references(new CheckoutReferences().merchantReference(caseRef))
        .shoppingCart(new ShoppingCartInput().items([
            new CartItemInput()
                .invoiceData(new CartItemInvoiceData().description("PCP preauth test item"))
                .orderLineDetails(new OrderLineDetailsInput()
                        .productCode("TEST-PRODUCT-1")
                        .productPrice(amountCents)
                        .quantity(1L)
                        .productType(ProductType.GOODS))]))

    def response = ccService.createCommerceCase(config,
            new CreateCommerceCaseRequest().merchantReference(caseRef).customer(customer).checkout(checkout))
    commerceCaseId = response?.commerceCaseId?.toString()
    checkoutId     = response?.checkout?.checkoutId?.toString()
    assertNotNull(commerceCaseId, "commerceCaseId")
    assertNotNull(checkoutId, "checkoutId")
    println "    CommerceCaseId : ${commerceCaseId}"
    println "    CheckoutId     : ${checkoutId}"
}

if (commerceCaseId == null) {
    println "  Stopping — no commerce case."
    return [passed: passed, failed: failed, errors: errors]
}

// -----------------------------------------------------------------------
// [3/5] Execute payment: 3390, requiresApproval = true
// -----------------------------------------------------------------------

println ""
println "[3/5] executePayment — Secured Invoice 3390, requiresApproval=true..."

String paymentExecutionId = null

test("executePayment (requiresApproval=true)") {
    def request = new PaymentExecutionRequest()
        .paymentExecutionSpecificInput(new PaymentExecutionSpecificInput()
                .amountOfMoney(new AmountOfMoney().amount(amountCents).currencyCode("EUR"))
                .paymentReferences(new References().merchantReference(paymentRef)))
        .paymentMethodSpecificInput(new PaymentMethodSpecificInput()
                .paymentChannel(PaymentChannel.ECOMMERCE)
                .customerDevice(new CustomerDevice().ipAddress(testCustomerIp))
                .financingPaymentMethodSpecificInput(new FinancingPaymentMethodSpecificInput()
                        .paymentProductId(3390)
                        .requiresApproval(Boolean.TRUE)))

    PaymentExecutionResponse resp = paymentService.executePayment(config, commerceCaseId, checkoutId, request)
    assertNotNull(resp, "PaymentExecutionResponse")
    paymentExecutionId = resp.paymentExecutionId
    println "    status             : ${resp.status}"
    println "    paymentExecutionId : ${resp.paymentExecutionId}"
    println "    paymentId          : ${resp.paymentId}"
    assertTrue(resp.status?.toString() != "REJECTED",
        "PCP rejected the payment (BNPL risk check or product not allowed) — see status/log")
}

// -----------------------------------------------------------------------
// [4/5] Show the uncaptured state
// -----------------------------------------------------------------------

def showState = { String label ->
    println ""
    println "  --- ${label} ---"
    def checkout = checkoutService.getCheckout(config, commerceCaseId, checkoutId)
    def so = checkout.statusOutput
    println "  checkoutStatus=${checkout.checkoutStatus} paymentStatus=${so?.paymentStatus} modifiable=${so?.isModifiable}"
    println "  amount=${checkout.amountOfMoney?.amount} open=${so?.openAmount} collected=${so?.collectedAmount}" +
            " cancelled=${so?.cancelledAmount} refunded=${so?.refundedAmount}"
    println "  allowedPaymentActions=${checkout.allowedPaymentActions}"
    (checkout.paymentExecutions ?: []).each { pe ->
        println "  execution ${pe.paymentExecutionId} (paymentId=${pe.paymentId})"
        (pe.events ?: []).sort { it.creationDateTime }.each { ev ->
            println "    · ${ev.creationDateTime} ${ev.type} -> ${ev.paymentStatus} ${ev.amountOfMoney?.amount ?: ''}"
        }
    }
    return [open: so?.openAmount ?: 0L, collected: so?.collectedAmount ?: 0L]
}

if (paymentExecutionId != null) {
    println ""
    println "[4/5] Checkout state (capture still missing)..."
    test("PCP reports an open, uncaptured amount") {
        def s = showState("BEFORE capture")
        assertTrue(s.collected == 0,
            "expected collected == 0 (not captured), got open=${s.open} collected=${s.collected}")
    }

    // -------------------------------------------------------------------
    // [5/5] Optional capture
    // -------------------------------------------------------------------

    println ""
    if (doCapture) {
        println "[5/5] capturePayment (full amount, isFinal=true)..."
        test("capturePayment") {
            // 4th param is the payment EXECUTION id (UUID), not paymentId —
            // see PayonePaymentExecutionService Javadoc.
            def cap = paymentService.capturePayment(config, commerceCaseId, checkoutId, paymentExecutionId,
                    new CapturePaymentRequest().amount(amountCents).isFinal(true))
            println "    capture -> status=${cap.status} paymentId=${cap.paymentId}"
            showState("AFTER capture")
        }
    } else {
        println "[5/5] Capture NOT executed (doCapture=false) — payment stays uncaptured."
        println "      Re-run with doCapture=true, or capture via the PCP portal, using"
        println "      CommerceCaseId ${commerceCaseId} / CheckoutId ${checkoutId}."
    }
}

// =======================================================================
// SUMMARY
// =======================================================================

println ""
println "=" * 75
println "  DONE — pass=${passed} fail=${failed}"
println "  CommerceCaseId=${commerceCaseId} CheckoutId=${checkoutId} PaymentExecutionId=${paymentExecutionId}"
println "=" * 75
if (!errors.isEmpty()) {
    println "  FAILURES:"
    errors.each { println "    • ${it}" }
}
if (errors.any { it.contains("50090923") || it.contains("PAYMENT_METHOD_NOT_ALLOWED") }) {
    println ""
    println "  HINT: 50090923 PAYMENT_METHOD_NOT_ALLOWED = Secured Invoice (3390) is not"
    println "  activated for merchant [${merchantId}] on PAYONE's side. Ask PAYONE to"
    println "  activate it, or set merchantId to a test merchant where it is."
}

return [passed: passed, failed: failed, commerceCaseId: commerceCaseId,
        checkoutId: checkoutId, paymentExecutionId: paymentExecutionId]
