# payonepcpfacades

Facade layer of the PAYONE Commerce Platform (PCP) plugin for SAP Commerce Cloud 2211.
It orchestrates the checkout with PCP, maps results into `*Data` objects for the OCC
layer, and provides the post-order payment operations.

This is a plain core extension without a web module. The REST endpoints live in
`payonepcpocc`.

| Depends on | Why |
|---|---|
| `payonepcpcore` | PCP data model, configuration, SDK services, payment strategies |
| `commercefacades` | OOTB converter, populator and `*Data` infrastructure |

---

## Facades

| Facade | Used by | Purpose |
|---|---|---|
| `PayoneCheckoutFacade` | `payonepcpocc` | Checkout: create the Commerce Case and Checkout for the cart, issue the authentication token for card tokenization, execute the payment, handle the redirect return, place the order |
| `PayonePaymentOperationsFacade` | Backoffice actions, custom code | Capture, refund, cancel, complete, pause and refresh for a placed order |
| `PayoneCheckoutOperationsFacade` | `payonepcpbackoffice` | Capture and refund for one `PayoneCheckout`, with the checks described below |

All facades resolve the `PayoneConfiguration` from the store of the cart or order, not
from the session. They can therefore also be called from jobs or business processes.

### Checkout

`PayoneCheckoutFacade.authorizePayment` picks the payment strategy from the PCP payment
product ID and rejects payment methods that are not allowed for the store
(`PayoneConfiguration.paymentModes`). For redirect methods (PayPal, Wero) it returns the
redirect URL; `handleRedirectCallback` reads the result from PCP when the customer comes
back. The PCP payment transaction created on the cart moves to the order when it is
placed.

### Post-order operations

`PayonePaymentOperationsFacade` works on a placed `OrderModel`:

- `capturePayment(order)` captures the full amount; `capturePayment(order, amount)` a
  given amount as final capture.
- `refundPayment(order)` refunds the order total; `refundPayment(order, amountOfMoney)`
  a given amount.
- `cancelPayment`, `completePayment`, `pausePayment`, `refreshPayment`.

Each call records the PCP result as a `PaymentTransactionEntry` (pause and refresh move
no money and write no entry). The order's PCP transactions must all carry the same
complete Commerce Case / Checkout / Payment Execution IDs; otherwise the call is refused.

These operations are not exposed through OCC.

### Checkout operations for the Backoffice

`PayoneCheckoutOperationsFacade` runs capture and refund for a selected
`PayoneCheckout`. Before each call it:

1. checks that the feature is enabled and the user is in the configured group,
2. locks the checkout row in the database, so parallel clicks run one after another,
3. checks that the order's PCP transactions belong to this checkout,
4. reads the checkout live from PCP and only continues if PCP's status allows the
   operation and an amount is left,
5. sends exactly that amount: the open amount for a capture, captured minus refunded
   for a refund.

Results are reported as success, rejected (nothing was executed at PCP) or unknown
outcome (the call may have reached PCP; reconcile before retrying). Operation and
troubleshooting are described in
[`payonepcpbackoffice/RUNBOOK-payment-actions.md`](../payonepcpbackoffice/RUNBOOK-payment-actions.md).

---

## Properties

| Property | Default | Meaning |
|---|---|---|
| `payonepcp.backoffice.paymentactions.enabled` | `false` | Enables the Backoffice capture/refund actions |
| `payonepcp.backoffice.paymentactions.usergroup` | `admingroup` | User group allowed to use them |

---

## Customising

All facades are Spring beans with an alias (`payoneCheckoutFacade`,
`payonePaymentOperationsFacade`, `payoneCheckoutOperationsFacade`). Point the alias to
your own subclass to change behaviour.

---

<p align="center">
  Implemented with ❤️ by <a href="https://www.adesso.de">adesso SE</a>
</p>