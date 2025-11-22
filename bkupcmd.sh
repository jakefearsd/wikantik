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
docker run --rm --mount source=jspwiki-data,target=/var/jspwiki -v $(pwd):/backup busybox tar -czvf /backup/jspwiki-backup.tar.gz /var/jspwiki 

