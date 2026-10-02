/*
 * pcp-facade-sepa-test.groovy
 *
 * PAYONE PCP — PayoneCheckoutFacade integration test: SEPA family
 * (SEPA Direct Debit, product 771 — PcpSepaDirectDebitPaymentMethodSpecificInputBuilder).
 *
 * FULLY SELF-CONTAINED — paste this entire file into a fresh HAC Groovy
 * console tab and run it. It does not depend on any other script. Each run
 * creates its own throwaway customer + cart (unique per run, see runId
 * below), so this script can be run repeatedly, or alongside the other
 * pcp-facade-*.groovy scripts in separate tabs, without interfering with
 * itself or them: payoneCheckoutFacade.placeOrder() DELETES the cart it
 * operates on (cartService.removeSessionCart(), confirmed in
 * DefaultCartService), so a shared/reused cart across scripts would break
 * whichever script ran second.
 *
 * WHAT THIS EXERCISES (payonepcpfacades):
 *   payoneCheckoutFacade.createOrGetCommerceCase(cart)
 *   payoneCheckoutFacade.authorizePayment(cart, 771, PaymentDetailsData{iban,...})
 *     -> DefaultPayoneCheckoutFacade.buildTransientPaymentInfo builds a
 *        transient PayoneMandateModel from iban/accountHolder/creditorId/
 *        mandateReference/dateOfSignature, attaches it to a transient
 *        PayonePaymentInfoModel
 *     -> PaymentStrategyRegistry.getStrategy(771, allowedProductIds)
 *     -> SepaDirectDebitStrategy.authorize(...)
 *     -> PcpSepaDirectDebitPaymentMethodSpecificInputBuilder reads
 *        paymentInfo.getMandate() (the SEPA fix, commit f781188)
 *   payoneCheckoutFacade.placeOrder(cart)
 *
 * PREREQUISITES (assumes a working CCv2 installation with proper secrets):
 *   - The BaseSite/BaseStore named below exists, is EUR-denominated, and
 *     carries an active PayoneConfiguration with apiKeyId/merchantId set;
 *     the matching apiSecret/webhookSecret are resolvable from the runtime
 *     environment (CCv2 Cloud Portal properties / local.properties —
 *     never stored in the database).
 *   - The store's PayoneConfiguration.paymentModes includes product 771.
 *   - At least one saleable product with a EUR price row exists.
 *   - The cart's currency must be EUR — the facade rejects SEPA otherwise
 *     (IllegalArgumentException "SEPA Direct Debit requires an EUR cart").
 *
 * SEPA settles synchronously (no redirect) — unlike CARD/REDIRECT there is
 * no 3DS/PSP-page detour, so this script always proceeds straight to
 * placeOrder().
 *
 * IMPORTANT: mutates the target environment — creates a real customer, a
 * real cart, a real PCP CommerceCase/Checkout/Payment, and a real hybris
 * Order. Point baseSiteUid at a preprod/sandbox-backed store, never a live
 * production merchant. No secret is ever printed. No System.exit() — the
 * result is returned as a map at the end.
 */

// =======================================================================
// HARDCODED TEST VALUES (edit for your environment)
// =======================================================================

def baseSiteUid      = "electronics"
def productCatalogId = "electronicsProductCatalog"   // from spartacussampledata site.impex ($productCatalog)
def paymentProductId = 771

def testIban            = "DE75512108001245126199"
def testAccountHolder   = "Test Customer"
def testCreditorId      = "DE98ZZZ09999999999"
def testDateOfSignature = "20240101"

def preferredProductCode = null   // null = pick any saleable product with a price

// Unique per run so repeated executions (and other scripts run concurrently
// in other tabs) never collide on customer uid, cart code, or mandate ref.
def runId = "sepa-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10)
def testCustomerUid = "pcp-facade-test-${runId}@example.com"
def testMandateRef  = "mand-${runId}".take(20)   // PCP caps mandate refs; keep well under

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
println "  PAYONE PCP — SEPA family (product ${paymentProductId}) facade integration test"
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
    println "  ✗ Store [${store.getUid()}] has no EUR currency — SEPA requires EUR."
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
// [3/6] Create a throwaway test customer + address
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
address.setPhone1("+4930123456")
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
println "  ✓ Created customer + DE address"

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
test("authorizePayment — SEPA (product ${paymentProductId})") {
    def details = new PaymentDetailsData()
    details.setIban(testIban)
    details.setAccountHolder(testAccountHolder)
    details.setCreditorId(testCreditorId)
    details.setMandateReference(testMandateRef)
    details.setDateOfSignature(testDateOfSignature)

    authResult = payoneCheckoutFacade.authorizePayment(cart, paymentProductId, details)
    assertNotNull(authResult, "PayoneAuthorizationResultData")
    println "    redirect  : ${authResult.isRedirect()}"
    println "    status    : ${authResult.getStatus()}"
    println "    paymentId : ${authResult.getPaymentId()}"
    // SEPA is synchronous — a redirect here would indicate the builder/strategy
    // wiring is wrong, not an expected outcome.
    assertTrue(!authResult.isRedirect(), "SEPA must not return a redirect action")
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
    test("placeOrder — SEPA") {
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
println "  SEPA TEST COMPLETE (run ${runId}) — pass=${passed} fail=${failed}"
println "=" * 75
if (!errors.isEmpty()) {
    println "  FAILURES:"
    errors.each { println "    • ${it}" }
}
println ""

return [passed: passed, failed: failed, skipped: 0]
