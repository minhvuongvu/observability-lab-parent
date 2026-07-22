# Object storage — MinIO

Every order gets an invoice, and the invoice does not live in the database. It is a document: written
once, read rarely, potentially large, and of no interest to any query. Object storage is where that
belongs, and putting it there is what gives the lab a third kind of I/O to observe alongside SQL and
Kafka.

MinIO speaks S3, so nothing in the application is specific to it beyond the endpoint — the same code
works against S3 with a different URL and credentials.

---

## 1. Where the pieces live

| Concern | Location |
| --- | --- |
| Server | `docker-compose.yml` `minio` (API `:9000`, console `:9001`) |
| Bucket, policy and app user | [`create-buckets.sh`](../infrastructure/minio/init/create-buckets.sh) via `minio-init` |
| Port (application layer) | [`InvoiceArchive`](../services/order-service/src/main/java/com/observability/lab/order/application/InvoiceArchive.java) |
| Adapter | [`MinioInvoiceArchive`](../services/order-service/src/main/java/com/observability/lab/order/infrastructure/storage/MinioInvoiceArchive.java) |
| Rendering | [`InvoiceRenderer`](../services/order-service/src/main/java/com/observability/lab/order/application/InvoiceRenderer.java) |
| Upload trigger | [`InvoiceUploader`](../services/order-service/src/main/java/com/observability/lab/order/infrastructure/storage/InvoiceUploader.java) |

---

## 2. Credentials

The service authenticates as `lab-invoice-app`, whose policy grants `GetObject`, `PutObject` and
`DeleteObject` on the invoice bucket and nothing else. The MinIO **root** account administers the
server and is never handed to an application — a compromised service should not be able to read every
bucket, create users, or change policy.

Versioning is enabled on the bucket, so re-storing an invoice keeps the previous rendering
recoverable rather than destroying it.

---

## 3. When the upload happens

`InvoiceUploader` listens `AFTER_COMMIT` — the opposite phase from the outbox write, for the opposite
reason.

Uploading inside the order's transaction would hold a database connection open for the length of an
HTTP round trip to another system, and would roll the order back if the bucket were briefly
unreachable. Neither is a trade worth making for a document that is *derived* from the order.

That derivation is also why a failed upload is logged rather than propagated: the invoice can be
rebuilt from the order at any time. `GET /api/v1/orders/{orderNumber}/invoice` does exactly that when
the object is missing, so a transient MinIO outage costs a rebuild on first request, not a broken
invoice forever.

## 4. Object naming

```
invoices/orders/{orderNumber}/invoice.json
```

Derived from the order number rather than stored in a column. The invoice for an order is at a known
address, so there is no second source of truth to keep in step and no migration was needed to add
object storage to an existing table. S3 has no directories, but every console and client renders the
slashes as one.

The document is a **snapshot**: it records what the order looked like when it was invoiced —
including the status at that moment, usually `PENDING` — and is not rewritten when the order is later
confirmed. An invoice that silently changed to match a mutated order would not be an invoice.

## 5. Signed URLs

`GET /api/v1/orders/{orderNumber}/invoice` returns a link, not the document:

```json
{
  "url": "http://127.0.0.1:9000/invoices/orders/ORD-.../invoice.json?X-Amz-Algorithm=AWS4-HMAC-SHA256&...",
  "expiresAt": "2026-07-21T06:08:56Z"
}
```

Three properties, each deliberate:

- **The service never proxies the bytes.** Large transfers stay off the application's threads and
  connection pool entirely. The client fetches from MinIO directly.
- **The signature is computed locally.** No request reaches MinIO to mint the URL. It encodes the
  object, the method and the expiry, so it grants exactly one read of one object for a bounded time
  and cannot be edited into access to anything else.
- **It expires** (15 minutes, `app.invoice.url-validity`). Long enough for a person to follow the
  link, short enough that one leaked through a log or a referrer header stops working quickly. The
  alternative — a public bucket — publishes who bought what, at scale.

`expiresAt` is returned alongside the URL so a client that caches the link knows when it stops
working, rather than discovering it through a 403 from object storage.

---

## 6. Verify it

```bash
TOKEN=$(./scripts/token.sh manager | tail -1)
ORDER=...   # an order number

# The link
curl -s "http://localhost:8081/api/v1/orders/${ORDER}/invoice" \
  -H "Authorization: Bearer ${TOKEN}" | python3 -m json.tool

# Following it needs no credentials of any kind
curl -s "$(curl -s "http://localhost:8081/api/v1/orders/${ORDER}/invoice" \
  -H "Authorization: Bearer ${TOKEN}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["url"])')"

# What is actually in the bucket
docker run --rm --network lab-net --entrypoint sh minio/mc:RELEASE.2025-08-13T08-35-41Z -c \
  "mc alias set l http://minio:9000 lab-minio-root localdev_minio_root_pw >/dev/null && mc ls --recursive l/invoices/"
```

The MinIO console at <http://localhost:9001> shows the same objects, their versions and their
content types.

To watch the rebuild path, delete the object and request the link again — the service notices it is
missing, re-renders it and returns a working URL.
