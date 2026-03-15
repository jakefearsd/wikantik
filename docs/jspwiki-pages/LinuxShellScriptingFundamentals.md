---
date: 2026-03-14T00:00:00Z
status: active
summary: Introduction to shell scripting — turning command sequences into reusable
  scripts, variables, conditionals, loops, and automating tasks with cron
related:
- LinuxForWindowsUsers
- LinuxCommandLineEssentials
- LinuxSystemAdministration
- LinuxFilesystemAndPermissions
tags:
- linux
- bash
- scripting
- automation
- command-line
type: article
cluster: linux-for-windows-users
---
# Linux Shell Scripting Fundamentals

Once you are comfortable running commands individually (see [LinuxCommandLineEssentials](LinuxCommandLineEssentials)), the next step is combining them into **scripts** — files that run a sequence of commands automatically. If you have ever written a batch file (.bat) in Windows or used PowerShell scripts, the concept is the same. The syntax is different, and bash scripting is both more powerful and more quirky than either.

Shell scripting is where Linux becomes genuinely transformative. The ability to automate anything you can do in the terminal — backups, deployments, log analysis, system monitoring — is the practical payoff of learning the command line.

## Your First Script

```bash
#!/bin/bash
# My first script — greet the user and show system info

echo "Hello, $(whoami)!"
echo "Today is $(date '+%A, %B %d, %Y')"
echo "You are on $(hostname)"
echo "Uptime: $(uptime -p)"
```

Save this as `info.sh`, make it executable, and run it:
```bash
chmod +x info.sh
./info.sh
```

**Key points:**
- `#!/bin/bash` (the "shebang") tells the system which interpreter to use
- `#` starts a comment
- `$(command)` runs a command and inserts its output
- `echo` prints text

## Variables

```bash
# Assigning variables (NO spaces around =)
name="Jake"
count=42
today=$(date '+%Y-%m-%d')

# Using variables (prefix with $)
echo "Hello, $name"
echo "Count: $count"
echo "Date: $today"

# Quoting matters
file="my document.txt"
cp "$file" backup/     # CORRECT: quotes preserve spaces
cp $file backup/        # WRONG: bash sees two arguments: "my" and "document.txt"
```

**Always quote your variables.** `"$variable"` prevents word splitting on spaces. This is the single most common source of bugs in shell scripts.

## Conditionals

```bash
#!/bin/bash

if [ -f "/etc/nginx/nginx.conf" ]; then
    echo "Nginx is configured"
elif [ -f "/etc/apache2/apache2.conf" ]; then
    echo "Apache is configured"
else
    echo "No web server config found"
fi
```

### Common Test Operators

| Operator | Meaning | Example |
|---------|---------|--------|
| `-f file` | File exists and is a regular file | `[ -f config.txt ]` |
| `-d dir` | Directory exists | `[ -d /var/log ]` |
| `-e path` | Path exists (any type) | `[ -e /tmp/lock ]` |
| `-z "$var"` | Variable is empty | `[ -z "$name" ]` |
| `-n "$var"` | Variable is not empty | `[ -n "$name" ]` |
| `"$a" = "$b"` | Strings are equal | `[ "$user" = "root" ]` |
| `"$a" != "$b"` | Strings differ | `[ "$env" != "prod" ]` |
| `$a -eq $b` | Numbers are equal | `[ $count -eq 0 ]` |
| `$a -gt $b` | Greater than | `[ $size -gt 1000 ]` |
| `$a -lt $b` | Less than | `[ $age -lt 18 ]` |

## Loops

```bash
# Loop over a list
for server in web01 web02 web03; do
    echo "Checking $server..."
    ping -c 1 "$server" > /dev/null 2>&1 && echo "  up" || echo "  down"
done

# Loop over files
for file in /var/log/*.log; do
    echo "$file: $(wc -l < "$file") lines"
done

# Loop with a counter
for i in {1..10}; do
    echo "Iteration $i"
done

# While loop
count=0
while [ $count -lt 5 ]; do
    echo "Count: $count"
    count=$((count + 1))
done

# Read a file line by line
while IFS= read -r line; do
    echo "Processing: $line"
done < input.txt
```

## Functions

```bash
#!/bin/bash

# Define a function
backup_file() {
    local source="$1"    # First argument
    local dest="$2"      # Second argument
    
    if [ ! -f "$source" ]; then
        echo "ERROR: $source does not exist" >&2
        return 1
    fi
    
    cp "$source" "$dest"
    echo "Backed up $source to $dest"
}

# Call the function
backup_file /etc/nginx/nginx.conf /tmp/nginx.conf.bak
```

## Practical Script Examples

### Backup Script

```bash
#!/bin/bash
# Daily backup of important directories

BACKUP_DIR="/backup/$(date '+%Y-%m-%d')"
SOURCE_DIRS=("/home/jake/documents" "/home/jake/projects" "/etc")

mkdir -p "$BACKUP_DIR"

for dir in "${SOURCE_DIRS[@]}"; do
    dirname=$(basename "$dir")
    tar czf "$BACKUP_DIR/${dirname}.tar.gz" "$dir" 2>/dev/null
    echo "Backed up $dir"
done

# Remove backups older than 30 days
find /backup -maxdepth 1 -type d -mtime +30 -exec rm -rf {} +

echo "Backup complete: $BACKUP_DIR"
```

### Log Monitor

```bash
#!/bin/bash
# Watch a log file for errors and alert

LOGFILE="/var/log/application.log"
ALERT_PATTERN="ERROR|CRITICAL|FATAL"

tail -F "$LOGFILE" | while IFS= read -r line; do
    if echo "$line" | grep -qE "$ALERT_PATTERN"; then
        echo "[ALERT $(date '+%H:%M:%S')] $line"
    fi
done
```

### System Health Check

```bash
#!/bin/bash
# Quick system health report

echo "=== System Health Report ==="
echo "Date: $(date)"
echo
echo "--- Disk Usage ---"
df -h / /home | tail -n +2
echo
echo "--- Memory ---"
free -h | head -2
echo
echo "--- Load Average ---"
uptime
echo
echo "--- Top 5 CPU Processes ---"
ps aux --sort=-%cpu | head -6
echo
echo "--- Failed Services ---"
systemctl --failed --no-legend
```

## Scheduling with cron

`cron` runs scripts on a schedule — the Linux equivalent of Windows Task Scheduler, but configured with a text file.

Edit your crontab:
```bash
crontab -e
```

Format: `minute hour day-of-month month day-of-week command`
```
# Run backup every day at 2 AM
0 2 * * * /home/jake/scripts/backup.sh

# Run health check every 5 minutes
*/5 * * * * /home/jake/scripts/health.sh >> /var/log/health.log 2>&1

# Run cleanup every Sunday at midnight
0 0 * * 0 /home/jake/scripts/cleanup.sh

# Run at 9 AM on the 1st of every month
0 9 1 * * /home/jake/scripts/monthly-report.sh
```

**Cron tip:** Always use full paths in cron jobs. Cron runs with a minimal environment — commands like `node` or `python3` need their full path (use `which python3` to find it).

## Customizing Your Environment

Your shell configuration lives in dotfiles in your home directory:

| File | Purpose |
|------|--------|
| `~/.bashrc` | Runs every time you open a terminal (aliases, functions, prompt) |
| `~/.bash_profile` | Runs on login (environment variables, PATH) |
| `~/.profile` | Generic login configuration (used by many shells) |

Common customizations:
```bash
# Add to ~/.bashrc

# Useful aliases
alias ll='ls -la'
alias gs='git status'
alias update='sudo apt update && sudo apt upgrade -y'
alias ..='cd ..'
alias ...='cd ../..'

# Better history
export HISTSIZE=10000
export HISTFILESIZE=20000
export HISTCONTROL=ignoredups:erasedups

# Custom prompt showing git branch
parse_git_branch() {
    git branch 2>/dev/null | grep '\*' | sed 's/* //'
}
export PS1='\u@\h:\w$(parse_git_branch)\$ '
```

After editing, apply changes: `source ~/.bashrc`

## Further Reading

- [LinuxForWindowsUsers](LinuxForWindowsUsers) — The complete learning roadmap
- [Linux Command Line Essentials](LinuxCommandLineEssentials) — The commands your scripts will use
- [Linux System Administration](LinuxSystemAdministration) — The systems your scripts will manage
- [Linux Filesystem and Permissions](LinuxFilesystemAndPermissions) — Understanding script permissions and paths
