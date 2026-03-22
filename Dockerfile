# Stage 1: Build WAR with Maven
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src
COPY pom.xml .
COPY wikantik-api/pom.xml wikantik-api/pom.xml
COPY wikantik-bootstrap/pom.xml wikantik-bootstrap/pom.xml
COPY wikantik-cache/pom.xml wikantik-cache/pom.xml
COPY wikantik-event/pom.xml wikantik-event/pom.xml
COPY wikantik-main/pom.xml wikantik-main/pom.xml
COPY wikantik-markdown/pom.xml wikantik-markdown/pom.xml
COPY wikantik-mcp/pom.xml wikantik-mcp/pom.xml
COPY wikantik-util/pom.xml wikantik-util/pom.xml
COPY wikantik-war/pom.xml wikantik-war/pom.xml

RUN mvn -B dependency:go-offline -pl wikantik-war -am || true

COPY . .
RUN mvn -B clean package -pl wikantik-war -am -DskipTests

# Stage 2: Tomcat 11 / JDK 21 runtime
FROM tomcat:11.0-jdk21-temurin

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*

# Remove default webapps
RUN rm -rf ${CATALINA_HOME}/webapps/* ${CATALINA_HOME}/bin/*.bat

# Create Wikantik directories
RUN mkdir -p /var/wikantik/pages /var/wikantik/work /var/wikantik/logs

# Download PostgreSQL JDBC driver
RUN curl -fsSL -o ${CATALINA_HOME}/lib/postgresql-42.7.4.jar \
    https://jdbc.postgresql.org/download/postgresql-42.7.4.jar

# Copy Tomcat configuration
COPY docker/config/server.xml ${CATALINA_HOME}/conf/server.xml
COPY docker/config/catalina.properties ${CATALINA_HOME}/conf/catalina.properties
COPY docker/config/log4j2-docker.xml ${CATALINA_HOME}/lib/log4j2.xml

# Copy entrypoint
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Deploy WAR (unzipped for faster startup)
COPY --from=build /src/wikantik-war/target/Wikantik.war /tmp/Wikantik.war
RUN mkdir -p ${CATALINA_HOME}/webapps/ROOT \
    && unzip -q -d ${CATALINA_HOME}/webapps/ROOT /tmp/Wikantik.war \
    && rm /tmp/Wikantik.war

# Copy default page content
COPY docs/wikantik-pages/ /var/wikantik/pages/

ENV CATALINA_OPTS="-Djava.security.egd=file:/dev/./urandom"
ENV LANG=en_US.UTF-8

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
CMD ["catalina.sh", "run"]
