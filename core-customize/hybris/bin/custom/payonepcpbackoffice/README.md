# payonepcpbackoffice

Backoffice extension of the PAYONE Commerce Platform (PCP) plugin for SAP Commerce Cloud
2211. Merchant configuration, read-only views of PCP data, a dashboard tile, and
capture/refund actions on checkouts.

Runs in the **backoffice** aspect.

| Depends on | Why |
|---|---|
| `payonepcpcore` | PCP data model and configuration |
| `payonepcpwebhook` | Webhook event view |
| `payonepcpfacades` | Capture/refund actions |
| `backoffice` | Backoffice framework |

---

## What you get

Explorer tree node **PAYONE Commerce Platform**:

| Node | Content |
|---|---|
| Configuration → Merchant Configurations | Create and edit `PayoneConfiguration` |
| Payments and Operations → Commerce Cases | Read-only |
| Payments and Operations → Checkouts | Read-only, with **PAYONE Capture** and **PAYONE Refund** actions |
| Payments and Operations → Payment Information | Read-only |
| Payments and Operations → Webhook Events | Read-only; shows processed and unprocessed events |

These records are created by the integration; creating them manually is disabled.

The **PAYONE Operations** tile on the administration dashboard shows active
configurations, checkouts of the last 24 hours, pending and retrying webhooks, and the
time of the last webhook.

The API secret is never shown or editable in the Backoffice.

---

## Capture and refund actions

The `PayoneCheckout` editor has two actions:

- **PAYONE Capture** (green) captures the amount that is still open.
- **PAYONE Refund** (orange) refunds what was captured and not yet refunded.

Both ask for confirmation and check the checkout live at PCP before sending anything.
They are **off by default**:

```properties
payonepcp.backoffice.paymentactions.enabled=true
payonepcp.backoffice.paymentactions.usergroup=admingroup
```

The checks run in `PayoneCheckoutOperationsFacade` (`payonepcpfacades`). Operation,
messages and troubleshooting are described in
[`RUNBOOK-payment-actions.md`](RUNBOOK-payment-actions.md).

---

## Customising

Backoffice configuration is in `resources/payonepcpbackoffice-backoffice-config.xml`,
labels in `resources/payonepcpbackoffice-backoffice-labels/` (English and German). To
restrict the PAYONE views or actions to specific roles, add `principal` contexts in your
own Backoffice configuration.

---

<p align="center">
  Implemented with ❤️ by <a href="https://www.adesso.de">adesso SE</a>
</p>