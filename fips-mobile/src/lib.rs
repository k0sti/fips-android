//! FIPS Mobile Node — UniFFI wrapper for embedding fips::Node.
//!
//! Manages a tokio runtime, spawns the node's RX loop, and provides
//! query access via an in-process control channel.

mod tun_adapter;

use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

uniffi::setup_scaffolding!();

/// A FIPS identity keypair (nsec + npub as bech32 strings).
#[derive(uniffi::Record)]
pub struct FipsIdentity {
    pub nsec: String,
    pub npub: String,
}

/// Generate a new FIPS identity keypair.
#[uniffi::export]
pub fn generate_identity() -> FipsIdentity {
    let identity = fips::Identity::generate();
    let nsec = fips::encode_nsec(&identity.keypair().secret_key());
    let npub = identity.npub();
    FipsIdentity { nsec, npub }
}

/// Derive identity info from an nsec (no running node needed).
///
/// Returns (npub, ipv6) — used to show identity in UI and configure the
/// VPN interface address before starting the node.
#[uniffi::export]
pub fn identity_from_nsec(nsec: String) -> Result<FipsIdentity, MobileNodeError> {
    let secret_key = fips::decode_nsec(&nsec).map_err(|e| MobileNodeError::CreateFailed {
        reason: format!("invalid nsec: {e}"),
    })?;
    let identity = fips::Identity::from_secret_key(secret_key);
    Ok(FipsIdentity {
        nsec,
        npub: identity.npub(),
    })
}

/// Compute the FIPS IPv6 address from an nsec (no running node needed).
#[uniffi::export]
pub fn ipv6_from_nsec(nsec: String) -> Result<String, MobileNodeError> {
    let secret_key = fips::decode_nsec(&nsec).map_err(|e| MobileNodeError::CreateFailed {
        reason: format!("invalid nsec: {e}"),
    })?;
    let identity = fips::Identity::from_secret_key(secret_key);
    Ok(identity.address().to_ipv6().to_string())
}

/// Opaque handle to a running FIPS node.
#[derive(uniffi::Object)]
pub struct FipsMobileNode {
    control_tx: mpsc::Sender<fips::control::ControlMessage>,
    runtime: Mutex<Option<tokio::runtime::Runtime>>,
    rx_loop_handle: Mutex<Option<JoinHandle<Result<(), fips::NodeError>>>>,
    /// TUN channel handles (set during construction, consumed by start_tun).
    tun_outbound_tx: Mutex<Option<tokio::sync::mpsc::Sender<Vec<u8>>>>,
    tun_inbound_rx: Mutex<Option<std::sync::mpsc::Receiver<Vec<u8>>>>,
    tun_adapter: Mutex<Option<tun_adapter::TunAdapter>>,
    transport_mtu: u16,
    /// Raw fds of transport sockets (for Android VPN `protect()` calls).
    transport_fds: Vec<i32>,
}

/// Errors from the mobile node.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MobileNodeError {
    #[error("Node creation failed: {reason}")]
    CreateFailed { reason: String },
    #[error("Query failed: {reason}")]
    QueryFailed { reason: String },
    #[error("Node not running")]
    NotRunning,
    #[error("TUN error: {reason}")]
    TunError { reason: String },
}

#[uniffi::export]
impl FipsMobileNode {
    #[uniffi::constructor]
    pub fn new(config_yaml: String) -> Result<Arc<Self>, MobileNodeError> {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("fips"),
        );

        #[cfg(not(target_os = "android"))]
        let _ = tracing_subscriber::fmt()
            .with_env_filter("fips=debug,fips_mobile=debug")
            .try_init();

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|e| MobileNodeError::CreateFailed {
                reason: format!("tokio runtime: {e}"),
            })?;

        let config: fips::Config = serde_yaml::from_str(&config_yaml)
            .map_err(|e| MobileNodeError::CreateFailed {
                reason: format!("config parse: {e}"),
            })?;

        let mut node = runtime.block_on(async {
            let mut node = fips::Node::new(config).map_err(|e| {
                MobileNodeError::CreateFailed {
                    reason: format!("node create: {e}"),
                }
            })?;
            node.start().await.map_err(|e| {
                MobileNodeError::CreateFailed {
                    reason: format!("node start: {e}"),
                }
            })?;
            Ok::<_, MobileNodeError>(node)
        })?;

        let control_tx = node.set_control_channel();
        let transport_mtu = node.transport_mtu();
        let transport_fds = node.transport_socket_fds();
        let (tun_outbound_tx, tun_inbound_rx) = node.set_tun_channels();

        let rx_loop_handle = runtime.spawn(async move {
            node.run_rx_loop().await
        });

        Ok(Arc::new(Self {
            control_tx,
            runtime: Mutex::new(Some(runtime)),
            rx_loop_handle: Mutex::new(Some(rx_loop_handle)),
            tun_outbound_tx: Mutex::new(Some(tun_outbound_tx)),
            tun_inbound_rx: Mutex::new(Some(tun_inbound_rx)),
            tun_adapter: Mutex::new(None),
            transport_mtu,
            transport_fds,
        }))
    }

    pub fn query(&self, command: String) -> Result<String, MobileNodeError> {
        let rt_guard = self.runtime.lock().unwrap();
        let runtime = rt_guard.as_ref().ok_or(MobileNodeError::NotRunning)?;
        let tx = self.control_tx.clone();
        runtime.block_on(async {
            let request = fips::control::protocol::Request {
                command,
                params: None,
            };
            let (resp_tx, resp_rx) = oneshot::channel();
            tx.send((request, resp_tx))
                .await
                .map_err(|_| MobileNodeError::NotRunning)?;
            let response = resp_rx
                .await
                .map_err(|_| MobileNodeError::QueryFailed {
                    reason: "response channel closed".into(),
                })?;
            match response.data {
                Some(data) => Ok(data.to_string()),
                None => Ok(serde_json::to_string(&response)
                    .unwrap_or_else(|_| "{}".into())),
            }
        })
    }

    /// Raw fds of transport sockets. Android must call `VpnService.protect(fd)`
    /// on each before establishing the TUN, to prevent routing loops.
    pub fn transport_socket_fds(&self) -> Vec<i32> {
        self.transport_fds.clone()
    }

    pub fn is_running(&self) -> bool {
        let guard = self.rx_loop_handle.lock().unwrap();
        guard
            .as_ref()
            .map(|h| !h.is_finished())
            .unwrap_or(false)
    }

    pub fn stop(&self) -> Result<(), MobileNodeError> {
        // Stop TUN adapter first (if running) — no need to restore channels on full stop
        if let Some(mut adapter) = self.tun_adapter.lock().unwrap().take() {
            let _ = adapter.stop();
        }

        let runtime = self.runtime.lock().unwrap().take();
        let Some(runtime) = runtime else {
            return Ok(());
        };

        // Send shutdown command
        let _ = runtime.block_on(async {
            let request = fips::control::protocol::Request {
                command: "shutdown".into(),
                params: None,
            };
            let (resp_tx, _) = oneshot::channel();
            let _ = self.control_tx.send((request, resp_tx)).await;
        });

        // Wait for rx_loop to finish so sockets are released
        let handle = self.rx_loop_handle.lock().unwrap().take();
        if let Some(h) = handle {
            let _ = runtime.block_on(async {
                tokio::time::timeout(Duration::from_secs(5), h).await
            });
        }

        // Force shutdown remaining tasks — releases all sockets
        runtime.shutdown_timeout(Duration::from_secs(2));
        Ok(())
    }

    /// Start the TUN adapter on an Android VpnService file descriptor.
    ///
    /// The fd must come from `ParcelFileDescriptor.detachFd()` — ownership
    /// transfers to Rust. Call `stop_tun()` to shut down.
    pub fn start_tun(&self, fd: i32) -> Result<(), MobileNodeError> {
        let outbound_tx = self
            .tun_outbound_tx
            .lock()
            .unwrap()
            .take()
            .ok_or(MobileNodeError::TunError {
                reason: "TUN channels already consumed (start_tun called twice?)".into(),
            })?;
        let inbound_rx = self
            .tun_inbound_rx
            .lock()
            .unwrap()
            .take()
            .ok_or(MobileNodeError::TunError {
                reason: "TUN inbound channel missing".into(),
            })?;

        let adapter = tun_adapter::TunAdapter::start(
            fd,
            outbound_tx,
            inbound_rx,
            self.transport_mtu,
        );

        *self.tun_adapter.lock().unwrap() = Some(adapter);
        Ok(())
    }

    /// Stop the TUN adapter. The fd is closed. Channels are restored for reuse.
    pub fn stop_tun(&self) -> Result<(), MobileNodeError> {
        if let Some(mut adapter) = self.tun_adapter.lock().unwrap().take() {
            if let Some(channels) = adapter.stop() {
                *self.tun_outbound_tx.lock().unwrap() = Some(channels.outbound_tx);
                *self.tun_inbound_rx.lock().unwrap() = Some(channels.inbound_rx);
            }
        }
        Ok(())
    }
}
