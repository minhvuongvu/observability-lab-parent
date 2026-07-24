// ---------------------------------------------------------------------------
// What order-service may do. Nothing else.
//
// Note the shape of a KV v2 path: the engine is mounted at `secret`, but a read
// goes to `secret/data/<name>`. The `data/` segment is not part of the name -
// it is how the v2 API separates a secret's value from its metadata, and a
// policy written against `secret/<name>` silently grants nothing.
//
// The denial is as much the point as the grant. docs/Vault.md walks through
// order-service being refused inventory-service's path and that refusal
// appearing in the audit log. A policy nobody has tested a denial against is
// indistinguishable from a policy of `*`.
// ---------------------------------------------------------------------------

// Shared, non-service-specific secrets. Spring Cloud Vault reads this first,
// then the service-specific path below, which overrides it.
path "secret/data/application" {
  capabilities = ["read"]
}

path "secret/data/order-service" {
  capabilities = ["read"]
}

// The profile-specific paths.
//
// Spring Cloud Vault reads <backend>/<context>/<profile> as well as
// <backend>/<context>, so a service running with SPRING_PROFILES_ACTIVE=dev
// asks for secret/data/order-service/dev whether or not that key exists.
//
// Vault answers a read the token has no grant for with 403, not 404 - so the
// missing GRANT and the missing SECRET are the same response, and omitting
// these two lines fails startup with "Status 403 Forbidden
// [secret/data/order-service/dev]" while the key it names was never expected
// to exist.
path "secret/data/application/*" {
  capabilities = ["read"]
}

path "secret/data/order-service/*" {
  capabilities = ["read"]
}

// Dynamic PostgreSQL credentials. A read here causes Vault to CREATE a
// PostgreSQL role, so this is closer to a write than the verb suggests - which
// is exactly why the capability is scoped to this one role name and not to
// `database/creds/*`.
path "database/creds/order-service" {
  capabilities = ["read"]
}

// Keep its own token alive. Without this the token expires at its TTL and the
// service loses Vault access while still running - and, because the datasource
// already holds open connections, it keeps working until the pool next grows.
path "auth/token/renew-self" {
  capabilities = ["update"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}

// Renew the database lease. Separate from the token: the token is the identity,
// the lease is the credential, and they expire independently.
path "sys/leases/renew" {
  capabilities = ["update"]
}
