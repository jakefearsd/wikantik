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
package org.apache.wiki.providers;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.exceptions.NoRequiredPropertyException;
import org.apache.wiki.api.exceptions.ProviderException;
import org.apache.wiki.api.providers.PageProvider;
import org.apache.wiki.api.providers.WikiProvider;
import org.apache.wiki.api.search.QueryItem;
import org.apache.wiki.api.search.SearchResult;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.search.SearchMatcher;
import org.apache.wiki.search.SearchResultComparator;
import org.apache.wiki.util.FileUtil;
import org.apache.wiki.util.TextUtil;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.SystemUtils;


/**
 *  Provides a simple directory based repository for Wiki pages.
 *  <P>
 *  All files have ".txt" appended to make life easier for those who insist on using Windows or other software which makes assumptions
 *  on the files contents based on its name.
 *  <p>
 *  This class functions as a superclass to all file based providers.
 *
 *  @since 2.1.21.
 */
public abstract class AbstractFileProvider implements PageProvider {

    private static final Logger LOG = LogManager.getLogger(AbstractFileProvider.class);
    private String pageDirectory = "/tmp/";
    protected String encoding;

    protected Engine engine;

    public static final String PROP_CUSTOMPROP_MAXLIMIT = "custom.pageproperty.max.allowed";
    public static final String PROP_CUSTOMPROP_MAXKEYLENGTH = "custom.pageproperty.key.length";
    public static final String PROP_CUSTOMPROP_MAXVALUELENGTH = "custom.pageproperty.value.length";

    public static final int DEFAULT_MAX_PROPLIMIT = 200;
    public static final int DEFAULT_MAX_PROPKEYLENGTH = 255;
    public static final int DEFAULT_MAX_PROPVALUELENGTH = 4096;

    /** This parameter limits the number of custom page properties allowed on a page */
    public static int MAX_PROPLIMIT = DEFAULT_MAX_PROPLIMIT;

    /**
     * This number limits the length of a custom page property key length. The default value here designed with future JDBC providers in mind.
     */
    public static int MAX_PROPKEYLENGTH = DEFAULT_MAX_PROPKEYLENGTH;

    /**
     * This number limits the length of a custom page property value length. The default value here designed with future JDBC providers in mind.
     */
    public static int MAX_PROPVALUELENGTH = DEFAULT_MAX_PROPVALUELENGTH;

    /** Name of the property that defines where page directories are. */
    public static final String PROP_PAGEDIR = "jspwiki.fileSystemProvider.pageDir";

    /**
     *  All files should have this extension to be recognized as JSPWiki files. We default to .txt, because that is probably easiest for
     *  Windows users, and guarantees correct handling.
     */
    public static final String FILE_EXT = ".txt";

    /**
     *  Markdown files should have this extension to be recognized as Markdown syntax files.
     */
    public static final String MARKDOWN_EXT = ".md";

    /** The default encoding. */
    public static final String DEFAULT_ENCODING = StandardCharsets.ISO_8859_1.toString();

    private boolean windowsHackNeeded;

    /**
     * Cache for page file extensions to avoid redundant filesystem existence checks.
     * Maps page name to file extension (".md" or ".txt").
     * This significantly reduces disk I/O as findPage() is called frequently.
     */
    private final ConcurrentHashMap<String, String> fileExtensionCache = new ConcurrentHashMap<>();

    /**
     *  {@inheritDoc}
     *  @throws FileNotFoundException If the specified page directory does not exist.
     *  @throws IOException In case the specified page directory is a file, not a directory.
     */
    @Override
    public void initialize( final Engine engine, final Properties properties ) throws NoRequiredPropertyException, IOException, FileNotFoundException {
        LOG.debug( "Initing FileSystemProvider" );
        pageDirectory = TextUtil.getCanonicalFilePathProperty( properties, PROP_PAGEDIR,
                                                          System.getProperty( "user.home" ) + File.separator + "jspwiki-files" );

        final File f = new File( pageDirectory );

        if( !f.exists() ) {
            if( !f.mkdirs() ) {
                throw new IOException( "Failed to create page directory " + f.getAbsolutePath() + " , please check property " + PROP_PAGEDIR );
            }
        } else {
            if( !f.isDirectory() ) {
                throw new IOException( "Page directory is not a directory: " + f.getAbsolutePath() );
            }
            if( !f.canWrite() ) {
                throw new IOException( "Page directory is not writable: " + f.getAbsolutePath() );
            }
        }

        this.engine = engine;
        encoding = properties.getProperty( Engine.PROP_ENCODING, DEFAULT_ENCODING );
        windowsHackNeeded = SystemUtils.IS_OS_WINDOWS;

        MAX_PROPLIMIT = TextUtil.getIntegerProperty( properties, PROP_CUSTOMPROP_MAXLIMIT, DEFAULT_MAX_PROPLIMIT );
        MAX_PROPKEYLENGTH = TextUtil.getIntegerProperty( properties, PROP_CUSTOMPROP_MAXKEYLENGTH, DEFAULT_MAX_PROPKEYLENGTH );
        MAX_PROPVALUELENGTH = TextUtil.getIntegerProperty( properties, PROP_CUSTOMPROP_MAXVALUELENGTH, DEFAULT_MAX_PROPVALUELENGTH );

        LOG.info( "Wikipages are read from '" + pageDirectory + "'" );
    }


    String getPageDirectory()
    {
        return pageDirectory;
    }

    private static final String[] WINDOWS_DEVICE_NAMES = {
        "con", "prn", "nul", "aux", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9"
    };

    /**
     *  This makes sure that the queried page name is still readable by the file system.  For example, all XML entities
     *  and slashes are encoded with the percent notation.
     *
     *  @param pagename The name to mangle
     *  @return The mangled name.
     */
    protected String mangleName( String pagename ) {
        pagename = TextUtil.urlEncode( pagename, encoding );
        pagename = TextUtil.replaceString( pagename, "/", "%2F" );

        //  Names which start with a dot must be escaped to prevent problems. Since we use URL encoding, this is invisible in our unescaping.
        if( pagename.startsWith( "." ) ) {
            pagename = "%2E" + pagename.substring( 1 );
        }

        if( windowsHackNeeded ) {
            final String pn = pagename.toLowerCase();
            final StringBuilder pagenameBuilder = new StringBuilder(pagename);
            for( final String windowsDeviceName : WINDOWS_DEVICE_NAMES ) {
                if( windowsDeviceName.equals( pn ) ) {
                    pagenameBuilder.insert(0, "$$$");
                }
            }
            pagename = pagenameBuilder.toString();
        }

        return pagename;
    }

    /**
     *  This makes the reverse of mangleName.
     *
     *  @param filename The filename to unmangle
     *  @return The unmangled name.
     */
    String unmangleName( String filename ) {
        // The exception should never happen.
        if( windowsHackNeeded && filename.startsWith( "$$$" ) && filename.length() > 3 ) {
            filename = filename.substring( 3 );
        }

        return TextUtil.urlDecode( filename, encoding );
    }

    /**
     *  Finds a Wiki page from the page repository. Checks for both .md (Markdown) and .txt (Wiki) extensions,
     *  with .md taking precedence if both exist.
     *  <p>
     *  Uses an internal cache to avoid redundant filesystem existence checks, which significantly
     *  improves performance for frequently accessed pages.
     *
     *  @param page The name of the page.
     *  @return A File to the page. Returns the file even if it doesn't exist (for creation purposes).
     */
    protected File findPage( final String page ) {
        final String mangledName = mangleName( page );

        // Check cache first to avoid filesystem calls
        final String cachedExtension = fileExtensionCache.get( page );
        if( cachedExtension != null ) {
            return new File( pageDirectory, mangledName + cachedExtension );
        }

        // Cache miss - check filesystem and cache result
        final File mdFile = new File( pageDirectory, mangledName + MARKDOWN_EXT );
        if( mdFile.exists() ) {
            fileExtensionCache.put( page, MARKDOWN_EXT );
            return mdFile;
        }

        // Check if .txt file exists - only cache if it does
        final File txtFile = new File( pageDirectory, mangledName + FILE_EXT );
        if( txtFile.exists() ) {
            fileExtensionCache.put( page, FILE_EXT );
        }

        // Fall back to .txt extension (traditional wiki syntax)
        return txtFile;
    }

    /**
     *  Determines the file extension for a given page based on which file exists.
     *  <p>
     *  Uses an internal cache to avoid redundant filesystem existence checks.
     *
     *  @param page The name of the page.
     *  @return The file extension (".md" or ".txt"), or ".txt" if neither exists (default for new pages).
     */
    protected String getPageFileExtension( final String page ) {
        // Check cache first
        final String cachedExtension = fileExtensionCache.get( page );
        if( cachedExtension != null ) {
            return cachedExtension;
        }

        // Cache miss - check filesystem
        final String mangledName = mangleName( page );
        final File mdFile = new File( pageDirectory, mangledName + MARKDOWN_EXT );

        if( mdFile.exists() ) {
            fileExtensionCache.put( page, MARKDOWN_EXT );
            return MARKDOWN_EXT;
        }

        // Check if .txt file exists - only cache if it does
        final File txtFile = new File( pageDirectory, mangledName + FILE_EXT );
        if( txtFile.exists() ) {
            fileExtensionCache.put( page, FILE_EXT );
        }

        return FILE_EXT;
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public boolean pageExists( final String page ) {
        return findPage( page ).exists();
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public boolean pageExists( final String page, final int version ) {
        return pageExists( page );
    }

    /**
     *  This implementation just returns the current version, as filesystem does not provide versioning information for now.
     *
     *  {@inheritDoc}
     */
    @Override
    public String getPageText( final String page, final int version ) throws ProviderException {
        return getPageText( page );
    }

    /**
     *  Read the text directly from the correct file.
     *  Uses BufferedInputStream for improved I/O performance.
     */
    private String getPageText( final String page ) {
        String result  = null;
        final File pagedata = findPage( page );
        if( pagedata.exists() ) {
            if( pagedata.canRead() ) {
                try( final InputStream in = new BufferedInputStream( Files.newInputStream( pagedata.toPath() ) ) ) {
                    result = FileUtil.readContents( in, encoding );
                } catch( final IOException e ) {
                    LOG.error( "Failed to read", e );
                }
            } else {
                LOG.warn( "Failed to read page '" + page + "' from '" + pagedata.getAbsolutePath() + "', possibly a permissions problem" );
            }
        } else {
            // This is okay.
            LOG.info( "New page '" + page + "'" );
        }

        return result;
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public void putPageText( final Page page, final String text ) throws ProviderException {
        // Determine the correct file extension based on markup syntax attribute
        String extension = FILE_EXT; // default to wiki syntax
        final String markupSyntax = page.getAttribute( Page.MARKUP_SYNTAX );
        if( "markdown".equals( markupSyntax ) ) {
            extension = MARKDOWN_EXT;
        }

        // If page already exists, use findPage to get the existing file
        // Otherwise, create a new file with the appropriate extension
        File file = findPage( page.getName() );
        if( !file.exists() ) {
            file = new File( pageDirectory, mangleName( page.getName() ) + extension );
        }

        try( final PrintWriter out = new PrintWriter( new OutputStreamWriter( Files.newOutputStream( file.toPath() ), encoding ) ) ) {
            out.print( text );
        } catch( final IOException e ) {
            LOG.error( "Saving failed", e );
        }

        // Update cache with the extension used for this page
        final String actualExtension = file.getName().endsWith( MARKDOWN_EXT ) ? MARKDOWN_EXT : FILE_EXT;
        fileExtensionCache.put( page.getName(), actualExtension );
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public Collection< Page > getAllPages()  throws ProviderException {
        LOG.debug("Getting all pages...");
        final var set = new ArrayList< Page >();
        final File wikipagedir = new File( pageDirectory );
        final File[] wikipages = wikipagedir.listFiles( new WikiFileFilter() );

        if( wikipages == null ) {
            LOG.error("Wikipages directory '" + pageDirectory + "' does not exist! Please check " + PROP_PAGEDIR + " in jspwiki.properties.");
            throw new ProviderException( "Page directory does not exist" );
        }

        for( final File wikipage : wikipages ) {
            final String wikiname = wikipage.getName();

            // Determine the extension and extract the base name
            int cutpoint;
            if( wikiname.endsWith( MARKDOWN_EXT ) ) {
                cutpoint = wikiname.lastIndexOf( MARKDOWN_EXT );
            } else {
                cutpoint = wikiname.lastIndexOf( FILE_EXT );
            }

            final Page page = getPageInfo( unmangleName( wikiname.substring( 0, cutpoint ) ), PageProvider.LATEST_VERSION );
            if( page == null ) {
                // This should not really happen.
                // FIXME: Should we throw an exception here?
                LOG.error( "Page " + wikiname + " was found in directory listing, but could not be located individually." );
                continue;
            }

            set.add( page );
        }

        return set;
    }

    /**
     *  Returns all pages that have been modified since the given date.
     *  When the date is {@code null} or represents epoch (time 0), this
     *  behaves identically to {@link #getAllPages()}.
     *
     *  @param date {@inheritDoc}
     *  @return {@inheritDoc}
     */
    @Override
    public Collection< Page > getAllChangedSince( final Date date ) {
        LOG.debug( "Getting all pages changed since {}", date );
        final var set = new ArrayList< Page >();
        final File wikipagedir = new File( pageDirectory );
        final File[] wikipages = wikipagedir.listFiles( new WikiFileFilter() );

        if( wikipages == null ) {
            LOG.error( "Wikipages directory '" + pageDirectory + "' does not exist! Please check " + PROP_PAGEDIR + " in jspwiki.properties." );
            return set;
        }

        final long sinceMillis = ( date == null ) ? 0L : date.getTime();

        for( final File wikipage : wikipages ) {
            // Skip files not modified since the cutoff date
            if( sinceMillis > 0L && wikipage.lastModified() < sinceMillis ) {
                continue;
            }

            final String wikiname = wikipage.getName();

            // Determine the extension and extract the base name
            int cutpoint;
            if( wikiname.endsWith( MARKDOWN_EXT ) ) {
                cutpoint = wikiname.lastIndexOf( MARKDOWN_EXT );
            } else {
                cutpoint = wikiname.lastIndexOf( FILE_EXT );
            }

            try {
                final Page page = getPageInfo( unmangleName( wikiname.substring( 0, cutpoint ) ), PageProvider.LATEST_VERSION );
                if( page == null ) {
                    LOG.error( "Page " + wikiname + " was found in directory listing, but could not be located individually." );
                    continue;
                }

                set.add( page );
            } catch( final ProviderException e ) {
                LOG.error( "Error getting page info for " + wikiname, e );
            }
        }

        return set;
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public int getPageCount() {
        final File wikipagedir = new File( pageDirectory );
        final File[] wikipages = wikipagedir.listFiles( new WikiFileFilter() );
        return wikipages != null ? wikipages.length : 0;
    }

    /**
     * Iterates through all WikiPages, matches them against the given query, and returns a Collection of SearchResult objects.
     *
     * {@inheritDoc}
     */
    @Override
    public Collection< SearchResult > findPages( final QueryItem[] query ) {
        final File wikipagedir = new File( pageDirectory );
        final var res = new TreeSet<>( new SearchResultComparator() );
        final SearchMatcher matcher = new SearchMatcher( engine, query );
        final File[] wikipages = wikipagedir.listFiles( new WikiFileFilter() );

        if( wikipages != null ) {
            for( final File wikipage : wikipages ) {
                final String filename = wikipage.getName();

                // Determine the extension and extract the base name
                int cutpoint;
                if( filename.endsWith( MARKDOWN_EXT ) ) {
                    cutpoint = filename.lastIndexOf( MARKDOWN_EXT );
                } else {
                    cutpoint = filename.lastIndexOf( FILE_EXT );
                }

                final String wikiname = unmangleName( filename.substring( 0, cutpoint ) );
                try( final InputStream input = new BufferedInputStream( Files.newInputStream( wikipage.toPath() ) ) ) {
                    final String pagetext = FileUtil.readContents( input, encoding );
                    final SearchResult comparison = matcher.matchPageContent( wikiname, pagetext );
                    if( comparison != null ) {
                        res.add( comparison );
                    }
                } catch( final IOException e ) {
                    LOG.error( "Failed to read " + filename, e );
                }
            }
        }

        return res;
    }

    /**
     *  Always returns the latest version, since FileSystemProvider
     *  does not support versioning.
     *
     *  {@inheritDoc}
     */
    @Override
    public Page getPageInfo( final String page, final int version ) throws ProviderException {
        final File file = findPage( page );
        if( !file.exists() ) {
            return null;
        }

        final Page p = Wiki.contents().page( engine, page );
        p.setLastModified( new Date( file.lastModified() ) );

        return p;
    }

    /**
     *  The FileSystemProvider provides only one version.
     *
     *  {@inheritDoc}
     */
    @Override
    public List< Page > getVersionHistory( final String page ) throws ProviderException {
        final var list = new ArrayList< Page >();
        list.add( getPageInfo( page, PageProvider.LATEST_VERSION ) );

        return list;
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
    public void deleteVersion( final String pageName, final int version ) throws ProviderException {
        if( version == WikiProvider.LATEST_VERSION ) {
            final File f = findPage( pageName );
            f.delete();
            // Invalidate cache since the page file no longer exists
            fileExtensionCache.remove( pageName );
        }
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public void deletePage( final String pageName ) throws ProviderException {
        final File f = findPage( pageName );
        f.delete();
        // Invalidate cache since the page file no longer exists
        fileExtensionCache.remove( pageName );
    }

    /**
     * Set the custom properties provided into the given page.
     *
     * @since 2.10.2
     */
    protected void setCustomProperties( final Page page, final Properties properties ) {
        final Enumeration< ? > propertyNames = properties.propertyNames();
    	while( propertyNames.hasMoreElements() ) {
            final String key = ( String )propertyNames.nextElement();
            if( !key.equals( Page.AUTHOR ) && !key.equals( Page.CHANGENOTE ) && !key.equals( Page.VIEWCOUNT ) ) {
                page.setAttribute( key, properties.get( key ) );
            }
    	}
    }

    /**
     * Get custom properties using {@link #addCustomProperties(Page, Properties)}, validate them using {@link #validateCustomPageProperties(Properties)}
     * and add them to default properties provided
     *
     * @since 2.10.2
     */
    protected void getCustomProperties( final Page page, final Properties defaultProperties ) throws IOException {
        final Properties customPageProperties = addCustomProperties( page, defaultProperties );
        validateCustomPageProperties( customPageProperties );
        defaultProperties.putAll( customPageProperties );
    }

    /**
     * By default all page attributes that start with "@" are returned as custom properties.
     * This can be overwritten by custom FileSystemProviders to save additional properties.
     * CustomPageProperties are validated by {@link #validateCustomPageProperties(Properties)}
     *
     * @since 2.10.2
     * @param page the current page
     * @param props the default properties of this page
     * @return default implementation returns empty Properties.
     */
    protected Properties addCustomProperties( final Page page, final Properties props ) {
        final Properties customProperties = new Properties();
        if( page != null ) {
            final Map< String, Object > atts = page.getAttributes();
            for( final String key : atts.keySet() ) {
                final Object value = atts.get( key );
                if( key.startsWith( "@" ) && value != null ) {
                    customProperties.put( key, value.toString() );
                }
            }

        }
        return customProperties;
    }

    /**
     * Default validation, validates that key and value is ASCII <code>StringUtils.isAsciiPrintable()</code> and within lengths set up in jspwiki-custom.properties.
     * This can be overwritten by custom FileSystemProviders to validate additional properties
     * See https://issues.apache.org/jira/browse/JSPWIKI-856
     * @since 2.10.2
     * @param customProperties the custom page properties being added
     */
    protected void validateCustomPageProperties( final Properties customProperties ) throws IOException {
    	// Default validation rules
        if( customProperties != null && !customProperties.isEmpty() ) {
            if( customProperties.size() > MAX_PROPLIMIT ) {
                throw new IOException( "Too many custom properties. You are adding " + customProperties.size() + ", but max limit is " + MAX_PROPLIMIT );
            }
            final Enumeration< ? > propertyNames = customProperties.propertyNames();
            while( propertyNames.hasMoreElements() ) {
                final String key = ( String )propertyNames.nextElement();
                final String value = ( String )customProperties.get( key );
                if( key != null ) {
                    if( key.length() > MAX_PROPKEYLENGTH ) {
                        throw new IOException( "Custom property key " + key + " is too long. Max allowed length is " + MAX_PROPKEYLENGTH );
                    }
                    if( !StringUtils.isAsciiPrintable( key ) ) {
                        throw new IOException( "Custom property key " + key + " is not simple ASCII!" );
                    }
                }
                if( value != null ) {
                    if( value.length() > MAX_PROPVALUELENGTH ) {
                        throw new IOException( "Custom property key " + key + " has value that is too long. Value=" + value + ". Max allowed length is " + MAX_PROPVALUELENGTH );
                    }
                    if( !StringUtils.isAsciiPrintable( value ) ) {
                        throw new IOException( "Custom property key " + key + " has value that is not simple ASCII! Value=" + value );
                    }
                }
            }
        }
    }

    /**
     * Invalidates the file extension cache entry for the specified page.
     * This should be called when a page is deleted, moved, or when an external
     * change to the filesystem is detected.
     *
     * @param pageName The name of the page to invalidate from the cache.
     */
    void invalidateFileExtensionCache( final String pageName ) {
        fileExtensionCache.remove( pageName );
    }

    /**
     * Clears the entire file extension cache.
     * This is primarily useful for testing purposes.
     */
    protected void clearFileExtensionCache() {
        fileExtensionCache.clear();
    }

    /**
     * Returns the current size of the file extension cache.
     * This is primarily useful for testing and monitoring purposes.
     *
     * @return The number of entries in the file extension cache.
     */
    protected int getFileExtensionCacheSize() {
        return fileExtensionCache.size();
    }

    /**
     *  A simple filter which filters only those filenames which correspond to the
     *  file extensions used (.txt for wiki syntax, .md for markdown syntax).
     */
    public static class WikiFileFilter implements FilenameFilter {
        /**
         *  {@inheritDoc}
         */
        @Override
        public boolean accept( final File dir, final String name ) {
            return name.endsWith( FILE_EXT ) || name.endsWith( MARKDOWN_EXT );
        }
    }

}
