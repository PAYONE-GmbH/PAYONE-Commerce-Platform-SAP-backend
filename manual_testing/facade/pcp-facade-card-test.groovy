/*
 * pcp-facade-card-test.groovy
 *
 * PAYONE PCP — PayoneCheckoutFacade integration test: CARD family
 * (Visa=1, Amex=2, Mastercard=3, Diners=132 — all dispatch through
 * PcpCardPaymentMethodSpecificInputBuilder, product ID only differs).
 *
 * Two modes, switched by preAuthorization below:
 *   false (default) — SALE, using the store's configured
 *     defaultAuthorizationMode: authorize + placeOrder, PaymentTransaction
 *     re-link asserted.
 *   true — authorised but NOT captured ("capture step missing"): the
 *     store's PayoneConfiguration.defaultAuthorizationMode is switched to
 *     PRE_AUTHORIZATION for this run (restored in the finally-block - other
 *     checkouts on the same store during the run also get PRE_AUTHORIZATION,
 *     so use a preprod store nobody else is testing on). After placeOrder the
 *     uncaptured state is shown from both sides (PCP getCheckout ->
 *     openAmount/collectedAmount + execution events, expect PENDING_CAPTURE;
 *     hybris Order.paymentStatus + PaymentTransactionEntries) and, with
 *     doCapture = true, captured via payonePaymentOperationsFacade.
 *     SEPA (771) has no authorizationMode field, so CARD is the deterministic
 *     way to produce an uncaptured payment. Re-inspect any time with
 *     manual_testing/pcp-show-uncaptured-payment.groovy.
 *
 * FULLY SELF-CONTAINED — paste this entire file into a fresh HAC Groovy
 * console tab and run it. Each run creates its own throwaway customer + cart
 * (unique per run, see runId), so it can run repeatedly or alongside the
 * other pcp-facade-*.groovy scripts: payoneCheckoutFacade.placeOrder()
 * DELETES the cart it operates on, so carts are never shared.
 *
 * WHAT THIS EXERCISES (payonepcpfacades):
 *   payoneCheckoutFacade.createOrGetCommerceCase(cart)
 *   payoneCheckoutFacade.authorizePayment(cart, 1, PaymentDetailsData{token})
 *     -> PaymentStrategyRegistry.getStrategy(1, allowedProductIds)
 *     -> VisaCardStrategy.authorize(...) -> PcpCardPaymentMethodSpecificInputBuilder
 *     -> real PCP PaymentExecution call
 *   payoneCheckoutFacade.placeOrder(cart)
 *     -> commerceCheckoutService.placeOrder(...) + PaymentTransaction re-link
 *        (the payone-checkout-facade-strategy-dispatch-analysis.md §3 fix)
 *
 * PREREQUISITES (assumes a working CCv2 installation with proper secrets):
 *   - The BaseSite/BaseStore named below carries an active
 *     PayoneConfiguration with apiKeyId/merchantId set; apiSecret/
 *     webhookSecret resolvable from the runtime environment (never stored in the database).
 *   - PayoneConfiguration.paymentModes includes the chosen product.
 *   - At least one saleable product with a price row in the store currency.
 *   - A CARD paymentProcessingToken from the hosted tokenizer. On preprod,
 *     PAYONE publishes fixed test tokens per scheme/outcome
 *     (docs.commerce.payone.com/docs/payment-methods/credit-card/). This
 *     script CANNOT generate a token: tokenization only happens client-side
 *     via the hosted iframe (PCI scope stays with PAYONE). If authorize returns a 3DS redirect,
 *     placeOrder and the capture-state part are skipped; use a frictionless
 *     test token.
 *
 * IMPORTANT: mutates the target environment (customer, cart, PCP
 * CommerceCase/Checkout/Payment, hybris Order and — with preAuthorization —
 * temporarily the PayoneConfiguration). Preprod/sandbox only. No secret is
 * printed. No System.exit(). Run with HAC commit mode ON.
 */

// =======================================================================
// HARDCODED TEST VALUES (edit for your environment)
// =======================================================================

def baseSiteUid       = "electronics"
def productCatalogId  = "electronicsProductCatalog"   // from spartacussampledata site.impex ($productCatalog)
def paymentProductId  = 1   // Visa. Swap for 2 (Amex) / 3 (Mastercard) / 132 (Diners) to test other schemes.

// PAYONE preprod publishes fixed test paymentProcessingTokens per scheme —
// see docs.commerce.payone.com/docs/payment-methods/credit-card/. Replace
// with a real token from your preprod test-token list.
def testPaymentProcessingToken = "REPLACE_WITH_PREPROD_TEST_TOKEN"

def preferredProductCode = null   // null = pick any saleable product with a price

def preAuthorization = false   // true = PRE_AUTHORIZATION run: payment stays uncaptured, state is shown
def doCapture        = false   // only with preAuthorization: capture at the end and show the state again

// Unique per run so repeated executions (and other scripts run concurrently
// in other tabs) never collide on customer uid or cart code.
def runId = (preAuthorization ? "card-preauth-" : "card-") + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10)
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
import com.payone.pcp.facades.PayonePaymentOperationsFacade
import com.payone.pcp.core.service.PayoneCheckoutService

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
println "  PAYONE PCP — CARD (product ${paymentProductId}) facade integration test" + (preAuthorization ? " — PRE_AUTHORIZATION, no capture" : "")
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

// currentCurrency/currentLanguage below are plain local variables, not
// commonI18NService.setCurrentCurrency/Language() session state - the cart
// created in step [4/6] carries its own currency/site/store directly.
def currentCurrency = null
if (store.getCurrencies() != null && !store.getCurrencies().isEmpty()) {
    currentCurrency = store.getCurrencies().find { it.isocode == "EUR" } ?: store.getCurrencies()[0]
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

// preAuthorization only: switch the store's configuration to
// PRE_AUTHORIZATION for this run. The card builder reads
// config.getDefaultAuthorizationMode() on every authorize call (no cache), so
// the change takes effect immediately. Restored in the finally-block.
def originalAuthMode = config.getDefaultAuthorizationMode()
if (preAuthorization) {
    def enumerationService = getBean("enumerationService")
    def preAuth = enumerationService.getEnumerationValue("PayoneAuthorizationModeEnum", "PRE_AUTHORIZATION")
    config.setDefaultAuthorizationMode(preAuth)
    modelService.save(config)
    println "  ✓ defaultAuthorizationMode: ${originalAuthMode?.code} -> PRE_AUTHORIZATION (restored at end)"
}

try {
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
    // [4/6] Create a cart with one entry
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
    cart.setCurrency(currentCurrency)
    cart.setDate(new Date())
    modelService.save(cart)

    ProductModel product = null
    if (preferredProductCode != null) {
        try {
            // Filtered to the Online catalog version for the same reason as the
            // fallback search below - an unfiltered code lookup can be ambiguous
            // across catalog versions.
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
        // this script never sets a session catalog version (deliberately, see
        // the session-independence notes above), so the candidate returned here
        // must already be catalog-version-unambiguous on its own.
        def candidatesQuery = new de.hybris.platform.servicelayer.search.FlexibleSearchQuery(
            "SELECT {p:pk} FROM {Product AS p JOIN PriceRow AS pr ON {pr.productId} = {p.code}} " +
            "WHERE {pr.currency} = ?cur AND {p.catalogVersion} = ?catalogVersion")
        candidatesQuery.addQueryParameter("cur", currentCurrency)
        candidatesQuery.addQueryParameter("catalogVersion",
            catalogVersionService.getCatalogVersion(productCatalogId, "Online"))
        candidatesQuery.setCount(1)   // any one priced product suffices - avoids a full-table scan/join on large catalogs
        def candidates = flexibleSearchService.search(candidatesQuery).result
        assertTrue(candidates != null && !candidates.isEmpty(), "at least one priced product exists")
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
    test("authorizePayment — CARD (product ${paymentProductId})") {
        def details = new PaymentDetailsData()
        details.setPaymentProcessingToken(testPaymentProcessingToken)

        authResult = payoneCheckoutFacade.authorizePayment(cart, paymentProductId, details)
        assertNotNull(authResult, "PayoneAuthorizationResultData")
        println "    redirect  : ${authResult.isRedirect()}"
        println "    status    : ${authResult.getStatus()}"
        println "    paymentId : ${authResult.getPaymentId()}"
        if (authResult.isRedirect()) {
            println "    redirectUrl : ${authResult.getRedirectUrl()}"
        }
        if (!authResult.isRedirect()) {
            assertTrue(authResult.getStatus() != "REJECTED" && authResult.getStatus() != "ERROR",
                "expected a non-terminal-failure status, got [${authResult.getStatus()}]")
        }
    }

    // -----------------------------------------------------------------------
    // [6/6] placeOrder (only if authorize did not require a redirect)
    // -----------------------------------------------------------------------

    println ""
    if (authResult != null && authResult.isRedirect()) {
        println "[6/6] Skipping placeOrder — CARD authorization returned a redirect"
        println "      (3DS challenge). In a real storefront the customer would be"
        println "      sent to: ${authResult.getRedirectUrl()}"
        println "      then payoneCheckoutFacade.handleRedirectCallback(cart, checkoutId)"
        println "      would run before placeOrder()."
    } else if (authResult != null) {
        println "[6/6] payoneCheckoutFacade.placeOrder(cart)..."

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
        test("placeOrder — CARD") {
            orderResult = payoneCheckoutFacade.placeOrder(cart)
            assertNotNull(orderResult, "PayoneCheckoutData")
            assertNotNull(orderResult.getCommerceCaseId(), "commerceCaseId")
            println "    commerceCaseId : ${orderResult.getCommerceCaseId()}"
            println "    checkoutId     : ${orderResult.getCheckoutId()}"
            println "    paymentId      : ${orderResult.getPaymentId()}"
            println "    status         : ${orderResult.getStatus()}"
        }

        // Verify the PaymentTransaction was actually re-linked to the order (the
        // fix documented in payone-checkout-facade-strategy-dispatch-analysis.md
        // §3 — this is the assertion that would have caught the original gap).
        test("PaymentTransaction re-linked to the placed order") {
            def order = flexibleSearchService.search(
                "SELECT {pk} FROM {Order} WHERE {payoneCommerceCaseId} = ?id",
                ["id": commerceCaseResult.getCommerceCaseId()]).result?.getAt(0)
            assertNotNull(order, "placed Order (found via payoneCommerceCaseId)")
            def txs = order.getPaymentTransactions()
            assertTrue(txs != null && !txs.isEmpty(), "order has at least one PaymentTransaction")
            def payoneTx = txs.find { it.getPayoneCommerceCaseId() == commerceCaseResult.getCommerceCaseId() }
            assertNotNull(payoneTx, "PaymentTransaction linked to this commerce case")
            println "    Order [${order.getCode()}] has PaymentTransaction [${payoneTx.getCode()}]"
        }
    }


    // -----------------------------------------------------------------------
    // [7/7] Show the uncaptured payment (PCP + hybris), optional capture
    // -----------------------------------------------------------------------

    def showState = { String label ->
        println ""
        println "  --- ${label} ---"
        def order = flexibleSearchService.search(
            "SELECT {pk} FROM {Order} WHERE {payoneCommerceCaseId} = ?id AND {versionID} IS NULL",
            ["id": commerceCaseResult.getCommerceCaseId()]).result?.getAt(0)
        if (order == null) { println "  no placed order found"; return null }
        modelService.refresh(order)

        // PCP side (source of truth)
        def checkout = ((PayoneCheckoutService) getBean("payoneCheckoutService"))
            .getCheckout(config, commerceCaseResult.getCommerceCaseId(), commerceCaseResult.getCheckoutId())
        def so = checkout.statusOutput
        println "  PCP    : checkoutStatus=${checkout.checkoutStatus} paymentStatus=${so?.paymentStatus} modifiable=${so?.isModifiable}"
        println "           amount=${checkout.amountOfMoney?.amount} open=${so?.openAmount} collected=${so?.collectedAmount}"
        (checkout.paymentExecutions ?: []).each { pe ->
            println "           execution ${pe.paymentExecutionId} (paymentId=${pe.paymentId})"
            (pe.events ?: []).sort { it.creationDateTime }.each { ev ->
                println "             · ${ev.type} -> ${ev.paymentStatus} ${ev.amountOfMoney?.amount ?: ''}"
            }
        }

        // hybris side
        println "  hybris : order ${order.code} paymentStatus=${order.paymentStatus} total=${order.totalPrice} ${order.currency?.isocode}"
        (order.paymentTransactions ?: []).collectMany { it.entries ?: [] }.sort { it.time }.each {
            println "           - ${it.type} ${it.transactionStatus}/${it.transactionStatusDetails} amount=${it.amount}"
        }
        return [order: order, open: so?.openAmount ?: 0L, collected: so?.collectedAmount ?: 0L]
    }

    if (preAuthorization && authResult != null && !authResult.isRedirect()) {
        println ""
        println "[7/7] Payment state after placeOrder (capture still missing)..."

        def before = null
        test("PCP reports an open, uncaptured amount (PRE_AUTHORIZATION)") {
            before = showState("BEFORE capture")
            assertNotNull(before, "state")
            assertTrue(before.open > 0 && before.collected == 0,
                "expected open > 0 and collected == 0, got open=${before.open} collected=${before.collected}")
        }

        if (doCapture && before?.order != null) {
            test("capturePayment (full amount) closes the open amount") {
                def ops = (PayonePaymentOperationsFacade) getBean("payonePaymentOperationsFacade")
                def result = ops.capturePayment(before.order)
                println "    capture -> status=${result.status} paymentId=${result.paymentId}"
                def after = showState("AFTER capture")
                // Capture may be asynchronous (CAPTURE_REQUESTED) - report rather
                // than insist on collected == amount immediately.
                assertTrue(after.collected > 0 || result.status in ["CAPTURE_REQUESTED", "CAPTURED"],
                    "expected capture to be requested/collected, got status=${result.status} collected=${after.collected}")
            }
        } else {
            println ""
            println "  Capture NOT executed (doCapture=false). Order stays uncaptured;"
            println "  re-inspect later with manual_testing/pcp-show-uncaptured-payment.groovy."
        }
    }


} finally {
    // Restore the original authorization mode no matter what happened above.
    if (preAuthorization) {
        modelService.refresh(config)
        config.setDefaultAuthorizationMode(originalAuthMode)
        modelService.save(config)
        println ""
        println "  ✓ defaultAuthorizationMode restored to ${originalAuthMode?.code}"
    }
}

// =======================================================================
// SUMMARY
// =======================================================================

println ""
println "=" * 75
println "  CARD TEST COMPLETE (run ${runId}) — pass=${passed} fail=${failed}"
println "=" * 75
if (!errors.isEmpty()) {
    println "  FAILURES:"
    errors.each { println "    • ${it}" }
}
println ""

return [passed: passed, failed: failed, skipped: 0]
