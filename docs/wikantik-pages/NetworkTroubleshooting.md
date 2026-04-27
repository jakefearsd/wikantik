---
canonical_id: 01KQ0P44T0JVWEDFSZA26A0248
title: Network Troubleshooting
type: article
cluster: networking
status: active
date: '2026-04-26'
summary: The diagnostic toolkit for network issues — ping, traceroute, dig, tcpdump,
  ss, curl — and the systematic approach to narrowing down where in the network a
  problem actually is.
tags:
- network-troubleshooting
- diagnostics
- networking
- tcpdump
- ping
related:
- TcpIpFundamentals
- DnsDeepDive
- LoadBalancingStrategies
- DebuggingStrategies
hubs:
- Networking Hub
---
# Network Troubleshooting

Network issues are common; the symptoms vary; the actual cause is often somewhere unexpected. The systematic approach: narrow down where in the network the problem is, before guessing at fixes.

This page covers the diagnostic toolkit and the workflow.

## The systematic approach

When a network problem appears:

1. **Reproduce**: confirm the issue. Does it happen reliably or intermittently?
2. **Narrow scope**: client problem? Network path? DNS? Server?
3. **Tools per layer**: each layer has tools to test it
4. **Fix at the right layer**: don't fix server when DNS is the problem

## Layer-by-layer tools

### DNS

```
dig example.com
nslookup example.com
host example.com
```

`dig` is the most flexible:

```
dig example.com           # A record
dig example.com AAAA      # IPv6
dig example.com MX        # mail
dig +trace example.com    # show full resolution path
dig @8.8.8.8 example.com  # specific resolver
```

If DNS doesn't resolve, that's the problem. If it resolves but to wrong IP, propagation or config issue.

### Reachability

```
ping <host>
ping6 <host>  # IPv6
```

Basic round-trip test. If ping fails, host is unreachable or filtering ICMP. Many cloud networks block ICMP.

`ping` doesn't test application — just basic IP connectivity.

### Path

```
traceroute <host>
mtr <host>     # combines ping + traceroute, continuous
```

Shows each hop. Useful for identifying where packets are lost or delayed.

`mtr` is more useful than `traceroute` for ongoing debugging — refreshes continuously.

### Port reachability

```
nc -zv <host> <port>     # check if port is open
telnet <host> <port>     # interactive
nmap -p <port> <host>    # more thorough
```

If port unreachable: firewall, security group, or service not listening.

### TCP details

```
ss -tnp                  # active connections
ss -tlnp                 # listening sockets
ss -s                    # summary
netstat -an | grep ESTAB # legacy version
```

Shows what's connected to what. Important for diagnosing connection-pool issues, port exhaustion, etc.

### HTTP

```
curl -v https://example.com
curl -I https://example.com    # headers only
curl --resolve host:80:1.2.3.4 ...   # override DNS
```

Verbose curl shows the full TLS handshake, headers, response. For HTTP-level issues, this is the workhorse.

```
curl --trace-ascii out.txt ...  # full trace
```

For deep debugging.

### Packet capture

```
tcpdump -i any -nn host <host>
tcpdump -i any -nn -w capture.pcap host <host>
```

Capture and analyze. The .pcap file opens in Wireshark for visualization.

For debugging issues that need to see actual packets — TCP retransmits, missing handshakes, malformed headers.

### TLS

```
openssl s_client -connect host:443
openssl s_client -connect host:443 -servername sni.example.com
```

Tests TLS handshake. Useful for cert issues, SNI problems, protocol mismatches.

## Common diagnostic flows

### "Site is slow"

1. `time curl -o /dev/null https://example.com` — total time
2. `curl -w '@curl-format.txt' ...` — break down by phase
   - `time_namelookup` (DNS)
   - `time_connect` (TCP)
   - `time_appconnect` (TLS)
   - `time_starttransfer` (TTFB)
3. Identify which phase is slow; investigate that

### "Can't connect"

1. `dig <host>` — DNS works?
2. `ping <host>` — IP reachable? (may be blocked)
3. `nc -zv <host> <port>` — port open?
4. `curl -v https://<host>` — application responds?

If DNS fails, fix DNS. If port closed, fix firewall/security group. If application fails, server-side issue.

### "Intermittent failures"

`mtr` for several minutes. Look for:
- Packet loss at specific hop
- Latency spikes
- Routing changes

Often a network mid-path issue that's not your immediate infrastructure.

### "TLS error"

```
openssl s_client -connect host:443 -showcerts
```

Examine the cert chain. Common issues:
- Expired cert
- Wrong CN/SAN
- Missing intermediate certs
- Untrusted CA

## Cloud-specific tools

### AWS

- VPC Flow Logs
- VPC Reachability Analyzer
- Route 53 Resolver query logging

### Kubernetes

- `kubectl exec` into pod, run standard tools
- `kubectl logs`
- Service mesh tooling (Istio dashboards, etc.)

### Containers

- Network namespaces; `ip netns`
- Inside-container tools may be limited; install on need

## Common pitfalls

### Different DNS in different places

Local DNS resolver, application DNS cache, VPC resolver — may give different answers.

### Caching obscuring problems

Browser cache, CDN cache, DNS cache. When debugging, work to bypass caches.

### Logging at the wrong layer

Web server logs don't show network errors. Application logs may not show TLS issues. Look at the right place for the right symptom.

### Time differences

Client clock vs. server clock matters for TLS (cert validity windows, JWT expiration).

### MTU issues

Fragmented packets through tunnels. Ping with `-M do -s 1472` to test path MTU.

## Common failure patterns

- **Guessing instead of measuring.** Assume DNS works without checking.
- **Fixing symptoms, not causes.** Restart the app when the network is the problem.
- **No baseline.** Don't know what "normal" looks like.
- **Logs not preserved.** Issue resolves; logs gone; can't analyze later.
- **Trusting the network is healthy.** It isn't, sometimes.

## Further Reading

- [TcpIpFundamentals](TcpIpFundamentals) — What you're diagnosing
- [DnsDeepDive](DnsDeepDive) — DNS-specific
- [LoadBalancingStrategies](LoadBalancingStrategies) — LB-related issues
- [DebuggingStrategies](DebuggingStrategies) — Broader debugging discipline
- [Networking Hub](Networking+Hub) — Cluster index
