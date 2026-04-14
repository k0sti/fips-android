# FIPS Android

An Android app that runs a [FIPS](./fips/README.md) mesh node as a Rust library
via UniFFI, routing IPv6 traffic through a `VpnService` TUN. Built on the
`fips/` submodule, which implements the core mesh protocol (spanning tree,
Noise encryption, multi-transport: UDP/TCP/Tor/Bluetooth, IPv6 shim).

## Status

Prototype. End-to-end mesh routing has been verified on real hardware.

**Working**

- IPv6 mesh routing over an Android `VpnService` TUN (verified: laptop
  `ping6` and TCP to an Android handset's `fd00::/8` address via a relay)
- `.fips` name resolution for `bionic` `getaddrinfo` clients — e.g. Termux
  `ping6 zephyrus.fips`, `curl http://<npub>.fips:port/`, `dig @10.1.1.1`
- Application traffic from system apps that use the bionic resolver
- Start / stop VPN from the UI; persistent Nostr identity in `filesDir`

**Known limitations**

- **Browsers don't work yet.** Chromium-based browsers (Brave, Chrome,
  Vanadium) suppress AAAA queries on ULA-only networks, so they cannot
  resolve `.fips` names even with Secure DNS disabled. This is not a bug
  in this code — it's an upstream Chromium Happy Eyeballs heuristic.
  **Planned fix: NAT46** — synthesize IPv4 addresses from a `10.2.0.0/16`
  pool and rewrite packets both ways in the TUN adapter. Not yet
  implemented.
- Stop/start VPN cycle is flaky — sometimes requires an app restart.
- TCP over mesh is experimental; UDP is the primary tested transport.

## `.fips` name resolution

A first-class feature: peers registered in the node config (by `npub` and
optional `alias`) are resolvable as `.fips` hostnames from any app on the
device that uses the system resolver.

- `alias.fips` — e.g. `zephyrus.fips`, `fips-test-node.fips`
- `<npub>.fips` — the full bech32 public key as a hostname
- Only `AAAA` records are served; `.fips` is IPv6-only by design
- Non-`.fips` queries are **refused** (RCODE=REFUSED) at our resolver so
  the system falls through to the next DNS server (8.8.8.8) — we do not
  proxy the public internet
- Implemented in `fips-mobile/src/dns_intercept.rs`. DNS packets are
  pulled out of the TUN reader thread (UDP/53 to the virtual `10.1.1.1`
  address) rather than bound to a port — no privileged port binding
  needed.

## Prerequisites

The easiest path is Nix:

```sh
nix develop          # provides Rust, cargo-ndk, NDK 26.1, JDK 17, Gradle, just, adb
```

Manual install, if you prefer:

- Rust stable toolchain + `cargo-ndk`
- Android NDK 26.1
- JDK 17, Android SDK, Gradle
- `just`, `adb`

## Build & run

All commands are driven by `just`:

```sh
just build        # cross-compile fips-mobile, generate Kotlin bindings, assemble debug APK
just device       # build, install, launch on connected device
just test         # autotest: start node, wait for peers, dump state, exit
just debug        # build, install, launch, tail filtered logcat
just status       # show last debug dump from logcat
just install      # build + install, no launch
just clean        # wipe Gradle + Cargo + jniLibs + generated bindings
```

`just build` runs three steps in order:

1. `cargo ndk -t arm64-v8a build -p fips-mobile --release`
2. Run the local `uniffi-bindgen` against the compiled `.so` to generate
   Kotlin stubs into `android/app/src/main/java`
3. Copy `libfips_mobile.so` into `android/app/src/main/jniLibs/arm64-v8a`
4. `./gradlew assembleDebug`

## Architecture

Four layers:

1. **`fips/`** (git submodule) — core mesh protocol. Spanning tree
   coordination, Noise-encrypted sessions, multi-transport (UDP / TCP /
   Tor / Bluetooth), IPv6 shim over the mesh address space.
2. **`fips-mobile/`** — Rust crate that exposes `FipsMobileNode` via
   UniFFI. Owns a tokio runtime, spawns the node's `run_rx_loop`, manages
   TUN reader/writer threads, and runs the `.fips` DNS interceptor.
3. **`uniffi-bindgen/`** — local fork of `uniffi-bindgen` that generates
   Kotlin bindings from the compiled `.so`.
4. **`android/`** — Kotlin / Jetpack Compose app.
   `FipsTunService` manages the `VpnService` lifecycle;
   `FipsViewModel` polls node state over the UniFFI control channel;
   `StatusScreen` is the dashboard (peers, transports, own npub + IPv6,
   start/stop).

### Lifecycle notes (non-obvious)

- The TUN file descriptor is established by `FipsTunService` **before**
  the Rust node starts — the node does not create the VPN interface.
  Ownership of the fd transfers into Rust via UniFFI and is closed on
  `stop_tun()`.
- The app is self-excluded from its own VPN via
  `addDisallowedApplication(packageName)` so the node's transport
  sockets use the real network. This removes the need to `protect()`
  individual sockets.
- DNS interception happens inside the TUN reader thread rather than on
  a listening socket — Android won't let an unprivileged app bind
  port 53.
- Identity is a Nostr keypair (nsec) persisted to `filesDir`. The
  device's FIPS IPv6 address is derived deterministically from the
  public key, so it's stable across restarts.

## Layout

```
android/            Kotlin / Compose app
fips/               core protocol (git submodule)
fips-mobile/        Rust UniFFI wrapper (TUN + DNS)
uniffi-bindgen/     Kotlin codegen fork
justfile            build / run / test recipes
flake.nix           Nix dev shell
Cargo.toml          workspace root
```

## Further reading

- Core protocol: `fips/README.md`
- Design documents: `fips/docs/design/` (transport layer, mesh operation,
  spanning tree, wire formats, IPv6 adapter, DNS, session layer,
  configuration)

## Authorship & AI disclosure

Most of the code in this repo — the `fips-mobile/` Rust wrapper, the
`android/` Kotlin / Compose app, and the build glue in `justfile` — was
generated by **Claude Opus 4.6** (Anthropic) under human direction, with
**minimal human code review**. The core `fips/` submodule is upstream
work and is **not** AI-generated.

Treat this project as an **experimental prototype**: it builds and runs,
end-to-end mesh routing over TUN has been verified on real hardware, but
the code has not had line-by-line human audit. If you depend on it, read
it first. Bugs, odd patterns, and over-engineering are to be expected.
PRs and issue reports welcome.
