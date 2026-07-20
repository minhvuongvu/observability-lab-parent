// ---------------------------------------------------------------------------
// Consul server agent.
//
// One server node. Production runs three or five so a quorum survives losing
// one; bootstrap_expect = 1 says plainly that this cluster tolerates no
// failures. It is enough to exercise registration, health checking and KV,
// which is what the lab needs.
//
// Service registration and KV seeding belong to step 08. This file only makes
// the agent a healthy, reachable server.
// ---------------------------------------------------------------------------

datacenter = "dc1"
data_dir   = "/consul/data"
node_name  = "lab-consul"
log_level  = "INFO"

server           = true
bootstrap_expect = 1

// Listen on every interface inside the container. The published port is bound
// to 127.0.0.1 on the host, so this does not widen exposure.
client_addr = "0.0.0.0"

ui_config {
  enabled = true
}

ports {
  http = 8500
  dns  = 8600
}

// Script checks let a registering service ask the agent to execute a command.
// That is remote code execution by design, so it stays off; services use HTTP
// and TTL checks instead.
enable_script_checks       = false
enable_local_script_checks = false

// ACLs are disabled. A single-node lab with every port bound to loopback does
// not gain much from them, and they would obscure the registration mechanics
// step 08 is meant to teach.
acl {
  enabled        = false
  default_policy = "allow"
}
