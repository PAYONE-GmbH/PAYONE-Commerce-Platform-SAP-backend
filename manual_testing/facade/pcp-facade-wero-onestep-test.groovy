/*
* pcp-facade-wero-onestep-test.groovy
*
* PAYONE PCP — One-Step Checkout diagnostic for Wero (product 900).
*
* WHY THIS SCRIPT EXISTS: pcp-facade-redirect-paypal-test.groovy (paymentProductId =
* 900) fails at authorizePayment with a real PCP 400:
*   errorCode 50507002, payment-error-missing-payment-execution,
*   "There is no valid paymentExecution for the checkout"
* PayPal (paymentProductId = 840) succeeds via the exact same code path in
* that same script - same converters, same PcpRedirectPaymentMethodSpecific-
* InputBuilder, same Step-by-Step create-checkout-then-separately-call-
* payment-executions flow. No known merchant-level restriction rules Wero
* out (checked). docs.commerce.payone.com/docs/payment-methods/wero/'s own
* testing walkthrough only ever demonstrates One-Step Checkout
* (autoExecuteOrder: true, the payment bundled into the initial
* commerce-cases POST) - PayPal's doc page does too, for what it's worth,
* but PayPal already works via Step-by-Step in this project. This script
* isolates whether Wero specifically needs One-Step by building the exact
* shape PAYONE's own doc demonstrates and verified, bypassing
* PayoneCheckoutFacade entirely (it has no One-Step method - see class
* Javadoc on PayoneCheckoutFacade, which is Step-by-Step only).
*
* FULLY SELF-CONTAINED — paste this entire file into a fresh HAC Groovy
* console tab and run it. Does not depend on, and does not modify,
* pcp-facade-redirect-paypal-test.groovy or its PayPal path.
*
* WHAT THIS EXERCISES (payonepcpcore, NOT payonepcpfacades):
*   createCommerceCaseRequestConverter.convert(cart, customer, address)
*   createCheckoutRequestConverter.convert(cart, address, customer, ref)
*     -> then OVERRIDES autoExecuteOrder to true (converter defaults to
*        false - Step-by-Step; see CreateCheckoutRequestConverter's own
*        comment) and attaches an OrderRequest carrying the Wero
*        RedirectPaymentMethodSpecificInput, built via
*        pcpRedirectPaymentMethodSpecificInputBuilder - the SAME builder
*        class the facade path uses, so this is a real like-for-like
*        comparison, not a hand-rolled reimplementation.
*   payoneCommerceCaseService.createCommerceCase(config, request) - single
*     call, no separate payment-executions call at all.
*   Result inspected directly: response.getCheckout().getPaymentResponse()
*     .getMerchantAction() - matches the doc's
*     checkout.paymentResponse.merchantAction.redirectData.redirectURL.
*
* This is a DIAGNOSTIC, not a permanent addition to the facade surface:
* PayoneCheckoutFacade has no One-Step method by design (see the plugin
* README, section 10 "Not yet available in this version"). If this script
* proves One-Step is required for Wero, that is a follow-up design decision
* (new facade method, or accept Wero cannot use the strategy-routed path),
* not something this script itself should carry.
*
* REDIRECT is asynchronous — this script CANNOT complete the flow
* end-to-end in one run (see pcp-facade-redirect-paypal-test.groovy's class
* comment for why). It only proves whether the createCommerceCase call
* itself returns a redirect action (i.e. a valid paymentExecution attached
* to the checkout) instead of erroring.
*
* PREREQUISITES: same as pcp-facade-redirect-paypal-test.groovy - active
* PayoneConfiguration with a returnUrl, product 900 in paymentModes, EUR
* currency, DE/BE billing address.
*
* IMPORTANT: mutates the target environment — creates a real customer, a
* real cart, and a real PCP CommerceCase/Checkout/PaymentExecution attempt.
* Point baseSiteUid at a preprod/sandbox-backed store, never a live
* production merchant. No secret is ever printed. No System.exit().
*/

// =======================================================================
// HARDCODED TEST VALUES (edit for your environment)
// =======================================================================

def baseSiteUid      = "electronics"
def productCatalogId = "electronicsProductCatalog"   // from spartacussampledata site.impex ($productCatalog)

def preferredProductCode = null   // null = pick any saleable product with a price

// Unique per run so repeated executions (and other scripts run concurrently
// in other tabs) never collide on customer uid or cart code.
def runId = "wero-onestep-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10)
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
import com.payone.pcp.core.converters.paymentmethod.PcpRedirectPaymentMethodSpecificInputBuilder

import com.payone.commerce.platform.lib.models.CreateCommerceCaseRequest
import com.payone.commerce.platform.lib.models.CreateCheckoutRequest
import com.payone.commerce.platform.lib.models.CreateCommerceCaseResponse
import com.payone.commerce.platform.lib.models.OrderRequest
import com.payone.commerce.platform.lib.models.OrderType
import com.payone.commerce.platform.lib.models.PaymentMethodSpecificInput
import com.payone.commerce.platform.lib.models.PaymentChannel
import com.payone.commerce.platform.lib.models.References
import com.payone.commerce.platform.lib.models.RedirectPaymentMethodSpecificInput
import com.payone.commerce.platform.lib.models.ActionType

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
println "  PAYONE PCP — Wero (product 900) ONE-STEP checkout diagnostic"
println "  Run id: ${runId}"
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
def redirectBuilder = (PcpRedirectPaymentMethodSpecificInputBuilder) getBean("pcpRedirectPaymentMethodSpecificInputBuilder")

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
if (eur == null) {
println "  ✗ Store [${store.getUid()}] has no EUR currency — Wero requires EUR."
return [passed: 0, failed: 1, skipped: 0]
}
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
println "  ⚠ PayoneConfiguration.returnUrl is not set — the PCP call below"
println "    will likely fail without it."
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
// [4/6] Create a EUR cart with one entry
// -----------------------------------------------------------------------

println ""
println "[4/6] Creating cart..."

def cart = modelService.create(CartModel.class)
cart.setCode("pcp-facade-${runId}")
cart.setGuid("pcp-facade-${runId}")
cart.setUser(customer)
cart.setSite(site)
cart.setStore(store)
cart.setCurrency(eur)
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
// Same converters the Step-by-Step facade path uses for the
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
// PcpReferencesConverter.convertReferences. Confirmed the hard way: PCP
// rejected the 40-char checkout-level reference here with HTTP 400
// "size must be between 0 and 20". Needs its own, separately-generated
// suffixed value within that limit - reusing the checkout-level one is
// a bug, not a simplification.
def orderMerchantReference = PcpMerchantReferenceGenerator.unique(cart.getCode(), 20)

// Override the converter's Step-by-Step default (autoExecuteOrder=false -
// see CreateCheckoutRequestConverter's own comment) - One-Step requires
// true, same override pattern createOrGetCommerceCase already applies
// for merchantReference.
checkoutRequest.autoExecuteOrder(Boolean.TRUE)

// Transient, never-persisted PayonePaymentInfo carrying only the
// productId - exactly what buildTransientPaymentInfo() falls through to
// for REDIRECT-family products with no paymentDetails (see
// DefaultPayoneCheckoutFacade's own comment on that method).
PayonePaymentInfoModel paymentInfo = modelService.create(PayonePaymentInfoModel.class)
paymentInfo.setPaymentProductId(900)

RedirectPaymentMethodSpecificInput redirectInput =
        redirectBuilder.build(paymentInfo, config.getReturnUrl())
assertNotNull(redirectInput, "RedirectPaymentMethodSpecificInput")

PaymentMethodSpecificInput methodInput = new PaymentMethodSpecificInput()
        .redirectPaymentMethodSpecificInput(redirectInput)
        .paymentChannel(PaymentChannel.ECOMMERCE)   // required field, api-reference

checkoutRequest.orderRequest(new OrderRequest()
        .orderType(OrderType.FULL)
        .orderReferences(new References().merchantReference(orderMerchantReference))
        .paymentMethodSpecificInput(methodInput))

request.checkout(checkoutRequest)

println "    merchantReference      : ${merchantReference}"
println "    orderMerchantReference : ${orderMerchantReference}"
println "    autoExecuteOrder       : ${checkoutRequest.getAutoExecuteOrder()}"
println "    paymentProductId  : 900 (Wero)"
}

if (request == null) {
println ""
println "  RESULT: FAILED (setup)  pass=${passed} fail=${failed}"
return [passed: passed, failed: failed, skipped: 0]
}

// -----------------------------------------------------------------------
// [6/6] payoneCommerceCaseService.createCommerceCase — expect a redirect
// -----------------------------------------------------------------------

println ""
println "[6/6] payoneCommerceCaseService.createCommerceCase(config, request)..."

CreateCommerceCaseResponse response = null
test("createCommerceCase (One-Step, Wero)") {
response = payoneCommerceCaseService.createCommerceCase(config, request)
assertNotNull(response, "CreateCommerceCaseResponse")
assertNotNull(response.getCommerceCaseId(), "commerceCaseId")
assertNotNull(response.getCheckout(), "checkout")
assertNotNull(response.getCheckout().getCheckoutId(), "checkoutId")
println "    CommerceCaseId : ${response.getCommerceCaseId()}"
println "    CheckoutId     : ${response.getCheckout().getCheckoutId()}"

def paymentResponse = response.getCheckout().getPaymentResponse()
assertNotNull(paymentResponse, "checkout.paymentResponse — a null value here reproduces the " +
        "50507002 'no valid paymentExecution' symptom even on this One-Step path")

def merchantAction = paymentResponse.getMerchantAction()
assertNotNull(merchantAction, "checkout.paymentResponse.merchantAction")
println "    actionType     : ${merchantAction.getActionType()}"
assertTrue(merchantAction.getActionType() == ActionType.REDIRECT,
        "Wero (direct sale) must return a REDIRECT merchant action")

def redirectUrl = merchantAction.getRedirectData()?.getRedirectURL()
assertNotNull(redirectUrl, "redirectData.redirectURL")
println "    redirectUrl    : ${redirectUrl}"
}

// =======================================================================
// SUMMARY
// =======================================================================

println ""
println "=" * 75
println "  WERO ONE-STEP DIAGNOSTIC COMPLETE (run ${runId}) — pass=${passed} fail=${failed}"
println "=" * 75
if (!errors.isEmpty()) {
println "  FAILURES:"
errors.each { println "    • ${it}" }
}
if (failed == 0) {
println ""
println "  CONCLUSION: One-Step checkout succeeded where Step-by-Step + separate"
println "  payment-executions call (pcp-facade-redirect-paypal-test.groovy, product 900)"
println "  failed with 50507002. This isolates the problem to the Step-by-Step"
println "  flow shape for Wero specifically, not a merchant/account-level issue."
println "  Follow-up: decide whether PayoneCheckoutFacade needs a One-Step-capable"
println "  method for REDIRECT-family products that require it, or whether Wero"
println "  is descoped to One-Step-only integration."
} else {
println ""
println "  CONCLUSION: One-Step also failed - the problem is not the Step-by-Step"
println "  flow shape. Re-check merchant/product activation for Wero (900) with"
println "  PAYONE support, or re-verify the request payload against the doc"
println "  example at docs.commerce.payone.com/docs/payment-methods/wero/."
}
println ""

return [passed: passed, failed: failed, skipped: 0]
