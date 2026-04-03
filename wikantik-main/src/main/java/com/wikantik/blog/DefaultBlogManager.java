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
package com.wikantik.blog;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.pages.PageManager;
import com.wikantik.providers.AbstractFileProvider;
import com.wikantik.render.RenderingManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Default implementation of {@link BlogManager} that manages blog lifecycle
 * using the filesystem-backed page provider.
 *
 * <p>Blogs are stored as real subdirectories under {@code pageDir/blog/<username>/}.
 * Each user may have at most one blog. Blog entries are wiki pages with
 * YYYYMMDD-prefixed filenames.
 *
 * @since 3.0.8
 */
public class DefaultBlogManager implements BlogManager {

    private static final Logger LOG = LogManager.getLogger( DefaultBlogManager.class );

    /** Date format for blog entry filename prefixes. */
    private static final DateTimeFormatter DATE_PREFIX_FORMAT = DateTimeFormatter.ofPattern( "yyyyMMdd" );

    /** Date format for frontmatter date field. */
    private static final DateTimeFormatter FRONTMATTER_DATE_FORMAT = DateTimeFormatter.ofPattern( "yyyy-MM-dd" );

    /** Classpath resource for the blog homepage template. */
    private static final String TEMPLATE_RESOURCE = "/com/wikantik/blog/BlogTemplate.md";

    private Engine engine;
    private PageManager pageManager;
    private UserManager userManager;
    private String pageDir;

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine newEngine, final Properties props ) throws WikiException {
        this.engine = newEngine;
        this.pageManager = newEngine.getManager( PageManager.class );
        this.userManager = newEngine.getManager( UserManager.class );
        this.pageDir = props.getProperty( AbstractFileProvider.PROP_PAGEDIR );

        if ( pageDir == null || pageDir.isBlank() ) {
            throw new WikiException( "BlogManager requires property " + AbstractFileProvider.PROP_PAGEDIR );
        }

        LOG.info( "BlogManager initialized with pageDir={}", pageDir );
    }

    /** {@inheritDoc} */
    @Override
    public Page createBlog( final Session session ) throws WikiException {
        final String username = extractUsername( session );

        // Create the blog directory atomically
        final Path blogDirPath = Path.of( pageDir, BLOG_DIR, username );
        try {
            Files.createDirectories( blogDirPath.getParent() );
            Files.createDirectory( blogDirPath );
        } catch ( final FileAlreadyExistsException e ) {
            throw new BlogAlreadyExistsException( username );
        } catch ( final IOException e ) {
            throw new WikiException( "Failed to create blog directory for " + username + ": " + e.getMessage(), e );
        }

        // Load the template and replace {username}
        final String templateContent = loadTemplate( username );

        // Create the Blog.md page via PageManager
        final String pageName = BlogManager.blogPagePath( username, BLOG_HOME_PAGE );
        final Page page = Wiki.contents().page( engine, pageName );
        pageManager.putPageText( page, templateContent );

        LOG.info( "Created blog for user '{}' at {}", username, pageName );
        return pageManager.getPage( pageName );
    }

    /** {@inheritDoc} */
    @Override
    public void deleteBlog( final Session session, final String username ) throws WikiException {
        final String normalizedUsername = username.toLowerCase();

        // Validate ownership or admin role
        final String callerUsername = extractUsername( session );
        if ( !callerUsername.equals( normalizedUsername ) && !isAdmin( session ) ) {
            throw new WikiException( "User '" + callerUsername + "' is not allowed to delete blog for '" + normalizedUsername + "'" );
        }

        if ( !blogExists( normalizedUsername ) ) {
            throw new WikiException( "Blog does not exist for user: " + normalizedUsername );
        }

        // Delete all blog pages via PageManager
        final List< Page > entries = listEntries( normalizedUsername );
        for ( final Page entry : entries ) {
            pageManager.deletePage( entry );
        }

        // Delete the Blog.md page
        final String blogPageName = BlogManager.blogPagePath( normalizedUsername, BLOG_HOME_PAGE );
        if ( pageManager.wikiPageExists( blogPageName ) ) {
            pageManager.deletePage( blogPageName );
        }

        // Remove the blog directory (should now be empty or have only .properties files)
        final Path blogDirPath = Path.of( pageDir, BLOG_DIR, normalizedUsername );
        try {
            deleteDirectoryRecursively( blogDirPath );
        } catch ( final IOException e ) {
            LOG.warn( "Failed to remove blog directory {}: {}", blogDirPath, e.getMessage() );
        }

        LOG.info( "Deleted blog for user '{}'", normalizedUsername );
    }

    /** {@inheritDoc} */
    @Override
    public Page createEntry( final Session session, final String topicName ) throws WikiException {
        return createEntry( session, topicName, null );
    }

    /** {@inheritDoc} */
    @Override
    public Page createEntry( final Session session, final String topicName, final String content ) throws WikiException {
        final String username = extractUsername( session );

        if ( !blogExists( username ) ) {
            throw new WikiException( "No blog exists for user: " + username );
        }

        // Build the entry page name with YYYYMMDD prefix
        final String datePrefix = LocalDate.now().format( DATE_PREFIX_FORMAT );
        final String entrySlug = datePrefix + topicName;
        final String pageName = BlogManager.blogPagePath( username, entrySlug );

        // Build frontmatter content
        final String title = camelCaseToSpaced( topicName );
        final String date = LocalDate.now().format( FRONTMATTER_DATE_FORMAT );
        final String author = session.getLoginPrincipal().getName();

        final StringBuilder sb = new StringBuilder();
        sb.append( "---\n" )
          .append( "title: \"" ).append( title ).append( "\"\n" )
          .append( "date: " ).append( date ).append( "\n" )
          .append( "author: \"" ).append( author ).append( "\"\n" )
          .append( "---\n" )
          .append( "\n" )
          .append( "# " ).append( title ).append( "\n" );

        if ( content != null && !content.isBlank() ) {
            sb.append( "\n" ).append( content.strip() ).append( "\n" );
        }

        final Page page = Wiki.contents().page( engine, pageName );
        pageManager.putPageText( page, sb.toString() );

        // Evict the blog homepage render cache so plugins (LatestArticle, ArticleListing) show the new entry
        getRenderingManager().evictRenderCache( BlogManager.blogPagePath( username, BLOG_HOME_PAGE ) );

        LOG.info( "Created blog entry '{}' for user '{}'", pageName, username );
        return pageManager.getPage( pageName );
    }

    /** {@inheritDoc} */
    @Override
    public Page getBlog( final String username ) throws ProviderException {
        final String normalizedUsername = username.toLowerCase();
        final String pageName = BlogManager.blogPagePath( normalizedUsername, BLOG_HOME_PAGE );

        if ( pageManager.wikiPageExists( pageName ) ) {
            return pageManager.getPage( pageName );
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public List< Page > listEntries( final String username ) throws ProviderException {
        final String normalizedUsername = username.toLowerCase();
        final Path blogDirPath = Path.of( pageDir, BLOG_DIR, normalizedUsername );

        if ( !Files.isDirectory( blogDirPath ) ) {
            return List.of();
        }

        final List< Page > entries = new ArrayList<>();
        final File blogDir = blogDirPath.toFile();
        final File[] files = blogDir.listFiles();

        if ( files == null ) {
            return List.of();
        }

        for ( final File file : files ) {
            if ( !isBlogEntryFile( file ) ) {
                continue;
            }

            final String fileName = file.getName();
            final String slug = fileName.endsWith( ".md" )
                ? fileName.substring( 0, fileName.length() - 3 )
                : fileName.substring( 0, fileName.length() - 4 );

            final String pageName = BlogManager.blogPagePath( normalizedUsername, slug );
            final Page page = pageManager.getPage( pageName, PageProvider.LATEST_VERSION );
            if ( page != null ) {
                entries.add( page );
            }
        }

        // Sort by name descending (YYYYMMDD prefix provides chronological order)
        entries.sort( Comparator.comparing( Page::getName ).reversed() );

        return entries;
    }

    /** {@inheritDoc} */
    @Override
    public boolean blogExists( final String username ) {
        final String normalizedUsername = username.toLowerCase();
        final Path blogDirPath = Path.of( pageDir, BLOG_DIR, normalizedUsername );
        return Files.isDirectory( blogDirPath );
    }

    /** {@inheritDoc} */
    @Override
    public BlogInfo getBlogInfo( final String username ) throws ProviderException {
        final String normalizedUsername = username.toLowerCase();
        final String blogPageName = BlogManager.blogPagePath( normalizedUsername, BLOG_HOME_PAGE );

        if ( !pageManager.wikiPageExists( blogPageName ) ) {
            return null;
        }

        // Read frontmatter for title and description
        final String content = pageManager.getPureText( blogPageName, PageProvider.LATEST_VERSION );
        final ParsedPage parsed = FrontmatterParser.parse( content );
        final Map< String, Object > metadata = parsed.metadata();

        final String title = metadata.getOrDefault( "title", normalizedUsername + "'s Blog" ).toString();
        final String description = metadata.containsKey( "description" )
            ? metadata.get( "description" ).toString()
            : "";

        // Count entries (excluding Blog.md and .properties files)
        final Path blogDirPath = Path.of( pageDir, BLOG_DIR, normalizedUsername );
        final int entryCount = countEntries( blogDirPath.toFile() );

        // Look up author full name from user account
        String authorFullName = normalizedUsername;
        try {
            final UserProfile profile = userManager.getUserDatabase().findByLoginName( normalizedUsername );
            if ( profile.getFullname() != null && !profile.getFullname().isEmpty() ) {
                authorFullName = profile.getFullname();
            }
        } catch ( final NoSuchPrincipalException e ) {
            LOG.debug( "No user profile found for blog owner {}", normalizedUsername );
        }

        return new BlogInfo( normalizedUsername, title, description, entryCount, authorFullName );
    }

    /** {@inheritDoc} */
    @Override
    public List< BlogInfo > listBlogs() throws ProviderException {
        final Path blogRootPath = Path.of( pageDir, BLOG_DIR );

        if ( !Files.isDirectory( blogRootPath ) ) {
            return List.of();
        }

        final File blogRoot = blogRootPath.toFile();
        final File[] userDirs = blogRoot.listFiles( File::isDirectory );

        if ( userDirs == null ) {
            return List.of();
        }

        final List< BlogInfo > blogs = new ArrayList<>();
        for ( final File userDir : userDirs ) {
            final BlogInfo info = getBlogInfo( userDir.getName() );
            if ( info != null ) {
                blogs.add( info );
            }
        }

        return blogs;
    }

    // ---- Private helpers ----

    /**
     * Lazy accessor for RenderingManager (initialized after BlogManager in the engine lifecycle).
     */
    private RenderingManager getRenderingManager() {
        return engine.getManager( RenderingManager.class );
    }

    /**
     * Extracts and lowercases the username from the session's login principal.
     */
    private String extractUsername( final Session session ) throws WikiException {
        final Principal loginPrincipal = session.getLoginPrincipal();
        if ( loginPrincipal == null ) {
            throw new WikiException( "Session has no login principal; user must be authenticated" );
        }
        final String name = loginPrincipal.getName();
        if ( name == null || name.isBlank() ) {
            throw new WikiException( "Session login principal has no name" );
        }
        return name.toLowerCase();
    }

    /**
     * Checks whether the session user holds the Admin role.
     */
    private boolean isAdmin( final Session session ) {
        final Principal[] roles = session.getRoles();
        if ( roles != null ) {
            for ( final Principal role : roles ) {
                if ( "Admin".equals( role.getName() ) ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Loads the blog template from the classpath and replaces the {@code {username}} placeholder.
     */
    private String loadTemplate( final String username ) throws WikiException {
        try ( final InputStream is = getClass().getResourceAsStream( TEMPLATE_RESOURCE ) ) {
            if ( is == null ) {
                throw new WikiException( "Blog template not found on classpath: " + TEMPLATE_RESOURCE );
            }
            final String template = new String( is.readAllBytes(), StandardCharsets.UTF_8 );
            return template.replace( "{username}", username );
        } catch ( final IOException e ) {
            throw new WikiException( "Failed to load blog template: " + e.getMessage(), e );
        }
    }

    /**
     * Converts a camelCase topic name into a space-separated title.
     * For example, "MyFirstPost" becomes "My First Post".
     */
    static String camelCaseToSpaced( final String camelCase ) {
        if ( camelCase == null || camelCase.isEmpty() ) {
            return camelCase;
        }

        final StringBuilder result = new StringBuilder();
        for ( int i = 0; i < camelCase.length(); i++ ) {
            final char ch = camelCase.charAt( i );
            if ( i > 0 && Character.isUpperCase( ch ) ) {
                result.append( ' ' );
            }
            result.append( ch );
        }
        return result.toString();
    }

    /**
     * Counts blog entry files in a user's blog directory (excluding Blog.md and .properties).
     */
    private int countEntries( final File userDir ) {
        final File[] files = userDir.listFiles();
        if ( files == null ) {
            return 0;
        }
        int count = 0;
        for ( final File file : files ) {
            if ( isBlogEntryFile( file ) ) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns {@code true} if the file is a blog entry (a {@code .md} or {@code .txt} file
     * that is not the Blog homepage and not a {@code .properties} file).
     */
    private static boolean isBlogEntryFile( final File file ) {
        if ( !file.isFile() ) {
            return false;
        }
        final String name = file.getName();
        if ( name.endsWith( ".properties" ) ) {
            return false;
        }
        if ( name.startsWith( BLOG_HOME_PAGE + "." ) ) {
            return false;
        }
        return name.endsWith( ".md" ) || name.endsWith( ".txt" );
    }

    /**
     * Recursively deletes a directory and all its contents.
     */
    private void deleteDirectoryRecursively( final Path directory ) throws IOException {
        if ( !Files.exists( directory ) ) {
            return;
        }
        try ( final Stream< Path > walk = Files.walk( directory ) ) {
            walk.sorted( Comparator.reverseOrder() )
                .forEach( path -> {
                    try {
                        Files.deleteIfExists( path );
                    } catch ( final IOException e ) {
                        LOG.warn( "Failed to delete {}: {}", path, e.getMessage() );
                    }
                } );
        }
    }
}
