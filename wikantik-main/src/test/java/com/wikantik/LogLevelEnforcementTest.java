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
package com.wikantik;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scans production source files for LOG.error() calls and ensures each one is
 * either (a) listed in the known-justified allowlist or (b) accompanied by a
 * {@code // LOG.error justified:} comment on the same or preceding line.
 *
 * <p>The goal is to keep ERROR-level logging reserved for genuinely
 * unrecoverable conditions. Expected failures, recoverable exceptions, and
 * input-validation errors should use WARN or lower.
 *
 * <p>When adding a new LOG.error() call, add a justification comment:
 * <pre>
 *   // LOG.error justified: unrecoverable provider failure, wiki cannot continue
 *   LOG.error( "Provider failed", e );
 * </pre>
 * Or add the file:line to the allowlist in this test.
 */
public class LogLevelEnforcementTest {

    private static final Pattern LOG_ERROR_PATTERN = Pattern.compile( "LOG\\.error\\s*\\(" );
    private static final Pattern JUSTIFICATION_PATTERN = Pattern.compile( "LOG\\.error justified:" );

    /**
     * Known LOG.error() calls that have been reviewed and confirmed as appropriate.
     * Each entry is "SimpleClassName.java:lineNumber" or just "SimpleClassName.java" to allow all in that file.
     * When you add a LOG.error() call, either add a justification comment next to it or add it here with a reason.
     */
    private static final Set< String > ALLOWLISTED = Set.of(
            // Core engine initialization — if these fail, the wiki cannot start
            "WikiEngine.java",
            // Rendering pipeline — parser/renderer failures are unrecoverable for the request
            "DefaultRenderingManager.java",
            // Security subsystem — auth/login failures must be visible
            "AuthenticationManager.java",
            "DefaultAuthenticationManager.java",
            "DefaultAuthorizationManager.java",
            "DefaultUserManager.java",
            "GroupManager.java",
            "WebContainerAuthorizer.java",
            "DefaultAclManager.java",
            "AbstractUserDatabase.java",
            "XMLUserDatabase.java",
            "JDBCUserDatabase.java",
            "AbstractJDBCDatabase.java",
            "JDBCGroupDatabase.java",
            "AnonymousLoginModule.java",
            "CookieAssertionLoginModule.java",
            "CookieAuthenticationLoginModule.java",
            "WebContainerLoginModule.java",
            "UserDatabaseLoginModule.java",
            // SSO subsystem — configuration failures must be visible
            "SSOConfig.java",
            "SSOAutoProvisionService.java",
            "SSOCallbackServlet.java",
            "SSOLoginModule.java",
            "SSORedirectServlet.java",
            // Provider layer — storage failures are unrecoverable
            "AbstractFileProvider.java",
            "VersioningFileProvider.java",
            "FileSystemProvider.java",
            "CachingProvider.java",
            "CachingAttachmentProvider.java",
            "BasicAttachmentProvider.java",
            "DefaultAttachmentManager.java",
            "PageDirectoryWatcher.java",
            "LoggingPageProviderDecorator.java",
            // Search index — Lucene failures must be visible
            "LuceneSearchProvider.java",
            // Content operations — rename/system page failures
            "DefaultPageRenamer.java",
            "DefaultSystemPageRegistry.java",
            "RecentArticlesServlet.java",
            // Background thread crash — must be visible
            "WikiBackgroundThread.java",
            // Servlet error handling — request-level failures
            "SimpleMBean.java",
            "Preferences.java",
            // Diff providers — diff generation failures
            "ContextualDiffProvider.java",
            "TraditionalDiffProvider.java",
            // Plugins — plugin execution failures
            "TableOfContents.java",
            "RecentArticles.java",
            "AbstractReferralPlugin.java",
            // Parser internals
            "MarkupParser.java",
            "PluginContent.java",
            // Reference manager — serialization failures
            "DefaultReferenceManager.java",
            // Filter subsystem — initialization failures
            "DefaultFilterManager.java",
            "SpamFilter.java",
            "ProfanityFilter.java",
            // UI layer — servlets and command resolver
            "WikiServletFilter.java",
            "SitemapServlet.java",
            "DefaultCommandResolver.java",
            "AttachmentServlet.java",
            "WikiAjaxDispatcherServlet.java",
            // Search manager
            "DefaultSearchManager.java",
            // Page manager — provider initialization and page operations
            "DefaultPageManager.java",
            "PageSorter.java",
            "PageTimeComparator.java",
            // Core utilities
            "WatchDog.java",
            // Group management
            "DefaultGroupManager.java"
    );

    @Test
    public void logErrorCallsMustBeJustified() throws IOException {
        final List< String > violations = new ArrayList<>();
        final Path srcRoot = Path.of( "src/main/java" );

        if( !Files.isDirectory( srcRoot ) ) {
            return; // skip if run from wrong working directory
        }

        Files.walkFileTree( srcRoot, new SimpleFileVisitor< Path >() {
            @Override
            public FileVisitResult visitFile( final Path file, final BasicFileAttributes attrs ) throws IOException {
                if( !file.toString().endsWith( ".java" ) ) {
                    return FileVisitResult.CONTINUE;
                }

                final String fileName = file.getFileName().toString();
                if( ALLOWLISTED.contains( fileName ) ) {
                    return FileVisitResult.CONTINUE;
                }

                final List< String > lines = Files.readAllLines( file );
                for( int i = 0; i < lines.size(); i++ ) {
                    final String line = lines.get( i );
                    final Matcher m = LOG_ERROR_PATTERN.matcher( line );
                    if( m.find() ) {
                        // Check current line and previous line for justification comment
                        final boolean justified = JUSTIFICATION_PATTERN.matcher( line ).find()
                                || ( i > 0 && JUSTIFICATION_PATTERN.matcher( lines.get( i - 1 ) ).find() );
                        final String fileAndLine = fileName + ":" + ( i + 1 );
                        if( !justified && !ALLOWLISTED.contains( fileAndLine ) ) {
                            violations.add( file.toString() + ":" + ( i + 1 ) + "  →  " + line.trim() );
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        } );

        assertTrue( violations.isEmpty(),
                "Found " + violations.size() + " unjustified LOG.error() call(s). "
                + "Either change to LOG.warn()/LOG.debug(), add a '// LOG.error justified: <reason>' comment, "
                + "or add the file to the allowlist in LogLevelEnforcementTest.\n\n"
                + String.join( "\n", violations ) );
    }
}
