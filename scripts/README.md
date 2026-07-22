# scripts

Operational scripts for building and running the lab. Every script is POSIX-friendly Bash, is safe
to run from any working directory, and resolves its own toolchain rather than trusting `PATH`.

## Available now

| Script | Purpose |
| --- | --- |
| `verify-toolchain.sh` | Reports whether this machine can build and run the lab. Exits non-zero when a build prerequisite is missing. |
| `build.sh` | Builds all Maven modules against a JDK 21 toolchain. Extra arguments are forwarded to Maven. |
| `infra.sh` | Drives the Docker infrastructure stack: start, stop, health, logs, destroy. Unrecognised commands pass through to `docker compose`. |
| `load.sh` | Runs a k6 load scenario from inside the Docker network: `smoke`, `load`, `stress`, `spike`, `soak`. Results remote-write into the same Prometheus that scrapes the platform. |
| `chaos.sh` | Injects latency, connection failures, resets and bandwidth caps into any dependency hop, through Toxiproxy. Takes effect immediately; `chaos.sh reset` undoes everything. |
| `run-service.sh` | **The exception path.** Runs one service on the host for attaching an IDE debugger. Its container must be stopped first. Everything normally runs in Docker — see the script header for what this path gives up. |
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

docker stop lab-order-service          # first: retire its container
./scripts/run-service.sh order-service

TOKEN=$(./scripts/token.sh alice)   # USER token; ./scripts/token.sh manager for ADMIN
curl -H "Authorization: Bearer $TOKEN" http://localhost/api/v1/orders
```

## Conventions

Scripts added here follow the same rules:

- `set -euo pipefail` at the top, so a failing command stops the script.
- Resolve paths from `BASH_SOURCE`, never from the caller's working directory.
- Fail with a message that names the fix, not just the symptom.
- Anything needing credentials reads them from the environment, never from a file in the repository.
