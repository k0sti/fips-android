//! TUN adapter for Android VpnService.
//!
//! Bridges an Android TUN file descriptor to the FIPS node's packet
//! processing channels (`set_tun_channels`).  Two dedicated OS threads
//! handle blocking I/O on the fd:
//!
//! - **Reader**: fd → validate IPv6 → TCP MSS clamp → outbound_tx → Node
//! - **Writer**: Node → inbound_rx → TCP MSS clamp → fd

use std::net::Ipv6Addr;
use std::os::unix::io::{FromRawFd, RawFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::{fs::File, io::Read, io::Write};
use tracing::{debug, error};

/// Default TUN MTU — matches Android VpnService builder config.
const TUN_MTU: u16 = 1280;

/// Channels returned when TUN adapter stops, so they can be reused.
pub struct TunChannels {
    pub outbound_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    pub inbound_rx: std::sync::mpsc::Receiver<Vec<u8>>,
}

/// Running TUN adapter with reader/writer threads.
pub struct TunAdapter {
    reader_handle: Option<JoinHandle<tokio::sync::mpsc::Sender<Vec<u8>>>>,
    writer_handle: Option<JoinHandle<std::sync::mpsc::Receiver<Vec<u8>>>>,
    stop: Arc<AtomicBool>,
    fd: RawFd,
}

impl TunAdapter {
    /// Start the TUN adapter on the given fd.
    ///
    /// `outbound_tx`: sends IPv6 packets read from the fd into the Node.
    /// `inbound_rx`: receives IPv6 packets from the Node to write to the fd.
    /// `transport_mtu`: the Node's transport MTU, used for TCP MSS clamping.
    pub fn start(
        fd: RawFd,
        outbound_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
        inbound_rx: std::sync::mpsc::Receiver<Vec<u8>>,
        transport_mtu: u16,
    ) -> Self {
        let stop = Arc::new(AtomicBool::new(false));

        let reader_handle = {
            let stop = stop.clone();
            // Safety: fd ownership transferred from Android via detachFd().
            // We dup() so reader and writer each own an independent fd.
            let read_fd = unsafe { libc::dup(fd) };
            let file = unsafe { File::from_raw_fd(read_fd) };
            std::thread::Builder::new()
                .name("tun-reader".into())
                .spawn(move || {
                    run_reader(&file, &outbound_tx, transport_mtu, &stop);
                    outbound_tx
                })
                .expect("spawn tun-reader thread")
        };

        let writer_handle = {
            let stop = stop.clone();
            let write_fd = unsafe { libc::dup(fd) };
            std::thread::Builder::new()
                .name("tun-writer".into())
                .spawn(move || {
                    let mut file = unsafe { File::from_raw_fd(write_fd) };
                    run_writer(&mut file, &inbound_rx, transport_mtu, &stop);
                    inbound_rx
                })
                .expect("spawn tun-writer thread")
        };

        Self {
            reader_handle: Some(reader_handle),
            writer_handle: Some(writer_handle),
            stop,
            fd,
        }
    }

    /// Stop the adapter and close the fd. Returns channels for reuse.
    pub fn stop(&mut self) -> Option<TunChannels> {
        self.stop.store(true, Ordering::Relaxed);

        // Close the original fd — causes blocking read()/write() to return EBADF,
        // breaking the reader/writer loops.
        unsafe { libc::close(self.fd) };

        let outbound_tx = self.reader_handle.take().and_then(|h| h.join().ok());
        let inbound_rx = self.writer_handle.take().and_then(|h| h.join().ok());

        debug!("TUN adapter stopped");

        match (outbound_tx, inbound_rx) {
            (Some(tx), Some(rx)) => Some(TunChannels {
                outbound_tx: tx,
                inbound_rx: rx,
            }),
            _ => None,
        }
    }
}

impl Drop for TunAdapter {
    fn drop(&mut self) {
        if self.reader_handle.is_some() || self.writer_handle.is_some() {
            let _ = self.stop();
        }
    }
}

/// Calculate max TCP MSS from the transport MTU.
fn max_tcp_mss(transport_mtu: u16) -> u16 {
    const IPV6_HEADER: u16 = 40;
    const TCP_HEADER: u16 = 20;
    let effective_mtu = fips::upper::icmp::effective_ipv6_mtu(transport_mtu);
    effective_mtu
        .saturating_sub(IPV6_HEADER)
        .saturating_sub(TCP_HEADER)
}

/// Reader loop: fd → validate → MSS clamp → outbound_tx.
fn run_reader(
    file: &File,
    outbound_tx: &tokio::sync::mpsc::Sender<Vec<u8>>,
    transport_mtu: u16,
    stop: &AtomicBool,
) {
    let max_mss = max_tcp_mss(transport_mtu);
    let mut buf = vec![0u8; TUN_MTU as usize + 100];

    debug!(max_mss, "TUN reader starting");

    loop {
        if stop.load(Ordering::Relaxed) {
            break;
        }

        let n = match (&*file).read(&mut buf) {
            Ok(0) => continue,
            Ok(n) => n,
            Err(e) => {
                if !stop.load(Ordering::Relaxed) {
                    let msg = e.to_string();
                    // EBADF / "Bad file descriptor" is expected on shutdown
                    if !msg.contains("Bad file descriptor") {
                        error!(error = %e, "TUN read error");
                    }
                }
                break;
            }
        };

        let packet = &mut buf[..n];

        // Must be a valid IPv6 packet (min 40 bytes, version 6)
        if n < 40 || packet[0] >> 4 != 6 {
            continue;
        }

        // Only forward FIPS-addressed packets (fd::/8).
        // Android VPN routing should already filter, but double-check.
        if packet[24] != fips::identity::FIPS_ADDRESS_PREFIX {
            continue;
        }

        // TCP MSS clamp on outbound SYN
        fips::upper::tcp_mss::clamp_tcp_mss(packet, max_mss);

        debug!(
            len = n,
            src = %Ipv6Addr::from(<[u8; 16]>::try_from(&packet[8..24]).unwrap()),
            dst = %Ipv6Addr::from(<[u8; 16]>::try_from(&packet[24..40]).unwrap()),
            proto = proto_name(packet[6]),
            "TUN → Node"
        );

        // blocking_send: blocks current thread if channel is full
        if outbound_tx.blocking_send(packet.to_vec()).is_err() {
            break; // channel closed → node shut down
        }
    }

    debug!("TUN reader stopped");
}

/// Writer loop: inbound_rx → MSS clamp → fd.
fn run_writer(
    file: &mut File,
    inbound_rx: &std::sync::mpsc::Receiver<Vec<u8>>,
    transport_mtu: u16,
    stop: &AtomicBool,
) {
    use std::sync::mpsc::RecvTimeoutError;
    use std::time::Duration;

    let max_mss = max_tcp_mss(transport_mtu);

    debug!(max_mss, "TUN writer starting");

    loop {
        if stop.load(Ordering::Relaxed) {
            break;
        }

        let mut packet = match inbound_rx.recv_timeout(Duration::from_millis(200)) {
            Ok(p) => p,
            Err(RecvTimeoutError::Timeout) => continue,
            Err(RecvTimeoutError::Disconnected) => break,
        };

        // TCP MSS clamp on inbound SYN-ACK
        fips::upper::tcp_mss::clamp_tcp_mss(&mut packet, max_mss);

        if packet.len() >= 40 {
            debug!(
                len = packet.len(),
                src = %Ipv6Addr::from(<[u8; 16]>::try_from(&packet[8..24]).unwrap()),
                dst = %Ipv6Addr::from(<[u8; 16]>::try_from(&packet[24..40]).unwrap()),
                proto = proto_name(packet[6]),
                "Node → TUN"
            );
        }

        if let Err(e) = file.write_all(&packet) {
            if !stop.load(Ordering::Relaxed) {
                let msg = e.to_string();
                if !msg.contains("Bad file descriptor") {
                    error!(error = %e, "TUN write error");
                }
            }
            break;
        }
    }

    debug!("TUN writer stopped");
}

fn proto_name(next_header: u8) -> &'static str {
    match next_header {
        6 => "TCP",
        17 => "UDP",
        58 => "ICMPv6",
        _ => "other",
    }
}
