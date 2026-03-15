---
date: 2026-03-14T00:00:00Z
status: active
summary: Managing a Linux system — services, processes, logs, networking, SSH, and
  the operational knowledge that makes you self-sufficient
related:
- LinuxForWindowsUsers
- LinuxCommandLineEssentials
- LinuxFilesystemAndPermissions
- LinuxPackageManagement
- LinuxShellScriptingFundamentals
- WhyLearnLinuxDeeply
tags:
- linux
- system-administration
- networking
- ssh
- services
- operating-systems
type: article
cluster: linux-for-windows-users
---
# Linux System Administration

Once you can navigate the filesystem and run commands, the next skill level is **managing the system itself** — starting and stopping services, diagnosing problems, configuring networking, and connecting to remote machines. This is where Linux knowledge transforms from academic to practical.

If you have ever administered Windows servers, many concepts will be familiar. The tools are different, but the problems are the same: "why is this service not running?", "why is the disk full?", "why can't this machine reach that machine?"

## Services and systemd

Modern Linux distributions use **systemd** to manage services (daemons). It is the equivalent of Windows Services — it starts things at boot, restarts them if they crash, and lets you control them.

### Essential systemctl Commands

```bash
# Check status of a service
sudo systemctl status nginx

# Start / stop / restart a service
sudo systemctl start nginx
sudo systemctl stop nginx
sudo systemctl restart nginx

# Enable a service to start at boot
sudo systemctl enable nginx

# Disable a service from starting at boot
sudo systemctl disable nginx

# Reload configuration without restarting
sudo systemctl reload nginx

# List all active services
systemctl list-units --type=service --state=active

# List failed services
systemctl --failed
```

The `status` command is your diagnostic starting point. It shows whether the service is running, its PID, recent log output, and how long it has been up.

### Common Services You Will Encounter

| Service | Purpose | Windows Equivalent |
|---------|---------|-------------------|
| `sshd` | Remote access (SSH server) | Remote Desktop / OpenSSH Server |
| `nginx` / `apache2` | Web server | IIS |
| `postgresql` / `mysql` | Database server | SQL Server |
| `ufw` / `firewalld` | Firewall | Windows Firewall |
| `cron` | Scheduled tasks | Task Scheduler |
| `NetworkManager` | Network management | Network and Sharing Center |
| `systemd-resolved` | DNS resolution | DNS Client |
| `cups` | Printing | Print Spooler |

## Process Management

### Viewing Processes

```bash
# Interactive process viewer (install htop for a better experience)
htop

# Snapshot of all processes
ps aux

# Find a specific process
ps aux | grep nginx
pgrep -a nginx

# Process tree (shows parent-child relationships)
pstree
```

### Resource Monitoring

```bash
# CPU and memory at a glance
htop                    # Interactive (best for humans)
top                     # Built-in alternative

# Memory usage
free -h                 # Human-readable summary

# Disk usage
df -h                   # Filesystem-level usage
du -sh /var/log         # Size of a specific directory
du -sh /home/* | sort -rh  # Largest home directories

# Disk I/O
iotop                   # Which processes are reading/writing

# Network usage
nethogs                 # Bandwidth per process
ss -tlnp                # Listening ports and their processes
```

### Killing Processes

```bash
# Graceful termination (ask process to exit)
kill PID

# Force kill (process cannot ignore this)
kill -9 PID

# Kill by name
pkill nginx
killall nginx
```

## Logs

Linux logs are your primary diagnostic tool. They tell you what happened, when, and usually why.

### journalctl (systemd journal)

```bash
# View all logs
sudo journalctl

# Logs for a specific service
sudo journalctl -u nginx

# Follow logs in real time (like tail -f)
sudo journalctl -u nginx -f

# Logs from the last hour
sudo journalctl --since "1 hour ago"

# Logs from today only
sudo journalctl --since today

# Logs from the last boot
sudo journalctl -b

# Show only errors and above
sudo journalctl -p err
```

### Traditional Log Files

Some applications still write to files in `/var/log/`:

| Log File | Contents |
|----------|----------|
| `/var/log/syslog` (Ubuntu) or `/var/log/messages` (RHEL) | General system messages |
| `/var/log/auth.log` | Authentication events (logins, sudo, SSH) |
| `/var/log/kern.log` | Kernel messages |
| `/var/log/nginx/access.log` | Web server access log |
| `/var/log/nginx/error.log` | Web server errors |
| `/var/log/apt/history.log` | Package installation history |

```bash
# Watch a log file in real time
tail -f /var/log/syslog

# Search for errors in logs
grep -i error /var/log/syslog | tail -20

# Count occurrences of a pattern
grep -c "Failed password" /var/log/auth.log
```

## Networking

### Basic Network Commands

```bash
# Show IP addresses
ip addr show
ip a                   # Short form

# Show routing table
ip route show

# Test connectivity
ping google.com
ping -c 4 192.168.1.1  # Send exactly 4 pings

# DNS lookup
nslookup example.com
dig example.com

# Show listening ports
ss -tlnp               # TCP listening ports with process names

# Test if a port is open on a remote host
nc -zv hostname 80

# Download a file
curl -O https://example.com/file.txt
wget https://example.com/file.txt

# Show active connections
ss -tn
```

### Firewall Basics

Ubuntu uses `ufw` (Uncomplicated Firewall):
```bash
sudo ufw status
sudo ufw enable
sudo ufw allow 22/tcp        # Allow SSH
sudo ufw allow 80/tcp        # Allow HTTP
sudo ufw allow 443/tcp       # Allow HTTPS
sudo ufw deny 3306/tcp       # Block MySQL from outside
sudo ufw status numbered     # List rules with numbers
sudo ufw delete 3            # Delete rule #3
```

Fedora/RHEL uses `firewalld`:
```bash
sudo firewall-cmd --state
sudo firewall-cmd --add-service=http --permanent
sudo firewall-cmd --add-port=8080/tcp --permanent
sudo firewall-cmd --reload
```

## SSH: Remote Access

SSH is how you connect to Linux machines remotely. It is the single most important tool for anyone managing servers.

```bash
# Connect to a remote machine
ssh username@hostname
ssh jake@192.168.1.100

# Connect on a non-standard port
ssh -p 2222 jake@hostname

# Copy files to/from remote machines
scp file.txt jake@server:/home/jake/
scp jake@server:/var/log/app.log ./

# Copy directories
scp -r project/ jake@server:/home/jake/

# SSH with key authentication (more secure than passwords)
ssh-keygen -t ed25519       # Generate a key pair
ssh-copy-id jake@server     # Copy public key to server
ssh jake@server             # Now logs in without a password
```

### SSH Key Authentication

Password authentication works, but key-based authentication is both more secure and more convenient. The process:

1. Generate a key pair: `ssh-keygen -t ed25519`
2. Your private key stays on your machine (`~/.ssh/id_ed25519`) — never share it
3. Your public key goes on the server (`~/.ssh/authorized_keys`) — share it freely
4. When you connect, the keys prove your identity without a password

### SSH Config File

If you connect to multiple servers, create `~/.ssh/config`:
```
Host web
    HostName 192.168.1.100
    User jake
    Port 22

Host database
    HostName 192.168.1.101
    User admin
    Port 2222
    IdentityFile ~/.ssh/db_key
```

Now you can type `ssh web` instead of `ssh jake@192.168.1.100`.

## User Management

```bash
# Add a new user
sudo useradd -m -s /bin/bash newuser   # -m creates home dir, -s sets shell
sudo passwd newuser                     # Set their password

# Add user to a group
sudo usermod -aG sudo newuser          # Add to sudo group (admin rights)
sudo usermod -aG docker newuser        # Add to docker group

# Delete a user
sudo userdel -r olduser                # -r removes home directory too

# See who is logged in
who
w

# See recent logins
last
```

## Diagnostic Checklist

When something is not working, this sequence covers most problems:

1. **Is the service running?** `systemctl status servicename`
2. **What do the logs say?** `journalctl -u servicename -n 50`
3. **Is the port open?** `ss -tlnp | grep portnumber`
4. **Is the firewall blocking it?** `sudo ufw status`
5. **Is there disk space?** `df -h`
6. **Is there memory?** `free -h`
7. **Can you reach the network?** `ping hostname`
8. **Is DNS resolving?** `dig hostname`

## Further Reading

- [LinuxForWindowsUsers](LinuxForWindowsUsers) — The complete learning roadmap
- [Linux Command Line Essentials](LinuxCommandLineEssentials) — The commands that underpin system administration
- [Linux Filesystem and Permissions](LinuxFilesystemAndPermissions) — Understanding ownership and access
- [Linux Package Management](LinuxPackageManagement) — Installing and managing the software you administer
- [Linux Shell Scripting Fundamentals](LinuxShellScriptingFundamentals) — Automating administrative tasks
- [Why Learn Linux Deeply](WhyLearnLinuxDeeply) — The career context for these skills
