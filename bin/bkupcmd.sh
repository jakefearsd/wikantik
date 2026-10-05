#!/bin/bash
# Back up the wikantik-data Docker volume to ./wikantik-backup.tar.gz in the current directory.
# Usage: bin/bkupcmd.sh [-h|--help]
case "${1:-}" in -h|--help)
  sed -n '2,3p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac
#docker run --rm \
#--mount source=<volume-name>,target=<target> \
#-v $(pwd):/backup \
#busybox \
#tar -czvf /backup/<backup-filename>.tar.gz <target>
#In this command, replace <volume-name> with the name of the volume you want to back up,
# <target> with the mount point inside the docker container, and <backup-filename> with a name for the backup file.
#
#
#
docker run --rm --mount source=wikantik-data,target=/var/wikantik -v $(pwd):/backup busybox tar -czvf /backup/wikantik-backup.tar.gz /var/wikantik 

