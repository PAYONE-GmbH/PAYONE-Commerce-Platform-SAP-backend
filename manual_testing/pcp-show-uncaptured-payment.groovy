/*
 * pcp-show-uncaptured-payment.groovy
 *
 * PAYONE PCP — show placed orders whose payment is authorised but NOT yet
 * captured ("capture step missing"), from both sides:
 *
 *   hybris side : Order.paymentStatus + PaymentTransactionEntries
 *                 (AUTHORIZATION present, no ACCEPTED CAPTURE)
 *   PCP side    : payoneCheckoutService.getCheckout(...) ->
 *                   checkoutStatus, statusOutput.{paymentStatus, openAmount,
 *                   collectedAmount, isModifiable}, and per PaymentExecution
 *                   the event history (StatusValue, e.g. PENDING_CAPTURE)
 *
 * PCP is the source of truth: a payment counts as "capture missing" when
 * PCP reports openAmount > 0 / an execution whose last event is
 * PENDING_CAPTURE. The hybris view is printed alongside so mismatches
 * (e.g. a missed webhook) are visible.
 *
 * TYPICAL USE: run pcp-facade-sepa-test.groovy (or the card test with
 * PayoneConfiguration.defaultAuthorizationMode = PRE_AUTHORIZATION) first,
 * then run this with orderCode = null to list the most recent PCP orders,
 * or with a concrete orderCode.
 *
 * NOTE on SEPA (771): SepaDirectDebitPaymentMethodSpecificInput has no
 * authorizationMode field (unlike Card/Mobile), so whether a SEPA payment
 * stays in PENDING_CAPTURE or is collected directly is decided by PCP /
 * the merchant's PCP setup, not by the plugin. This script shows whatever
 * PCP reports; a card payment with PRE_AUTHORIZATION is the deterministic
 * way to produce an uncaptured payment.
 *
 * READ-ONLY by default. Set doCapture = true to additionally capture the
 * selected order via payonePaymentOperationsFacade.capturePayment(order)
 * (full amount) and re-read the PCP state — that call is a real PCP
 * operation, so only against a preprod/sandbox merchant. No secret is
 * printed. Paste into a HAC Groovy console tab; commit mode not required
 * for the read-only part (capture persists transaction entries -> enable
 * commit if you want to keep them).
 */

// =======================================================================
// SETTINGS
// =======================================================================

def orderCode = null     // null = scan the most recent PCP orders
def scanLimit = 10       // how many recent orders to scan when orderCode == null
def doCapture = false    // true = capture orderCode (requires orderCode != null)

// =======================================================================
// IMPORTS
// =======================================================================

import de.hybris.platform.core.model.order.OrderModel
import de.hybris.platform.payment.enums.PaymentTransactionType
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery

import com.payone.pcp.core.service.PayoneCheckoutService
import com.payone.pcp.core.service.PayoneConfigurationService
import com.payone.pcp.facades.PayonePaymentOperationsFacade

// =======================================================================
// HELPERS
// =======================================================================

def getBean = { String name ->
    try {
        return spring.getBean(name)
    } catch (MissingPropertyException e) {
        return de.hybris.platform.core.Registry.getApplicationContext().getBean(name)
    }
}

def rootMessage = { Throwable t ->
    def sb = new StringBuilder(t.message ?: t.class.simpleName)
    def cause = t.cause
    while (cause != null) {
        if (cause.class.simpleName == "ApiErrorResponseException") {
            try { sb.append(" [HTTP ").append(cause.statusCode).append("]") } catch (Exception ignored) {}
            try { cause.errors?.each { sb.append("\n      - ").append(it.message) } } catch (Exception ignored) {}
            break
        }
        cause = cause.cause
    }
    return sb.toString()
}

def flexibleSearchService      = getBean("flexibleSearchService")
def payoneConfigurationService = (PayoneConfigurationService) getBean("payoneConfigurationService")
def payoneCheckoutService      = (PayoneCheckoutService) getBean("payoneCheckoutService")

// =======================================================================
// hybris view
// =======================================================================

def hybrisView = { OrderModel order ->
    def entries = (order.paymentTransactions ?: []).collectMany { it.entries ?: [] }
    // PayonePaymentStatusMapper: PCP PENDING_CAPTURE -> transactionStatus
    // "WAITING", CAPTURED -> "ACCEPTED"; the raw PCP status is kept in
    // transactionStatusDetails, so match on that.
    def authorised = entries.any { it.transactionStatusDetails == "PENDING_CAPTURE" }
    def captured   = entries.any { it.transactionStatusDetails == "CAPTURED" ||
                                   (it.type == PaymentTransactionType.CAPTURE && it.transactionStatus == "ACCEPTED") }
    println "  hybris : paymentStatus=${order.paymentStatus} total=${order.totalPrice} ${order.currency?.isocode}"
    entries.sort { it.time }.each {
        println "           - ${it.type} ${it.transactionStatus}/${it.transactionStatusDetails} amount=${it.amount} code=${it.code}"
    }
    if (entries.isEmpty()) println "           (no PaymentTransactionEntries)"
    return [authorised: authorised, captured: captured]
}

// =======================================================================
// PCP view
// =======================================================================

def pcpView = { OrderModel order ->
    def config = payoneConfigurationService.getActiveConfigurationForStore(order.store)
    if (config == null) {
        println "  PCP    : store [${order.store?.uid}] has no active PayoneConfiguration — skipped"
        return null
    }
    // Order carries only payoneCommerceCaseId; the checkout id lives on the
    // PaymentTransaction (a case may hold >1 checkout).
    def checkoutId = (order.paymentTransactions ?: []).find { it.payoneCheckoutId }?.payoneCheckoutId
    if (order.payoneCommerceCaseId == null || checkoutId == null) {
        println "  PCP    : order carries no commerceCaseId/checkoutId — skipped"
        return null
    }

    def checkout = payoneCheckoutService.getCheckout(config, order.payoneCommerceCaseId, checkoutId)
    def so = checkout.statusOutput
    println "  PCP    : checkoutStatus=${checkout.checkoutStatus} paymentStatus=${so?.paymentStatus}"
    println "           amount=${checkout.amountOfMoney?.amount} open=${so?.openAmount} collected=${so?.collectedAmount}" +
            " cancelled=${so?.cancelledAmount} refunded=${so?.refundedAmount} modifiable=${so?.isModifiable}"

    def lastStatuses = []
    (checkout.paymentExecutions ?: []).each { pe ->
        def events = (pe.events ?: []).sort { it.creationDateTime }
        def last = events ? events[-1].paymentStatus : null
        lastStatuses << last
        println "           execution ${pe.paymentExecutionId} (paymentId=${pe.paymentId}) last=${last}"
        events.each { ev ->
            println "             · ${ev.creationDateTime} ${ev.type} -> ${ev.paymentStatus} ${ev.amountOfMoney?.amount ?: ''}"
        }
    }
    def openAmount = so?.openAmount ?: 0L
    def captureMissing = openAmount > 0 || lastStatuses.any { it?.toString() == "PENDING_CAPTURE" }
    return [captureMissing: captureMissing, openAmount: openAmount]
}

// =======================================================================
// RUN
// =======================================================================

println ""
println "=" * 75
println "  PAYONE PCP — orders with missing capture"
println "=" * 75

List<OrderModel> orders
if (orderCode != null) {
    orders = flexibleSearchService.search(
        "SELECT {pk} FROM {Order} WHERE {code} = ?code AND {versionID} IS NULL", [code: orderCode]).result
} else {
    def q = new FlexibleSearchQuery(
        "SELECT {pk} FROM {Order} WHERE {payoneCommerceCaseId} IS NOT NULL AND {versionID} IS NULL " +
        "ORDER BY {creationtime} DESC")
    q.setCount(scanLimit)
    orders = flexibleSearchService.search(q).result
}

if (orders.isEmpty()) {
    println "  No matching PCP order found."
    return [scanned: 0, captureMissing: []]
}

def missing = []
orders.each { OrderModel order ->
    println ""
    def productId = '?'
    try { productId = order.paymentInfo?.paymentProductId ?: '?' } catch (MissingPropertyException ignored) {}
    println "Order ${order.code} — ${order.creationtime} — product ${productId}"
    def h = hybrisView(order)
    def p = null
    try {
        p = pcpView(order)
    } catch (Exception e) {
        println "  PCP    : getCheckout failed: ${rootMessage(e)}"
    }
    def verdict
    if (p != null) {
        verdict = p.captureMissing ? "CAPTURE MISSING (open ${p.openAmount})" : "nothing open at PCP"
        if (p.captureMissing) missing << order.code
        def hybrisOpen = h.authorised && !h.captured
        if (p.captureMissing != hybrisOpen) verdict += "  ⚠ hybris/PCP disagree (missed webhook?)"
    } else {
        verdict = (h.authorised && !h.captured) ? "CAPTURE MISSING (hybris view only)" : "no open authorisation in hybris"
        if (h.authorised && !h.captured) missing << order.code
    }
    println "  ⇒ ${verdict}"
}

// =======================================================================
// OPTIONAL CAPTURE
// =======================================================================

if (doCapture) {
    println ""
    if (orderCode == null || orders.size() != 1) {
        println "  doCapture=true requires a concrete orderCode — capture skipped."
    } else {
        def order = orders[0]
        println "Capturing order ${order.code} via payonePaymentOperationsFacade.capturePayment(order)..."
        try {
            def ops = (PayonePaymentOperationsFacade) getBean("payonePaymentOperationsFacade")
            def result = ops.capturePayment(order)
            println "  ✓ capture -> status=${result.status} paymentId=${result.paymentId} execution=${result.paymentExecutionId}"
            getBean("modelService").refresh(order)
            println ""
            println "State after capture:"
            hybrisView(order)
            pcpView(order)
        } catch (Exception e) {
            println "  ✗ capture failed: ${rootMessage(e)}"
        }
    }
}

println ""
println "=" * 75
println "  Scanned ${orders.size()} order(s); capture missing: ${missing ?: 'none'}"
println "=" * 75

return [scanned: orders.size(), captureMissing: missing]
