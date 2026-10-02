/*
 * pcp-facade-mobile-onestep-test.groovy
 *
 * PAYONE PCP — One-Step Checkout diagnostic for the MOBILE wallet family.
 * Switch paymentProductId below:
 *   320 = Google Pay  (real token from facade/googlepay-test-page/)
 *   302 = Apple Pay   (placeholders only - see APPLE PAY LIMITATION)
 *
 * WHY ONE-STEP: same finding as Wero (pcp-facade-wero-onestep-test.groovy;
 * plugin README, section 10 "Not yet available in this version"). PAYONE's docs for both wallets
 * (docs.commerce.payone.com/docs/payment-methods/google-pay/standard-checkout,
 * .../apple-pay) embed the payment in the initial commerce-cases POST
 * (autoExecuteOrder: true), not a separate payment-executions call after
 * checkout creation. PayoneCheckoutFacade's strategy-routed authorizePayment
 * is Step-by-Step only, so the wallets are not reachable through it. This
 * script bypasses the facade and exercises the core directly.
 *
 * GOOGLE PAY (320) — HOW TO GET THE TOKEN:
 *   Serve manual_testing/facade/googlepay-test-page/index.html via Docker (see
 *   manual_testing/README.md), set its totalPrice to the cart total this script
 *   prints ("Cart [...] ready — total X EUR"), click the Google Pay button and
 *   paste the shown value into GOOGLE_PAY_ENCRYPTED_PAYMENT_DATA. The page
 *   base64-encodes the token IN THE BROWSER - paste that, NOT the raw token
 *   JSON: the raw JSON contains backslash-escaped nested JSON and \u003d
 *   escapes that Groovy processes at the SOURCE level, so a single backslash
 *   surviving copy-paste wrong silently corrupts the token. That was the
 *   root cause of the persistent 50092703/VALIDATION_ERROR seen earlier.
 *
 * APPLE PAY (302) LIMITATION:
 *   PcpMobilePaymentMethodSpecificInputBuilder has a MINIMAL Apple Pay cut
 *   only paymentProductId, authorizationMode,
 *   encryptedPaymentData, publicKeyHash, ephemeralKey. It does NOT build
 *   paymentProduct302SpecificInput (full PKPaymentToken shape: signature,
 *   header.transactionId, network, integrationType, domainName,
 *   displayName). Real Apple Pay data
 *   needs a real device and Apple merchant certificate, so this script uses
 *   placeholders and only verifies that PCP accepts the request shape: a
 *   token-validation error (or a missing-302-input complaint) is EXPECTED;
 *   a flow-shape error like 50507002/50102001 would not be.
 *
 * FULLY SELF-CONTAINED — paste this entire file into a fresh HAC Groovy
 * console tab and run it.
 *
 * WHAT THIS EXERCISES (payonepcpcore, NOT payonepcpfacades):
 *   createCommerceCaseRequestConverter.convert(cart, customer, address)
 *   createCheckoutRequestConverter.convert(cart, address, customer, ref)
 *     -> then OVERRIDES autoExecuteOrder to true (converter defaults to
 *        false - Step-by-Step) and attaches an OrderRequest carrying the
 *        MobilePaymentMethodSpecificInput built via
 *        pcpMobilePaymentMethodSpecificInputBuilder - the SAME builder the
 *        (not-yet-reachable) facade path would use.
 *   payoneCommerceCaseService.createCommerceCase(config, request) - single
 *     call, no separate payment-executions call at all.
 *   Result inspected directly: checkout.paymentResponse.merchantAction /
 *     payment.status, and checkout.errorResponse for inline rejections.
 *
 * This is a DIAGNOSTIC, not a permanent addition to the facade surface
 * (PayoneCheckoutFacade has no One-Step method by design).
 *
 * PREREQUISITES: active PayoneConfiguration with a returnUrl, the chosen
 * product in paymentModes, the wallet activated for the merchant (PAYONE
 * support + Google Pay Business Console / Apple Developer setup per the docs).
 *
 * IMPORTANT: mutates the target environment — creates a real customer, a
 * real cart, and a real PCP CommerceCase/Checkout/PaymentExecution attempt.
 * Preprod/sandbox only. No secret is ever printed. No System.exit().
 */

// =======================================================================
// HARDCODED TEST VALUES (edit for your environment)
// =======================================================================

def baseSiteUid      = "electronics"
def productCatalogId = "electronicsProductCatalog"   // from spartacussampledata site.impex ($productCatalog)

def preferredProductCode = null   // null = pick any saleable product with a price

def paymentProductId = 320   // 320 = Google Pay, 302 = Apple Pay

// Google Pay (320): paste the ALREADY-BASE64-ENCODED value shown by
// facade/googlepay-test-page/ - NOT the raw token JSON (see header).
def GOOGLE_PAY_ENCRYPTED_PAYMENT_DATA = "___PASTE_ALREADY_BASE64_ENCODED_TOKEN_HERE___"

// Apple Pay (302): placeholders - real values need a real device + Apple
// merchant certificate exchange (see header, APPLE PAY LIMITATION).
def APPLE_PAY_ENCRYPTED_PAYMENT_DATA = "___PLACEHOLDER_NOT_REAL_APPLE_PAY_DATA___"
def APPLE_PAY_PUBLIC_KEY_HASH        = "___PLACEHOLDER_NOT_A_REAL_PUBLIC_KEY_HASH___"
def APPLE_PAY_EPHEMERAL_KEY          = "___PLACEHOLDER_NOT_A_REAL_EPHEMERAL_KEY___"

def isApplePay  = paymentProductId == 302
def walletName  = isApplePay ? "Apple Pay" : "Google Pay"
def placeholder = isApplePay ? APPLE_PAY_ENCRYPTED_PAYMENT_DATA.startsWith("___")
                             : GOOGLE_PAY_ENCRYPTED_PAYMENT_DATA.startsWith("___")

// Unique per run so repeated executions (and other scripts run concurrently
// in other tabs) never collide on customer uid or cart code.
def runId = (isApplePay ? "applepay-onestep-" : "googlepay-onestep-") + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10)
def testCustomerUid = "pcp-facade-test-${runId}@example.com"

// =======================================================================
// IMPORTS
// =======================================================================

import de.hybris.platform.core.model.order.CartModel
import de.hybris.platform.core.model.user.CustomerModel
import de.hybris.platform.core.model.user.AddressModel
import de.hybris.platform.core.model.product.ProductModel
import de.hybris.platform.servicelayer.model.ModelService

import com.payone.pcp.core.model.PayoneConfigurationModel
import com.payone.pcp.core.model.PayonePaymentInfoModel
import com.payone.pcp.core.service.PayoneConfigurationService
import com.payone.pcp.core.service.PayoneCommerceCaseService
import com.payone.pcp.core.converters.CreateCommerceCaseRequestConverter
import com.payone.pcp.core.converters.CreateCheckoutRequestConverter
import com.payone.pcp.core.converters.PcpMerchantReferenceGenerator
import com.payone.pcp.core.converters.paymentmethod.PcpMobilePaymentMethodSpecificInputBuilder

import com.payone.commerce.platform.lib.models.CreateCommerceCaseRequest
import com.payone.commerce.platform.lib.models.CreateCheckoutRequest
import com.payone.commerce.platform.lib.models.CreateCommerceCaseResponse
import com.payone.commerce.platform.lib.models.OrderRequest
import com.payone.commerce.platform.lib.models.OrderType
import com.payone.commerce.platform.lib.models.PaymentMethodSpecificInput
import com.payone.commerce.platform.lib.models.PaymentChannel
import com.payone.commerce.platform.lib.models.References
import com.payone.commerce.platform.lib.models.MobilePaymentMethodSpecificInput
import com.payone.commerce.platform.lib.models.ActionType
import com.payone.commerce.platform.lib.models.CustomerDevice

// =======================================================================
// TEST FRAMEWORK (inline)
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
        try {
            if (cause.class.simpleName == "ApiErrorResponseException") {
                try { sb.append(" [HTTP ").append(cause.statusCode).append("]") } catch (Exception ignored) {}
                try { sb.append("\n      - ").append(cause.responseBody) } catch (Exception ignored) {}
                try { cause.errors?.each { sb.append("\n      - ").append(it.message) } } catch (Exception ignored) {}
                break
            }
        } catch (Exception ignored) {}
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
        try {
            return de.hybris.platform.core.Registry.getApplicationContext().getBean(name)
        } catch (Exception e2) {
            throw new RuntimeException("Bean [${name}] not found via spring or Registry", e2)
        }
    } catch (Exception e2) {
        throw new RuntimeException("Bean [${name}] not found: ${e2.message}", e2)
    }
}

// =======================================================================
// START
// =======================================================================

println ""
println "=" * 75
println "  PAYONE PCP — ${walletName} (product ${paymentProductId}) ONE-STEP checkout diagnostic"
println "  Run id: ${runId}"
if (placeholder) {
    println "  ⚠ WARNING: using PLACEHOLDER ${walletName} data - see class comment."
    println "  ⚠ A PCP token-validation failure below is EXPECTED, not a bug."
}
println "=" * 75

def baseSiteService = getBean("baseSiteService")
def modelService = (ModelService) getBean("modelService")
def cartService = getBean("cartService")
def commerceCartService = getBean("commerceCartService")
def flexibleSearchService = getBean("flexibleSearchService")
def catalogVersionService = getBean("catalogVersionService")
def payoneConfigurationService = (PayoneConfigurationService) getBean("payoneConfigurationService")
def payoneCommerceCaseService = (PayoneCommerceCaseService) getBean("payoneCommerceCaseService")
def createCommerceCaseRequestConverter = (CreateCommerceCaseRequestConverter) getBean("createCommerceCaseRequestConverter")
def createCheckoutRequestConverter = (CreateCheckoutRequestConverter) getBean("createCheckoutRequestConverter")
def mobileBuilder = (PcpMobilePaymentMethodSpecificInputBuilder) getBean("pcpMobilePaymentMethodSpecificInputBuilder")

// -----------------------------------------------------------------------
// [1/6] Resolve BaseSite/BaseStore
// -----------------------------------------------------------------------

println ""
println "[1/6] Resolving BaseSite [${baseSiteUid}]..."

def site = baseSiteService.getBaseSiteForUID(baseSiteUid)
if (site == null) {
    println "  ✗ BaseSite [${baseSiteUid}] not found — cannot continue."
    return [passed: 0, failed: 1, skipped: 0]
}
def store = site.getStores()?.getAt(0)
assertNotNull(store, "a BaseStore linked to BaseSite [${baseSiteUid}]")
println "  ✓ BaseSite/BaseStore: [${site.getUid()}] / [${store.getUid()}]"

def eur = store.getCurrencies()?.find { it.isocode == "EUR" }
def cartCurrency = eur ?: (store.getCurrencies()?.getAt(0))
assertNotNull(cartCurrency, "a currency configured on store [${store.getUid()}]")
def currentLanguage = store.getDefaultLanguage()

def onlineCatalogVersion = catalogVersionService.getCatalogVersion(productCatalogId, "Online")
catalogVersionService.setSessionCatalogVersions(java.util.Collections.singletonList(onlineCatalogVersion))

// -----------------------------------------------------------------------
// [2/6] Verify the store has an active PayoneConfiguration with a returnUrl
// -----------------------------------------------------------------------

println ""
println "[2/6] Resolving PayoneConfiguration for store [${store.getUid()}]..."

PayoneConfigurationModel config = null
try {
    config = payoneConfigurationService.getActiveConfigurationForStore(store)
} catch (Exception e) {
    println "  ✗ Could not resolve configuration: ${printSdkError(e)}"
}
if (config == null) {
    println "  ✗ Store [${store.getUid()}] has no active PayoneConfiguration, or its"
    println "    apiSecret/webhookSecret runtime properties are not configured."
    return [passed: 0, failed: 1, skipped: 0]
}
if (config.getReturnUrl() == null) {
    println "  ⚠ PayoneConfiguration.returnUrl is not set — the 3DS/liability-shift"
    println "    redirect (threeDSecure.redirectionData.returnUrl) will not be set."
}
println "  ✓ Resolved PayoneConfiguration (merchantId=${config.merchantId})"

// -----------------------------------------------------------------------
// [3/6] Create a throwaway test customer + DE address
// -----------------------------------------------------------------------

println ""
println "[3/6] Creating test customer [${testCustomerUid}]..."

CustomerModel customer = modelService.create(CustomerModel.class)
customer.setUid(testCustomerUid)
customer.setName("PCP Facade Test Customer (${runId})")
modelService.save(customer)

def countryDE = flexibleSearchService.search("SELECT {pk} FROM {Country} WHERE {isocode} = 'DE'")?.result?.getAt(0)
assertNotNull(countryDE, "DE Country row")

AddressModel address = modelService.create(AddressModel.class)
address.setOwner(customer)
address.setFirstname("Rüdiger")
address.setLastname("Sörensen")
address.setStreetname("Rathausplatz")
address.setStreetnumber("1")
address.setPostalcode("24937")
address.setTown("Flensburg")
address.setCountry(countryDE)
address.setEmail(testCustomerUid)
address.setPhone1("+4930123456")
address.setBillingAddress(true)
address.setShippingAddress(true)
modelService.save(address)

customer.setAddresses(java.util.Collections.singletonList(address))
customer.setDefaultPaymentAddress(address)
customer.setDefaultShipmentAddress(address)
if (currentLanguage != null) {
    customer.setSessionLanguage(currentLanguage)
}
modelService.save(customer)
println "  ✓ Created customer + DE address"

// -----------------------------------------------------------------------
// [4/6] Create a cart with one entry
// -----------------------------------------------------------------------

println ""
println "[4/6] Creating cart..."

def cart = modelService.create(CartModel.class)
cart.setCode("pcp-facade-${runId}")
cart.setGuid("pcp-facade-${runId}")
cart.setUser(customer)
cart.setSite(site)
cart.setStore(store)
cart.setCurrency(cartCurrency)
cart.setDate(new Date())
modelService.save(cart)

ProductModel product = null
if (preferredProductCode != null) {
    try {
        product = flexibleSearchService.search(
            "SELECT {pk} FROM {Product} WHERE {code} = ?code AND {catalogVersion} = ?catalogVersion",
            ["code": preferredProductCode,
             "catalogVersion": catalogVersionService.getCatalogVersion(productCatalogId, "Online")]).result?.getAt(0)
    } catch (Exception ignored) {}
}
if (product == null) {
    def candidatesQuery = new de.hybris.platform.servicelayer.search.FlexibleSearchQuery(
        "SELECT {p:pk} FROM {Product AS p JOIN PriceRow AS pr ON {pr.productId} = {p.code}} " +
        "WHERE {pr.currency} = ?cur AND {p.catalogVersion} = ?catalogVersion")
    candidatesQuery.addQueryParameter("cur", cart.getCurrency())
    candidatesQuery.addQueryParameter("catalogVersion",
        catalogVersionService.getCatalogVersion(productCatalogId, "Online"))
    candidatesQuery.setCount(1)
    def candidates = flexibleSearchService.search(candidatesQuery).result
    assertTrue(candidates != null && !candidates.isEmpty(), "at least one priced product exists")
    product = candidates[0]
}
println "  Using product [${product.getCode()}]"

def entry = cartService.addNewEntry(cart, product, (long) 1, product.getUnit(), -1, true)
assertNotNull(entry, "CartEntryModel")
modelService.save(entry)
modelService.refresh(cart)

cart.setDeliveryAddress(address)
cart.setPaymentAddress(address)
modelService.save(cart)
commerceCartService.recalculateCart(cart)
modelService.refresh(cart)

println "  ✓ Cart [${cart.getCode()}] ready — total ${cart.getTotalPrice()} ${cart.getCurrency()?.isocode}"

// -----------------------------------------------------------------------
// [5/6] Build the One-Step CreateCommerceCaseRequest
// -----------------------------------------------------------------------

println ""
println "[5/6] Building One-Step CreateCommerceCaseRequest (autoExecuteOrder=true + orderRequest)..."

CreateCommerceCaseRequest request = null
test("build request") {
    // Same converters the (not-yet-reachable) facade path would use for the
    // customer/checkout shape - only the order-attachment differs below.
    request = createCommerceCaseRequestConverter.convert(cart, customer, address)
    assertNotNull(request, "CreateCommerceCaseRequest")

    // Same uniqueness fix DefaultPayoneCheckoutFacade.createOrGetCommerceCase
    // applies: the converter's raw merchantReference is just cart.getCode(),
    // which collides across retries (PCP requires it unique per commerce
    // case AND per checkout - api-reference). Override both to the same
    // suffixed value. CommerceCase/CheckoutReferences.merchantReference
    // maxLength is 40 (PcpReferencesConverter.convertCheckoutReferences).
    def merchantReference = PcpMerchantReferenceGenerator.unique(cart.getCode(), 40)
    request.merchantReference(merchantReference)

    CreateCheckoutRequest checkoutRequest = createCheckoutRequestConverter.convert(
            cart, address, customer, cart.getStore()?.getUid())
    assertNotNull(checkoutRequest, "CreateCheckoutRequest")
    checkoutRequest.getReferences()?.merchantReference(merchantReference)

    // orderRequest.orderReferences.merchantReference is a DIFFERENT field
    // with a DIFFERENT, tighter limit: References.merchantReference
    // (PaymentExecution-level, api-reference) maxLength is 20, not 40 - see
    // PcpReferencesConverter.convertReferences and the lesson learned the
    // hard way in pcp-facade-wero-onestep-test.groovy (PCP HTTP 400 "size
    // must be between 0 and 20" when this was wrongly reused as 40 chars).
    def orderMerchantReference = PcpMerchantReferenceGenerator.unique(cart.getCode(), 20)

    // Override the converter's Step-by-Step default (autoExecuteOrder=false -
    // see CreateCheckoutRequestConverter's own comment) - One-Step requires
    // true, same override pattern createOrGetCommerceCase already applies
    // for merchantReference.
    checkoutRequest.autoExecuteOrder(Boolean.TRUE)

    // Transient, never-persisted PayonePaymentInfo carrying the productId and
    // the wallet fields - exactly what PcpMobilePaymentMethodSpecificInputBuilder
    // reads (Apple Pay: minimal cut, no paymentProduct302SpecificInput).
    PayonePaymentInfoModel paymentInfo = modelService.create(PayonePaymentInfoModel.class)
    paymentInfo.setPaymentProductId(paymentProductId)
    if (isApplePay) {
        paymentInfo.setApplePayEncryptedPaymentData(APPLE_PAY_ENCRYPTED_PAYMENT_DATA)
        paymentInfo.setApplePayPublicKeyHash(APPLE_PAY_PUBLIC_KEY_HASH)
        paymentInfo.setApplePayEphemeralKey(APPLE_PAY_EPHEMERAL_KEY)
    } else {
        paymentInfo.setGooglePayEncryptedPaymentData(GOOGLE_PAY_ENCRYPTED_PAYMENT_DATA)
    }

    MobilePaymentMethodSpecificInput mobileInput = mobileBuilder.build(paymentInfo, config)
    assertNotNull(mobileInput, "MobilePaymentMethodSpecificInput")

    // customerDevice is a SIBLING of mobilePaymentMethodSpecificInput on
    // PaymentMethodSpecificInput, not nested inside it - PAYONE's own doc
    // example (docs.commerce.payone.com/docs/payment-methods/google-pay/
    // standard-checkout, "Step 2: Create CommerceCase") always sets
    // customerDevice.ipAddress alongside mobilePaymentMethodSpecificInput.
    // ipAddress alone did NOT resolve the 50092703/VALIDATION_ERROR seen
    // here across frictionless/challenge cards and varying amounts - the
    // SDK source (CustomerDevice.java, PAYONE-GmbH/PCP-ServerSDK-java)
    // documents acceptHeader/userAgent as "can be mandatory depending on
    // the selected payment method and routing option", unlike ipAddress
    // (BNPL's only requirement, which is all PaymentExecutionRequestConverter
    // sets generically). Mobile-wallet flows involve a real browser origin
    // (unlike server-to-server BNPL), so acceptHeader/userAgent are set here
    // as the next candidate for the "depends on payment method" condition.
    CustomerDevice customerDevice = new CustomerDevice()
            .ipAddress("127.0.0.1")
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
            .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

    PaymentMethodSpecificInput methodInput = new PaymentMethodSpecificInput()
            .mobilePaymentMethodSpecificInput(mobileInput)
            .customerDevice(customerDevice)
            .paymentChannel(PaymentChannel.ECOMMERCE)   // required field, api-reference

    checkoutRequest.orderRequest(new OrderRequest()
            .orderType(OrderType.FULL)
            .orderReferences(new References().merchantReference(orderMerchantReference))
            .paymentMethodSpecificInput(methodInput))

    request.checkout(checkoutRequest)

    println "    merchantReference      : ${merchantReference}"
    println "    orderMerchantReference : ${orderMerchantReference}"
    println "    autoExecuteOrder       : ${checkoutRequest.getAutoExecuteOrder()}"
    println "    paymentProductId       : ${paymentProductId} (${walletName})"
}

if (request == null) {
    println ""
    println "  RESULT: FAILED (setup)  pass=${passed} fail=${failed}"
    return [passed: passed, failed: failed, skipped: 0]
}

// -----------------------------------------------------------------------
// [6/6] payoneCommerceCaseService.createCommerceCase — expect a response
// -----------------------------------------------------------------------

println ""
println "[6/6] payoneCommerceCaseService.createCommerceCase(config, request)..."

CreateCommerceCaseResponse response = null
test("createCommerceCase (One-Step, ${walletName})") {
    response = payoneCommerceCaseService.createCommerceCase(config, request)
    assertNotNull(response, "CreateCommerceCaseResponse")
    assertNotNull(response.getCommerceCaseId(), "commerceCaseId")
    assertNotNull(response.getCheckout(), "checkout")
    assertNotNull(response.getCheckout().getCheckoutId(), "checkoutId")
    println "    CommerceCaseId : ${response.getCommerceCaseId()}"
    println "    CheckoutId     : ${response.getCheckout().getCheckoutId()}"

    def paymentResponse = response.getCheckout().getPaymentResponse()
    assertNotNull(paymentResponse, "checkout.paymentResponse — a null value here reproduces the " +
            "same 'no valid paymentExecution' symptom found for Wero even on this One-Step path")

    def merchantAction = paymentResponse.getMerchantAction()
    if (merchantAction != null) {
        println "    actionType     : ${merchantAction.getActionType()}"
        if (merchantAction.getActionType() == ActionType.REDIRECT) {
            def redirectUrl = merchantAction.getRedirectData()?.getRedirectURL()
            println "    redirectUrl    : ${redirectUrl}"
        }
    } else {
        println "    merchantAction : none (direct capture, no 3DS challenge triggered)"
    }

    // A 201/no-exception response only means PCP accepted the REQUEST SHAPE -
    // it says nothing about whether the payment itself was accepted or
    // declined. The verdict can land in one of TWO different places on
    // CreateCheckoutResponse, which are siblings, not nested:
    //   - checkout.paymentResponse.payment.{status,statusOutput}  - a normal
    //     accepted/declined payment result (payment object fully populated)
    //   - checkout.errorResponse                                  - PCP
    //     rejected the payment inline within the 201 and returned a non-null
    //     but EMPTY paymentResponse.payment (every getter null) instead -
    //     confirmed by javap against CreateCheckoutResponse/ErrorResponse in
    //     pcp-serversdk-java-1.13.0.jar. A prior version of this script only
    //     checked the first path and printed "status: null" for exactly this
    //     case, hiding the real rejection reason a human could see in the
    //     PAYONE portal but not from this script's own output.
    def checkout = response.getCheckout()
    def errorResponse = checkout.getErrorResponse()
    if (errorResponse != null) {
        println "    ⚠ checkout.errorResponse is set — PCP rejected this payment inline:"
        println "      errorId : ${errorResponse.getErrorId()}"
        errorResponse.getErrors()?.each { err ->
            println "      - errorCode=${err.getErrorCode()} category=${err.getCategory()} id=${err.getId()} message=${err.getMessage()}"
        }
        println "      checkoutStatus: ${checkout.getCheckoutStatus()}"
        println "      With a FRICTIONLESS card this can be a normal PCP-side risk decision"
        println "      on the token, not a request-shape bug (see doc: 'No challenge, auth"
        println "      declined' is a documented, valid frictionless outcome alongside 'auth"
        println "      accepted'). Compare the errorCode/message above against your merchant"
        println "      setup (paymentModes, wallet activation) before assuming this is"
        println "      just a declined test card."
    }

    def payment = paymentResponse.getPayment()
    assertNotNull(payment, "checkout.paymentResponse.payment")
    def paymentStatus = payment.getStatus()
    def statusCategory = payment.getStatusOutput()?.getStatusCategory()
    println "    paymentId      : ${payment.getId()}"
    println "    status         : ${paymentStatus}"
    println "    statusCategory : ${statusCategory}"
    if (paymentStatus == null && errorResponse == null) {
        println "    ⚠ payment.status is null AND checkout.errorResponse is null — this is"
        println "      neither documented outcome. Dumping the raw checkout object for manual"
        println "      inspection (this indicates a gap in this script's understanding of the"
        println "      response shape, not necessarily a PCP-side problem):"
        println "      ${checkout}"
    }
    if (paymentStatus?.toString() == "REJECTED") {
        println "    ⚠ REJECTED — with a FRICTIONLESS card this is a PCP-side risk decision on"
        println "      the token, not a request-shape bug (see doc: 'No challenge, auth declined'"
        println "      is a documented, valid frictionless outcome alongside 'auth accepted')."
        println "      To get a deterministic result instead, redo the browser step and pick a"
        println "      CHALLENGE test card (e.g. 'Mastercard Challenge') so PCP returns"
        println "      actionType=REDIRECT, then confirm with Visa=1234 / Mastercard=4321 on"
        println "      PAYONE's preprod 3DS page."
    }
}

// =======================================================================
// SUMMARY
// =======================================================================

println ""
println "=" * 75
println "  ${walletName.toUpperCase()} ONE-STEP DIAGNOSTIC COMPLETE (run ${runId}) — pass=${passed} fail=${failed}"
println "=" * 75
if (!errors.isEmpty()) {
    println "  FAILURES:"
    errors.each { println "    • ${it}" }
}
if (placeholder) {
    println ""
    println "  REMINDER: this run used PLACEHOLDER ${walletName} data. A PCP-side"
    println "  token-validation failure above is EXPECTED and does NOT indicate a"
    println "  request-shape bug. Compare the errorCode against 50507002/50102001"
    println "  (the Wero-diagnostic failures caused by a wrong FLOW shape) - a"
    println "  DIFFERENT errorCode means the shape is right and only the token was"
    println "  rejected."
    if (isApplePay) {
        println "  Apple Pay may also be rejected for the missing"
        println "  paymentProduct302SpecificInput - a known gap of the minimal cut."
    }
} else if (failed == 0) {
    println ""
    println "  CONCLUSION: One-Step checkout succeeded for ${walletName} with real"
    println "  data. Same finding as Wero: the Step-by-Step + separate"
    println "  payment-executions call the facade uses would not work for this"
    println "  family either (plugin README, section 10)."
}
println ""

return [passed: passed, failed: failed, skipped: 0]
