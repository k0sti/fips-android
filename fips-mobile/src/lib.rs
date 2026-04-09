//! FIPS Mobile Node — UniFFI wrapper for embedding fips::Node.
//!
//! Manages a tokio runtime, spawns the node's RX loop, and provides
//! query access via an in-process control channel.

use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

uniffi::setup_scaffolding!();

/// Opaque handle to a running FIPS node.
#[derive(uniffi::Object)]
pub struct FipsMobileNode {
    control_tx: mpsc::Sender<fips::control::ControlMessage>,
    runtime: Mutex<Option<tokio::runtime::Runtime>>,
    rx_loop_handle: Mutex<Option<JoinHandle<Result<(), fips::NodeError>>>>,
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
}

#[uniffi::export]
impl FipsMobileNode {
    #[uniffi::constructor]
    pub fn new(config_yaml: String) -> Result<Arc<Self>, MobileNodeError> {
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

        let rx_loop_handle = runtime.spawn(async move {
            node.run_rx_loop().await
        });

        Ok(Arc::new(Self {
            control_tx,
            runtime: Mutex::new(Some(runtime)),
            rx_loop_handle: Mutex::new(Some(rx_loop_handle)),
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

    pub fn is_running(&self) -> bool {
        let guard = self.rx_loop_handle.lock().unwrap();
        guard
            .as_ref()
            .map(|h| !h.is_finished())
            .unwrap_or(false)
    }

    pub fn stop(&self) -> Result<(), MobileNodeError> {
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
}
