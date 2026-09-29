# Runbook — PAYONE Capture/Refund from the Backoffice

Operating guide for the **PAYONE Capture** and **PAYONE Refund** actions on the
`PayoneCheckout` editor. Audience: whoever installs the plugin and whoever
runs it day to day.

> **Scope.** This feature shows that Capture and Refund can be triggered from the
> Backoffice. It is not a full order-management integration: no Cancel, no partial
> amounts chosen by the operator, no link to SAP returns/consignments. Access control
> beyond one user group is the installing merchant's responsibility (see
> [Before go-live](#before-go-live)).

---

## 1. What the actions do

| Action | Sends to PCP | Amount | PCP must report checkout status |
|---|---|---|---|
| PAYONE Capture | `POST …/payment-executions/{id}/capture` | PCP `openAmount` (what is still authorized and not captured), as final capture | `COMPLETED`, `BILLED` or `CHARGEBACKED` |
| PAYONE Refund | `POST …/payment-executions/{id}/refund` | PCP `collectedAmount − refundedAmount`, with checkout currency | `BILLED` or `CHARGEBACKED` |

Status rules and request fields: <https://docs.commerce.payone.com/api-reference>
(*Capture a Payment*, *Refund a Payment*).

Each click runs these steps on the server, in order. Any failed step stops the action
before PCP is called:

1. Feature switch on, and the user is a member of the configured group.
2. Database row lock on the Checkout. A second click (same or another node) waits, then
   sees the result of the first one.
3. Every PCP payment transaction on the linked order must carry exactly this Checkout's
   Commerce Case / Checkout / Payment Execution ids.
4. The Checkout is read **live from PCP**. Status and remaining amount decide whether the
   action may run. An operation that already ran leaves no amount, so it is refused.
   This is what prevents a double capture or double refund.
5. The call is sent. A `PaymentTransactionEntry` (type `CAPTURE` / `REFUND_FOLLOW_ON`)
   records the PCP response.

The local Checkout status is **not** changed by the action. It changes when the PCP
webhook arrives (usually seconds later).

---

## 2. Installation and configuration

1. Register the extensions (`payonepcpcore`, `payonepcpfacades`, `payonepcpwebhook`,
   `payonepcpbackoffice`) as described in the plugin `README.md`, build, `ant updatesystem`.
2. Set the properties per environment (CCv2: *Environments → Services → Backoffice →
   Properties*, or `local.properties` locally):

   | Property | Default | Meaning |
   |---|---|---|
   | `payonepcp.backoffice.paymentactions.enabled` | `false` | Master switch. `false` = buttons disabled and every server call refused. |
   | `payonepcp.backoffice.paymentactions.usergroup` | `admingroup` | UID of the user group allowed to trigger the actions. Unknown group = nobody. |

3. The webhook endpoint (`/payonepcpwebhook`) must be reachable from PCP, otherwise
   statuses never update locally (the actions themselves still work).
4. Restart the Backoffice nodes after changing properties.

Turning the feature **off** needs only step 2 (`enabled=false`) and a restart. No data
migration is involved.

---

## 3. Using the actions

1. Backoffice → *PAYONE Commerce Platform → Payments and Operations → Checkouts*.
2. Open the Checkout (it must be linked to an order).
3. Click **PAYONE Capture** (green, arrow into tray: collect money) or **PAYONE Refund**
   (orange, return arrow: pay money back) in the editor toolbar. German UI labels:
   *PAYONE Capture (einziehen)* / *PAYONE Refund (erstatten)*.
4. Read the confirmation (checkout id, order, checkout total) and confirm.
5. Read the notification (next section). Reload the editor after the webhook to see the
   new status.

A disabled button means the local checks already fail: feature off, user not in the
group, no linked order, or incomplete/mismatching PCP ids. PCP-side reasons (wrong
status, nothing left) are only known after clicking and show as *rejected*.

---

## 4. Outcomes and what to do

| Notification | Meaning | Action |
|---|---|---|
| **Success** — "… submitted to PAYONE, reported status: X" | PCP accepted the request. `X` is usually `REQUESTED` (e.g. `CAPTURE_REQUESTED`, `REFUND_REQUESTED`): accepted, **not yet settled**. | None. Wait for the webhook. |
| **Rejected** — "… was rejected (nothing was executed at PAYONE): …" | Refused by our checks or by PCP with an error response. Nothing happened. | Read the reason (table below). Fix it, then retry if still needed. |
| **Unknown outcome** — "… UNKNOWN outcome at PAYONE … Do not retry" | The request may have reached PCP, but no readable answer came back (timeout, network, IO). | **Do not click again.** Follow [section 5](#5-reconciling-an-unknown-outcome). |

Common rejection reasons:

| Reason text contains | Cause | Fix |
|---|---|---|
| `disabled (payonepcp.backoffice.paymentactions.enabled)` | Feature switch off | Enable per section 2, if intended. |
| `not allowed to trigger` | User not in the configured group | Add the user to the group. |
| `not linked to an order` | Checkout without order (abandoned checkout) | Nothing to do: there is no order to capture or refund. |
| `do not all match checkout` / `incomplete PCP identifiers` | Order's PCP transactions point to another checkout, or ids missing | Data problem: escalate to development (section 7). Do not edit ids by hand. |
| `is not allowed for PCP checkout status [X]` | PCP status does not allow the operation (e.g. refund before the capture has settled → still `COMPLETED`) | Wait until PCP reports `BILLED` (capture settled), then retry. |
| `No open amount left to capture` | Already fully captured (or cancelled) | Nothing to do. Check the PAYONE Portal if unexpected. |
| `Nothing left to refund` | Already fully refunded | Nothing to do. |
| `Could not read checkout … from PCP` | PCP unreachable or credentials wrong; nothing was sent | Check PCP availability and the store's `PayoneConfiguration`; retry later. |
| `PAYONE is not configured for the order's store` | No active `PayoneConfiguration` for the store | Configure the store. |
| `Could not lock checkout` | Database does not support row locks (HSQLDB) or lock timed out | Use a supported DB; on timeout retry once the other operation finished. |
| PCP error text (e.g. `errorCode=…`) | PCP refused the request | Check the error code in the PCP docs (*Error codes*); fix and retry. |

---

## 5. Reconciling an unknown outcome

1. **Do not retry** from the Backoffice yet.
2. Wait a few minutes for a webhook, then reload the Checkout. If the status moved
   (e.g. to `BILLED`) or a new `PaymentTransactionEntry` appeared, the operation went
   through. Done.
3. Otherwise open the transaction in the **PAYONE Commerce Portal** (by checkout id)
   and check whether a capture/refund exists.
   - **Exists:** the operation ran. The webhook will catch up. If it never arrives,
     check webhook delivery (section 6).
   - **Does not exist:** retrying is safe. The action re-reads PCP's amounts and would
     refuse anyway if something had been executed in the meantime.
4. Record what you found in the order's notes / ticket.

---

## 6. Monitoring and logs

- Log category: `com.payone.pcp.facades.impl.DefaultPayoneCheckoutOperationsFacade`.
  Every triggered operation logs one `INFO` line with user, checkout, order, amount and
  currency, for example:
  `PAYONE Backoffice REFUND by [jdoe] for checkout [947b…], order [00012345], amount [1999 EUR]`.
  Failures of the PCP call log in `DefaultPayonePaymentOperationsFacade` with the PCP
  error description (no secrets, no card data).
- Audit trail in the data model: the order's `PaymentTransaction` → entries of type
  `CAPTURE` / `REFUND_FOLLOW_ON` with amount, status and PCP request id.
- Webhook health: Backoffice → *Webhook Events*. Many unprocessed events or growing
  attempt counts mean statuses are stale. The dashboard tile *PAYONE Operations* shows
  pending/retrying webhooks.

---

## 7. Known limits and escalation

- Full remaining amount only. Partial amounts and Cancel are not offered.
- One PCP payment execution per checkout (the current data model). Orders with
  transactions for several checkouts are refused (safety over convenience).
- The row lock needs a database with row locking (all CCv2 databases). On HSQLDB the
  action is *rejected* ("Could not lock checkout …") before anything is sent. Use a real
  DB for local tests.
- Escalate to development with: checkout id, order code, time, notification text and
  the log lines from section 6.

## Before go-live

The installing merchant decides and sets up:

- a dedicated user group instead of `admingroup` (`payonepcp.backoffice.paymentactions.usergroup`),
  optionally also hiding the buttons for other roles via a `principal` context in their
  own Backoffice configuration;
- which environments enable the feature;
- whether Capture/Refund should instead run through their order process (consignment,
  returns). That integration is not part of this plugin.
