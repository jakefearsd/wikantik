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
package com.wikantik.content;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Default implementation of {@link SystemPageRegistry} that auto-discovers system pages
 * from the {@code wikantik-wikipages} JAR on the classpath at startup.
 *
 * <p>Discovery works by locating the well-known resource {@code About.txt} via the
 * thread context classloader, then enumerating all sibling {@code *.txt} entries in
 * the same JAR (or directory in dev mode). The {@code .txt} suffix is stripped to
 * produce page names.
 *
 * @since 3.0.7
 */
public class DefaultSystemPageRegistry implements SystemPageRegistry {

    private static final Logger LOG = LogManager.getLogger( DefaultSystemPageRegistry.class );

    /** The well-known resource used as an anchor to find the wiki pages location. */
    private static final String ANCHOR_RESOURCE = "About.txt";

    private Set<String> systemPageNames = Collections.emptySet();
    private List<Pattern> extraPatterns = Collections.emptyList();

    @Override
    public void initialize( final Engine engine, final Properties props ) {
        final Set<String> discovered = discoverSystemPages();
        systemPageNames = Collections.unmodifiableSet( discovered );

        extraPatterns = parseExtraPatterns( props );

        LOG.info( "SystemPageRegistry initialized: {} system pages discovered, {} extra patterns",
                  systemPageNames.size(), extraPatterns.size() );
        LOG.debug( "System pages: {}", systemPageNames );
    }

    @Override
    public boolean isSystemPage( final String pageName ) {
        if ( pageName == null ) {
            return false;
        }
        if ( systemPageNames.contains( pageName ) ) {
            return true;
        }
        for ( final Pattern pattern : extraPatterns ) {
            if ( pattern.matcher( pageName ).matches() ) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<String> getSystemPageNames() {
        return systemPageNames;
    }

    /**
     * Discovers system page names by finding {@code About.txt} on the classpath
     * and enumerating sibling {@code *.txt} resources.
     */
    private Set<String> discoverSystemPages() {
        final Set<String> names = new HashSet<>();
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if ( cl == null ) {
            LOG.warn( "No thread context classloader available; cannot discover system pages" );
            return names;
        }

        final URL anchorUrl = cl.getResource( ANCHOR_RESOURCE );
        if ( anchorUrl == null ) {
            LOG.warn( "Could not find {} on classpath; system page discovery skipped", ANCHOR_RESOURCE );
            return names;
        }

        LOG.debug( "Found anchor resource at: {}", anchorUrl );

        final String protocol = anchorUrl.getProtocol();
        if ( "jar".equals( protocol ) ) {
            discoverFromJar( anchorUrl, names );
        } else if ( "file".equals( protocol ) ) {
            discoverFromDirectory( anchorUrl, names );
        } else {
            LOG.warn( "Unsupported protocol '{}' for system page discovery", protocol );
        }

        return names;
    }

    /**
     * Enumerates {@code *.txt} entries from a JAR file.
     */
    private void discoverFromJar( final URL anchorUrl, final Set<String> names ) {
        try {
            final JarURLConnection jarConn = ( JarURLConnection ) anchorUrl.openConnection();
            // Determine the directory prefix within the JAR (e.g., "" for root or "some/path/")
            final String entryName = jarConn.getEntryName(); // e.g., "About.txt" or "path/About.txt"
            final String prefix;
            final int lastSlash = entryName.lastIndexOf( '/' );
            if ( lastSlash >= 0 ) {
                prefix = entryName.substring( 0, lastSlash + 1 );
            } else {
                prefix = "";
            }

            try ( JarFile jarFile = jarConn.getJarFile() ) {
                final Enumeration<JarEntry> entries = jarFile.entries();
                while ( entries.hasMoreElements() ) {
                    final JarEntry entry = entries.nextElement();
                    final String name = entry.getName();
                    if ( !entry.isDirectory() && name.startsWith( prefix ) && name.endsWith( ".txt" ) ) {
                        // Strip prefix and .txt suffix
                        final String pageName = name.substring( prefix.length(), name.length() - 4 );
                        if ( !pageName.contains( "/" ) && !pageName.isEmpty() ) {
                            names.add( pageName );
                        }
                    }
                }
            }
        } catch ( final IOException e ) {
            LOG.error( "Error reading JAR for system page discovery: {}", e.getMessage(), e );
        }
    }

    /**
     * Enumerates {@code *.txt} files from a filesystem directory (dev mode).
     */
    private void discoverFromDirectory( final URL anchorUrl, final Set<String> names ) {
        try {
            final Path anchorPath = new File( anchorUrl.toURI() ).toPath();
            final Path dir = anchorPath.getParent();
            if ( dir == null || !Files.isDirectory( dir ) ) {
                return;
            }

            try ( DirectoryStream<Path> stream = Files.newDirectoryStream( dir, "*.txt" ) ) {
                for ( final Path file : stream ) {
                    final String fileName = file.getFileName().toString();
                    final String pageName = fileName.substring( 0, fileName.length() - 4 );
                    names.add( pageName );
                }
            }
        } catch ( final URISyntaxException | IOException e ) {
            LOG.error( "Error reading directory for system page discovery: {}", e.getMessage(), e );
        }
    }

    /**
     * Parses extra system page patterns from properties.
     */
    private List<Pattern> parseExtraPatterns( final Properties props ) {
        final List<Pattern> patterns = new ArrayList<>();
        final String patternsStr = props.getProperty( PROP_EXTRA_PATTERNS );
        if ( patternsStr != null && !patternsStr.isEmpty() ) {
            for ( final String patternStr : patternsStr.split( "," ) ) {
                try {
                    patterns.add( Pattern.compile( patternStr.trim() ) );
                } catch ( final PatternSyntaxException e ) {
                    LOG.warn( "Invalid extra system page pattern '{}': {}", patternStr, e.getMessage() );
                }
            }
        }
        return Collections.unmodifiableList( patterns );
    }
}
