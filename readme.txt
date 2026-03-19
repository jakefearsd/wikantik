Docker Backup
docker run --rm --mount source=wikantik_wikantik-data,target=/var/wikantik -v $(pwd):/backup busybox tar -czvf /backup/wikantik-backup.tar.gz /var/wikantik

