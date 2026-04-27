#!/bin/bash
docker ps -a --filter "ancestor=pgvector/pgvector:pg17" -q | xargs -r docker rm -f
tomcat/tomcat-11/bin/shutdown.sh 2>/dev/null; sleep 3
mvn clean install -Pintegration-tests -fae
