---
date: 2026-03-14T00:00:00Z
status: active
summary: How software installation works on Linux — package managers, repositories,
  updates, and why you rarely download installers from websites
related:
- LinuxForWindowsUsers
- ChoosingALinuxDistribution
- LinuxSystemAdministration
- LinuxCommandLineEssentials
tags:
- linux
- package-management
- apt
- dnf
- operating-systems
type: article
cluster: linux-for-windows-users
---
# Linux Package Management

On Windows, installing software means visiting a website, downloading an .exe or .msi, running it, clicking through a wizard, and hoping it does not bundle a toolbar. On Linux, you type one command.

Package management is one of Linux's strongest advantages over Windows — and one of the features Windows has been slowly trying to replicate (with winget and the Microsoft Store). Understanding it is essential because it is how you install everything from text editors to web servers to programming languages.

## How It Works

A **package manager** is a tool that:
1. Maintains a database of available software (**repositories**)
2. Downloads software and all its dependencies
3. Installs everything in the correct locations
4. Tracks what is installed so it can be updated or removed cleanly
5. Handles updates for everything on your system at once

Think of it as an app store with a command-line interface — except it manages your entire system, not just apps.

### Repositories

Repositories ("repos") are servers that host software packages. Your distribution ships with a default set of repos maintained by the distribution's team. You can add third-party repos for additional software.

```bash
# Ubuntu: list configured repos
cat /etc/apt/sources.list
ls /etc/apt/sources.list.d/

# Fedora: list configured repos
dnf repolist
```

## The Three Major Package Managers

### apt (Debian/Ubuntu family)

```bash
# Update the package database (check for new versions)
sudo apt update

# Upgrade all installed packages
sudo apt upgrade

# Install a package
sudo apt install nginx

# Install multiple packages
sudo apt install git curl vim

# Remove a package (keep config files)
sudo apt remove nginx

# Remove a package and its config files
sudo apt purge nginx

# Search for a package
apt search image editor

# Show package details
apt show nginx

# Clean up downloaded package files
sudo apt autoremove
sudo apt clean
```

**Important:** Always run `sudo apt update` before `sudo apt install`. The package database is a local cache — if you do not update it, you might install an outdated version or get a "package not found" error for something that exists.

### dnf (Fedora/RHEL family)

```bash
# Check for updates
sudo dnf check-update

# Upgrade all packages
sudo dnf upgrade

# Install a package
sudo dnf install nginx

# Remove a package
sudo dnf remove nginx

# Search for a package
dnf search image editor

# Show package details
dnf info nginx

# Clean caches
sudo dnf clean all
```

### pacman (Arch family)

```bash
# Synchronize database and upgrade all packages
sudo pacman -Syu

# Install a package
sudo pacman -S nginx

# Remove a package and unused dependencies
sudo pacman -Rs nginx

# Search for a package
pacman -Ss image editor

# Show package info
pacman -Si nginx

# List explicitly installed packages
pacman -Qe
```

## Universal Package Formats

Traditional package managers are distribution-specific. Several universal formats have emerged to solve this:

### Flatpak
Sandboxed desktop applications that work on any distribution. Installed from Flathub (the main repository).
```bash
flatpak install flathub org.gimp.GIMP
flatpak run org.gimp.GIMP
flatpak update
```
**Best for:** Desktop applications (browsers, media players, editors).

### Snap (Ubuntu/Canonical)
Similar to Flatpak, backed by Canonical. Auto-updates.
```bash
snap install code --classic    # Install VS Code
snap list                      # List installed snaps
snap refresh                   # Update all snaps
```
**Best for:** Ubuntu users who want easy installs.

### AppImage
Single-file applications. Download, make executable, run. No installation required.
```bash
chmod +x MyApp.AppImage
./MyApp.AppImage
```
**Best for:** Trying applications without installing them.

## System Updates

One of Linux's most practical advantages: **one command updates everything**. Not just the OS, but every application installed through the package manager.

```bash
# Ubuntu: update everything
sudo apt update && sudo apt upgrade -y

# Fedora: update everything
sudo dnf upgrade -y

# Arch: update everything
sudo pacman -Syu
```

Compare this to Windows, where you update the OS through Windows Update, each application has its own updater, and some applications never update at all.

### Update Strategies

| Strategy | How | Best For |
|----------|-----|----------|
| Regular updates | Run updates weekly | Desktop systems, development machines |
| Unattended upgrades | Configure automatic security updates | Servers that need to stay patched |
| Manual review | Check changelogs before upgrading | Production servers where stability matters |
| Snapshot before update | Use Timeshift/btrfs snapshots before major updates | When you want a rollback option |

## Building From Source

Sometimes software is not in any repository. Building from source (compiling the code yourself) is the fallback:

```bash
# Typical source build workflow
git clone https://github.com/project/repo.git
cd repo
./configure           # Check dependencies, set build options
make                  # Compile
sudo make install     # Install to /usr/local/
```

Before building from source, install the build toolchain:
```bash
# Ubuntu
sudo apt install build-essential

# Fedora
sudo dnf groupinstall "Development Tools"
```

**Warning:** Software installed from source is not tracked by the package manager. It will not be updated automatically, and removing it requires `sudo make uninstall` (if the project supports it). Prefer packages from repositories when available.

## Dependency Management

One of the package manager's most important jobs is handling **dependencies** — libraries and tools that a package requires. When you install nginx, the package manager also installs the SSL libraries, PCRE library, and everything else nginx needs.

This is dramatically better than Windows, where DLL conflicts ("DLL hell") and missing Visual C++ redistributables are common headaches.

## The Windows User's Package Management Cheat Sheet

| Windows Action | Linux Equivalent |
|---------------|------------------|
| Download .exe from website | `sudo apt install packagename` |
| Windows Update | `sudo apt update && sudo apt upgrade` |
| Add/Remove Programs | `sudo apt remove packagename` |
| Check if something is installed | `dpkg -l \| grep packagename` or `apt list --installed` |
| "Where did it install?" | `dpkg -L packagename` or `which commandname` |
| Microsoft Store | Flatpak (Flathub) or Snap |

## Further Reading

- [LinuxForWindowsUsers](LinuxForWindowsUsers) — The complete learning roadmap
- [Choosing a Linux Distribution](ChoosingALinuxDistribution) — Your distro choice determines your package manager
- [Linux System Administration](LinuxSystemAdministration) — Managing services installed via packages
- [Linux Command Line Essentials](LinuxCommandLineEssentials) — The commands you need to work with packages
