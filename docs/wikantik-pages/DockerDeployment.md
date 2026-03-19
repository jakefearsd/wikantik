---
type: article
tags:
- uncategorized
summary: Professional Wikantik Deployment with Docker
---
1. Professional Wikantik Deployment with Docker

This guide provides a comprehensive walkthrough for deploying a production-ready Wikantik instance using Docker, with a focus on configuration, data persistence, and automated backups.

  1. 1. Configuration

Wikantik's Docker container is highly configurable through environment variables. This allows you to customize your installation without modifying the core application files.

    1. Environment Variables

The following environment variables are available to configure your Wikantik instance. You can set them in your `docker-compose.yml` file or directly with the `docker run` command.

- `CATALINA_OPTS`: Additional options for the Tomcat server. The default value, `-Djava.security.egd=file:/dev/./urandom`, is recommended for better performance on systems with low entropy.
- `LANG`: Sets the language for the container. Defaults to `en_US.UTF-8`.
- `jspwiki_basicAttachmentProvider_storageDir`: The directory where attachments are stored. Defaults to `/var/jspwiki/pages`.
- `jspwiki_fileSystemProvider_pageDir`: The directory where wiki pages are stored. Defaults to `/var/jspwiki/pages`.
- `jspwiki_frontPage`: The name of the wiki's front page. Defaults to `Main`.
- `jspwiki_pageProvider`: The page provider to use. Defaults to `VersioningFileProvider`.
- `jspwiki_use_external_logconfig`: Set to `true` to use an external Log4j2 configuration file. Defaults to `true`.
- `jspwiki_workDir`: The working directory for Wikantik. Defaults to `/var/jspwiki/work`.
- `jspwiki_xmlUserDatabaseFile`: The path to the user database file. Defaults to `/var/jspwiki/etc/userdatabase.xml`.
- `jspwiki_xmlGroupDatabaseFile`: The path to the group database file. Defaults to `/var/jspwiki/etc/groupdatabase.xml`.

  1. 2. Data Persistence and Backup Strategy

To ensure that your wiki's data persists across container restarts and to facilitate backups, it is crucial to use Docker volumes.

    1. Critical Data Directories

The following directories contain all of Wikantik's critical data and should be mounted as volumes:

- `/var/jspwiki/pages`: Contains the wiki pages and attachments.
- `/var/jspwiki/etc`: Contains the user and group databases.
- `/var/jspwiki/logs`: Contains the application logs.
- `/var/jspwiki/work`: The working directory for Wikantik.

    1. Automated Backups

Our recommended backup strategy involves a dedicated backup container that has read-only access to the Wikantik data volume. This container runs a cron job to create compressed archives of the data at regular intervals.

  1. 3. Example Docker Compose Deployment

This example uses `docker-compose` to define and run a multi-container Wikantik application with an automated backup service.

    1. Project Structure

```
.
├── docker-compose.yml
└── backup/
    ├── backup.sh
    └── crontab
```

    1. `docker-compose.yml`

```yaml
version: '3.7'

services:
  jspwiki:
    build: .
    container_name: jspwiki
    restart: always
    ports:
      - "8080:8080"
    volumes:
      - jspwiki-data:/var/jspwiki

  backup:
    image: alpine
    container_name: jspwiki-backup
    restart: always
    volumes:
      - jspwiki-data:/var/jspwiki:ro
      - ./backups:/backups
      - ./backup:/etc/periodic/daily
    command: ["crond", "-f", "-d", "8"]

volumes:
  jspwiki-data:
```

    1. `backup/backup.sh`

```bash
1. !/bin/sh

set -e

1. Create a compressed archive of the wikantik data
TIMESTAMP=$(date +"%Y-%m-%d_%H-%M-%S")
BACKUP_FILE="/backups/jspwiki-backup-${TIMESTAMP}.tar.gz"

tar -czf "${BACKUP_FILE}" -C /var/jspwiki .

1. Prune old backups (keep the last 7)
find /backups -name "jspwiki-backup-*.tar.gz" -type f -mtime +7 -delete
```

    1. `backup/crontab`

```
1. Run the backup script daily at 2:00 AM
0 2 * * * /etc/periodic/daily/backup.sh
```

    1. Deployment Steps

1.  **Create the necessary files and directories** as shown in the project structure above.
2.  **Make the `backup.sh` script executable**:

    ```bash
    chmod +x backup/backup.sh
    ```

3.  **Start the application**:

    ```bash
    docker-compose up -d
    ```

4.  **Verify the deployment**:

    *   Access Wikantik at `http://localhost:8080`.
    *   Check the logs of the backup container to ensure that the cron job is running:

        ```bash
        docker logs jspwiki-backup
        ```

    1. Restoring from a Backup

To restore your Wikantik instance from a backup:

1.  **Stop the `jspwiki` container**:

    ```bash
    docker-compose stop jspwiki
    ```

2.  **Extract the backup archive** to the `jspwiki-data` volume:

    ```bash
    docker run --rm -v jspwiki-data:/var/jspwiki -v $(pwd)/backups:/backups alpine tar -xzf /backups/jspwiki-backup-<TIMESTAMP>.tar.gz -C /var/jspwiki
    ```

3.  **Restart the `jspwiki` container**:

    ```bash
    docker-compose start jspwiki
    ```
