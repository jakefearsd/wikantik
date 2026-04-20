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
package com.wikantik.tools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Loads OpenAPI tool-server configuration from classpath properties files.
 *
 * <p>Defaults live in {@code /wikantik-tools.properties} inside the JAR. An optional
 * override file with the same name on the parent classloader (e.g.
 * {@code tomcat/lib/wikantik-tools.properties}) is overlaid on top, so operators can
 * configure keys and CIDR allowlists without code changes.</p>
 */
public class ToolsConfig {

    private static final Logger LOG = LogManager.getLogger( ToolsConfig.class );
    private static final String RESOURCE_NAME = "wikantik-tools.properties";

    private final Properties props;

    public ToolsConfig() {
        props = new Properties();
        final ClassLoader ownCl = ToolsConfig.class.getClassLoader();
        if ( ownCl != null ) {
            loadFromClasspath( ownCl, props );
        }
        final ClassLoader parent = ownCl != null ? ownCl.getParent() : null;
        if ( parent != null ) {
            loadFromClasspath( parent, props );
        }
        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if ( tccl != null && tccl != ownCl && tccl != parent ) {
            loadFromClasspath( tccl, props );
        }
    }

    /** Package-private constructor for testing with pre-built properties. */
    ToolsConfig( final Properties properties ) {
        this.props = properties;
    }

    /**
     * Returns the list of configured API keys for tool-server access control.
     * Reads from {@code tools.access.keys} (comma-separated).
     */
    public List< String > accessKeys() {
        final String raw = props.getProperty( "tools.access.keys" );
        if ( raw == null || raw.isBlank() ) {
            return List.of();
        }
        return Arrays.stream( raw.split( "," ) )
                .map( String::strip )
                .filter( s -> !s.isEmpty() )
                .toList();
    }

    public String allowedCidrs() {
        final String cidrs = props.getProperty( "tools.access.allowedCidrs" );
        return cidrs != null && !cidrs.isBlank() ? cidrs.strip() : null;
    }

    /**
     * When no API keys and no CIDR allowlist are configured, the filter fails closed
     * unless {@code tools.access.allowUnrestricted=true} is set. Defaults to {@code false}.
     */
    public boolean allowUnrestricted() {
        final String raw = props.getProperty( "tools.access.allowUnrestricted" );
        return raw != null && Boolean.parseBoolean( raw.strip() );
    }

    public int rateLimitGlobal() {
        return intProperty( "tools.ratelimit.global", 0 );
    }

    public int rateLimitPerClient() {
        return intProperty( "tools.ratelimit.perClient", 0 );
    }

    /**
     * Public base URL used to build citation links in tool responses. When null,
     * callers should fall back to the request's own scheme/host.
     */
    public String publicBaseUrl() {
        final String raw = props.getProperty( "wikantik.public.baseURL" );
        return raw != null && !raw.isBlank() ? raw.strip() : null;
    }

    private int intProperty( final String key, final int defaultValue ) {
        final String val = props.getProperty( key );
        if ( val == null || val.isBlank() ) {
            return defaultValue;
        }
        try {
            return Integer.parseInt( val.strip() );
        } catch ( final NumberFormatException e ) {
            LOG.warn( "Invalid integer for '{}': '{}'", key, val );
            return defaultValue;
        }
    }

    private static void loadFromClasspath( final ClassLoader cl, final Properties target ) {
        try ( final InputStream is = cl.getResourceAsStream( RESOURCE_NAME ) ) {
            if ( is != null ) {
                target.load( is );
            }
        } catch ( final IOException e ) {
            LOG.warn( "Could not load {}: {}", RESOURCE_NAME, e.getMessage() );
        }
    }
}
