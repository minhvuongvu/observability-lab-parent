# scripts

Operational scripts for building and running the lab. Every script is POSIX-friendly Bash, is safe
to run from any working directory, and resolves its own toolchain rather than trusting `PATH`.

## Available now

| Script | Purpose |
| --- | --- |
| `verify-toolchain.sh` | Reports whether this machine can build and run the lab. Exits non-zero when a build prerequisite is missing. |
| `build.sh` | Builds all Maven modules against a JDK 21 toolchain. Extra arguments are forwarded to Maven. |
| `infra.sh` | Drives the Docker infrastructure stack: start, stop, health, logs, destroy. Unrecognised commands pass through to `docker compose`. |
| `run-service.sh` | Runs a service against the running stack, reading the effective ports from `docker/compose/.env` so a remapped stack still connects correctly. |
| `gateway.sh` | Validates, reloads and inspects the edge: Kong's routes, plugins and upstream target health. |
| `token.sh` | Fetches a Keycloak access token via the password grant, for calling the protected APIs by hand. Prints only the token. |
| `toolchain.sh` | Sourced helper that resolves a JDK 21+ into `JAVA_HOME`. Not executed directly. |

## Usage

```bash
./scripts/verify-toolchain.sh     # check prerequisites first
./scripts/build.sh                # clean verify across every module
./scripts/build.sh -DskipTests    # arguments pass straight through to Maven

./scripts/infra.sh up             # start the stack, wait until healthy
./scripts/infra.sh health         # one line per container
./scripts/infra.sh logs kafka     # follow one service
./scripts/infra.sh destroy        # stop and delete every volume (asks first)
./scripts/infra.sh exec redis sh  # anything else goes to docker compose

./scripts/run-service.sh order-service
./scripts/run-service.sh order-service --spring.profiles.active=dev

TOKEN=$(./scripts/token.sh alice)   # USER token; ./scripts/token.sh manager for ADMIN
curl -H "Authorization: Bearer $TOKEN" http://localhost/api/v1/orders
```

## Conventions

Scripts added here follow the same rules:

- `set -euo pipefail` at the top, so a failing command stops the script.
- Resolve paths from `BASH_SOURCE`, never from the caller's working directory.
- Fail with a message that names the fix, not just the symptom.
- Anything needing credentials reads them from the environment, never from a file in the repository.
