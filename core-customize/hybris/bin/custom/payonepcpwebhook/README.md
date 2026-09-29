# payonepcpwebhook

Webhook receiver of the PAYONE Commerce Platform (PCP) plugin for SAP Commerce Cloud 2211.
It receives PCP events, verifies their signature, stores them and updates checkouts,
payment transactions and the order payment status.

The endpoint is called by PAYONE, not by a logged-in user. It therefore runs in its own
web application (`/payonepcpwebhook`), separate from the storefront and OCC API, and
authenticates each request by its signature.

| Depends on | Why |
|---|---|
| `payonepcpcore` | Merchant configuration, `PayoneCheckout` / `PayoneCommerceCase`, payment transactions |
| `webservicescommons` | REST infrastructure |

The extension owns one item type: `PayoneWebhookEvent` (typecode 15116).

---

## Setup

1. Expose the web module on the aspect that should receive webhooks, e.g. in
   `manifest.json`:

   ```json
   { "name": "payonepcpwebhook", "contextPath": "/payonepcpwebhook" }
   ```

2. Register the URL in the PAYONE merchant portal under **Configuration → Webhooks** and
   select the event categories you need:

   ```
   https://<host>/payonepcpwebhook/webhooks
   ```

3. Allow PAYONE's webhook source IPs in your firewall or CCv2 IP filter:

   | Environment | IP addresses |
   |---|---|
   | Pre-production | 82.98.224.158, 3.75.34.70, 3.127.45.36, 18.198.31.240 |
   | Production | 185.60.20.0/24, 52.57.137.71, 3.69.190.236, 3.64.115.185 |

4. Make sure the API secret of each merchant is set
   (`payone.pcp.<merchantId>.apiSecret`, see `payonepcpcore/README.md`). The same secret
   verifies the webhook signature.

5. Set `payonepcpwebhook.process.user` to a technical user with the rights to update
   orders and payment data (default: `admin`).

---

## How a webhook is processed

```
POST /payonepcpwebhook/webhooks  →  204 No Content
```

1. **Verify** — the `X-GCS-KeyId` header must match the merchant's `apiKeyId`, and
   `X-GCS-Signature` must equal `Base64(HmacSHA256(apiSecret, raw body))`. The merchant
   is taken from the event body.
2. **Parse** the JSON event.
3. **Switch** to the technical user.
4. **Store** the event as `PayoneWebhookEvent`, identified by its event ID, before any
   processing. A repeated delivery of an already processed event is ignored.
5. **Process** — find the local checkout or commerce case and run the matching handler.
6. **Mark** the event processed. If processing fails, the attempt is counted and PAYONE
   delivers the event again.

### Responses

| Status | When | PAYONE's reaction |
|---|---|---|
| `204` | Signature valid and event stored | Done |
| `401` | Signature missing or wrong, unknown key ID, or no secret configured | Retries |
| `400` | Body is not valid JSON, has no event ID, or is larger than the limit | Retries |
| `500` | Processing failed or the technical user does not exist | Retries |

PAYONE retries after 10 minutes, 1 hour, 2 hours, 8 hours and 24 hours.

### What the handlers update

| Event domain | Effect |
|---|---|
| Payment, payment execution, payment information, refund | Updates `PayoneCheckout.status`, adds a `PaymentTransactionEntry` and recalculates `Order.paymentStatus` from captured and refunded amounts |
| Checkout | Updates `PayoneCheckout.status` |
| Commerce case | Creates or updates the local `PayoneCommerceCase` |
| Payout | Stored, not processed |

Events that are stored but not processed stay in the Backoffice (*PAYONE Commerce
Platform → Payments and Operations → Webhook Events*) with `processed = false`. This
applies to payouts, to event types without a status mapping (logged at WARN) and to
API versions other than `payonepcpwebhook.apiversion.supported`.

PCP does not guarantee the order of events. Each handler applies the status it receives.

---

## Properties

| Property | Default | Meaning |
|---|---|---|
| `payonepcpwebhook.process.user` | `admin` | User the receiver runs as. Use a dedicated technical user in production. An unknown user makes every delivery fail with 500. |
| `payonepcpwebhook.apiversion.supported` | `v1` | Webhook API versions that are processed |
| `payonepcpwebhook.max.body.bytes` | `1048576` | Maximum accepted body size |

---

## Adding a handler

Add a `PayoneWebhookEventHandler` bean to the `payoneWebhookEventHandlers` list in
`payonepcpwebhook-spring.xml`. Handlers must be idempotent, because a failed event is
processed again on redelivery. To decline an event on purpose (store it without
processing), throw `PayoneWebhookNotSupportedException`.

## Security

- Only `POST /webhooks` is open; every other path and method of the web application
  requires authentication (`web/webroot/WEB-INF/config/security-spring.xml`).
- All signature failures return the same response, so the endpoint reveals nothing.
- Secrets, signatures and raw bodies are never logged.

---

<p align="center">
  Implemented with ❤️ by <a href="https://www.adesso.de">adesso SE</a>
</p>