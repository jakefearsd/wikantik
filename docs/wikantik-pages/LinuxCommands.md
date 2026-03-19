---
cluster: linux-for-windows-users
tags:
- linux
- command-line
- reference
- shell
- system-administration
summary: 'Essential Linux command reference organized by category: file operations,
  text processing, process management, networking, and system administration'
---
# Linux Commands

This article is a practical reference for essential [Linux](https://en.wikipedia.org/wiki/Linux) commands, particularly useful for users transitioning from Windows. Linux's command-line interface (CLI) is its greatest strength: most system administration, development, and automation tasks can be accomplished efficiently through the terminal. While the graphical desktop has improved greatly, the CLI remains the preferred tool for developers, system administrators, and power users.

Commands are organized by category with brief explanations and practical examples. For pipes, redirection, and combining commands, see the dedicated section below.

## File System Navigation

Linux uses a single unified file system tree rooted at `/`, unlike Windows with its drive letters (C:\, D:\).

| Command | Purpose | Example |
|---------|---------|---------|
| `pwd` | Print working (current) directory | `pwd` → `/home/alice` |
| `cd` | Change directory | `cd /var/log` |
| `cd ..` | Move up one directory | `cd ..` |
| `cd ~` | Go to home directory | `cd ~` (or just `cd`) |
| `cd -` | Go to previous directory | `cd -` |
| `ls` | List directory contents | `ls -la /etc` |
| `ls -l` | Long format with permissions, sizes | `ls -l *.txt` |
| `ls -a` | Show hidden files (starting with `.`) | `ls -a ~` |
| `find` | Search for files by name, type, date, etc. | `find /home -name "*.pdf" -type f` |
| `locate` | Fast file search using a pre-built index | `locate jspwiki.properties` |
| `tree` | Display directory tree structure | `tree -L 2 /var` |

**Key differences from Windows:**
- Paths use forward slashes (`/`), not backslashes (`\`)
- File names are case-sensitive (`File.txt` and `file.txt` are different files)
- Hidden files start with a dot (`.bashrc`, `.ssh`)
- There is no recycle bin on the command line — `rm` deletes immediately

## File Operations

| Command | Purpose | Example |
|---------|---------|---------|
| `cp` | Copy files or directories | `cp file.txt backup/` |
| `cp -r` | Copy directories recursively | `cp -r project/ project_backup/` |
| `mv` | Move or rename files | `mv old_name.txt new_name.txt` |
| `rm` | Remove files | `rm unwanted.txt` |
| `rm -r` | Remove directories recursively | `rm -r old_directory/` |
| `rm -i` | Remove with confirmation prompt | `rm -i important.txt` |
| `mkdir` | Create a directory | `mkdir new_project` |
| `mkdir -p` | Create nested directories | `mkdir -p src/main/java` |
| `touch` | Create an empty file or update timestamp | `touch newfile.txt` |
| `chmod` | Change file permissions | `chmod 755 script.sh` |
| `chown` | Change file ownership | `chown alice:developers file.txt` |
| `ln -s` | Create a symbolic link | `ln -s /usr/bin/python3 /usr/bin/python` |

### Understanding Permissions

Linux file permissions use three groups (owner, group, others) and three types (read, write, execute):

```
-rwxr-xr-- 1 alice developers 4096 Jan 15 10:30 script.sh
│├─┤├─┤├─┤
│ │   │  └── Others: read only (4)
│ │   └───── Group: read + execute (5)
│ └───────── Owner: read + write + execute (7)
└──────────── File type (- = regular file, d = directory, l = link)
```

Numeric notation: read=4, write=2, execute=1. So `chmod 755` means owner=rwx(7), group=r-x(5), others=r-x(5).

## Text Processing

Text processing is where Linux truly excels. These commands form a powerful toolkit for analyzing and transforming text data.

| Command | Purpose | Example |
|---------|---------|---------|
| `cat` | Display entire file contents | `cat config.txt` |
| `less` | View file with scrolling (q to quit) | `less /var/log/syslog` |
| `head` | Show first N lines (default 10) | `head -20 logfile.txt` |
| `tail` | Show last N lines | `tail -50 error.log` |
| `tail -f` | Follow a file in real time | `tail -f /var/log/syslog` |
| `grep` | Search for patterns in text | `grep "ERROR" app.log` |
| `grep -r` | Search recursively in directories | `grep -r "TODO" src/` |
| `grep -i` | Case-insensitive search | `grep -i "warning" output.txt` |
| `grep -c` | Count matching lines | `grep -c "404" access.log` |
| `sed` | Stream editor for text substitution | `sed 's/old/new/g' file.txt` |
| `awk` | Pattern scanning and processing | `awk '{print $1, $3}' data.txt` |
| `wc` | Count lines, words, characters | `wc -l file.txt` |
| `sort` | Sort lines | `sort -n numbers.txt` |
| `uniq` | Remove adjacent duplicates | `sort names.txt \| uniq -c` |
| `cut` | Extract columns from text | `cut -d',' -f2 data.csv` |
| `tr` | Translate or delete characters | `echo "HELLO" \| tr 'A-Z' 'a-z'` |
| `diff` | Compare two files | `diff file1.txt file2.txt` |

### Practical Examples

Count unique IP addresses in an access log:
```bash
awk '{print $1}' access.log | sort | uniq -c | sort -rn | head -10
```

Replace all occurrences of a string in multiple files:
```bash
find . -name "*.java" -exec sed -i 's/oldMethod/newMethod/g' {} +
```

Extract the third column from a CSV, sorted and deduplicated:
```bash
cut -d',' -f3 data.csv | sort -u
```

## Process Management

| Command | Purpose | Example |
|---------|---------|---------|
| `ps` | Show running processes | `ps aux` |
| `ps aux` | Detailed list of all processes | `ps aux \| grep java` |
| `top` | Real-time process monitor | `top` |
| `htop` | Enhanced process monitor (interactive) | `htop` |
| `kill` | Send signal to a process | `kill 1234` |
| `kill -9` | Force kill a process | `kill -9 1234` |
| `killall` | Kill processes by name | `killall firefox` |
| `bg` | Resume a stopped job in background | `bg %1` |
| `fg` | Bring a background job to foreground | `fg %1` |
| `jobs` | List background jobs | `jobs` |
| `nohup` | Run command immune to hangups | `nohup ./server.sh &` |
| `&` | Run command in background | `./long_task.sh &` |
| `Ctrl+C` | Interrupt (kill) foreground process | — |
| `Ctrl+Z` | Suspend foreground process | — |

## Package Management

Different Linux distributions use different package managers:

| Distribution | Package Manager | Install Example | Update System |
|-------------|----------------|-----------------|---------------|
| **Ubuntu/Debian** | `apt` | `sudo apt install nginx` | `sudo apt update && sudo apt upgrade` |
| **Fedora/RHEL** | `dnf` | `sudo dnf install nginx` | `sudo dnf upgrade` |
| **Arch Linux** | `pacman` | `sudo pacman -S nginx` | `sudo pacman -Syu` |
| **openSUSE** | `zypper` | `sudo zypper install nginx` | `sudo zypper update` |

Common patterns:
- Search for packages: `apt search keyword` / `dnf search keyword`
- Remove a package: `apt remove package` / `dnf remove package`
- List installed packages: `apt list --installed` / `dnf list installed`

## Disk and System Information

| Command | Purpose | Example |
|---------|---------|---------|
| `df -h` | Disk space usage (human-readable) | `df -h` |
| `du -sh` | Directory size summary | `du -sh /var/log` |
| `du -h --max-depth=1` | Size of immediate subdirectories | `du -h --max-depth=1 /home` |
| `free -h` | Memory usage | `free -h` |
| `uname -a` | System information | `uname -a` |
| `lsblk` | List block devices (disks/partitions) | `lsblk` |
| `lscpu` | CPU information | `lscpu` |
| `uptime` | System uptime and load averages | `uptime` |
| `hostname` | Display system hostname | `hostname` |

## Networking

| Command | Purpose | Example |
|---------|---------|---------|
| `ip addr` | Show network interfaces and IP addresses | `ip addr` |
| `ip route` | Show routing table | `ip route` |
| `ss -tlnp` | Show listening TCP ports | `ss -tlnp` |
| `curl` | Transfer data from/to a URL | `curl -O https://example.com/file.tar.gz` |
| `wget` | Download files | `wget https://example.com/file.tar.gz` |
| `ping` | Test network connectivity | `ping -c 4 google.com` |
| `dig` | DNS lookup | `dig example.com` |
| `ssh` | Secure remote login | `ssh user@192.168.1.100` |
| `scp` | Secure copy over SSH | `scp file.txt user@host:/path/` |
| `rsync` | Efficient file synchronization | `rsync -avz src/ user@host:dest/` |
| `netstat -tlnp` | (Legacy) Show listening ports | `netstat -tlnp` |

## Pipes and Redirection

Pipes and redirection are fundamental to the Linux philosophy of combining small, focused tools into powerful pipelines.

| Operator | Purpose | Example |
|----------|---------|---------|
| `\|` | Pipe: send output of one command to another | `ls -l \| grep ".txt"` |
| `>` | Redirect output to file (overwrite) | `echo "hello" > file.txt` |
| `>>` | Redirect output to file (append) | `echo "line 2" >> file.txt` |
| `<` | Redirect file as input | `sort < unsorted.txt` |
| `2>` | Redirect error output | `command 2> errors.log` |
| `2>&1` | Redirect errors to same place as output | `command > all.log 2>&1` |
| `&>` | Redirect both stdout and stderr | `command &> all.log` |
| `tee` | Write to file AND display on screen | `command \| tee output.log` |
| `xargs` | Build commands from piped input | `find . -name "*.tmp" \| xargs rm` |

### Pipeline Example

Find the 10 largest files in a directory tree:
```bash
find /var -type f -exec du -h {} + 2>/dev/null | sort -rh | head -10
```

## Compression and Archiving

| Command | Purpose | Example |
|---------|---------|---------|
| `tar -czf` | Create compressed archive (.tar.gz) | `tar -czf backup.tar.gz /home/user/` |
| `tar -xzf` | Extract .tar.gz archive | `tar -xzf backup.tar.gz` |
| `tar -xjf` | Extract .tar.bz2 archive | `tar -xjf archive.tar.bz2` |
| `gzip` | Compress a file | `gzip large_file.txt` |
| `gunzip` | Decompress a .gz file | `gunzip large_file.txt.gz` |
| `zip` | Create ZIP archive | `zip -r archive.zip directory/` |
| `unzip` | Extract ZIP archive | `unzip archive.zip` |

## User Management

| Command | Purpose | Example |
|---------|---------|---------|
| `sudo` | Run command as superuser | `sudo apt update` |
| `su` | Switch to another user | `su - alice` |
| `whoami` | Show current username | `whoami` |
| `id` | Show user ID and group memberships | `id` |
| `groups` | Show groups current user belongs to | `groups` |
| `passwd` | Change password | `passwd` |
| `useradd` | Create a new user | `sudo useradd -m newuser` |
| `usermod` | Modify a user account | `sudo usermod -aG docker alice` |

## Essential Tips for Windows Users

1. **Tab completion** works everywhere — press Tab to auto-complete file names and commands.
2. **Ctrl+R** searches your command history — start typing and it finds matching previous commands.
3. **`man command`** opens the manual page for any command (`man grep`, `man chmod`).
4. **`command --help`** provides a quick usage summary.
5. The **home directory** (`~` or `/home/username`) is the equivalent of `C:\Users\Username`.
6. **`sudo`** is the equivalent of "Run as Administrator."
7. Use **`alias`** to create shortcuts: `alias ll='ls -la'` in your `.bashrc`.
