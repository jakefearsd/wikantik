Docker Backup
docker run --rm --mount source=jspwiki_jspwiki-data,target=/var/jspwiki -v $(pwd):/backup busybox tar -czvf /backup/jspwiki-backup.tar.gz /var/jspwiki

