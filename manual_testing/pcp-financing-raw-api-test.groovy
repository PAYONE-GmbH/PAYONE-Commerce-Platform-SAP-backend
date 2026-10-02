/*
 * pcp-financing-raw-api-test.groovy
 *
 * Sends a raw, hand-signed POST /v1/{merchantId}/commerce-cases request for a
 * PAYONE BNPL product, bypassing the SAP Commerce plugin and the SDK entirely.
 * Switch paymentProductId below:
 *   3390 = PAYONE Secured Invoice
 *   3392 = PAYONE Secured Direct Debit (adds paymentProduct3392SpecificInput)
 *
 * Purpose: isolate whether an error seen from
 * facade/pcp-facade-financing-test.groovy comes from our code or from PCP /
 * merchant configuration. If this raw request, built straight from PAYONE's
 * own doc example, returns the same error, the cause is PAYONE-side (product
 * not activated for the merchant). Typical errors when a product is not
 * activated for the merchant:
 *   3390 -> errorCode 50090923 PAYMENT_METHOD_NOT_ALLOWED
 *   3392 -> errorCode 1201 PAYMENT_PRODUCT_CONFIGURATION_ERROR
 * In that case ask PAYONE to activate the product, or use the
 * merchantId/credentials of a test merchant where it is active.
 * Set requiresApproval = true to check that a merchant supports the
 * authorise-only flow (expect PENDING_CAPTURE) before running
 * pcp-secured-invoice-preauth-test.groovy.
 *
 * Request bodies are PAYONE's own worked examples from
 * docs.commerce.payone.com/docs/payment-methods/payone-bnpl/payone-secured-invoice
 * and .../payone-secured-direct-debit, with only the merchant references
 * randomized per run (PCP requires uniqueness). For 3390 no
 * paymentProduct3392SpecificInput is sent — the doc explicitly says not to.
 *
 * Auth scheme per docs.commerce.payone.com/docs/integration-guide/:
 *   stringToHash = "POST\napplication/json; charset=utf-8\n<RFC1123 date>\n<urlPath>\n"
 *   signature    = Base64(HMAC-SHA256(stringToHash, apiSecret))
 *   Authorization: GCS v1HMAC:<apiKeyId>:<signature>
 *
 * IMPORTANT: replace the credential placeholders below locally before running
 * (never commit real values). No System.exit(). No database access — pure
 * HTTPS call to PCP preprod; creates a real CommerceCase there.
 */

// =======================================================================
// HARDCODED TEST VALUES (edit for your environment)
// =======================================================================

def merchantId = "REPLACE_WITH_MERCHANT_ID"
def apiKeyId    = "REPLACE_WITH_API_KEY_ID"
def apiSecret   = "REPLACE_WITH_API_SECRET"
def endpointHost = "api.preprod.commerce.payone.com"

def paymentProductId = 3390   // 3390 = Secured Invoice, 3392 = Secured Direct Debit
// false = PCP captures automatically (doc example). true = authorise only,
// capture later (what pcp-secured-invoice-preauth-test.groovy needs) - use it
// to check a new test merchant supports the uncaptured BNPL flow.
def requiresApproval = false

// =======================================================================
// IMPORTS
// =======================================================================

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// =======================================================================
// START
// =======================================================================

def productName = paymentProductId == 3392 ? "Secured Direct Debit" : "Secured Invoice"
def refPrefix   = paymentProductId == 3392 ? "sdd" : "si"
// PAYONE doc example bank account (3392 only).
def product3392Input = paymentProductId == 3392 ? """,
          "paymentProduct3392SpecificInput": {
            "bankAccountInformation": {
              "iban": "DE26210700240444444444",
              "accountHolder": "Max Mustermann"
            }
          }""" : ""

def urlPath = "/v1/${merchantId}/commerce-cases"
def method = "POST"
def contentType = "application/json; charset=utf-8"

// RFC 1123 date - must be the exact same string used for both the Date
// header and the signature calculation.
def dateHeader = ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)

// checkout.references.merchantReference has a 40-char limit; the SEPARATE
// checkout.orderRequest.orderReferences.merchantReference has only a 20-char
// limit (PcpReferencesConverter.convertReferences vs. convertCheckoutReferences
// in payonepcpcore - same lesson learned the hard way in
// pcp-facade-wero-onestep-test.groovy). Two different refs, two different caps.
def ref = "${refPrefix}-raw-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 15)
def orderRef = "${refPrefix}-o-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10)

def body = """
{
  "merchantReference": "${ref}",
  "customer": {
    "merchantCustomerId": "CustomerId_rawtest",
    "billingAddress": {
      "city": "Bremervoerde",
      "countryCode": "DE",
      "houseNumber": "23a",
      "street": "Moritz-Guenther-Strasse",
      "zip": "22332"
    },
    "contactDetails": {
      "emailAddress": "test@test.com",
      "phoneNumber": "0188711725"
    },
    "businessRelation": "B2C",
    "locale": "de",
    "personalInformation": {
      "dateOfBirth": "19780101",
      "gender": "MALE",
      "name": {
        "firstName": "Ruediger",
        "surname": "Soerensen"
      }
    }
  },
  "checkout": {
    "amountOfMoney": {
      "amount": 20000,
      "currencyCode": "EUR"
    },
    "references": {
      "merchantReference": "${ref}"
    },
    "shoppingCart": {
      "items": [
        {
          "invoiceData": {
            "description": "Test item 1"
          },
          "orderLineDetails": {
            "productCode": "TEST-PRODUCT-1",
            "productPrice": 20000,
            "productType": "GOODS",
            "quantity": 1,
            "taxAmount": 19,
            "taxAmountPerUnit": false
          }
        }
      ]
    },
    "orderRequest": {
      "orderType": "FULL",
      "orderReferences": {
        "merchantReference": "${orderRef}"
      },
      "paymentMethodSpecificInput": {
        "financingPaymentMethodSpecificInput": {
          "paymentProductId": ${paymentProductId},
          "requiresApproval": ${requiresApproval}${product3392Input}
        }
      }
    },
    "autoExecuteOrder": true
  }
}
""".trim()

def stringToHash = "${method}\n${contentType}\n${dateHeader}\n${urlPath}\n"

def mac = Mac.getInstance("HmacSHA256")
mac.init(new SecretKeySpec(apiSecret.getBytes("UTF-8"), "HmacSHA256"))
def signatureBytes = mac.doFinal(stringToHash.getBytes("UTF-8"))
def signature = java.util.Base64.getEncoder().encodeToString(signatureBytes)

def authHeader = "GCS v1HMAC:${apiKeyId}:${signature}"

println ""
println "=" * 70
println "  PCP raw API test — ${productName} (${paymentProductId}), requiresApproval=${requiresApproval}"
println "  POST https://${endpointHost}${urlPath}"
println "  merchantReference: ${ref}"
println "=" * 70
println ""

def client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

def request = HttpRequest.newBuilder()
    .uri(URI.create("https://${endpointHost}${urlPath}"))
    .timeout(Duration.ofSeconds(30))
    .header("Authorization", authHeader)
    .header("Date", dateHeader)
    .header("Content-Type", contentType)
    .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
    .build()

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())

println "HTTP status: ${response.statusCode()}"
println ""
println "Response body:"
println response.body()

println ""
println "=" * 70
// NOTE: PCP can return HTTP 2xx at the top level while embedding a
// payment-level errorResponse/REJECTED event inside the body - check the body
// text for the error code, not just the top-level status.
def knownError = paymentProductId == 3392
    ? (response.body()?.contains("1201") ? "1201 PAYMENT_PRODUCT_CONFIGURATION_ERROR" : null)
    : ((response.body()?.contains("50090923") || response.body()?.contains("PAYMENT_METHOD_NOT_ALLOWED")) ? "50090923 PAYMENT_METHOD_NOT_ALLOWED" : null)
def captured = response.body()?.contains('"status":"CAPTURED"') || response.body()?.contains('"paymentStatus":"CAPTURED"')
def pendingCapture = response.body()?.contains('PENDING_CAPTURE')
if (knownError != null) {
    println "  Known errorCode ${knownError} -> confirmed PAYONE-side"
    println "  (product ${paymentProductId} not activated for this merchant), not a code issue."
} else if (response.statusCode() < 300 && requiresApproval && pendingCapture) {
    println "  SUCCESS - product ${paymentProductId} IS allowed and stays uncaptured"
    println "  (PENDING_CAPTURE). This merchant can run pcp-secured-invoice-preauth-test.groovy."
} else if (response.statusCode() < 300 && captured) {
    println "  SUCCESS - product ${paymentProductId} IS allowed. If the facade test still"
    println "  fails, the difference is in what our request builds (customer/cart/context)."
} else {
    println "  Different result than the known PAYONE-side error - compare manually"
    println "  with facade/pcp-facade-financing-test.groovy."
}
println "=" * 70

return [status: response.statusCode(), body: response.body()]
