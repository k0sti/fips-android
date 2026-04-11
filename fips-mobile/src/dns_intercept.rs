//! DNS intercept module — packet parsing and response building.
//!
//! Android's VPN sends DNS queries to 10.1.1.1:53 via TUN, but apps cannot
//! bind port 53 (EACCES).  Instead we intercept raw IPv4+UDP packets in the
//! TUN fd reader thread, extract the DNS payload, delegate to
//! `fips::upper::dns::handle_dns_packet()`, and wrap the DNS response back
//! into an IPv4+UDP packet that is written to the TUN fd.

use fips::upper::hosts::HostMap;

/// Target DNS server address embedded in the VPN configuration.
const DNS_DST_IP: [u8; 4] = [10, 1, 1, 1];

/// DNS port.
const DNS_PORT: u16 = 53;

/// Check if a TUN packet is an IPv4 UDP packet destined for 10.1.1.1:53.
///
/// Returns `Some(ihl)` where `ihl` is the IPv4 header length in bytes
/// (IHL field * 4), or `None` if the packet does not match.
pub fn is_dns_query(packet: &[u8]) -> Option<usize> {
    // Minimum: 20 bytes IPv4 header + 8 bytes UDP header + at least 12 bytes DNS header
    if packet.len() < 40 {
        return None;
    }

    // Version must be 4
    let version = packet[0] >> 4;
    if version != 4 {
        return None;
    }

    // IHL (Internet Header Length) in 32-bit words; minimum 5 (20 bytes)
    let ihl_words = (packet[0] & 0x0F) as usize;
    if ihl_words < 5 {
        return None;
    }
    let ihl = ihl_words * 4;

    // Need at least ihl + 8 (UDP header) bytes
    if packet.len() < ihl + 8 {
        return None;
    }

    // Protocol must be 17 (UDP)
    if packet[9] != 17 {
        return None;
    }

    // Destination IP must be 10.1.1.1
    if packet[16..20] != DNS_DST_IP {
        return None;
    }

    // Destination port (in UDP header, offset ihl+2..ihl+4) must be 53
    let dst_port = u16::from_be_bytes([packet[ihl + 2], packet[ihl + 3]]);
    if dst_port != DNS_PORT {
        return None;
    }

    Some(ihl)
}

/// Handle a DNS query embedded in an IPv4+UDP TUN packet.
///
/// Extracts the DNS payload, delegates to `fips::upper::dns::handle_dns_packet`,
/// and wraps the DNS response in a new IPv4+UDP packet with swapped addresses.
pub fn handle_dns_query(packet: &[u8], hosts: &HostMap) -> Option<Vec<u8>> {
    let ihl = is_dns_query(packet)?;

    // DNS payload starts after IPv4 header + 8 bytes UDP header
    let dns_offset = ihl + 8;
    if packet.len() <= dns_offset {
        return None;
    }
    let dns_payload = &packet[dns_offset..];

    let (dns_response, _identity) =
        fips::upper::dns::handle_dns_packet(dns_payload, 300, hosts)?;

    Some(build_ipv4_udp_response(packet, ihl, &dns_response))
}

/// Build an IPv4+UDP response packet from the original query and a DNS payload.
///
/// - IPv4 header: version/IHL copied, total length updated, TTL=64, protocol=17,
///   flags=0x4000 (DF), src/dst IPs swapped, checksum recomputed.
/// - UDP header: ports swapped, length=8+dns_payload.len(), checksum=0 (optional
///   for IPv4).
/// - DNS payload appended verbatim.
fn build_ipv4_udp_response(query: &[u8], ihl: usize, dns_payload: &[u8]) -> Vec<u8> {
    let udp_len = 8 + dns_payload.len();
    let total_len = ihl + udp_len;

    let mut pkt = vec![0u8; total_len];

    // --- IPv4 header ---
    // Byte 0: version + IHL (copied from query)
    pkt[0] = query[0];
    // Byte 1: DSCP/ECN — zero
    pkt[1] = 0;
    // Bytes 2-3: total length
    let total_len_u16 = total_len as u16;
    pkt[2..4].copy_from_slice(&total_len_u16.to_be_bytes());
    // Bytes 4-5: identification — zero
    pkt[4] = 0;
    pkt[5] = 0;
    // Bytes 6-7: flags + fragment offset — DF flag (0x4000)
    pkt[6..8].copy_from_slice(&0x4000u16.to_be_bytes());
    // Byte 8: TTL
    pkt[8] = 64;
    // Byte 9: protocol (UDP = 17)
    pkt[9] = 17;
    // Bytes 10-11: header checksum — fill after everything else
    pkt[10] = 0;
    pkt[11] = 0;
    // Bytes 12-15: source IP = query's dst IP
    pkt[12..16].copy_from_slice(&query[16..20]);
    // Bytes 16-19: destination IP = query's src IP
    pkt[16..20].copy_from_slice(&query[12..16]);
    // Copy any IPv4 options from the query header (bytes 20..ihl)
    if ihl > 20 {
        pkt[20..ihl].copy_from_slice(&query[20..ihl]);
    }

    // Compute IPv4 header checksum
    let cksum = ipv4_checksum(&pkt[..ihl]);
    pkt[10..12].copy_from_slice(&cksum.to_be_bytes());

    // --- UDP header ---
    let udp_start = ihl;
    // Source port = query's dst port
    pkt[udp_start..udp_start + 2].copy_from_slice(&query[ihl + 2..ihl + 4]);
    // Destination port = query's src port
    pkt[udp_start + 2..udp_start + 4].copy_from_slice(&query[ihl..ihl + 2]);
    // UDP length
    let udp_len_u16 = udp_len as u16;
    pkt[udp_start + 4..udp_start + 6].copy_from_slice(&udp_len_u16.to_be_bytes());
    // UDP checksum = 0 (optional for IPv4)
    pkt[udp_start + 6] = 0;
    pkt[udp_start + 7] = 0;

    // --- DNS payload ---
    pkt[udp_start + 8..].copy_from_slice(dns_payload);

    pkt
}

/// Compute the IPv4 header checksum per RFC 1071.
///
/// Sum all 16-bit words in the header, fold any carry bits, and return the
/// one's complement.  The checksum field in the header must be zeroed before
/// calling.
fn ipv4_checksum(header: &[u8]) -> u16 {
    let mut sum: u32 = 0;

    // Sum 16-bit words
    let mut i = 0;
    while i + 1 < header.len() {
        sum += u16::from_be_bytes([header[i], header[i + 1]]) as u32;
        i += 2;
    }

    // If odd number of bytes, pad with zero
    if i < header.len() {
        sum += (header[i] as u32) << 8;
    }

    // Fold 32-bit sum to 16 bits
    while sum >> 16 != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }

    !(sum as u16)
}

#[cfg(test)]
mod tests {
    use super::*;
    use fips::Identity;

    /// Build a minimal valid IPv4+UDP packet destined for 10.1.1.1:53.
    fn make_dns_query_packet(dns_payload: &[u8]) -> Vec<u8> {
        let ihl: usize = 20;
        let udp_len = 8 + dns_payload.len();
        let total_len = ihl + udp_len;
        let mut pkt = vec![0u8; total_len];

        // IPv4 header
        pkt[0] = 0x45; // version=4, IHL=5
        let total_len_u16 = total_len as u16;
        pkt[2..4].copy_from_slice(&total_len_u16.to_be_bytes());
        pkt[8] = 64; // TTL
        pkt[9] = 17; // UDP
        // Source IP: 10.0.0.2
        pkt[12..16].copy_from_slice(&[10, 0, 0, 2]);
        // Destination IP: 10.1.1.1
        pkt[16..20].copy_from_slice(&DNS_DST_IP);
        // IPv4 checksum
        let cksum = ipv4_checksum(&pkt[..ihl]);
        pkt[10..12].copy_from_slice(&cksum.to_be_bytes());

        // UDP header
        // Source port: 12345
        pkt[20..22].copy_from_slice(&12345u16.to_be_bytes());
        // Destination port: 53
        pkt[22..24].copy_from_slice(&DNS_PORT.to_be_bytes());
        // UDP length
        let udp_len_u16 = udp_len as u16;
        pkt[24..26].copy_from_slice(&udp_len_u16.to_be_bytes());
        // UDP checksum: 0
        pkt[26] = 0;
        pkt[27] = 0;

        // DNS payload
        pkt[28..].copy_from_slice(dns_payload);

        pkt
    }

    /// Build a DNS query using simple_dns crate.
    fn build_dns_query(name: &str) -> Vec<u8> {
        use simple_dns::{Packet, Question, Name, QTYPE, QCLASS, CLASS, TYPE};

        let mut packet = Packet::new_query(0xABCD);
        let question = Question::new(
            Name::new_unchecked(name).into_owned(),
            QTYPE::TYPE(TYPE::AAAA),
            QCLASS::CLASS(CLASS::IN),
            false,
        );
        packet.questions.push(question);
        packet.build_bytes_vec().unwrap()
    }

    #[test]
    fn test_is_dns_query_valid() {
        let dns = build_dns_query("test.fips");
        let pkt = make_dns_query_packet(&dns);
        let result = is_dns_query(&pkt);
        assert_eq!(result, Some(20), "should detect valid DNS query with IHL=20");
    }

    #[test]
    fn test_is_dns_query_wrong_dst_ip() {
        let dns = build_dns_query("test.fips");
        let mut pkt = make_dns_query_packet(&dns);
        // Change dst IP to 10.1.1.2
        pkt[19] = 2;
        assert!(is_dns_query(&pkt).is_none(), "wrong dst IP should be rejected");
    }

    #[test]
    fn test_is_dns_query_wrong_port() {
        let dns = build_dns_query("test.fips");
        let mut pkt = make_dns_query_packet(&dns);
        // Change dst port to 5353
        pkt[22..24].copy_from_slice(&5353u16.to_be_bytes());
        assert!(is_dns_query(&pkt).is_none(), "wrong port should be rejected");
    }

    #[test]
    fn test_is_dns_query_ipv6_rejected() {
        let dns = build_dns_query("test.fips");
        let mut pkt = make_dns_query_packet(&dns);
        // Set version to 6
        pkt[0] = (6 << 4) | (pkt[0] & 0x0F);
        assert!(is_dns_query(&pkt).is_none(), "IPv6 packets should be rejected");
    }

    #[test]
    fn test_is_dns_query_too_short() {
        let pkt = vec![0u8; 39]; // Less than minimum 40
        assert!(is_dns_query(&pkt).is_none(), "too-short packet should be rejected");
    }

    #[test]
    fn test_handle_dns_query_resolves_npub() {
        let identity = Identity::generate();
        let npub = identity.npub();
        let expected_ipv6 = identity.address().to_ipv6();
        let hosts = HostMap::new();

        let dns_query = build_dns_query(&format!("{}.fips", npub));
        let pkt = make_dns_query_packet(&dns_query);

        let response = handle_dns_query(&pkt, &hosts);
        assert!(response.is_some(), "should resolve npub.fips");

        let resp_pkt = response.unwrap();
        // Extract DNS payload from response
        let resp_ihl = ((resp_pkt[0] & 0x0F) as usize) * 4;
        let dns_response = &resp_pkt[resp_ihl + 8..];

        let parsed = simple_dns::Packet::parse(dns_response).unwrap();
        assert_eq!(parsed.answers.len(), 1);

        if let simple_dns::rdata::RData::AAAA(aaaa) = &parsed.answers[0].rdata {
            let addr = std::net::Ipv6Addr::from(aaaa.address);
            assert_eq!(addr, expected_ipv6);
        } else {
            panic!("expected AAAA record in response");
        }
    }

    #[test]
    fn test_handle_dns_query_hostname_via_hosts() {
        let identity = Identity::generate();
        let expected_ipv6 = identity.address().to_ipv6();

        let mut hosts = HostMap::new();
        hosts.insert("mynode", &identity.npub()).unwrap();

        let dns_query = build_dns_query("mynode.fips");
        let pkt = make_dns_query_packet(&dns_query);

        let response = handle_dns_query(&pkt, &hosts);
        assert!(response.is_some(), "should resolve hostname via HostMap");

        let resp_pkt = response.unwrap();
        let resp_ihl = ((resp_pkt[0] & 0x0F) as usize) * 4;
        let dns_response = &resp_pkt[resp_ihl + 8..];

        let parsed = simple_dns::Packet::parse(dns_response).unwrap();
        assert_eq!(parsed.answers.len(), 1);

        if let simple_dns::rdata::RData::AAAA(aaaa) = &parsed.answers[0].rdata {
            let addr = std::net::Ipv6Addr::from(aaaa.address);
            assert_eq!(addr, expected_ipv6);
        } else {
            panic!("expected AAAA record in response");
        }
    }

    #[test]
    fn test_handle_dns_query_unknown_returns_nxdomain() {
        let hosts = HostMap::new();

        let dns_query = build_dns_query("unknown.fips");
        let pkt = make_dns_query_packet(&dns_query);

        let response = handle_dns_query(&pkt, &hosts);
        assert!(response.is_some(), "should return NXDOMAIN response, not None");

        let resp_pkt = response.unwrap();
        let resp_ihl = ((resp_pkt[0] & 0x0F) as usize) * 4;
        let dns_response = &resp_pkt[resp_ihl + 8..];

        let parsed = simple_dns::Packet::parse(dns_response).unwrap();
        assert_eq!(parsed.rcode(), simple_dns::RCODE::NameError);
        assert!(parsed.answers.is_empty());
    }

    #[test]
    fn test_ipv4_checksum() {
        // Example from RFC 1071 — use a known IPv4 header
        let mut header = [0u8; 20];
        header[0] = 0x45; // version=4, IHL=5
        header[2..4].copy_from_slice(&40u16.to_be_bytes()); // total length
        header[8] = 64; // TTL
        header[9] = 17; // UDP
        header[12..16].copy_from_slice(&[10, 0, 0, 1]); // src IP
        header[16..20].copy_from_slice(&[10, 1, 1, 1]); // dst IP

        let cksum = ipv4_checksum(&header);
        // Set the checksum and verify it sums to zero
        header[10..12].copy_from_slice(&cksum.to_be_bytes());
        let verify = ipv4_checksum(&header);
        assert_eq!(verify, 0, "checksum should verify to 0");
    }

    #[test]
    fn test_response_checksum_valid() {
        let identity = Identity::generate();
        let npub = identity.npub();
        let hosts = HostMap::new();

        let dns_query = build_dns_query(&format!("{}.fips", npub));
        let pkt = make_dns_query_packet(&dns_query);

        let response = handle_dns_query(&pkt, &hosts).expect("should produce response");

        // Verify IPv4 header checksum of the response
        let resp_ihl = ((response[0] & 0x0F) as usize) * 4;
        let verify = ipv4_checksum(&response[..resp_ihl]);
        assert_eq!(verify, 0, "response IPv4 checksum should verify to 0");

        // Verify IPs are swapped
        assert_eq!(&response[12..16], &DNS_DST_IP, "response src should be 10.1.1.1");
        assert_eq!(&response[16..20], &[10, 0, 0, 2], "response dst should be original src");

        // Verify ports are swapped
        let src_port = u16::from_be_bytes([response[resp_ihl], response[resp_ihl + 1]]);
        let dst_port = u16::from_be_bytes([response[resp_ihl + 2], response[resp_ihl + 3]]);
        assert_eq!(src_port, DNS_PORT, "response src port should be 53");
        assert_eq!(dst_port, 12345, "response dst port should be original src port");
    }
}
