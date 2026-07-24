// ---------------------------------------------------------------------------
// Vault server.
//
// Deliberately NOT `vault server -dev`. Dev mode auto-unseals, keeps everything
// in memory and prints a fixed root token, which removes the three things that
// are actually hard about operating Vault - seal state, unseal ceremony and
// token lifecycle - and those are what this step exists to teach.
//
// The cost is real and is accepted: this Vault boots SEALED after every restart
// and cannot serve a single secret until something unseals it. ./scripts/vault.sh
// does that, and ./scripts/infra.sh calls it before the services start. A stack
// that comes up without an unseal step is a stack running dev mode.
// ---------------------------------------------------------------------------

// File storage rather than integrated Raft.
//
// Raft is the production choice because it replicates and snapshots, and both
// of those matter when losing the node means losing every credential. Neither
// applies here: one node, no peers to join, and the data directory is a named
// volume the reader can delete on purpose. Raft would add a node_id, a retry_join
// block and a cluster listener to configure - all of it inert on a single node,
// and all of it in the reader's way.
//
// What this costs: no `vault operator raft snapshot`. Backup is a volume copy.
//
// /vault/file, not a path of our choosing. The image creates this directory
// owned by the uid the Vault process drops to; a named volume mounted anywhere
// else is created by Docker as root, and Vault then fails initialisation with
// "failed to persist keyring: permission denied" - which reads like a storage
// bug and is a uid.
storage "file" {
  path = "/vault/file"
}

listener "tcp" {
  address     = "0.0.0.0:8200"

  // No TLS, consistent with every other hop in this lab (see docs/Security.md
  // section on what is deliberately absent). The port binds to 127.0.0.1 on the
  // host and nothing outside lab-net reaches it.
  //
  // Understand what this means before copying it: unseal keys, the root token
  // and every secret Vault serves cross this listener in cleartext. In a real
  // deployment TLS here is not optional, because the thing being protected is
  // the protection mechanism itself.
  tls_disable = 1

  telemetry {
    // Lets Prometheus scrape /v1/sys/metrics without presenting a token.
    //
    // The alternative is issuing Prometheus its own Vault token, renewing it,
    // and handling its expiry - which puts a credential in the monitoring
    // config in order to monitor the credential store, and leaves the metrics
    // pipeline broken the day that token lapses.
    //
    // This exposes operational telemetry only: seal state, request rates,
    // lease and token counts. No secret material passes through this endpoint.
    unauthenticated_metrics_access = true
  }
}

// How Vault refers to itself when it redirects a client. The compose name on
// lab-net, because every caller is on that network - the published port exists
// for a human with a browser and is never used by a service.
api_addr = "http://vault:8200"

ui = true

telemetry {
  // Long enough that a scrape gap does not lose a counter, short enough that
  // an idle Vault is not holding a day of samples for nothing.
  prometheus_retention_time = "24h"

  // The hostname is the container id, which changes on every recreate. Left on,
  // every metric acquires a new label value each time the stack restarts and
  // the series become uncomparable across runs.
  disable_hostname = true
}

// Leases shorter than the defaults, on purpose.
//
// A 32-day default TTL means a lab reader never sees a renewal, never sees an
// expiry, and concludes that dynamic credentials are just credentials. One hour
// with an eight hour cap means renewal happens while somebody is watching, and
// the database role below shortens it further still.
default_lease_ttl = "1h"
max_lease_ttl     = "8h"
