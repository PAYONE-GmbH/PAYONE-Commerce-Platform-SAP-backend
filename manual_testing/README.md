# PAYONE PCP – Groovy Test Scripts

Manual integration and diagnostic scripts for the PAYONE Commerce Platform plugin. They are run in the
**HAC → Console → Scripting Languages (Groovy)** of a running SAP Commerce instance and talk to
**PCP preprod** (`api.preprod.commerce.payone.com`).

Every script is self-contained: paste the whole file into a fresh console tab, adjust the values in
the `HARDCODED TEST VALUES` / `SETTINGS` block (replace every `REPLACE_WITH_…` placeholder, e.g.
`REPLACE_WITH_MERCHANT_ID` with your PAYONE merchant ID), run it.

> **Caution:** Most scripts create real objects – PCP commerce cases/payments on preprod and
> (facade tests) customers, carts and orders in SAP Commerce. Run them only against
> preprod/sandbox stores and a PAYONE test merchant, never against production.
> No script prints secrets, none calls `System.exit()`.

## Prerequisites

| What | Details |
|---|---|
| SAP Commerce | running locally or on CCv2, HAC reachable, `payonepcp*` extensions deployed |
| Test data | site `electronics` / `electronicsProductCatalog` (Spartacus sample data) with a saleable product that has a EUR price |
| `PayoneConfiguration` | active on the BaseStore, `merchantId` + `apiKeyId` set, required payment methods in `paymentModes`, a `returnUrl` for redirect/wallet methods |
| Secrets | `payone.pcp.<merchantId>.apiSecret` / `.webhookSecret` from `local.properties`, environment or CCv2 properties – never in the database |
| Commit mode | **on** for facade tests, otherwise orders and configuration changes are rolled back |

## Recommended order

1. `pcp-connection-test.groovy` – can the instance reach PCP at all?
2. `pcp-service-test.groovy` – Spring wiring, configuration, converters (no PCP calls)
3. `facade/pcp-facade-*-test.groovy` – checkout flows per payment method (start with `pcp-facade-sepa-test`, it needs no token)
4. `pcp-show-uncaptured-payment.groovy` – check result / capture status

## Base tests (`manual_testing/`)

| Script | Purpose | Writes | Notes |
|---|---|---|---|
| `pcp-connection-test.groovy` | Checks DNS, TCP/TLS, SDK configuration and PCP authentication (auth token) | nothing | Signs requests itself – replace the credential placeholders locally |
| `pcp-service-test.groovy` | Smoke test without PCP calls: bean resolution, secret resolution, client factory (caching/fingerprint), converters, `PaymentTransaction` service, status mapper, configuration validator | nothing | Client-factory tests are skipped if no `apiSecret` can be resolved |
| `pcp-show-uncaptured-payment.groovy` | Lists orders whose payment is authorised but not captured – hybris view (`paymentStatus`, transactions) and PCP view (`openAmount`, events) | only with `doCapture = true` | `orderCode = null` scans the most recent `scanLimit` orders. PCP is the source of truth |
| `pcp-financing-raw-api-test.groovy` | Raw, hand-signed HTTP call built from PAYONE's doc example for BNPL, bypassing SDK and plugin. Switches `paymentProductId = 3390 \| 3392`, `requiresApproval` | PCP preprod | Tells apart whether an error comes from our code or from merchant activation. Typical errors if a product is not activated for the merchant: 3390 → `50090923 PAYMENT_METHOD_NOT_ALLOWED`, 3392 → `1201 PAYMENT_PRODUCT_CONFIGURATION_ERROR` (both PAYONE-side). With `requiresApproval = true` it checks whether a merchant supports the flow without capture (expect `PENDING_CAPTURE`). Replace the credential placeholders locally |
| `pcp-secured-invoice-preauth-test.groovy` | Secured Invoice (3390) with `requiresApproval = true` → authorised but not captured; shows `openAmount`/events, optional capture (`doCapture = true`) | PCP preprod | Goes through the core services directly (the facade builder hardcodes `requiresApproval = false`), no hybris cart/order. Works for **any test merchant with 3390 activated**: set `merchantId`; needs a `PayoneConfiguration` row + `payone.pcp.<merchantId>.apiSecret`. `50090923` means 3390 is not activated for the merchant |

## Facade and payment-method tests (`manual_testing/facade/`)

Each run creates its own throwaway customer and cart (`runId`), so runs are repeatable and can run
in parallel in several tabs. `placeOrder()` deletes the cart.

| Script | Payment method (product ID) | Switches | Path | Result / limitation |
|---|---|---|---|---|
| `pcp-facade-card-test.groovy` | Visa 1 (or Amex 2, Mastercard 3, Diners 132) | `paymentProductId`, `preAuthorization`, `doCapture` | `PayoneCheckoutFacade`: `createOrGetCommerceCase` → `authorizePayment` → `placeOrder` | Needs a preprod `paymentProcessingToken`; on a 3DS redirect `placeOrder` is skipped. With `preAuthorization = true`, `defaultAuthorizationMode` is temporarily set to `PRE_AUTHORIZATION` (restored in `finally`), then `PENDING_CAPTURE` is shown and optionally captured – don't use the store for anything else meanwhile |
| `pcp-facade-sepa-test.groovy` | SEPA Direct Debit 771 | – | Facade, mandate from IBAN/account holder/creditor ID | Synchronous, no redirect; cart must be EUR |
| `pcp-facade-redirect-paypal-test.groovy` | PayPal 840 | – | Facade, redirect flow | Prints `redirectUrl` + `checkoutId`. After approving manually in the browser, run only the commented-out block at the end (`handleRedirectCallback` + `placeOrder`) |
| `pcp-facade-financing-test.groovy` | Secured Invoice 3390 / Secured Direct Debit 3392 (B2C) | `paymentProductId` | Facade; for 3392 additionally bank data in `paymentProduct3392SpecificInput` | DE/AT + EUR; date of birth/phone/IP mandatory. Must be activated for the merchant (check with the raw-API test). `REJECTED` can be a genuine BNPL risk decision |
| `pcp-facade-mobile-onestep-test.groovy` | Google Pay 320 / Apple Pay 302 | `paymentProductId` | Core directly, **One-Step** (`autoExecuteOrder = true`) | Google Pay: real token from the test page (see below). Apple Pay: request-shape diagnostic with placeholders only – real tokens need a device + merchant certificate |
| `pcp-facade-wero-onestep-test.groovy` | Wero 900 | – | Core directly, **One-Step** | Wero fails on the step-by-step path (`50507002`); One-Step is not yet available via the facade (plugin README, section 10). Checks whether a redirect comes back; EUR, DE/BE address |

Authorised but uncaptured payment:
- **Card:** `pcp-facade-card-test` with `preAuthorization = true` – via the facade, with a hybris order.
- **Secured Invoice 3390:** `pcp-secured-invoice-preauth-test` – core services only, no hybris order. Not possible
  via the facade yet, because `PcpFinancingPaymentMethodSpecificInputBuilder` hardcodes
  `requiresApproval = false`. Once a merchant with 3390 activated exists and the shop flow needs it,
  `requiresApproval` must become configurable (e.g. from `PayoneConfiguration.defaultAuthorizationMode`,
  like the card builder).

New BNPL test merchant – order of steps:
1. Create a `PayoneConfiguration` for the merchant (ImpEx/Backoffice), set `payone.pcp.<merchantId>.apiSecret`.
2. `pcp-connection-test` with its credentials.
3. `pcp-financing-raw-api-test` with `paymentProductId = 3390`, first `requiresApproval = false`, then `true`.
4. `pcp-secured-invoice-preauth-test` with the new merchant's `merchantId`; then `facade/pcp-facade-financing-test`
   via a BaseStore that this configuration is assigned to.

## Generating a Google Pay token (`facade/googlepay-test-page/`)

`googlepay-test-page/index.html` is a test page based on PAYONE's example
(Google Pay `TEST` environment, gateway `payonegmbh`, `gatewayMerchantId` = your PAYONE merchant ID – replace `REPLACE_WITH_MERCHANT_ID` in `index.html`).
It returns the token **already base64-encoded** – only then can it be pasted into a Groovy string
literal without corruption (the raw token JSON with its `=` escapes was mangled by Groovy on
paste → `50092703 VALIDATION_ERROR`).

The page must be served by a web server (opened as a local file, the Google Pay script does not work):

```bash
docker run -d --name gpay-test -p 80:80 \
  -v "$(pwd)/manual_testing/facade/googlepay-test-page":/usr/local/apache2/htdocs/ \
  httpd:latest
# → http://localhost/
docker rm -f gpay-test   # stop
```

Steps:

1. Run `pcp-facade-mobile-onestep-test.groovy` (with `paymentProductId = 320`) once or determine the cart total – the script prints `Cart [...] ready — total X EUR`.
2. In `index.html`, set `getGoogleTransactionInfo().totalPrice` to **exactly** that amount (PCP compares the token amount with the checkout amount). Reload the page – no container restart needed, the directory is mounted.
3. Click the Google Pay button and choose a test card (signed in with a Google account in the browser).
4. Paste the displayed base64 string into `GOOGLE_PAY_ENCRYPTED_PAYMENT_DATA` and run the script.

Tokens are short-lived and single-use – generate a new one for every run.

## Security note

`pcp-connection-test.groovy` and `pcp-financing-raw-api-test.groovy` sign requests themselves and need
the API key and secret. The repository contains only the placeholders `REPLACE_WITH_API_KEY_ID` /
`REPLACE_WITH_API_SECRET` (and `REPLACE_WITH_MERCHANT_ID`) – insert real values only locally in the HAC tab and **never commit them**.
All other scripts resolve the secret at runtime via `PayoneConfiguration`.
The same applies to Google Pay tokens: the repository contains only the placeholder
`___PASTE_ALREADY_BASE64_ENCODED_TOKEN_HERE___`.

---

<p align="center">
  Implemented with ❤️ by <a href="https://www.adesso.de">adesso SE</a>
</p>
