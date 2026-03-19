#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing,
#  software distributed under the License is distributed on an
#  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
#  KIND, either express or implied.  See the License for the
#  specific language governing permissions and limitations
#  under the License.

FROM maven:3.9-eclipse-temurin-17 as package

WORKDIR /tmp

COPY . .

RUN mvn -B dependency:go-offline

RUN set -x \
# fastest, minimum build
  && mvn -B clean package -pl wikantik-war,wikantik-wikipages/en -am -DskipTests

FROM tomcat:10.1-jdk17

COPY --from=package /tmp/wikantik-war/target/Wikantik.war /tmp
COPY --from=package /tmp/wikantik-wikipages/en/target/wikantik-wikipages-en-*-wikantik.zip /tmp
COPY docker-files/log4j2.properties /tmp
COPY docker-files/tomcat-users.xml $CATALINA_HOME/conf/tomcat-users.xml
COPY docker-files/web.xml $CATALINA_HOME/conf/web.xml
COPY docker-files/server.xml $CATALINA_HOME/conf/server.xml
COPY docker-files/catalina.properties $CATALINA_HOME/conf/catalina.properties

#
# set default environment entries to configure wikantik
ENV CATALINA_OPTS -Djava.security.egd=file:/dev/./urandom
ENV LANG en_US.UTF-8
ENV wikantik_basicAttachmentProvider_storageDir /var/wikantik/pages
ENV wikantik_fileSystemProvider_pageDir /var/wikantik/pages
ENV wikantik_frontPage Main
ENV wikantik_pageProvider VersioningFileProvider
ENV wikantik_use_external_logconfig true
ENV wikantik_workDir /var/wikantik/work
ENV wikantik_xmlUserDatabaseFile /var/wikantik/etc/userdatabase.xml
ENV wikantik_xmlGroupDatabaseFile /var/wikantik/etc/groupdatabase.xml

RUN set -x \
 && export DEBIAN_FRONTEND=noninteractive \
 && apt update \
 && apt upgrade -y \
 && apt install --fix-missing --quiet --yes unzip

#
# install wikantik
RUN set -x \
 && mkdir /var/wikantik \
# remove default tomcat applications, we dont need them to run wikantik
 && cd $CATALINA_HOME/webapps \
 && rm -rf examples host-manager manager docs ROOT \
# remove other stuff we don't need
 && rm -rf /usr/local/tomcat/bin/*.bat \
# create subdirectories where all wikantik stuff will live
 && cd /var/wikantik \
 && mkdir pages logs etc work \
# deploy wikantik
 && mkdir $CATALINA_HOME/webapps/ROOT \
 && unzip -q -d $CATALINA_HOME/webapps/ROOT /tmp/Wikantik.war \
 && rm /tmp/Wikantik.war \
# deploy wiki pages
 && cd /tmp/ \
 && unzip -q wikantik-wikipages-en-*-wikantik.zip \
 && mv wikantik-wikipages-en-*/* /var/wikantik/pages/ \
 && rm -rf wikantik-wikipages-en-* \
# move the userdatabase.xml and groupdatabase.xml to /var/wikantik/etc
 && cd $CATALINA_HOME/webapps/ROOT/WEB-INF \
 && mv userdatabase.xml groupdatabase.xml /var/wikantik/etc \
# arrange proper logging (wikantik.use.external.logconfig = true needs to be set)
 && mv /tmp/log4j2.properties $CATALINA_HOME/lib/log4j2.properties

# make port visible in metadata
EXPOSE 8443

#
# by default we start the Tomcat container when the docker container is started.
CMD ["/usr/local/tomcat/bin/catalina.sh","run", ">/usr/local/tomcat/logs/catalina.out"]
