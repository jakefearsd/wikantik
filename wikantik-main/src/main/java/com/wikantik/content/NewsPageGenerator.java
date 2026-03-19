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
import com.wikantik.WikiBackgroundThread;
import com.wikantik.api.core.Engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * Background thread that auto-generates a {@code News.md} page from the git commit history
 * of the repository containing the page directory. Runs once daily by default.
 *
 * <p>The generated page groups commits by month and formats them as Markdown with bold dates.
 * Content is only written when it has actually changed (compared via SHA-256 hash) to avoid
 * unnecessary {@link com.wikantik.providers.PageDirectoryWatcher} events.
 *
 * <p>If the page directory is not inside a git repository, or if git is not available on
 * the system PATH, the generator logs a warning and disables itself — it never crashes
 * the background thread.
 *
 * @since 2.12.3
 */
public class NewsPageGenerator extends WikiBackgroundThread {

    private static final Logger LOG = LogManager.getLogger( NewsPageGenerator.class );

    private static final int DEFAULT_INTERVAL_SECONDS = 86_400;
    private static final int DEFAULT_MONTHS_OF_HISTORY = 6;
    private static final String NEWS_PAGE_NAME = "News.md";
    private static final long GIT_TIMEOUT_SECONDS = 30;

    private static final DateTimeFormatter MONTH_HEADER_FORMAT =
            DateTimeFormatter.ofPattern( "MMMM yyyy", Locale.ENGLISH );

    private final Path pageDirectory;
    private final int monthsOfHistory;
    private String lastContentHash;
    private Path gitRoot;
    private boolean disabled;

    /**
     * Creates a new NewsPageGenerator with default settings (24-hour interval, 6 months of history).
     *
     * @param engine the wiki engine
     * @param pageDirectory absolute path to the page directory
     */
    public NewsPageGenerator( final Engine engine, final String pageDirectory ) {
        this( engine, pageDirectory, DEFAULT_INTERVAL_SECONDS, DEFAULT_MONTHS_OF_HISTORY );
    }

    /**
     * Creates a new NewsPageGenerator with custom settings.
     *
     * @param engine the wiki engine
     * @param pageDirectory absolute path to the page directory
     * @param intervalSeconds interval between runs in seconds
     * @param monthsOfHistory number of months of git history to include
     */
    public NewsPageGenerator( final Engine engine, final String pageDirectory,
                              final int intervalSeconds, final int monthsOfHistory ) {
        super( engine, intervalSeconds );
        this.pageDirectory = Path.of( pageDirectory );
        this.monthsOfHistory = monthsOfHistory;
        setName( "JSPWiki News Page Generator" );
        setDaemon( true );
    }

    /**
     * Locates the git repository root on first startup, then generates the news page immediately.
     */
    @Override
    public void startupTask() throws Exception {
        gitRoot = findGitRoot( pageDirectory );
        if( gitRoot == null ) {
            LOG.warn( "Page directory {} is not inside a git repository; news page generator disabled", pageDirectory );
            disabled = true;
            return;
        }
        LOG.info( "News page generator started, git root: {}", gitRoot );

        // Read existing file hash to avoid unnecessary first write
        final Path newsFile = pageDirectory.resolve( NEWS_PAGE_NAME );
        if( Files.exists( newsFile ) ) {
            lastContentHash = sha256( Files.readString( newsFile, StandardCharsets.UTF_8 ) );
        }
    }

    /**
     * Runs the git log command, formats the output, and writes {@code News.md} if content changed.
     */
    @Override
    public void backgroundTask() throws Exception {
        if( disabled ) {
            return;
        }

        try {
            final List< String > gitLogLines = runGitLog();
            final String content = formatNewsPage( gitLogLines );
            final String hash = sha256( content );

            if( hash.equals( lastContentHash ) ) {
                LOG.debug( "News page content unchanged, skipping write" );
                return;
            }

            Files.writeString( pageDirectory.resolve( NEWS_PAGE_NAME ), content, StandardCharsets.UTF_8 );
            lastContentHash = hash;
            LOG.info( "News page updated with {} commits", gitLogLines.size() );
        } catch( final IOException e ) {
            LOG.warn( "Failed to generate news page, will retry next cycle", e );
        }
    }

    /**
     * Runs {@code git log} and returns the output lines.
     *
     * @return list of lines in the format "YYYY-MM-DD commit message"
     * @throws IOException if the process cannot be started or times out
     */
    List< String > runGitLog() throws IOException {
        final ProcessBuilder pb = new ProcessBuilder(
                "git", "--no-pager", "log",
                "--since=" + monthsOfHistory + " months ago",
                "--format=%ad %s",
                "--date=short"
        );
        pb.directory( gitRoot.toFile() );
        pb.redirectErrorStream( true );

        final Process process = pb.start();
        final List< String > lines = new ArrayList<>();

        try( final BufferedReader reader = new BufferedReader(
                new InputStreamReader( process.getInputStream(), StandardCharsets.UTF_8 ) ) ) {
            String line;
            while( ( line = reader.readLine() ) != null ) {
                if( !line.isBlank() ) {
                    lines.add( line );
                }
            }
        }

        try {
            if( !process.waitFor( GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS ) ) {
                process.destroyForcibly();
                throw new IOException( "git log timed out after " + GIT_TIMEOUT_SECONDS + " seconds" );
            }
            if( process.exitValue() != 0 ) {
                throw new IOException( "git log exited with code " + process.exitValue() );
            }
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IOException( "git log interrupted", e );
        }

        return lines;
    }

    /**
     * Formats git log lines into a Markdown news page grouped by month.
     *
     * <p>Each line is expected to be in the format {@code YYYY-MM-DD commit message}.
     * Lines that don't match this format are silently skipped.
     *
     * @param gitLogLines lines from {@code git log --format="%ad %s" --date=short}
     * @return formatted Markdown content
     */
    static String formatNewsPage( final List< String > gitLogLines ) {
        final StringBuilder sb = new StringBuilder();
        sb.append( "# JSPWiki Development News\n\n" );
        sb.append( "A log of recent development activity on the JSPWiki project.\n" );

        // Parse and group by month, preserving insertion order (most recent first)
        final Map< YearMonth, List< String[] > > grouped = new LinkedHashMap<>();
        for( final String line : gitLogLines ) {
            // Expect "YYYY-MM-DD message"
            if( line.length() < 11 || line.charAt( 4 ) != '-' || line.charAt( 7 ) != '-' || line.charAt( 10 ) != ' ' ) {
                continue;
            }
            final String date = line.substring( 0, 10 );
            final String message = line.substring( 11 );
            try {
                final YearMonth ym = YearMonth.parse( date.substring( 0, 7 ) );
                grouped.computeIfAbsent( ym, k -> new ArrayList<>() ).add( new String[]{ date, message } );
            } catch( final Exception e ) {
                // Skip malformed date
            }
        }

        for( final Map.Entry< YearMonth, List< String[] > > entry : grouped.entrySet() ) {
            sb.append( "\n---\n\n" );
            sb.append( "## " ).append( entry.getKey().format( MONTH_HEADER_FORMAT ) ).append( "\n\n" );
            for( final String[] commit : entry.getValue() ) {
                sb.append( "**" ).append( commit[ 0 ] ).append( "** — " ).append( commit[ 1 ] ).append( "\n\n" );
            }
        }

        return sb.toString();
    }

    /**
     * Walks up from the given path looking for a {@code .git} directory.
     *
     * @param startPath the directory to start searching from
     * @return the git repository root, or {@code null} if not found
     */
    static Path findGitRoot( final Path startPath ) {
        Path current = startPath.toAbsolutePath();
        while( current != null ) {
            if( Files.isDirectory( current.resolve( ".git" ) ) ) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Computes the SHA-256 hex digest of a string.
     */
    static String sha256( final String content ) {
        try {
            final MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
            final byte[] hash = digest.digest( content.getBytes( StandardCharsets.UTF_8 ) );
            return HexFormat.of().formatHex( hash );
        } catch( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 not available", e );
        }
    }

}
