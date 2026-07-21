# Protocol Buffers Contract Design

The `.proto` file is not a serialisation detail. It is **the API contract**, it is compiled into both
sides, and once a consumer exists it cannot be changed freely. This document defines how contracts in
this system are structured, versioned and evolved.

Companion: [GRPC_ARCHITECTURE.md](GRPC_ARCHITECTURE.md) · [GRPC_ERROR_HANDLING.md](GRPC_ERROR_HANDLING.md)

---

## 1. Repository layout

```
proto/
└── inventory/
    └── v1/
        └── inventory_service.proto        # owned by the Inventory Service
```

| Rule | Why |
| --- | --- |
| **The provider owns the proto.** `inventory/` is the Inventory Service's contract; only that team changes it. | A schema owned by committee, or copied into the consumer, is how a shared contract becomes a distributed monolith. |
| **The version is a directory *and* a package.** `inventory/v1/` ↔ `package inventory.v1`. | Two major versions must be able to coexist on disk, in the classpath and on the wire. |
| **One service per file, named after it.** | Locating the contract for `InventoryService` requires no search. |
| **Generated code is never committed.** It is a build output. | Committed stubs drift from the `.proto` that supposedly produced them. |

In this repository the `proto/` directory sits at the root rather than inside the owning module,
because both modules consume it — the provider generates server stubs, the consumer generates client
stubs, from **one file**. That single source is the entire point: it is what makes the step 09
envelope-decoding defect impossible.

---

## 2. The contract

```protobuf
syntax = "proto3";

package inventory.v1;

option java_package = "com.observability.lab.inventory.grpc.v1";
option java_multiple_files = true;
option java_outer_classname = "InventoryServiceProto";

import "google/protobuf/timestamp.proto";

// Stock availability and reservation.
//
// Authoritative reservation happens asynchronously over Kafka (`order-created`).
// The unary RPCs here are for the synchronous path: advisory checks that a caller
// must have before it can render a page, and an express reservation that races
// the asynchronous flow under a short deadline.
service InventoryService {

  // Availability for one product. Advisory: reserves nothing.
  rpc CheckStock(CheckStockRequest) returns (CheckStockResponse);

  // Availability for many products in one round trip.
  //
  // This is the reason the service exists in gRPC form: the REST equivalent
  // costs one HTTP request per SKU, which is linear in basket size on the
  // checkout path.
  rpc BatchCheckStock(BatchCheckStockRequest) returns (BatchCheckStockResponse);

  // Reserve stock synchronously. Complements, never replaces, the Kafka path.
  //
  // Idempotent on event_id: the same event_id always yields the same outcome,
  // whether it arrives here or over Kafka.
  rpc ReserveStock(ReserveStockRequest) returns (ReserveStockResponse);

  // Live stock levels for a watchlist. Server streams on change.
  rpc WatchStockLevels(WatchStockLevelsRequest) returns (stream StockUpdate);

  // Bulk adjustments, e.g. nightly warehouse reconciliation.
  // Client streams; the server applies in batches and answers once.
  rpc BulkAdjustStock(stream StockAdjustment) returns (BulkAdjustStockSummary);
}

// ---------------------------------------------------------------------------
// CheckStock
// ---------------------------------------------------------------------------

message CheckStockRequest {
  // Product identifier. Required; empty yields INVALID_ARGUMENT.
  string product_sku = 1;

  // How many the caller is considering. Optional; when 0 the response reports
  // availability without a sufficiency verdict.
  int32 requested_quantity = 2;
}

message CheckStockResponse {
  string product_sku = 1;
  int32 available_quantity = 2;
  int32 reserved_quantity = 3;

  // Whether requested_quantity could be satisfied at the moment of the call.
  // Advisory: another caller may reserve the same units microseconds later.
  bool sufficient = 4;

  // False when inventory does not track this SKU at all. Distinguished from
  // available_quantity == 0, which means "tracked, and none left".
  bool tracked = 5;

  // When this figure was read. Lets a caller reason about staleness rather
  // than assuming the answer is current.
  google.protobuf.Timestamp as_of = 6;
}

// ---------------------------------------------------------------------------
// BatchCheckStock
// ---------------------------------------------------------------------------

message BatchCheckStockRequest {
  // Bounded server-side. More than 100 yields INVALID_ARGUMENT rather than a
  // slow success — an unbounded batch is a denial-of-service primitive.
  repeated CheckStockRequest items = 1;
}

message BatchCheckStockResponse {
  // One result per requested item, in request order. A SKU that is not tracked
  // appears with tracked = false rather than being omitted, so the caller can
  // zip the lists positionally.
  repeated CheckStockResponse results = 1;
}

// ---------------------------------------------------------------------------
// ReserveStock
// ---------------------------------------------------------------------------

message ReserveStockRequest {
  // Idempotency key. The same event_id is used by the Kafka path, which is what
  // makes the two paths safe to race.
  string event_id = 1;

  string order_number = 2;

  repeated ReservationLine lines = 3;
}

message ReservationLine {
  string product_sku = 1;
  int32 quantity = 2;
}

message ReserveStockResponse {
  ReservationOutcome outcome = 1;

  // Human-readable shortages, populated only when outcome is REJECTED.
  repeated string shortages = 2;

  // True when this event_id had already been applied and the outcome is a
  // replay rather than a new decision.
  bool replayed = 3;
}

enum ReservationOutcome {
  // Every proto3 enum must have a zero value, and it must mean "unspecified".
  // A caller that receives this is talking to a newer server that returned a
  // value it does not know.
  RESERVATION_OUTCOME_UNSPECIFIED = 0;
  RESERVATION_OUTCOME_RESERVED = 1;
  RESERVATION_OUTCOME_REJECTED = 2;
}

// ---------------------------------------------------------------------------
// Streaming
// ---------------------------------------------------------------------------

message WatchStockLevelsRequest {
  repeated string product_skus = 1;

  // Send the current level for each SKU immediately on subscribe, before any
  // change events. Without this a client cannot distinguish "no change yet"
  // from "not subscribed".
  bool send_initial_state = 2;
}

message StockUpdate {
  string product_sku = 1;
  int32 available_quantity = 2;
  int32 reserved_quantity = 3;
  google.protobuf.Timestamp occurred_at = 4;
  StockChangeReason reason = 5;
}

enum StockChangeReason {
  STOCK_CHANGE_REASON_UNSPECIFIED = 0;
  STOCK_CHANGE_REASON_INITIAL_STATE = 1;
  STOCK_CHANGE_REASON_RESERVED = 2;
  STOCK_CHANGE_REASON_RELEASED = 3;
  STOCK_CHANGE_REASON_RECEIVED = 4;
  STOCK_CHANGE_REASON_ADJUSTED = 5;
}

message StockAdjustment {
  string product_sku = 1;
  int32 quantity_delta = 2;
  string reference = 3;
  google.protobuf.Timestamp occurred_at = 4;
}

message BulkAdjustStockSummary {
  int32 applied = 1;
  int32 rejected = 2;
  repeated string rejection_reasons = 3;
  int64 duration_ms = 4;
}
```

---

## 3. Why this design is production-safe

### 3.1 Package naming and versioning

```
package inventory.v1;
option java_package = "com.observability.lab.inventory.grpc.v1";
```

| Element | Convention | Reason |
| --- | --- | --- |
| Proto package | `<domain>.<major-version>` | The wire-level service name becomes `inventory.v1.InventoryService`. Version is part of the routing identity, so v1 and v2 can be served by one process on one port. |
| Java package | Distinct from the proto package | Generated code must not collide with hand-written code in the same namespace. `…inventory.grpc.v1` makes its origin obvious. |
| `java_multiple_files` | `true` | One class per message, rather than everything nested inside one outer class. Readable imports, smaller recompiles. |

**Only the major version appears.** There is no `v1.2`. Minor evolution is additive and therefore
invisible; anything that is not additive is a new major version.

### 3.2 Field numbering rules

Field numbers are the wire format. **The name is documentation; the number is the contract.**

| Rule | Consequence of breaking it |
| --- | --- |
| Never change an existing field's number | Old and new peers read different fields from the same bytes. Silent data corruption, not an error. |
| Never reuse a number from a deleted field | An old client's value is decoded into the new field's type. Silent corruption again. |
| Reserve deleted numbers and names | Without `reserved`, nothing stops the next person reusing them. |
| Keep 1–15 for hot fields | They encode in one byte instead of two. `product_sku` and `available_quantity` are on every response. |
| Never renumber to "tidy up" | There is no tidying. The numbers are permanent. |

Deleting a field correctly:

```protobuf
message CheckStockResponse {
  reserved 7;                    // was warehouse_code, removed in v1.4
  reserved "warehouse_code";     // stops the *name* being reused too
  // ...
}
```

### 3.3 Optional fields and the zero-value problem

proto3 has no required fields — deliberately, because required is unremovable and has broken more
schemas than it has protected. Every field is optional, and an absent field decodes as its zero value.

That creates a genuine ambiguity: **`available_quantity = 0` could mean "zero in stock" or "the
sender never set this field"**.

This contract resolves it explicitly rather than hoping:

| Ambiguity | Resolution |
| --- | --- |
| Is 0 a real quantity or an unset field? | `tracked` distinguishes "tracked, none left" from "not tracked at all" |
| Is `sufficient = false` a verdict or an unset field? | Only meaningful when `requested_quantity > 0`, documented in the field comment |
| Is `RESERVATION_OUTCOME_UNSPECIFIED` a valid outcome? | No. It means the sender used a value this client does not know — treat as unknown, never as success |

Where an explicit "was this set" is genuinely needed, proto3 offers the `optional` keyword (which
generates `hasX()`) or a wrapper type. Both are used sparingly here: a scalar with a documented
sentinel is simpler than a wrapper when the sentinel is unambiguous.

### 3.4 Enum evolution

Three rules, each preventing a specific failure:

1. **The zero value is always `*_UNSPECIFIED`.** A message from a peer that never set the field, and
   a message from a newer peer using a value this build does not have, both arrive as zero. Naming
   zero `RESERVED` would silently turn "I don't know" into "success".
2. **Prefix every value with the enum name.** Proto enum values live in the *enclosing* scope, not the
   enum's — so an unprefixed `RESERVED` in two enums in one package is a compile error.
3. **Only append.** Removing or renumbering a value breaks every existing peer.

**Consumers must handle unknown enum values.** A client built against v1.1 talking to a v1.3 server
will receive values it has no constant for. In Java these arrive as `UNRECOGNIZED`:

```java
switch (response.getOutcome()) {
    case RESERVATION_OUTCOME_RESERVED -> confirm();
    case RESERVATION_OUTCOME_REJECTED -> reject();
    // UNSPECIFIED, UNRECOGNIZED, and anything added later
    default -> fallBackToAsynchronousPath();
}
```

A `switch` without a default is a compile-time success and a runtime failure the first time the
server is upgraded.

### 3.5 Backward and forward compatibility

| Change | Wire-compatible? | Notes |
| --- | --- | --- |
| Add a field with a new number | ✅ Both ways | Old peers ignore it; new peers see the zero value from old peers |
| Add an RPC | ✅ | Old clients never call it |
| Add an enum value | ✅ with care | Old clients see `UNRECOGNIZED`; §3.4 |
| Rename a field, same number | ✅ on the wire | JSON mapping and generated code both break — treat as breaking |
| Delete a field | ⚠️ | Only with `reserved`; readers see the zero value |
| Change a field's type | ❌ | Except a documented set (`int32`↔`int64`↔`bool` share varint encoding, with truncation risk) |
| Change a field number | ❌ | Silent corruption |
| Rename a service or RPC | ❌ | The method path is the wire identity |
| `singular` → `repeated` | ❌ | Different encoding |

**The rule that follows:** additive changes ship as minor releases with no coordination. Anything
else is `inventory.v2`, served alongside `v1` until every consumer has moved.

### 3.6 Preventing breaking changes mechanically

Review does not catch these reliably — the failure is invisible in a diff that looks tidy. The
protection has to be automated:

| Control | Mechanism |
| --- | --- |
| **Breaking-change detection in CI** | `buf breaking --against '.git#branch=main'`. Fails the build on any wire-incompatible change. This is the important one. |
| **Lint** | `buf lint` enforces the naming, prefixing and zero-value rules above. |
| **Generated code is never committed** | Build output only, so it cannot drift from the source. |
| **One source file, both sides** | The provider and consumer generate from the same `.proto`; there is no copy to fall out of step. |
| **Reflection in non-prod** | `grpcurl` can dump the live schema, so what is deployed can be compared with what is in the repository. |

### 3.7 What is deliberately *not* in the contract

| Excluded | Why |
| --- | --- |
| Authentication tokens as fields | They belong in metadata, where interceptors handle them uniformly and they never reach a message log |
| Trace or correlation ids as fields | Same — metadata, propagated by the OTel agent |
| Error details as response fields | gRPC has a status channel. An `error_message` field alongside `status: OK` means every client must check two places, and one of them will be forgotten |
| Pagination on `BatchCheckStock` | The request is already bounded at 100. A bounded request needs no cursor |
| Free-form `map<string,string>` metadata | An escape hatch that becomes the API. If a field is needed, add a field |

The third row is worth restating: **errors go in the status, not in the payload.** That is what
[GRPC_ERROR_HANDLING.md](GRPC_ERROR_HANDLING.md) is about.

---

## 4. Build integration

```
proto/inventory/v1/inventory_service.proto
        │
        ├── protoc + protoc-gen-grpc-java
        │
        ├──► inventory-service   : server base class to extend
        └──► order-service       : blocking + async client stubs
```

Both modules generate from the same file at build time via `protobuf-maven-plugin`. Generated sources
land in `target/generated-sources/protobuf` and are added to the compile path automatically — never
committed, never edited.

`buf lint` and `buf breaking` run in the same build, so a contract violation fails the build rather
than reaching a consumer.

---

## 5. Versioning in practice — a worked example

**Requirement:** report which warehouse holds the stock.

**Wrong** — reusing a reserved number, changing a type, renaming a field:

```protobuf
message CheckStockResponse {
  string product_sku = 1;
  int64 available_quantity = 2;   // ❌ type change
  string warehouse = 3;           // ❌ number 3 was reserved_quantity
}
```

**Right** — additive, in `v1`, no coordination needed:

```protobuf
message CheckStockResponse {
  string product_sku = 1;
  int32 available_quantity = 2;
  int32 reserved_quantity = 3;
  bool sufficient = 4;
  bool tracked = 5;
  google.protobuf.Timestamp as_of = 6;

  // Added v1.4. Empty for stock not yet assigned to a warehouse, and for
  // responses from servers older than v1.4 — the two are indistinguishable,
  // which is acceptable because the field is advisory.
  string warehouse_code = 7;
}
```

Old clients ignore field 7. Old servers omit it and new clients see `""`. No release coordination,
no downtime, no v2.

**When v2 is genuinely required** — say `available_quantity` must become a decimal:

1. Create `proto/inventory/v2/inventory_service.proto` with `package inventory.v2`.
2. Serve both from the same process. Different package ⇒ different method paths ⇒ no conflict.
3. Migrate consumers one at a time; the `grpc_service` metric label shows who is still on v1.
4. Retire v1 only when that label goes to zero.

That last step is the reason `grpc_service` is a metric dimension in
[GRPC_OBSERVABILITY.md](GRPC_OBSERVABILITY.md) — a deprecation you cannot measure is a deprecation
that never finishes.
