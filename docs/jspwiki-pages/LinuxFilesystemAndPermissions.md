---
date: 2026-03-14T00:00:00Z
status: active
summary: How the Linux filesystem works — the directory tree, everything-is-a-file
  philosophy, users and groups, and the permission model explained for Windows users
related:
- LinuxForWindowsUsers
- LinuxCommandLineEssentials
- LinuxSystemAdministration
- LinuxCommands
tags:
- linux
- filesystem
- permissions
- operating-systems
- security
type: article
cluster: linux-for-windows-users
---
# Linux Filesystem and Permissions

The Linux filesystem is probably the single biggest conceptual shift from Windows. No drive letters. No `C:\`. No separate "My Documents" per user that lives somewhere you cannot quite find. Instead, there is one unified tree starting at `/`, and every file on the system — whether it is on your hard drive, a USB stick, or a network share — appears somewhere in that tree.

Understanding the filesystem and its permission model is foundational. Almost every Linux operation involves navigating this tree or managing who can access what.

## One Tree, Not Drive Letters

In Windows, each disk gets a letter: `C:\`, `D:\`, `E:\`. In Linux, there is a single root directory: `/`. Everything else is a branch of that tree. Additional disks, USB drives, and network shares are **mounted** at a point in the tree rather than getting their own letter:

| Windows | Linux | Explanation |
|---------|-------|------------|
| `C:\` | `/` | The root of the filesystem |
| `C:\Users\jake` | `/home/jake` | Your personal directory |
| `C:\Program Files` | `/usr/bin`, `/usr/lib` | System-wide programs and libraries |
| `C:\Windows` | `/boot`, `/sbin`, `/lib` | OS core files |
| `D:\` (second disk) | `/mnt/data` or `/media/data` | Mounted wherever you choose |
| `E:\` (USB drive) | `/media/jake/USB_DRIVE` | Auto-mounted for removable media |

## The Directory Tree

| Directory | Purpose | Windows Analogy |
|-----------|---------|----------------|
| `/` | Root of everything | `C:\` |
| `/home/username` | Your personal files, settings, configs | `C:\Users\username` |
| `/etc` | System-wide configuration files | Registry + various config locations |
| `/var` | Variable data: logs, databases, mail, caches | `C:\ProgramData`, Event Logs |
| `/var/log` | Log files | Event Viewer |
| `/tmp` | Temporary files (often cleared on reboot) | `%TEMP%` |
| `/usr` | User programs, libraries, documentation | `C:\Program Files` |
| `/usr/bin` | User command binaries | `C:\Program Files\app\app.exe` |
| `/usr/local` | Locally installed software (not from package manager) | Manual installs |
| `/bin`, `/sbin` | Essential system binaries | `C:\Windows\System32` |
| `/dev` | Device files (disks, terminals, hardware) | Device Manager |
| `/proc` | Virtual filesystem exposing kernel/process info | No equivalent |
| `/sys` | Virtual filesystem for hardware/driver info | No equivalent |
| `/boot` | Boot loader and kernel images | Hidden system partition |
| `/opt` | Optional/third-party software | `C:\Program Files` (alternate) |
| `/mnt` | Mount point for manually mounted filesystems | Arbitrary drive letters |
| `/media` | Auto-mounted removable media | Removable drives |

## Everything Is a File

This is the most important conceptual difference. In Linux, almost everything is represented as a file:

- Regular files (documents, images, binaries) — obviously files
- Directories — special files that contain references to other files
- Devices — `/dev/sda` is your hard drive, `/dev/null` is a data sink
- Processes — `/proc/1234/status` shows info about process 1234
- Network sockets — can be accessed through the filesystem
- Named pipes — inter-process communication through file-like objects

This is not just a philosophical curiosity. It means you can use the **same tools** (cat, grep, ls, permissions) to interact with files, devices, processes, and system information. The abstraction is powerful.

## Users and Groups

Linux is a multi-user system from the ground up. Every file is owned by a **user** and a **group**, and these ownerships determine who can do what.

- **Root** (uid 0): The superuser. Can do anything. The Linux equivalent of Administrator, but with no safety rails — root can delete the entire system without a confirmation dialog.
- **Regular users**: Your day-to-day account. Can only access their own files and files explicitly shared with them.
- **System users**: Accounts for services (like `www-data` for the web server, `postgres` for the database). They exist for security isolation, not for humans.
- **Groups**: Collections of users. A file owned by group `developers` is accessible to everyone in that group.

### sudo: Controlled Root Access

`sudo` ("superuser do") lets a permitted user run a single command as root:

```bash
sudo apt update              # Run package update as root
sudo systemctl restart nginx  # Restart a service as root
sudo nano /etc/hosts          # Edit a system config file as root
```

You will be prompted for your own password (not root's). Your admin privileges are granted for a few minutes, then you return to normal user mode.

**Rule of thumb:** Use `sudo` only when you must. Running everything as root is dangerous — one typo can destroy your system. Use your regular user account for everyday work.

## The Permission Model

Every file has three sets of permissions for three audiences:

**Audiences:**
- **Owner** (u): The user who owns the file
- **Group** (g): Members of the file's group
- **Others** (o): Everyone else

**Permissions:**
- **Read** (r): Can view the file's contents (or list a directory's contents)
- **Write** (w): Can modify the file (or create/delete files in a directory)
- **Execute** (x): Can run the file as a program (or enter a directory)

### Reading Permission Strings

`ls -la` shows permissions:
```
-rwxr-xr-- 1 jake developers 4096 Mar 14 10:30 script.sh
```

Breaking down `-rwxr-xr--`:
| Position | Meaning |
|---------|--------|
| `-` | File type (`-` = regular file, `d` = directory, `l` = symlink) |
| `rwx` | Owner permissions: read + write + execute |
| `r-x` | Group permissions: read + execute (no write) |
| `r--` | Others permissions: read only |

### Changing Permissions

**Symbolic mode** (easier to read):
```bash
chmod u+x script.sh     # Give owner execute permission
chmod g+w shared.txt    # Give group write permission
chmod o-r private.txt   # Remove read from others
chmod a+r public.txt    # Give everyone read
```

**Numeric mode** (faster once you learn it):
Each permission has a value: read=4, write=2, execute=1. Add them up for each audience:
```bash
chmod 755 script.sh     # Owner: rwx(7), Group: r-x(5), Others: r-x(5)
chmod 644 document.txt  # Owner: rw-(6), Group: r--(4), Others: r--(4)
chmod 700 private/      # Owner: rwx(7), Group: ---(0), Others: ---(0)
```

| Numeric | Permissions | Typical Use |
|---------|------------|------------|
| 755 | rwxr-xr-x | Executable scripts, directories |
| 644 | rw-r--r-- | Regular files (readable by all, writable by owner) |
| 700 | rwx------ | Private directories |
| 600 | rw------- | Private files (SSH keys, configs with passwords) |

### Changing Ownership

```bash
chown jake file.txt           # Change owner to jake
chown jake:developers file.txt # Change owner and group
chown -R jake:developers dir/  # Recursive (all files in directory)
```

## Common Permission Scenarios for Windows Users

| Scenario | What to Do |
|----------|----------|
| "Permission denied" when editing a config file | It is in `/etc/` — use `sudo nano /etc/filename` |
| Downloaded script will not run | It is not executable — `chmod +x script.sh` |
| Cannot access another user's files | Check permissions with `ls -la`. Use group membership or `sudo` if appropriate. |
| SSH key "permissions too open" | SSH requires `chmod 600 ~/.ssh/id_rsa` — private keys must be private |
| Web server cannot read your files | The web server runs as a different user. Set group permissions or change ownership. |

## Further Reading

- [LinuxForWindowsUsers](LinuxForWindowsUsers) — The complete learning roadmap
- [Linux Command Line Essentials](LinuxCommandLineEssentials) — The commands you need to navigate and manipulate files
- [Linux System Administration](LinuxSystemAdministration) — Managing users, services, and system resources
- [Linux Shell Scripting Fundamentals](LinuxShellScriptingFundamentals) — Automating file operations
