/*
 * pcp-connection-test.groovy
 *
 * PAYONE PCP — Connection Test
 *
 * Purpose:
 *   Verify that the SAP Commerce instance can reach PAYONE PCP preprod
 *   and authenticate using the supplied credentials.
 *
 * Tests:
 *   1. DNS resolution
 *   2. TCP/TLS/HTTP connectivity with explicit timeouts
 *   3. PCP SDK configuration
 *   4. PCP authentication request
 *
 * No database access.
 * No configuration changes.
 * No System.exit().
 *
 * IMPORTANT:
 *   Replace the credentials below before running.
 */

// =======================================================================
// HARDCODED TEST VALUES
// =======================================================================

def merchantId   = "REPLACE_WITH_MERCHANT_ID"
def apiSecret     = "REPLACE_WITH_API_SECRET"
def apiKeyId    = "REPLACE_WITH_API_KEY_ID"
def integrator   = "PAYONE_SAP_COMMERCE_PLUGIN_DEV"
def endpointHost = "api.preprod.commerce.payone.com"

// Network test timeouts
def connectTimeoutMs = 5000
def readTimeoutMs    = 10000

// =======================================================================
// IMPORTS
// =======================================================================

import com.payone.commerce.platform.lib.CommunicatorConfiguration
import com.payone.commerce.platform.lib.endpoints.AuthenticationApiClient
import com.payone.commerce.platform.lib.errors.ApiErrorResponseException

import java.net.InetAddress
import java.net.ConnectException
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

// =======================================================================
// HELPER
// =======================================================================

def printResult = { boolean success, String message ->
    println "  ${success ? '✓' : '✗'} ${message}"
}

// =======================================================================
// START
// =======================================================================

println ""
println "=" * 70
println "  PAYONE PCP — Connection Test"
println "=" * 70
println "  Target    : ${endpointHost}"
println "  Merchant  : ${merchantId}"
println "  API Key   : ${apiKeyId}"
println "  Integrator: ${integrator}"
println "=" * 70

// =======================================================================
// STEP 1 — DNS
// =======================================================================

println ""
println "[1/4] Testing DNS resolution..."

InetAddress address

try {
    println "  Resolving ${endpointHost}..."

    address = InetAddress.getByName(endpointHost)

    printResult(true, "DNS resolution successful")
    println "    Address: ${address.hostAddress}"

} catch (Exception e) {

    printResult(false, "DNS resolution failed")
    println "    ${e.class.simpleName}: ${e.message}"

    println ""
    println "  The test cannot continue because the hostname could not be resolved."
    return
}

// =======================================================================
// STEP 2 — TCP + TLS + HTTP
// =======================================================================

println ""
println "[2/4] Testing HTTPS connectivity..."
println "  Connect timeout: ${connectTimeoutMs} ms"
println "  Read timeout   : ${readTimeoutMs} ms"

HttpsURLConnection connection = null

try {
    def url = new URL("https://${endpointHost}/")

    connection = (HttpsURLConnection) url.openConnection()

    connection.setConnectTimeout(connectTimeoutMs)
    connection.setReadTimeout(readTimeoutMs)
    connection.setRequestMethod("GET")
    connection.setInstanceFollowRedirects(false)

    println "  Connecting..."

    def start = System.currentTimeMillis()

    connection.connect()

    def elapsed = System.currentTimeMillis() - start
    def status = connection.responseCode

    printResult(
            true,
            "HTTPS connection successful (${elapsed} ms)"
    )

    println "    HTTP status: ${status}"

    /*
     * Any HTTP response is useful here.
     *
     * 2xx / 3xx / 4xx / 5xx all prove that:
     *
     *   DNS ✓
     *   TCP ✓
     *   TLS ✓
     *   HTTP ✓
     *
     * Authentication is tested separately below.
     */

} catch (SSLHandshakeException e) {

    printResult(false, "TLS handshake failed")
    println "    ${e.message}"
    println "    Check the certificate chain / JVM truststore."

} catch (ConnectException e) {

    printResult(false, "TCP connection failed")
    println "    ${e.message}"
    println "    Check firewall, routing, proxy/VPN and outbound access."

} catch (java.net.SocketTimeoutException e) {

    printResult(false, "Network timeout")
    println "    ${e.message}"
    println "    The Commerce server could not complete the HTTPS request within the timeout."

} catch (Exception e) {

    printResult(false, "HTTPS connectivity test failed")
    println "    ${e.class.name}: ${e.message}"

} finally {

    if (connection != null) {
        connection.disconnect()
    }
}

// =======================================================================
// STEP 3 — SDK CONFIGURATION
// =======================================================================

println ""
println "[3/4] Creating PAYONE SDK configuration..."

CommunicatorConfiguration sdkConfig

try {

    sdkConfig = new CommunicatorConfiguration(
            apiKeyId,
            apiSecret,
            endpointHost,
            integrator
    )

    printResult(true, "CommunicatorConfiguration created")

    println "    API Key ID: ${apiKeyId}"
    println "    Host      : ${endpointHost}"

} catch (Exception e) {

    printResult(false, "Could not create CommunicatorConfiguration")
    println "    ${e.class.name}: ${e.message}"

    println ""
    println "  SDK configuration failed. Authentication test skipped."
    return
}

// =======================================================================
// STEP 4 — PCP AUTHENTICATION
// =======================================================================

println ""
println "[4/4] Requesting PCP authentication token..."
println "  This is the actual PAYONE API authentication test."
println ""
println "  IMPORTANT: If this step does not return, the SDK's HTTP client"
println "             is probably waiting without an appropriate timeout."
println ""

try {

    def authClient = new AuthenticationApiClient(sdkConfig)

    def allowedPaymentAction = "PAYMENT_EXECUTION"

    println "  Calling getAuthenticationTokens()..."
    println "  Started: ${new Date()}"

    def start = System.currentTimeMillis()

    def token = authClient.getAuthenticationTokens(
            merchantId,
            allowedPaymentAction
    )

    def elapsed = System.currentTimeMillis() - start

    println ""
    printResult(true, "PCP authentication successful")
    println "    Duration : ${elapsed} ms"
    println "    Token ID : ${token.id}"
    println "    Created  : ${token.creationDate}"
    println "    Expires  : ${token.expirationDate}"

    /*
     * Don't print the actual token.
     *
     * Even printing the first 20 characters is unnecessary for a
     * connectivity test and could end up in HAC logs.
     */

    if (token.expirationDate != null) {
        def expiryMs = java.time.Duration.between(
                java.time.OffsetDateTime.now(),
                token.expirationDate
        ).toMillis()

        println "    Validity : ${expiryMs / 1000} seconds"
    }

} catch (SSLHandshakeException e) {

    printResult(false, "TLS handshake failed during PCP authentication")
    println "    ${e.message}"

} catch (ConnectException e) {

    printResult(false, "Connection failed during PCP authentication")
    println "    ${e.message}"

} catch (java.net.UnknownHostException e) {

    printResult(false, "DNS resolution failed during PCP authentication")
    println "    ${e.message}"

} catch (java.net.SocketTimeoutException e) {

    printResult(false, "PCP authentication timed out")
    println "    ${e.message}"

    println ""
    println "  The SDK HTTP client did not complete the request in time."
    println "  Check the SDK HTTP-client timeout configuration."

} catch (ApiErrorResponseException e) {

    printResult(false, "PAYONE API returned an error")

    try {
        println "    HTTP status: ${e.httpStatus}"
    } catch (Exception ignored) {
        // Some SDK versions may expose the status differently.
    }

    println "    ${e.message}"
    println ""
    println "  Network connectivity appears to work."
    println "  Check merchantId, apiKeyId, apiSecret and allowedPaymentAction."

} catch (java.io.IOException e) {

    printResult(false, "I/O error during PCP authentication")
    println "    ${e.class.name}: ${e.message}"

} catch (Exception e) {

    printResult(false, "Unexpected authentication error")
    println "    ${e.class.name}: ${e.message}"

    if (e.stackTrace != null) {
        println ""
        println "    Stack trace:"
        e.stackTrace.take(8).each {
            println "      ${it}"
        }
    }
}

// =======================================================================
// SUMMARY
// =======================================================================

println ""
println "=" * 70
println "  TEST COMPLETE"
println "=" * 70

println ""
println "Interpretation:"
println ""
println "  Step 1 successful → DNS works"
println "  Step 2 successful → DNS + TCP + TLS + HTTP work"
println "  Step 3 successful → PCP SDK configuration works"
println "  Step 4 successful → PCP authentication works"
println ""
println "  If Step 2 works but Step 4 hangs:"
println "    → investigate the SDK HTTP client timeout/proxy configuration."
println ""
println "  If Step 4 returns an ApiErrorResponseException:"
println "    → network connectivity is working; investigate credentials,"
println "      merchantId and the requested payment action."
println ""
println "=" * 70