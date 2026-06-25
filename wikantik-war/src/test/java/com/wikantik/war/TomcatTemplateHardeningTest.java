/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the bare-metal / local-dev Tomcat {@code server.xml} template, keeping it
 * from drifting back to the un-hardened stock Tomcat config and re-opening the gaps the container
 * config ({@code docker/config/server.xml}) already closes.
 *
 * <p>The shutdown listener intentionally diverges from the container (which uses {@code port="-1"}
 * because it stops via {@code docker stop}): a bare-metal install stops via {@code bin/shutdown.sh},
 * which needs the port — so it stays open but is bound to loopback so it cannot be reached remotely.
 *
 * <p>Also guards the container {@code docker/config/server.xml} error-report hardening: it must use
 * an explicit {@code ErrorReportValve} and NOT {@code showReport}/{@code showServerInfo} on
 * {@code <Host>} (which {@code StandardHost} has no setters for, so the Digester ignores them).
 */
class TomcatTemplateHardeningTest {

    private static final Path TEMPLATE =
            Paths.get( "src", "main", "config", "tomcat", "Tomcat-server.xml.template" );
    /** Container server.xml lives at the repo root, one level above this module. */
    private static final Path DOCKER_SERVER_XML = Paths.get( "..", "docker", "config", "server.xml" );

    private static String template() throws Exception {
        return Files.readString( TEMPLATE );
    }

    @Test
    void shutdownListener_isBoundToLoopback() throws Exception {
        assertTrue( template().contains( "<Server port=\"8005\" address=\"127.0.0.1\"" ),
                "the shutdown port must be bound to 127.0.0.1 so the SHUTDOWN command cannot be "
                + "sent from off-box (the port stays open because bin/shutdown.sh uses it)" );
    }

    @Test
    void host_disablesRuntimeAutoDeployAndWarContextDescriptors() throws Exception {
        final String t = template();
        assertTrue( t.contains( "autoDeploy=\"false\"" ),
                "autoDeploy must be false — never hot-deploy a WAR dropped into webapps/ at runtime" );
        assertTrue( t.contains( "deployXML=\"false\"" ),
                "deployXML must be false — ignore any context.xml packaged inside a deployed WAR so a "
                + "rogue WAR cannot inject JNDI resources or privileged context options" );
    }

    @Test
    void errorReporting_suppressesVersionAndStackTraces() throws Exception {
        final String t = template();
        assertTrue( t.contains( "org.apache.catalina.valves.ErrorReportValve" ),
                "an explicit ErrorReportValve must be configured to control error-page output" );
        assertTrue( t.contains( "showReport=\"false\"" ),
                "ErrorReportValve showReport must be false — no stack-trace bodies on error pages" );
        assertTrue( t.contains( "showServerInfo=\"false\"" ),
                "ErrorReportValve showServerInfo must be false — no Tomcat version banner" );
    }

    @Test
    void connector_hidesPoweredByAndBlocksTrace() throws Exception {
        final String t = template();
        assertTrue( t.contains( "xpoweredBy=\"false\"" ),
                "the connector must not advertise X-Powered-By" );
        assertTrue( t.contains( "allowTrace=\"false\"" ),
                "the connector must reject the TRACE method (cross-site tracing defense)" );
    }

    @Test
    void containerServerXml_suppressesErrorReportViaExplicitValve() throws Exception {
        assertTrue( Files.exists( DOCKER_SERVER_XML ),
                "container server.xml must exist at " + DOCKER_SERVER_XML.toAbsolutePath() );
        final String docker = Files.readString( DOCKER_SERVER_XML );
        // showReport/showServerInfo are ErrorReportValve properties, NOT <Host> attributes (the
        // Digester ignores them on Host), so error-report hardening MUST be on an explicit valve.
        assertTrue( docker.contains( "org.apache.catalina.valves.ErrorReportValve" ),
                "container server.xml must configure an explicit ErrorReportValve — showReport/"
                + "showServerInfo on <Host> are silently ignored by StandardHost" );
        assertTrue( docker.contains( "showReport=\"false\"" )
                        && docker.contains( "showServerInfo=\"false\"" ),
                "the ErrorReportValve must set showReport=false and showServerInfo=false so error "
                + "pages leak neither stack traces nor the Tomcat version" );
    }

    @Test
    void noDeploymentShipsACommittedTomcatManagerCredential() throws Exception {
        // The dead docker-files/ dir shipped a Tomcat Manager user "admin/admin"; that file must
        // stay deleted (the wiki authenticates via JAAS, not the Tomcat container realm).
        assertFalse( Files.exists( Paths.get( "..", "docker-files", "tomcat-users.xml" ) ),
                "docker-files/tomcat-users.xml (an admin/admin Tomcat Manager credential) must not return" );

        // And no committed tomcat-users.xml under the deployment-config homes may declare a user
        // with a password — a committed Tomcat credential is only ever a Manager-webapp footgun.
        final List< Path > userFiles = new ArrayList<>();
        for ( final Path dir : List.of( Paths.get( "..", "docker" ),
                                        Paths.get( "..", "docker-files" ),
                                        Paths.get( "src", "main", "config", "tomcat" ) ) ) {
            if ( !Files.isDirectory( dir ) ) {
                continue;
            }
            try ( var paths = Files.walk( dir ) ) {
                paths.filter( p -> "tomcat-users.xml".equals( p.getFileName().toString() ) )
                     .forEach( userFiles::add );
            }
        }
        for ( final Path p : userFiles ) {
            final String xml = Files.readString( p ).replaceAll( "(?s)<!--.*?-->", "" );  // ignore commented examples
            assertFalse( xml.matches( "(?s).*<user\\b[^>]*\\bpassword\\s*=.*" ),
                    "committed " + p + " must not declare a Tomcat user with a password" );
        }
    }
}
