/*
 * pcp-facade-financing-test.groovy
 *
 * PAYONE PCP — PayoneCheckoutFacade integration test: FINANCING family
 * (PAYONE BNPL, B2C only). Switch paymentProductId below:
 *   3390 = PAYONE Secured Invoice
 *   3392 = PAYONE Secured Direct Debit (adds bank account data)
 *
 * FULLY SELF-CONTAINED — paste this entire file into a fresh HAC Groovy
 * console tab and run it. Each run creates its own throwaway customer + cart
 * (unique per run, see runId), so it can run repeatedly or alongside the
 * other pcp-facade-*.groovy scripts: payoneCheckoutFacade.placeOrder()
 * DELETES the cart it operates on, so carts are never shared.
 *
 * SCOPE: B2C only, proven through the facade and core services directly —
 * NOT wired into Spartacus or B2B (PcpCustomerConverter hardcodes
 * BusinessRelation.B2C).
 *
 * WHAT THIS EXERCISES (payonepcpcore + payonepcpfacades):
 *   payoneCheckoutFacade.createOrGetCommerceCase(cart)
 *     -> CreateCommerceCaseRequestConverter -> PcpCustomerConverter, setting
 *        personalInformation.dateOfBirth (payment address dateOfBirth,
 *        yyyyMMdd) and contactDetails.phoneNumber (payment address phone1) —
 *        both mandatory for every PAYONE BNPL payment.
 *   payoneCheckoutFacade.authorizePayment(cart, productId, PaymentDetailsData{
 *       customerIpAddress [, iban, bic, accountHolder, creditorId,
 *       mandateReference, dateOfSignature — 3392 only]})
 *     -> buildTransientPaymentInfo sets PayonePaymentInfo.customerIpAddress
 *        and, when iban is non-blank, PayonePaymentInfo.mandate
 *     -> PaymentStrategyRegistry.getStrategy(productId, allowedProductIds)
 *     -> SecuredInvoiceStrategy / SecuredDirectDebitStrategy.authorize(...)
 *     -> PaymentExecutionRequestConverter sets
 *        PaymentMethodSpecificInput.customerDevice.ipAddress
 *     -> PcpFinancingPaymentMethodSpecificInputBuilder builds
 *        financingPaymentMethodSpecificInput{paymentProductId,
 *        requiresApproval}; for 3392 it also attaches
 *        paymentProduct3392SpecificInput.bankAccountInformation{iban, bic,
 *        accountHolder} from paymentInfo.getMandate() (requireNonNull — a
 *        missing mandate is a hard failure). For 3390 it correctly does NOT
 *        populate paymentProduct3392SpecificInput, per PCP's docs.
 *   payoneCheckoutFacade.placeOrder(cart)
 *
 * PREREQUISITES (assumes a working CCv2 installation with proper secrets):
 *   - The BaseSite/BaseStore named below carries an active
 *     PayoneConfiguration with apiKeyId/merchantId set; apiSecret/
 *     webhookSecret resolvable from the runtime environment (never stored in the database).
 *   - PayoneConfiguration.paymentModes includes the chosen product.
 *   - Germany or Austria, EUR (docs.commerce.payone.com/docs/payment-methods/
 *     payone-bnpl/ — "General prerequisites"). This script uses a DE
 *     customer/address and forces EUR.
 *   - The product must be activated for the merchant on PAYONE's side. If
 *     not, 3390 returns errorCode 50090923 PAYMENT_METHOD_NOT_ALLOWED and 3392
 *     returns errorCode 1201 PAYMENT_PRODUCT_CONFIGURATION_ERROR — confirm with
 *     manual_testing/pcp-financing-raw-api-test.groovy.
 *   - 3392: testIban is syntactically valid but not a real account; PCP is
 *     expected to reject it with a documented error (invalid IBAN / risk
 *     decline) rather than a 500 — that already proves the request shape.
 *   - PCP preprod must actually approve this (synthetic) customer for BNPL —
 *     a real credit-risk decision on PAYONE's side. A REJECTED outcome is a
 *     business decision, not necessarily a code bug; check the printed error.
 *
 * NOT COVERED: an authorised-but-uncaptured BNPL payment (requiresApproval =
 * true) — PcpFinancingPaymentMethodSpecificInputBuilder hardcodes
 * requiresApproval(false). For an uncaptured payment use
 * pcp-facade-card-test.groovy with preAuthorization = true.
 *
 * IMPORTANT: mutates the target environment — creates a real customer, a
 * real cart, a real PCP CommerceCase/Checkout/Payment, and a real hybris
 * Order. Preprod/sandbox only. No secret is ever printed. No System.exit() —
 * the result is returned as a map at the end.
 */

// =======================================================================
// HARDCODED TEST VALUES (edit for your environment)
// =======================================================================

def baseSiteUid      = "electronics"
def productCatalogId = "electronicsProductCatalog"   // from spartacussampledata site.impex ($productCatalog)
def paymentProductId = 3390   // 3390 = Secured Invoice, 3392 = Secured Direct Debit

def testDateOfBirth    = java.time.LocalDate.of(1978, 1, 1)   // PCP example uses 1978-01-01; well over any age-check threshold
def testCustomerPhone  = "0188711725"                          // PCP's own Secured Invoice example value
def testCustomerIp     = "127.0.0.1"                           // any syntactically valid IPv4 works against preprod

// Bank account data — only sent for 3392 (Secured Direct Debit). Syntactically
// valid DE IBAN (correct check-digit structure), NOT a real bank account —
// see header note on the expected PCP outcome for this.
def testIban            = "DE89370400440532013000"
def testBic             = "COBADEFFXXX"   // Commerzbank BIC, paired with a syntactically valid but non-real IBAN
def testAccountHolder   = "Rüdiger Sörensen"
def testCreditorId      = "DE98ZZZ09999999999"   // PCP's own SEPA example creditor id
def testMandateReference = "MANDATE-${System.currentTimeMillis()}"
def testDateOfSignature  = "2024-01-01"

def preferredProductCode = null   // null = pick any saleable product with a price

// Unique per run so repeated executions (and other scripts run concurrently
// in other tabs) never collide on customer uid or cart code.
def runId = "financing-${paymentProductId}-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10)
def testCustomerUid = "pcp-facade-test-${runId}@example.com"

// =======================================================================
// IMPORTS
// =======================================================================

import de.hybris.platform.core.model.order.CartModel
import de.hybris.platform.core.model.user.CustomerModel
import de.hybris.platform.core.model.user.AddressModel
import de.hybris.platform.core.model.product.ProductModel
import de.hybris.platform.basecommerce.model.site.BaseSiteModel
import de.hybris.platform.servicelayer.model.ModelService

import com.payone.pcp.core.model.PayoneConfigurationModel
import com.payone.pcp.core.service.PayoneConfigurationService
import com.payone.pcp.facades.PayoneCheckoutFacade
import com.payone.pcp.facades.data.CommerceCaseResultData
import com.payone.pcp.facades.data.PaymentDetailsData
import com.payone.pcp.facades.data.PayoneAuthorizationResultData
import com.payone.pcp.facades.data.PayoneCheckoutData

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
def productName = paymentProductId == 3392 ? "Secured Direct Debit" : "Secured Invoice"
println "  PAYONE PCP — FINANCING family (${productName}, product ${paymentProductId}) facade integration test"
println "  Run id: ${runId}"
println "=" * 75

def baseSiteService = getBean("baseSiteService")
def modelService = (ModelService) getBean("modelService")
def cartService = getBean("cartService")
def commerceCartService = getBean("commerceCartService")
def flexibleSearchService = getBean("flexibleSearchService")
def catalogVersionService = getBean("catalogVersionService")
def commonI18NService = getBean("commonI18NService")
def payoneConfigurationService = (PayoneConfigurationService) getBean("payoneConfigurationService")
def payoneCheckoutFacade = (PayoneCheckoutFacade) getBean("payoneCheckoutFacade")

// -----------------------------------------------------------------------
// [1/6] Resolve BaseSite/BaseStore, set current on session
// -----------------------------------------------------------------------

println ""
println "[1/6] Resolving BaseSite [${baseSiteUid}]..."

// Deliberately NOT baseSiteService.setCurrentBaseSite(site, ...): that mutates
// global session state for the whole HAC console tab, the same class of
// problem as userService.setCurrentUser() below. BaseSite.stores is a direct
// relation - read the store straight off the site instead.
BaseSiteModel site = baseSiteService.getBaseSiteForUID(baseSiteUid)
if (site == null) {
    println "  ✗ BaseSite [${baseSiteUid}] not found — cannot continue."
    return [passed: 0, failed: 1, skipped: 0]
}
def store = site.getStores()?.getAt(0)
assertNotNull(store, "a BaseStore linked to BaseSite [${baseSiteUid}]")
println "  ✓ BaseSite/BaseStore: [${site.getUid()}] / [${store.getUid()}]"

// eur/currentLanguage below are plain local variables, not
// commonI18NService.setCurrentCurrency/Language() session state - the cart
// created in step [4/6] carries its own currency/site/store directly.
def eur = null
if (store.getCurrencies() != null && !store.getCurrencies().isEmpty()) {
    eur = store.getCurrencies().find { it.isocode == "EUR" }
}
if (eur == null) {
    println "  ✗ Store [${store.getUid()}] has no EUR currency — ${productName} requires EUR (DE/AT only)."
    return [passed: 0, failed: 1, skipped: 0]
}
def currentLanguage = store.getDefaultLanguage()

// This is the one piece of session state this script does set, deliberately:
// OOTB commerceCartService.addToCart()/recalculateCart() re-resolve products
// internally via CatalogVersionService.getSessionCatalogVersions() rather
// than via the exact ProductModel PK we already hold, and throw
// AmbiguousIdentifierException when a product code exists in more than one
// catalog version (Online + Staged here) and no session catalog version
// narrows it down. Unlike userService.setCurrentUser()/baseSiteService.
// setCurrentBaseSite() (which point the session at a specific, possibly
// short-lived DB row - a customer or a cart), a catalog version is stable
// reference configuration that is never deleted mid-script, so this carries
// none of the "leaves the HAC console broken" risk that the other calls did.
def onlineCatalogVersion = catalogVersionService.getCatalogVersion(productCatalogId, "Online")
catalogVersionService.setSessionCatalogVersions(java.util.Collections.singletonList(onlineCatalogVersion))

// -----------------------------------------------------------------------
// [2/6] Verify the store has an active PayoneConfiguration
// -----------------------------------------------------------------------

println ""
println "[2/6] Resolving PayoneConfiguration for store [${store.getUid()}]..."

// getActiveConfigurationForStore(store) instead of getCurrentActiveConfiguration()
// - the latter reads baseStoreService.getCurrentBaseStore(), i.e. session
// state we deliberately never set above.
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
println "  ✓ Resolved PayoneConfiguration (merchantId=${config.merchantId})"

// -----------------------------------------------------------------------
// [3/6] Create a throwaway test customer + address (with dateOfBirth/phone)
// -----------------------------------------------------------------------

println ""
println "[3/6] Creating test customer [${testCustomerUid}]..."

CustomerModel customer = modelService.create(CustomerModel.class)
customer.setUid(testCustomerUid)
customer.setName("PCP Facade Test Customer (${runId})")
modelService.save(customer)
// Deliberately NOT userService.setCurrentUser(customer): that overwrites the
// HAC console's own session user for the rest of the browser tab. If anything
// later in this script rolls back / deletes the throwaway customer, the HAC
// session is left pointing at a dead user pk, breaking every subsequent
// request in that tab (SessionFilter -> "Entity not found ... core_User").
// PayoneCheckoutFacade never reads the session user - it reads cart.getUser()
// - so the customer only needs to be set directly on the cart below.

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
address.setPhone1(testCustomerPhone)
// dateOfBirth/phone1 are what PcpCustomerConverter reads for
// personalInformation.dateOfBirth / contactDetails.phoneNumber - both
// mandatory for every PAYONE BNPL payment (see header). Without these two
// lines this test regresses to the old "PCP rejects the request" behavior.
address.setDateOfBirth(java.sql.Date.valueOf(testDateOfBirth))
address.setBillingAddress(true)
address.setShippingAddress(true)
modelService.save(address)

customer.setAddresses(java.util.Collections.singletonList(address))
customer.setDefaultPaymentAddress(address)
customer.setDefaultShipmentAddress(address)
// sessionLanguage is a persistent Customer attribute (the customer's stored
// language preference), NOT commonI18NService session state - safe to set
// directly. PcpCustomerConverter reads it for Customer.locale.
if (currentLanguage != null) {
    customer.setSessionLanguage(currentLanguage)
}
modelService.save(customer)
println "  ✓ Created customer + DE address (dateOfBirth=${testDateOfBirth}, phone=${testCustomerPhone})"

// -----------------------------------------------------------------------
// [4/6] Create a cart with one entry (forced to EUR)
// -----------------------------------------------------------------------

println ""
println "[4/6] Creating cart..."

// Built directly with modelService.create() instead of
// cartService.getSessionCart(): the latter auto-creates via
// CommerceCartFactory, which reads the session's current user/site/store/
// currency - exactly the global state this script avoids setting.
// PayoneCheckoutFacade only ever reads cart.getUser()/getStore()/getCurrency()
// (never session services), so a fully explicit cart works identically.
//
// guid: CommerceCartFactory always sets one (via CartService.generateGuid()/
// KeyGenerator) - without it, commerceCheckoutService.placeOrder() ->
// WebServicesPlaceOrderHook.afterPlaceOrder() (an OOTB
// CommercePlaceOrderMethodHook enabled by placeOrderParameter.setEnableHooks
// (true)) throws IllegalArgumentException("Parameter 'cartGuid' must not be
// null!") from PaymentSubscriptionResultService.removePaymentSubscriptionResultForCart.
// Any unique string works; the hook only uses it as a lookup key.
CartModel cart = modelService.create(CartModel.class)
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
        // Filtered to the Online catalog version - see the fallback search
        // below for why (AmbiguousIdentifierException otherwise).
        product = flexibleSearchService.search(
            "SELECT {pk} FROM {Product} WHERE {code} = ?code AND {catalogVersion} = ?catalogVersion",
            ["code": preferredProductCode,
             "catalogVersion": catalogVersionService.getCatalogVersion(productCatalogId, "Online")]).result?.getAt(0)
    } catch (Exception ignored) {}
}
if (product == null) {
    // PriceRow has NO "product" relation attribute - it stores the product
    // code as a plain String in "productId" (confirmed: europe1-items.xml
    // declares PriceRow.productId as java.lang.String). The previous join
    // condition {pr.product} = {p:pk} referenced a field that does not
    // exist on PriceRow; FlexibleSearch silently returned zero rows for it
    // instead of failing loudly, which read as a hang in the HAC console.
    // Join on productId = code instead.
    //
    // Filtered to the Online catalog version: the same product code exists
    // in multiple catalog versions (Online + Staged), and without this
    // filter downstream code that re-resolves the product by code (observed:
    // commerceCartService.addToCart) throws AmbiguousIdentifierException -
    // this script never sets a session catalog version (deliberately), so
    // the candidate returned here must be catalog-version-unambiguous on
    // its own.
    def candidatesQuery = new de.hybris.platform.servicelayer.search.FlexibleSearchQuery(
        "SELECT {p:pk} FROM {Product AS p JOIN PriceRow AS pr ON {pr.productId} = {p.code}} " +
        "WHERE {pr.currency} = ?cur AND {p.catalogVersion} = ?catalogVersion")
    candidatesQuery.addQueryParameter("cur", eur)
    candidatesQuery.addQueryParameter("catalogVersion",
        catalogVersionService.getCatalogVersion(productCatalogId, "Online"))
    candidatesQuery.setCount(1)   // any one priced product suffices - avoids a full-table scan/join on large catalogs
    def candidates = flexibleSearchService.search(candidatesQuery).result
    assertTrue(candidates != null && !candidates.isEmpty(), "at least one EUR-priced product exists")
    product = candidates[0]
}
println "  Using product [${product.getCode()}]"

// commerceCartService.addToCart() is deliberately NOT used here: internally
// (DefaultCommerceAddToCartStrategy.isProductForCode) it re-resolves the
// product via ProductService.getProductForCode(String) - a lookup that has
// NO catalog-version awareness at all (confirmed in ProductDao.
// findProductsByCode(String), which queries only by code) and throws
// AmbiguousIdentifierException whenever the same code exists in more than
// one catalog version, no matter what session catalog version is set. Since
// we already hold the exact ProductModel PK, cartService.addNewEntry(...)
// (DefaultAbstractOrderService) adds the entry directly against that PK and
// never re-resolves by code, sidestepping the ambiguity entirely.
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
// [5/6] createOrGetCommerceCase + authorizePayment
// -----------------------------------------------------------------------

println ""
println "[5/6] payoneCheckoutFacade.createOrGetCommerceCase(cart)..."

CommerceCaseResultData commerceCaseResult = null
test("createOrGetCommerceCase") {
    commerceCaseResult = payoneCheckoutFacade.createOrGetCommerceCase(cart)
    assertNotNull(commerceCaseResult, "CommerceCaseResultData")
    assertNotNull(commerceCaseResult.getCommerceCaseId(), "commerceCaseId")
    assertNotNull(commerceCaseResult.getCheckoutId(), "checkoutId")
    println "    CommerceCaseId : ${commerceCaseResult.getCommerceCaseId()}"
    println "    CheckoutId     : ${commerceCaseResult.getCheckoutId()}"
}

if (commerceCaseResult == null) {
    println ""
    println "  RESULT: FAILED (setup)  pass=${passed} fail=${failed}"
    return [passed: passed, failed: failed, skipped: 0]
}

println ""
println "      payoneCheckoutFacade.authorizePayment(cart, ${paymentProductId}, ...)..."

PayoneAuthorizationResultData authResult = null
test("authorizePayment — ${productName} (product ${paymentProductId})") {
    def details = new PaymentDetailsData()
    details.setCustomerIpAddress(testCustomerIp)
    if (paymentProductId == 3392) {
        details.setIban(testIban)
        details.setBic(testBic)
        details.setAccountHolder(testAccountHolder)
        details.setCreditorId(testCreditorId)
        details.setMandateReference(testMandateReference)
        details.setDateOfSignature(testDateOfSignature)
    }

    authResult = payoneCheckoutFacade.authorizePayment(cart, paymentProductId, details)
    assertNotNull(authResult, "PayoneAuthorizationResultData")
    println "    redirect  : ${authResult.isRedirect()}"
    println "    status    : ${authResult.getStatus()}"
    println "    paymentId : ${authResult.getPaymentId()}"
    // PAYONE BNPL settles synchronously in this flow (autoExecuteOrder
    // is not set by this script, so PCP either captures immediately or reports
    // a status without a redirect) - unlike CARD/REDIRECT there is no 3DS/PSP
    // page detour.
    assertTrue(!authResult.isRedirect(), "${productName} must not return a redirect action")
    // A REJECTED status here can mean either PCP's own credit/BNPL risk check
    // declined this synthetic customer, or (3392, testIban is not a real bank
    // account) an invalid-IBAN rejection - both real business/data
    // outcomes, not necessarily a code defect. Print the status and move on
    // rather than hard-failing the whole script; if it's REJECTED, check the
    // printed error before assuming the builder's 3392 branch regressed (that
    // would surface as a request-shape error, e.g. missing
    // paymentProduct3392SpecificInput or a NullPointerException from the
    // mandate requireNonNull, not an IBAN/risk decline).
}

// -----------------------------------------------------------------------
// [6/6] placeOrder
// -----------------------------------------------------------------------

println ""
println "[6/6] payoneCheckoutFacade.placeOrder(cart)..."

if (authResult != null) {
    // Another deliberate, narrow session exception (same class as
    // catalogVersionService.setSessionCatalogVersions() in step [1/6]):
    // commerceCheckoutService.placeOrder() (called inside
    // payoneCheckoutFacade.placeOrder()) delegates to OOTB
    // DefaultCommercePlaceOrderStrategy, which sets the resulting Order's
    // site/store/language directly from baseSiteService.getCurrentBaseSite()/
    // baseStoreService.getCurrentBaseStore()/commonI18NService.
    // getCurrentLanguage() - not from the cart - regardless of what the cart
    // itself carries. Without this, the placed Order would end up with
    // site=null/store=null/language=null. Site/store/language are stable
    // reference configuration, not a short-lived DB row, so this carries
    // none of the "leaves the HAC console broken" risk userService.
    // setCurrentUser()/baseSiteService.setCurrentBaseSite() elsewhere in
    // this script were removed for. setCurrentBaseSite() alone should drive
    // getCurrentBaseStore() too (BaseStoreService resolves it via
    // BaseSite.stores), but commonI18NService's current language is tracked
    // independently, so it is set explicitly as well.
    baseSiteService.setCurrentBaseSite(site, false)
    if (currentLanguage != null) {
        commonI18NService.setCurrentLanguage(currentLanguage)
    }

    PayoneCheckoutData orderResult = null
    test("placeOrder — ${productName}") {
        orderResult = payoneCheckoutFacade.placeOrder(cart)
        assertNotNull(orderResult, "PayoneCheckoutData")
        println "    commerceCaseId : ${orderResult.getCommerceCaseId()}"
        println "    checkoutId     : ${orderResult.getCheckoutId()}"
        println "    paymentId      : ${orderResult.getPaymentId()}"
        println "    status         : ${orderResult.getStatus()}"
    }

    test("PaymentTransaction re-linked to the placed order") {
        def order = flexibleSearchService.search(
            "SELECT {pk} FROM {Order} WHERE {payoneCommerceCaseId} = ?id",
            ["id": commerceCaseResult.getCommerceCaseId()]).result?.getAt(0)
        assertNotNull(order, "placed Order (found via payoneCommerceCaseId)")
        def txs = order.getPaymentTransactions()
        assertTrue(txs != null && !txs.isEmpty(), "order has at least one PaymentTransaction")
    }
}

// =======================================================================
// SUMMARY
// =======================================================================

println ""
println "=" * 75
println "  ${productName.toUpperCase()} TEST COMPLETE (run ${runId}) — pass=${passed} fail=${failed}"
println "=" * 75
if (!errors.isEmpty()) {
    println "  FAILURES:"
    errors.each { println "    • ${it}" }
}
println ""

return [passed: passed, failed: failed, skipped: 0]
