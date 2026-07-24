// ---------------------------------------------------------------------------
// What inventory-service may do. Nothing else.
//
// Deliberately narrower than order-service.hcl: there is no database/creds
// grant, because Oracle is not covered by the database secrets engine built
// into the Vault binary. The Oracle plugin ships separately, needs the Oracle
// Instant Client libraries and has to be registered in the plugin catalogue.
//
// So inventory-service uses a static credential from KV v2 while order-service
// gets dynamic ones. That asymmetry is a stated limitation of this lab, not an
// oversight, and docs/Vault.md says so where a reader will find it.
// ---------------------------------------------------------------------------

path "secret/data/application" {
  capabilities = ["read"]
}

path "secret/data/inventory-service" {
  capabilities = ["read"]
}

// The profile-specific paths Spring Cloud Vault also reads -
// <backend>/<context>/<profile>, e.g. secret/data/inventory-service/dev.
// Vault returns 403 rather than 404 for an ungranted path, so without these
// the service fails startup naming a key that was never meant to exist.
path "secret/data/application/*" {
  capabilities = ["read"]
}

path "secret/data/inventory-service/*" {
  capabilities = ["read"]
}

path "auth/token/renew-self" {
  capabilities = ["update"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}
