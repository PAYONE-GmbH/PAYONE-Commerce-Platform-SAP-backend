# payonepcpocc

OCC REST layer of the PAYONE Commerce Platform (PCP) plugin for SAP Commerce Cloud 2211.
It provides the endpoints the Composable Storefront (Spartacus) calls during checkout.

The extension has no web module of its own. Its controllers are added to the
`commercewebservices` OCC v2 web application and served under `/occ/v2`.

| Depends on | Why |
|---|---|
| `payonepcpfacades` | All business logic |
| `commercewebservices` | OCC v2 web application, WsDTO and field-mapping infrastructure |

---

## Endpoints

Base path: `/occ/v2/{baseSiteId}/users/{userId}/carts/{cartId}/payone`
(`PayoneCheckoutController`). All calls work on the given cart.

| Method and path | Purpose |
|---|---|
| `POST /commerce-case` | Create (or reuse) the PCP Commerce Case and Checkout for the cart |
| `POST /authentication-token` | Issue a short-lived PCP token for client-side card tokenization. No API key or secret leaves the server. |
| `POST /payments/authorize?paymentProductId=…` | Execute the payment for the chosen payment method. Returns a redirect URL for redirect methods. |
| `POST /payments/redirect-callback?checkoutId=…` | Read the payment result after the customer returns from a redirect |
| `POST /orders` | Place the order after a successful payment |
| `POST /placeorder` | Execute a card or SEPA payment and place the order in one call. Deprecated; use `/payments/authorize` and `/orders`. |

Redirect URLs are returned in the response body, never as an HTTP redirect, so the
storefront stays in control of navigation.

### Error responses

| Status | Cause |
|---|---|
| `400` | Invalid request data |
| `409` | PAYONE is not configured for the store, or the cart does not match the PCP checkout |
| `502` | The call to PCP failed |

Error messages never contain secrets or card data.

---

## Customising

Request and response objects are WsDTOs defined in `payonepcpocc-beans.xml`. Add
properties there and map them in your own populators, as with any OCC extension.

---

<p align="center">
  Implemented with ❤️ by <a href="https://www.adesso.de">adesso SE</a>
</p>