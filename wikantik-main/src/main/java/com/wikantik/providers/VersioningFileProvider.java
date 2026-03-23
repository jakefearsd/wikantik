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
package com.wikantik.providers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.InternalWikiException;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.util.FileUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 *  Versioning, file-system-backed {@link com.wikantik.api.providers.PageProvider}.
 *
 *  <p>This subclass of {@link AbstractFileProvider} adds full version history.  The current
 *  version of each page lives as a single file in the page directory (handled by the
 *  superclass), while previous versions are archived under an {@code OLD/} subdirectory
 *  with the following layout:</p>
 *  <PRE>
 *    Main.txt
 *    Foobar.txt
 *    OLD/
 *       Main/
 *          1.txt
 *          2.txt
 *          page.properties
 *       Foobar/
 *          page.properties
 *  </PRE>
 *
 *  <p>In this example, "Main" has three versions (1.txt, 2.txt, and the current Main.txt),
 *  while "Foobar" has just one version.</p>
 *
 *  <p>The {@code page.properties} file stores per-version metadata (author, changenote,
 *  markup syntax) keyed by version number (e.g. {@code 2.author=admin}).
 *  <b>DO NOT MODIFY IT BY HAND.</b></p>
 *
 *  <h3>Hook methods overridden from {@code AbstractFileProvider}</h3>
 *  <ul>
 *    <li>{@link #initialize(Engine, Properties)} -- calls {@code super} then creates the
 *        {@code OLD/} directory and initialises the property cache.</li>
 *    <li>{@link #putPageText(Page, String)} -- archives the current file into {@code OLD/},
 *        calls {@code super} to write the new content, then writes versioned metadata.</li>
 *    <li>{@link #getPageText(String, int)} -- for the latest version, delegates to
 *        {@code super}; for older versions, reads from the {@code OLD/} directory.</li>
 *    <li>{@link #getPageInfo(String, int)} -- for the latest version, calls {@code super}
 *        then enriches with versioned metadata; for older versions, reads directly from
 *        {@code OLD/}.</li>
 *    <li>{@link #getVersionHistory(String)} -- returns all versions in descending order.</li>
 *    <li>{@link #deleteVersion(String, int)} -- removes a specific version, promoting the
 *        previous version when the latest is deleted.</li>
 *    <li>{@link #deletePage(String)} -- calls {@code super} then removes the entire version
 *        directory under {@code OLD/}.</li>
 *    <li>{@link #pageExists(String, int)} -- checks the {@code OLD/} directory for
 *        historical versions.</li>
 *    <li>{@link #getAllPages()} -- calls {@code super} then re-fetches each page to include
 *        version numbers.</li>
 *    <li>{@link #movePage(String, String)} -- renames both the current file and the version
 *        directory.</li>
 *  </ul>
 *
 *  @see AbstractFileProvider
 *  @see FileSystemProvider
 */
public class VersioningFileProvider extends AbstractFileProvider {

    private static final Logger LOG = LogManager.getLogger( VersioningFileProvider.class );

    /** Name of the directory where the old versions are stored. */
    public static final String PAGEDIR = "OLD";

    /** Name of the property file which stores the metadata. */
    public static final String PROPERTYFILE = "page.properties";

    /** Property name for configuring the property cache size. Set to 0 for single-entry, -1 for no caching. */
    public static final String PROP_CACHE_SIZE = "wikantik.versioningFileProvider.cacheSize";

    /** Default property cache size. */
    public static final int DEFAULT_CACHE_SIZE = 100;

    private PropertyCacheStrategy propertyCache;

    /**
     *  {@inheritDoc}
     */
    @Override
    public void initialize( final Engine engine, final Properties properties ) throws NoRequiredPropertyException, IOException {
        super.initialize( engine, properties );
        // some additional sanity checks :
        final File oldpages = new File( getPageDirectory(), PAGEDIR );
        if( !oldpages.exists() ) {
            if( !oldpages.mkdirs() ) {
                throw new IOException( "Failed to create page version directory " + oldpages.getAbsolutePath() );
            }
        } else {
            if( !oldpages.isDirectory() ) {
                throw new IOException( "Page version directory is not a directory: " + oldpages.getAbsolutePath() );
            }
            if( !oldpages.canWrite() ) {
                throw new IOException( "Page version directory is not writable: " + oldpages.getAbsolutePath() );
            }
        }
        LOG.info( "Using directory " + oldpages.getAbsolutePath() + " for storing old versions of pages" );

        // Initialize property cache strategy based on configuration
        final int cacheSize = Integer.parseInt( properties.getProperty( PROP_CACHE_SIZE, String.valueOf( DEFAULT_CACHE_SIZE ) ) );
        if ( cacheSize < 0 ) {
            propertyCache = new NoOpPropertyCache();
            LOG.info( "Property caching disabled" );
        } else if ( cacheSize == 0 ) {
            propertyCache = new SingleEntryPropertyCache();
            LOG.info( "Using single-entry property cache" );
        } else {
            propertyCache = new LruPropertyCache( cacheSize );
            LOG.info( "Using LRU property cache with size {}", cacheSize );
        }
    }

    /**
     *  Returns the directory where the old versions of the pages
     *  are being kept.
     */
    private File findOldPageDir( final String page ) {
        if( page == null ) {
            throw new InternalWikiException( "Page may NOT be null in the provider!" );
        }
        final File oldpages = new File( getPageDirectory(), PAGEDIR );
        return new File( oldpages, mangleName( page ) );
    }

    /**
     *  Goes through the repository and decides which version is the newest one in that directory.
     *
     *  @return Latest version number in the repository, or -1, if there is no page in the repository.
     */

    // FIXME: This is relatively slow.
    /*
    private int findLatestVersion( String page )
    {
        File pageDir = findOldPageDir( page );

        String[] pages = pageDir.list( new WikiFileFilter() );

        if( pages == null )
        {
            return -1; // No such thing found.
        }

        int version = -1;

        for( int i = 0; i < pages.length; i++ )
        {
            int cutpoint = pages[i].indexOf( '.' );
            if( cutpoint > 0 )
            {
                String pageNum = pages[i].substring( 0, cutpoint );

                try
                {
                    int res = Integer.parseInt( pageNum );

                    if( res > version )
                    {
                        version = res;
                    }
                }
                catch( NumberFormatException e ) {} // It's okay to skip these.
            }
        }

        return version;
    }
*/
    private int findLatestVersion( final String page ) {
        int version = -1;

        try {
            final Properties props = getPageProperties( page );

            for( final Object propertyKey : props.keySet() ) {
                final String key = ( String )propertyKey;
                if( key.endsWith( ".author" ) ) {
                    final int cutpoint = key.indexOf( '.' );
                    if( cutpoint > 0 ) {
                        final String pageNum = key.substring( 0, cutpoint );

                        try {
                            final int res = Integer.parseInt( pageNum );

                            if( res > version ) {
                                version = res;
                            }
                        } catch( final NumberFormatException e ) {
                        } // It's okay to skip these.
                    }
                }
            }
        } catch( final IOException e ) {
            LOG.error( "Unable to figure out latest version - dying...", e );
        }

        return version;
    }

    /**
     *  Reads page properties from the file system.
     */
    private Properties getPageProperties( final String page ) throws IOException {
        final File propertyFile = new File( findOldPageDir(page), PROPERTYFILE );
        if( propertyFile.exists() ) {
            final long lastModified = propertyFile.lastModified();
            return propertyCache.get( page, lastModified, () -> loadPropertiesFromFile( propertyFile ) );
        }

        return new Properties(); // Returns an empty object
    }

    /**
     * Loads properties from a file. Used as a supplier for the cache strategy.
     */
    private Properties loadPropertiesFromFile( final File propertyFile ) {
        try( final InputStream in = new BufferedInputStream( Files.newInputStream( propertyFile.toPath() ) ) ) {
            final Properties props = new Properties();
            props.load( in );
            return props;
        } catch( final IOException e ) {
            LOG.error( "Failed to load properties from {}", propertyFile.getAbsolutePath(), e );
            return new Properties();
        }
    }

    /**
     *  Writes the page properties back to the file system.
     *  Note that it WILL overwrite any previous properties.
     */
    private void putPageProperties( final String page, final Properties properties ) throws IOException {
        final File propertyFile = new File( findOldPageDir(page), PROPERTYFILE );
        try( final OutputStream out = Files.newOutputStream( propertyFile.toPath() ) ) {
            properties.store( out, " JSPWiki page properties for "+page+". DO NOT MODIFY!" );
        }

        // Update cache with the new properties
        propertyCache.put( page, properties, propertyFile.lastModified() );
    }

    /**
     *  Figures out the real version number of the page and also checks for its existence.
     *
     *  @throws NoSuchVersionException if there is no such version.
     */
    private int realVersion( final String page, final int requestedVersion ) throws NoSuchVersionException {
        //  Quickly check for the most common case.
        if( requestedVersion == WikiProvider.LATEST_VERSION ) {
            return -1;
        }

        final int latest = findLatestVersion(page);

        if( requestedVersion == latest || (requestedVersion == 1 && latest == -1 ) ) {
            return -1;
        } else if( requestedVersion <= 0 || requestedVersion > latest ) {
            throw new NoSuchVersionException( "Requested version " + requestedVersion + ", but latest is " + latest );
        }

        return requestedVersion;
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public synchronized String getPageText( final String page, int version ) throws ProviderException {
        final File dir = findOldPageDir( page );

        version = realVersion( page, version );
        if( version == -1 ) {
            // We can let the FileSystemProvider take care of these requests.
            return super.getPageText( page, PageProvider.LATEST_VERSION );
        }

        final File pageFile = new File( dir, ""+version+FILE_EXT );
        if( !pageFile.exists() ) {
            throw new NoSuchVersionException("Version "+version+"does not exist.");
        }

        return readFile( pageFile );
    }


    // FIXME: Should this really be here?
    private String readFile( final File pagedata ) throws ProviderException {
        String result = null;
        if( pagedata.exists() ) {
            if( pagedata.canRead() ) {
                try( final InputStream in = new BufferedInputStream( Files.newInputStream( pagedata.toPath() ) ) ) {
                    result = FileUtil.readContents( in, encoding );
                } catch( final IOException e ) {
                    LOG.error("Failed to read", e);
                    throw new ProviderException("I/O error: "+e.getMessage());
                }
            } else {
                LOG.warn("Failed to read page from '"+pagedata.getAbsolutePath()+"', possibly a permissions problem");
                throw new ProviderException("I cannot read the requested page.");
            }
        } else {
            // This is okay.
            // FIXME: is it?
            LOG.info("New page");
        }

        return result;
    }

    // FIXME: This method has no rollback whatsoever.

    /*
      This is how the page directory should look like:

         version    pagedir       olddir
          none       empty         empty
           1         Main.txt (1)  empty
           2         Main.txt (2)  1.txt
           3         Main.txt (3)  1.txt, 2.txt
    */
    /**
     *  {@inheritDoc}
     */
    @Override
    public synchronized void putPageText( final Page page, final String text ) throws ProviderException {
        // This is a bit complicated.  We'll first need to copy the old file to be the newest file.
        final int  latest  = findLatestVersion( page.getName() );
        final File pageDir = findOldPageDir( page.getName() );
        if( !pageDir.exists() ) {
            pageDir.mkdirs();
        }

        try {
            // Copy old data to safety, if one exists.
            final File oldFile = findPage( page.getName() );

            // Figure out which version should the old page be? Numbers should always start at 1.
            // "most recent" = -1 ==> 1
            // "first"       = 1  ==> 2
            int versionNumber = (latest > 0) ? latest : 1;
            final boolean firstUpdate = (versionNumber == 1);

            if( oldFile != null && oldFile.exists() ) {
                final File pageFile = new File( pageDir, versionNumber + FILE_EXT );
                try( final InputStream in = new BufferedInputStream( Files.newInputStream( oldFile.toPath() ) );
                     final OutputStream out = new BufferedOutputStream( Files.newOutputStream( pageFile.toPath() ) ) ) {
                    FileUtil.copyContents( in, out );

                    // We need also to set the date, since we rely on this.
                    pageFile.setLastModified( oldFile.lastModified() );

                    // Kludge to make the property code to work properly.
                    versionNumber++;
                }
            }

            //  Let superclass handler writing data to a new version.
            super.putPageText( page, text );

            //  Finally, write page version data.
            // FIXME: No rollback available.
            final Properties props = getPageProperties( page.getName() );

            String authorFirst = null;
            // if the following file exists, we are NOT migrating from FileSystemProvider
            final File pagePropFile = new File(getPageDirectory() + File.separator + PAGEDIR + File.separator + mangleName(page.getName()) + File.separator + "page" + FileSystemProvider.PROP_EXT);
            if( firstUpdate && ! pagePropFile.exists() ) {
                // we might not yet have a versioned author because the old page was last maintained by FileSystemProvider
                final Properties props2 = getHeritagePageProperties( page.getName() );

                // remember the simulated original author (or something) in the new properties
                authorFirst = props2.getProperty( "1.author", "unknown" );
                props.setProperty( "1.author", authorFirst );
            }

            String newAuthor = page.getAuthor();
            if ( newAuthor == null ) {
                newAuthor = ( authorFirst != null ) ? authorFirst : "unknown";
            }
            page.setAuthor(newAuthor);
            props.setProperty( versionNumber + ".author", newAuthor );

            final String changeNote = page.getAttribute( Page.CHANGENOTE );
            if( changeNote != null ) {
                props.setProperty( versionNumber + ".changenote", changeNote );
            }

            // Store markup syntax for this version
            final String markupSyntax = page.getAttribute( Page.MARKUP_SYNTAX );
            if( markupSyntax != null ) {
                props.setProperty( versionNumber + ".markup.syntax", markupSyntax );
            }

            // Get additional custom properties from page and add to props
            getCustomProperties( page, props );
            putPageProperties( page.getName(), props );
        } catch( final IOException e ) {
            LOG.error( "Saving failed", e );
            throw new ProviderException("Could not save page text: "+e.getMessage());
        }
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public Page getPageInfo( final String page, final int version ) throws ProviderException {
        final int latest = findLatestVersion( page );
        final int realVersion;

        Page p = null;

        if( version == PageProvider.LATEST_VERSION || version == latest || (version == 1 && latest == -1) ) {
            //
            // Yes, we need to talk to the top level directory to get this version.
            //
            // I am listening to Press Play On Tape's guitar version of the good old C64 "Wizardry" -tune at this moment.
            // Oh, the memories...
            //
            realVersion = (latest >= 0) ? latest : 1;

            p = super.getPageInfo( page, PageProvider.LATEST_VERSION );

            if( p != null ) {
                p.setVersion( realVersion );
            }
        } else {
            // The file is not the most recent, so we'll need to find it from the deep trenches of the "OLD" directory structure.
            realVersion = version;
            final File dir = findOldPageDir( page );
            if( !dir.exists() || !dir.isDirectory() ) {
                return null;
            }

            final File file = new File( dir, version + FILE_EXT );
            if( file.exists() ) {
                p = Wiki.contents().page( engine, page );

                p.setLastModified( new Date( file.lastModified() ) );
                p.setVersion( version );
            }
        }

        //  Get author and other metadata information (Modification date has already been set.)
        if( p != null ) {
            try {
                final Properties props = getPageProperties( page );
                String author = props.getProperty( realVersion + ".author" );
                if( author == null ) {
                    // we might not have a versioned author because the old page was last maintained by FileSystemProvider
                    final Properties props2 = getHeritagePageProperties( page );
                    author = props2.getProperty( Page.AUTHOR );
                }
                if( author != null ) {
                    p.setAuthor( author );
                }

                final String changenote = props.getProperty( realVersion + ".changenote" );
                if( changenote != null ) {
                    p.setAttribute( Page.CHANGENOTE, changenote );
                }

                // Get markup syntax for this version, or infer from file extension
                String markupSyntax = props.getProperty( realVersion + ".markup.syntax" );
                if( markupSyntax == null ) {
                    // Infer from file extension of the current version
                    final String extension = getPageFileExtension( page );
                    if( MARKDOWN_EXT.equals( extension ) ) {
                        markupSyntax = "markdown";
                    } else {
                        markupSyntax = "markdown";
                    }
                }
                p.setAttribute( Page.MARKUP_SYNTAX, markupSyntax );

                // Set the props values to the page attributes
                setCustomProperties( p, props );
            } catch( final IOException e ) {
                LOG.error( "Cannot get author for page" + page + ": ", e );
            }
        }

        return p;
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public boolean pageExists( final String pageName, final int version ) {
        if (version == PageProvider.LATEST_VERSION || version == findLatestVersion( pageName ) ) {
            return pageExists(pageName);
        }

        final File dir = findOldPageDir( pageName );
        if( !dir.exists() || !dir.isDirectory() ) {
            return false;
        }

        return new File( dir, version + FILE_EXT ).exists();
    }

    /**
     *  {@inheritDoc}
     */
     // FIXME: Does not get user information.
    @Override
    public List< Page > getVersionHistory( final String page ) throws ProviderException {
        final var list = new ArrayList< Page >();
        final int latest = findLatestVersion( page );
        for( int i = latest; i > 0; i-- ) {
            final Page info = getPageInfo( page, i );
            if( info != null ) {
                list.add( info );
            }
        }

        return list;
    }

    /*
     * Support for migration of simple properties created by the FileSystemProvider when coming under Versioning management.
     * Simulate an initial version.
     */
    private Properties getHeritagePageProperties( final String page ) throws IOException {
        final File propertyFile = new File( getPageDirectory(), mangleName( page ) + FileSystemProvider.PROP_EXT );
        if ( propertyFile.exists() ) {
            final long lastModified = propertyFile.lastModified();
            // Use a special cache key prefix to distinguish heritage properties from regular versioned properties
            final String cacheKey = "heritage:" + page;
            return propertyCache.get( cacheKey, lastModified, () -> loadHeritageProperties( propertyFile, cacheKey, lastModified ) );
        }

        return new Properties(); // Returns an empty object
    }

    /**
     * Loads heritage properties from a file and transforms them for versioning compatibility.
     */
    private Properties loadHeritageProperties( final File propertyFile, final String cacheKey, final long lastModified ) {
        try( final InputStream in = new BufferedInputStream( Files.newInputStream( propertyFile.toPath() ) ) ) {
            final Properties props = new Properties();
            props.load( in );

            final String originalAuthor = props.getProperty( Page.AUTHOR );
            if ( originalAuthor != null && !originalAuthor.isEmpty() ) {
                // simulate original author as if already versioned
                props.setProperty( "1.author", originalAuthor );
            }

            return props;
        } catch( final IOException e ) {
            LOG.error( "Failed to load heritage properties from {}", propertyFile.getAbsolutePath(), e );
            return new Properties();
        }
    }

    /**
     *  Removes the relevant page directory under "OLD" -directory as well, but does not remove any extra subdirectories from it.
     *  It will only touch those files that it thinks to be WikiPages.
     *
     *  @param page {@inheritDoc}
     *  @throws {@inheritDoc}
     */
    // FIXME: Should log errors.
    @Override
    public void deletePage( final String page ) throws ProviderException {
        super.deletePage( page );
        final File dir = findOldPageDir( page );
        if( dir.exists() && dir.isDirectory() ) {
            final File[] files = dir.listFiles( new WikiFileFilter() );
            if( files == null ) {
                return;
            }
            for( final File file : files ) {
                file.delete();
            }

            final File propfile = new File( dir, PROPERTYFILE );
            if( propfile.exists() ) {
                propfile.delete();
            }

            dir.delete();
        }
    }

    /**
     *  {@inheritDoc}
     *
     *  Deleting versions has never really worked, JSPWiki assumes that version histories are "not gappy". Using deleteVersion() is
     *  definitely not recommended.
     */
    @Override
    public void deleteVersion( final String page, final int version ) throws ProviderException {
        final File dir = findOldPageDir( page );
        int latest = findLatestVersion( page );
        if( version == PageProvider.LATEST_VERSION ||
            version == latest ||
            (version == 1 && latest == -1) ) {
            //  Delete the properties
            try {
                final Properties props = getPageProperties( page );
                props.remove( ((latest > 0) ? latest : 1)+".author" );
                putPageProperties( page, props );
            } catch( final IOException e ) {
                LOG.error("Unable to modify page properties",e);
                throw new ProviderException("Could not modify page properties: " + e.getMessage());
            }

            // We can let the FileSystemProvider take care of the actual deletion
            super.deleteVersion( page, PageProvider.LATEST_VERSION );

            //  Copy the old file to the new location
            latest = findLatestVersion( page );

            final File pageDir = findOldPageDir( page );
            final File previousFile = new File( pageDir, latest + FILE_EXT );
            final File pageFile = findPage(page);
            try( final InputStream in = new BufferedInputStream( Files.newInputStream( previousFile.toPath() ) );
                 final OutputStream out = new BufferedOutputStream( Files.newOutputStream( pageFile.toPath() ) ) ) {
                if( previousFile.exists() ) {
                    FileUtil.copyContents( in, out );
                    // We need also to set the date, since we rely on this.
                    pageFile.setLastModified( previousFile.lastModified() );
                }
            } catch( final IOException e ) {
                LOG.fatal("Something wrong with the page directory - you may have just lost data!",e);
            }

            return;
        }

        final File pageFile = new File( dir, ""+version+FILE_EXT );
        if( pageFile.exists() ) {
            if( !pageFile.delete() ) {
                LOG.error("Unable to delete page." + pageFile.getPath() );
            }
        } else {
            throw new NoSuchVersionException("Page "+page+", version="+version);
        }
    }

    /**
     *  {@inheritDoc}
     */
    // FIXME: This is kinda slow, we should need to do this only once.
    @Override
    public Collection< Page > getAllPages() throws ProviderException {
        final Collection< Page > pages = super.getAllPages();
        final Collection< Page > returnedPages = new ArrayList<>();
        for( final Page page : pages ) {
            final Page info = getPageInfo( page.getName(), WikiProvider.LATEST_VERSION );
            returnedPages.add( info );
        }

        return returnedPages;
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public String getProviderInfo()
    {
        return "";
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public void movePage( final String from, final String to ) {
        // Move the file itself
        final File fromFile = findPage( from );
        final File toFile = findPage( to );
        fromFile.renameTo( toFile );

        // Move any old versions
        final File fromOldDir = findOldPageDir( from );
        final File toOldDir = findOldPageDir( to );
        fromOldDir.renameTo( toOldDir );

        // Invalidate file extension cache for both old and new page names
        invalidateFileExtensionCache( from );
        invalidateFileExtensionCache( to );

        // Invalidate property cache for both pages
        propertyCache.invalidate( from );
        propertyCache.invalidate( to );
    }

}
