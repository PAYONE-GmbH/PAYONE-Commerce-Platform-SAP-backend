# payonepcpcore

Core extension of the PAYONE Commerce Platform (PCP) plugin for SAP Commerce Cloud 2211.
It contains the PCP data model, the per-store configuration, the PCP server SDK
integration and one payment strategy per payment method. All other `payonepcp*`
extensions build on it.

The extension only adds types and beans; it does not override SAP core or OOTB
behaviour.

---

## Requirements

- SAP Commerce 2211 (JDK 21)
- OOTB extensions `commerceservices` and `payment`
- PCP server SDK `io.github.payone-gmbh:pcp-serversdk-java` 1.13.0 and its runtime
  dependencies, shipped in `lib/` (declared in `external-dependencies.xml`)

---

## Data model

Typecode range reserved for all `payonepcp*` types: **15110–15139**. Make sure no other
extension in your system uses it.

| Type | Typecode | Purpose |
|---|---|---|
| `PayoneConfiguration` | 15110 | Merchant configuration (one per merchant, attached to a `BaseStore`) |
| `PayoneCommerceCase` | 15112 | Local copy of a PCP Commerce Case |
| `PayoneCheckout` | 15113 | Local copy of a PCP Checkout: IDs, amount, currency, status, linked order |
| `PayoneRecurringToken` | 15114 | Stored payment token (data model only) |
| `PayoneMandate` | 15115 | SEPA mandate data |
| `PayonePaymentInfo` | – | Extends `PaymentInfo`; stored in the OOTB `PaymentInfos` table |

`PayoneWebhookEvent` (15116) is declared in `payonepcpwebhook`.

The extension also adds attributes to OOTB types:

- `BaseStore.payoneConfiguration`, `BaseStore.payoneCheckoutType`
- `PaymentMode.paymentFamily` (card, mobile, redirect, SEPA, financing)
- PCP identifiers on `PaymentTransaction`, `Cart` and `Order`

---

## Configuration

### Merchant configuration

Create one `PayoneConfiguration` per merchant and attach it to the `BaseStore`, in the
Backoffice or by ImpEx. A sample is in
`resources/payonepcpcore/import/sampledata/payone-sample-configuration.impex`.

| Attribute | Meaning |
|---|---|
| `merchantId` | PCP merchant ID. Letters, digits, `_` and `-` only. |
| `apiEndpointHost` | PCP API host, e.g. `https://api.preprod.commerce.payone.com` |
| `apiKeyId` | Public half of the API key |
| `defaultAuthorizationMode` | `PRE_AUTHORIZATION` or `SALE` |
| `captureDelayHours` | Delay for automatic capture |
| `askConsumerConsent` | Ask the customer for consent where required |
| `sessionTimeoutSeconds` | Checkout session timeout |
| `returnUrl` | Return URL after redirect payments (absolute HTTPS URL) |
| `paymentModes` | Optional allow-list of payment methods for this store |
| `active` | Only active configurations are used |

The configuration is validated when it is loaded. An invalid `merchantId`,
`apiEndpointHost` or `returnUrl` is reported with all problems in one message.

### Payment modes

Import `resources/payonepcpcore/import/projectdata/projectdata-paymentmodes.impex` once.
It creates a `PaymentMode` for each supported PCP payment product and sets its payment
family. The payment family decides which request type is sent to PCP.

### API secret

The API secret is **not** stored in the database, in ImpEx or in the Backoffice. It is
read at runtime from a platform property:

```properties
payone.pcp.<merchantId>.apiSecret=<secret from the PAYONE merchant portal>
```

`<merchantId>` is the exact, case-sensitive value of `PayoneConfiguration.merchantId`,
for example `payone.pcp.ACME_DE.apiSecret`.

- **CCv2:** set it as a secret property in the Cloud Portal (*Environment → Services →
  Properties*) for every service that runs the storefront, OCC API, Backoffice or
  webhook receiver.
- **Local:** set it in `hybris/config/local.properties` of your local platform. Do not
  commit it.

The same secret signs outgoing PCP calls and verifies incoming webhooks.

If the secret is missing, loading the configuration fails with:

```
Secret property [payone.pcp.ACME_DE.apiSecret] for merchantId [ACME_DE] is not
configured. Set it in local.properties or the CCv2 environment.
```

A store without any active `PayoneConfiguration` is simply treated as "PAYONE not
configured".

### Properties

| Property | Default | Meaning |
|---|---|---|
| `payone.pcp.<merchantId>.apiSecret` | – | API secret per merchant (see above) |
| `payone.pcp.http.connectTimeoutSeconds` | `10` | Connect timeout for PCP calls |
| `payone.pcp.http.readTimeoutSeconds` | `30` | Read timeout for PCP calls |
| `payone.pcp.http.writeTimeoutSeconds` | `30` | Write timeout for PCP calls |

---

## Main components

| Package | Content |
|---|---|
| `service` | Services over the PCP SDK: Commerce Case, Checkout, Payment Execution, Payment Information, authentication token, configuration, payment transactions |
| `service.impl.DefaultPayonePcpClientFactory` | Creates SDK clients per merchant configuration; shares one HTTP client |
| `strategy` | `PaymentStrategyRegistry` and one `PayonePaymentStrategy` per payment method (card schemes, SEPA, PayPal, Wero, Secured Invoice, Secured Direct Debit, Apple Pay, Google Pay) |
| `converters` | Converts SAP carts, orders, addresses and customers into PCP request objects |
| `validation` | Validation of `PayoneConfiguration` |

### Payment transactions

`PayoneTransactionService` records every PCP result as a `PaymentTransactionEntry` on the
cart or order and updates `Order.paymentStatus` from the captured and refunded amounts.
Each `PaymentTransaction` carries the PCP Commerce Case, Checkout and Payment Execution
IDs. If an order's transactions point to different PCP checkouts, operations on that
order are refused instead of guessing which one is meant.

### Adding or changing a payment method

Strategies are Spring beans registered in `payonepcpcore-spring.xml` and looked up by PCP
payment product ID. To customise a payment method, override its strategy bean; to add
one, register a new strategy and a matching `PaymentMode`.

---

## Logging

PCP calls are logged under `com.payone.pcp.core`. Errors include the PCP error ID, code
and category; secrets, card data and raw response bodies are never logged.

---

<p align="center">
  Implemented with ❤️ by <a href="https://www.adesso.de">adesso SE</a>
</p>