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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the JSESSIONID cookie hardening chain.
 *
 * <p>The production cookie is issued as {@code Secure; HttpOnly; SameSite=Lax} (verified live:
 * {@code curl -I} of {@code https://wiki.wikantik.com/api/auth/login}), but each attribute comes
 * from a <b>different, easily-removed</b> config knob, and the chain is implicit — which is exactly
 * why an audit once flagged it (incorrectly) as missing. This test fails if any link is dropped:
 *
 * <ul>
 *   <li><b>Secure</b> ← {@code RemoteIpValve protocolHeader="X-Forwarded-Proto"} in the prod
 *       {@code server.xml}. Behind Cloudflare's TLS termination (Tomcat sees plain HTTP) it makes
 *       {@code request.isSecure()} true, so Tomcat marks the session cookie Secure. Note: forcing
 *       {@code <secure>true</secure>} / {@code secureCookies="true"} is deliberately AVOIDED because
 *       the CookieProcessor template is shared with {@code http://localhost} dev, where a forced
 *       Secure cookie is dropped by the browser and breaks login.</li>
 *   <li><b>SameSite=Lax</b> ← {@code CookieProcessor sameSiteCookies="lax"} in the shared Tomcat
 *       context template (the Dockerfile copies it to {@code conf/context.xml}).</li>
 *   <li><b>HttpOnly</b> ← {@code web.xml} {@code <cookie-config><http-only>true}.</li>
 * </ul>
 */
class JsessionidCookieHardeningTest {

    @Test
    void webXml_marksSessionCookieHttpOnlyAndCookieOnlyTracking() throws Exception {
        final String compact = read( Paths.get( "src", "main", "webapp", "WEB-INF", "web.xml" ) )
                .replaceAll( "\\s+", "" );
        assertTrue( compact.contains( "<http-only>true</http-only>" ),
                "JSESSIONID must be HttpOnly (web.xml cookie-config) so an XSS cannot read it" );
        assertTrue( compact.contains( "<tracking-mode>COOKIE</tracking-mode>" ),
                "session tracking must be COOKIE-only — URL rewriting would leak session IDs into "
                + "logs and Referer headers" );
    }

    @Test
    void contextTemplate_setsSameSiteLaxForTheSessionCookie() throws Exception {
        final String ctx = read( Paths.get( "src", "main", "config", "tomcat", "Tomcat-context.xml.template" ) );
        assertTrue( ctx.contains( "sameSiteCookies=\"lax\"" ),
                "the shared Tomcat context template must set CookieProcessor sameSiteCookies=\"lax\" — "
                + "this is what gives the prod JSESSIONID its SameSite=Lax attribute" );
    }

    @Test
    void prodServerXml_processesForwardedProtoSoTheSecureFlagIsSet() throws Exception {
        // The prod server.xml lives at the repo root, one level above this module.
        final Path serverXml = Paths.get( "..", "docker", "config", "server.xml" );
        assertTrue( Files.exists( serverXml ),
                "prod server.xml must exist at " + serverXml.toAbsolutePath() );
        final String content = read( serverXml );
        assertTrue( content.contains( "org.apache.catalina.valves.RemoteIpValve" ),
                "prod server.xml must keep the RemoteIpValve" );
        assertTrue( content.contains( "protocolHeader=\"X-Forwarded-Proto\"" ),
                "RemoteIpValve must process X-Forwarded-Proto so request.isSecure() is true behind "
                + "Cloudflare's TLS termination — that is what makes Tomcat mark JSESSIONID Secure. "
                + "Without it the session cookie silently loses its Secure attribute." );
    }

    private static String read( final Path p ) throws Exception {
        return Files.readString( p );
    }
}
