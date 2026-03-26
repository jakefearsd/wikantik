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
package com.wikantik.plugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.StringTransmutator;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.pages.PageManager;
import com.wikantik.pages.PageSorter;
import com.wikantik.parser.MarkupParser;
import com.wikantik.parser.WikiDocument;
import com.wikantik.preferences.Preferences;
import com.wikantik.preferences.Preferences.TimeFormat;
import com.wikantik.render.RenderingManager;
import com.wikantik.util.TextUtil;
import com.wikantik.util.comparators.CollatorComparator;
import com.wikantik.util.comparators.HumanComparator;
import com.wikantik.util.comparators.JavaNaturalComparator;
import com.wikantik.util.comparators.LocaleComparator;

import java.io.IOException;
import java.text.Collator;
import java.text.ParseException;
import java.text.RuleBasedCollator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 *  This is a base class for all plugins using referral things.
 *
 *  <p>Parameters (also valid for all subclasses of this class) : </p>
 *  <ul>
 *  <li><b>maxwidth</b> - maximum width of generated links</li>
 *  <li><b>separator</b> - separator between generated links (wikitext)</li>
 *  <li><b>after</b> - output after the link</li>
 *  <li><b>before</b> - output before the link</li>
 *  <li><b>exclude</b> -  a regular expression of pages to exclude from the list. </li>
 *  <li><b>include</b> -  a regular expression of pages to include in the list. </li>
 *  <li><b>show</b> - value is either "pages" (default) or "count".  When "count" is specified, shows only the count
 *      of pages which match. (since 2.8)</li>
 *  <li><b>columns</b> - How many columns should the output be displayed on.</li>
 *  <li><b>showLastModified</b> - When show=count, shows also the last modified date. (since 2.8)</li>
 *  <li><b>sortOrder</b> - specifies the sort order for the resulting list.  Options are
 *  'human', 'java', 'locale' or a <code>RuleBasedCollator</code> rule string. (since 2.8.3)</li>
 *  </ul>
 *
 */
public abstract class AbstractReferralPlugin implements Plugin {

    private static final Logger LOG = LogManager.getLogger( AbstractReferralPlugin.class );

    /** Magic value for rendering all items. */
    public static final int    ALL_ITEMS              = -1;

    /** Parameter name for setting the maximum width.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_MAXWIDTH         = "maxwidth";

    /** Parameter name for the separator string.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_SEPARATOR        = "separator";

    /** Parameter name for the output after the link.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_AFTER            = "after";

    /** Parameter name for the output before the link.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_BEFORE           = "before";

    /** Parameter name for setting the list of excluded patterns.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_EXCLUDE          = "exclude";

    /** Parameter name for setting the list of included patterns.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_INCLUDE          = "include";

    /** Parameter name for the show parameter.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_SHOW             = "show";

    /** Parameter name for setting show to "pages".  Value is <tt>{@value}</tt>. */
    public static final String PARAM_SHOW_VALUE_PAGES = "pages";

    /** Parameter name for setting show to "count".  Value is <tt>{@value}</tt>. */
    public static final String PARAM_SHOW_VALUE_COUNT = "count";

    /** Parameter name for showing the last modification count.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_LASTMODIFIED     = "showLastModified";

    /** Parameter name for setting the number of columns that will be displayed by the plugin.  Value is <tt>{@value}</tt>. Available since 2.11.0. */
    public static final String PARAM_COLUMNS          = "columns";

    /** Parameter name for specifying the sort order.  Value is <tt>{@value}</tt>. */
    protected static final String PARAM_SORTORDER        = "sortOrder";
    protected static final String PARAM_SORTORDER_HUMAN  = "human";
    protected static final String PARAM_SORTORDER_JAVA   = "java";
    protected static final String PARAM_SORTORDER_LOCALE = "locale";

    /** Parameter name for including system pages in the output.  Value is <tt>{@value}</tt>.
     *  Default is false — system/template pages (LeftMenu, CSS themes, etc.) are excluded. */
    public static final String PARAM_INCLUDE_SYSTEM_PAGES = "includeSystemPages";

    protected int maxwidth = Integer.MAX_VALUE;
    protected String before = ""; // null not blank
    protected String separator = ""; // null not blank
    protected String after = "\\\\";
    protected int items;

    protected Pattern[]  exclude;
    protected Pattern[]  include;
    protected boolean includeSystemPages;
    protected PageSorter sorter;

    protected String show = "pages";
    protected boolean lastModified;
    // the last modified date of the page that has been last modified:
    protected Date dateLastModified = new Date(0);
    protected SimpleDateFormat dateFormat;

    protected Engine engine;
    protected boolean useWikiSyntax;

    /**
     * @param context the wiki context
     * @param params parameters for initializing the plugin
     * @throws PluginException if any of the plugin parameters are malformed
     */
    // FIXME: The compiled pattern strings should really be cached somehow.
    public void initialize( final Context context, final Map< String, String > params ) throws PluginException {
        dateFormat = Preferences.getDateFormat( context, TimeFormat.DATETIME );
        engine = context.getEngine();
        // Determine link syntax: use Markdown links unless the configured parser for this page is the legacy wiki-syntax parser
        final MarkupParser configuredParser = engine.getManager( RenderingManager.class ).getParser( context, "" );
        useWikiSyntax = !configuredParser.getClass().getName().contains( "MarkdownParser" );
        maxwidth = TextUtil.parseIntParameter( params.get( PARAM_MAXWIDTH ), Integer.MAX_VALUE );
        if( maxwidth < 0 ) {
            maxwidth = 0;
        }

        String s = params.get( PARAM_SEPARATOR );
        if( s != null ) {
            separator = TextUtil.replaceEntities( s );
            // pre-2.1.145 there was a separator at the end of the list
            // if they set the parameters, we use the new format of
            // before Item1 after separator before Item2 after separator before Item3 after
            after = "";
        }

        s = params.get( PARAM_BEFORE );
        if( s != null ) {
            before = s;
        }

        s = params.get( PARAM_AFTER );
        if( s != null ) {
            after = s;
        }

        s = params.get( PARAM_COLUMNS );
        if( s!= null ) {
            items = TextUtil.parseIntParameter( s, 0 );
        }

        exclude = compileGlobPatterns( params.get( PARAM_EXCLUDE ), PARAM_EXCLUDE );
        include = compileGlobPatterns( params.get( PARAM_INCLUDE ), PARAM_INCLUDE );
        includeSystemPages = TextUtil.isPositive( params.get( PARAM_INCLUDE_SYSTEM_PAGES ) );

        // LOG.debug( "Requested maximum width is "+maxwidth );
        s = params.get(PARAM_SHOW);
        if ( s != null ) {
            if ( s.equalsIgnoreCase( "count" ) ) {
                show = "count";
            }
        }

        s = params.get( PARAM_LASTMODIFIED );
        if ( s != null ) {
            if ( s.equalsIgnoreCase( "true" ) ) {
                if ( show.equals( "count" ) ) {
                    lastModified = true;
                } else {
                    throw new PluginException( "showLastModified=true is only valid if show=count is also specified" );
                }
            }
        }

        initSorter( context, params );
    }

    protected List< Page > filterWikiPageCollection( final Collection< Page > pages ) {
        final List< String > pageNames = filterCollection( pages.stream()
                                                                .map( Page::getName )
                                                                .toList() );
        return pages.stream()
                    .filter( wikiPage -> pageNames.contains( wikiPage.getName() ) )
                    .toList();
    }

    /**
     *  Filters a collection according to the include and exclude parameters.
     *
     *  @param c The collection to filter.
     *  @return A filtered collection.
     */
    protected List< String > filterCollection( final Collection< String > c ) {
        final SystemPageRegistry spr = engine.getManager( SystemPageRegistry.class );
        final var result = new ArrayList< String >();
        for( final String pageName : c ) {
            // Filter system/template pages (LeftMenu, CSS themes, etc.) unless explicitly requested
            if ( !includeSystemPages && spr != null && spr.isSystemPage( pageName ) ) {
                continue;
            }
            //
            //  If include parameter exists, then by default we include only those
            //  pages in it (excluding the ones in the exclude pattern list).
            //
            //  include='*' means the same as no include.
            //
            boolean includeThis = include.length == 0;

            if( include.length > 0 ) {
                includeThis = Arrays.stream(include).anyMatch(pattern -> pattern.matcher(pageName).matches());
            }

            if( exclude.length > 0 ) {
                // The inner loop, continue on the next item
                if (Arrays.stream(exclude).anyMatch(pattern -> pattern.matcher(pageName).matches())) {
                    includeThis = false;
                }
            }

            if( includeThis ) {
                result.add( pageName );
                //  if we want to show the last modified date of the most recently change page, we keep a "high watermark" here:
                final Page page;
                if( lastModified ) {
                    page = engine.getManager( PageManager.class ).getPage( pageName );
                    if( page != null ) {
                        final Date lastModPage = page.getLastModified();
                        LOG.debug( "lastModified Date of page {} : {}", pageName, dateLastModified );
                        if( lastModPage.after( dateLastModified ) ) {
                            dateLastModified = lastModPage;
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * Converts a glob pattern to a regex pattern.
     * @param glob The glob pattern
     * @return The equivalent regex pattern
     */
    protected static String globToRegex( final String glob ) {
        final StringBuilder regex = new StringBuilder();
        for( int i = 0; i < glob.length(); i++ ) {
            final char c = glob.charAt( i );
            switch( c ) {
                case '*': regex.append( ".*" ); break;
                case '?': regex.append( "." ); break;
                case '.': regex.append( "\\." ); break;
                case '\\': regex.append( "\\\\" ); break;
                case '(': regex.append( "\\(" ); break;
                case ')': regex.append( "\\)" ); break;
                case '[': regex.append( "\\[" ); break;
                case ']': regex.append( "\\]" ); break;
                case '{': regex.append( "\\{" ); break;
                case '}': regex.append( "\\}" ); break;
                case '^': regex.append( "\\^" ); break;
                case '$': regex.append( "\\$" ); break;
                case '+': regex.append( "\\+" ); break;
                case '|': regex.append( "\\|" ); break;
                default: regex.append( c );
            }
        }
        return regex.toString();
    }

    /**
     * Compiles a comma-separated list of glob patterns into an array of {@link Pattern}s.
     *
     * @param csv       the comma-separated glob patterns, or {@code null}
     * @param paramName the parameter name (for error messages)
     * @return compiled patterns; empty array if {@code csv} is {@code null}
     * @throws PluginException if any pattern has invalid syntax
     */
    private Pattern[] compileGlobPatterns( final String csv, final String paramName ) throws PluginException {
        if ( csv == null ) {
            return new Pattern[0];
        }
        try {
            final String[] ptrns = StringUtils.split( csv, "," );
            final Pattern[] compiled = new Pattern[ ptrns.length ];
            for ( int i = 0; i < ptrns.length; i++ ) {
                compiled[ i ] = Pattern.compile( globToRegex( ptrns[ i ] ) );
            }
            return compiled;
        } catch ( final PatternSyntaxException e ) {
            throw new PluginException( paramName + "-parameter has a malformed pattern: " + e.getMessage() );
        }
    }

    /**
     *  Filters and sorts a collection according to the include and exclude parameters.
     *
     *  @param c The collection to filter.
     *  @return A filtered and sorted collection.
     */
    protected List< String > filterAndSortCollection( final Collection< String > c ) {
        final List< String > result = filterCollection( c );
        result.sort( sorter );
        return result;
    }

    /**
     *  Makes WikiText from a Collection.
     *
     *  @param links Collection to make into WikiText.
     *  @param separator Separator string to use.
     *  @param numItems How many items to show.
     *  @return The WikiText
     */
    protected String wikitizeCollection( final Collection< String > links, final String separator, final int numItems ) {
        if( links == null || links.isEmpty() ) {
            return "";
        }

        final StringBuilder output = new StringBuilder();
        final boolean useMarkdownLinks = !useWikiSyntax;

        // In Markdown mode the default JSPWiki line-break separator ("\\") renders as a literal
        // backslash.  When the caller has not overridden before/after, switch to Markdown bullet
        // list syntax so items appear as a proper <ul> list.
        final String effectiveBefore;
        final String effectiveAfter;
        if( useMarkdownLinks && "".equals( before ) && "\\\\".equals( after ) ) {
            effectiveBefore = "* ";
            effectiveAfter  = "\n";
        } else {
            effectiveBefore = before;
            effectiveAfter  = after;
        }

        final Iterator< String > it = links.iterator();
        int count = 0;

        //  The output will be B Item[1] A S B Item[2] A S B Item[3] A
        while( it.hasNext() && ( (count < numItems) || ( numItems == ALL_ITEMS ) ) ) {
            final String value = it.next();
            if( count > 0 ) {
                output.append( effectiveAfter );
                output.append( separator );
            }

            output.append( effectiveBefore );

            final String title = engine.getManager( RenderingManager.class ).beautifyTitle( value );
            if( useMarkdownLinks ) {
                // Markdown link: [title](pageName)
                output.append( "[" ).append( title ).append( "](" ).append( value ).append( ")" );
            } else {
                // Legacy wiki link: [title|pageName]
                output.append( "[" ).append( title ).append( "|" ).append( value ).append( "]" );
            }
            count++;
        }

        //  Output final item - if there have been none, no "after" is printed
        if( count > 0 ) {
            output.append( effectiveAfter );
        }

        return output.toString();
    }

    /**
     *  Makes HTML with common parameters.
     *
     *  @param context The WikiContext
     *  @param wikitext The wikitext to render
     *  @return HTML
     *  @since 1.6.4
     */
    protected String makeHTML( final Context context, final String wikitext ) {
        String result = "";

        final RenderingManager mgr = engine.getManager( RenderingManager.class );

        try {
            final MarkupParser parser = mgr.getParser( context, wikitext );
            parser.addLinkTransmutator( new CutMutator( maxwidth ) );
            parser.enableImageInlining( false );

            final WikiDocument doc = parser.parse();
            result = mgr.getHTML( context, doc );
        } catch( final IOException e ) {
            LOG.error("Failed to convert page data to HTML", e);
        }

        return result;
    }

    protected String applyColumnsStyle( final String result ) {
        if( items > 1 ) {
            return "<div style=\"columns:" + items + ";" +
                                "-moz-columns:" + items + ";" +
                                "-webkit-columns:" + items + ";" + "\">"
                    + result + "</div>";
        }
        return result;
    }

    /**
     *  A simple class that just cuts a String to a maximum
     *  length, adding three dots after the cutpoint.
     */
    private static class CutMutator implements StringTransmutator {

        private final int length;

        public CutMutator( final int length ) {
            this.length = length;
        }

        @Override
        public String mutate( final Context context, final String text ) {
            if( text.length() > length ) {
                return text.substring( 0, length ) + "...";
            }

            return text;
        }
    }

    /**
     * Helper method to initialize the comparator for this page.
     */
    private void initSorter( final Context context, final Map< String, String > params ) {
        final String order = params.get( PARAM_SORTORDER );
        if( order == null || order.isEmpty() ) {
            // Use the configured comparator
            sorter = context.getEngine().getManager( PageManager.class ).getPageSorter();
        } else if( order.equalsIgnoreCase( PARAM_SORTORDER_JAVA ) ) {
            // use Java "natural" ordering
            sorter = new PageSorter( JavaNaturalComparator.DEFAULT_JAVA_COMPARATOR );
        } else if( order.equalsIgnoreCase( PARAM_SORTORDER_LOCALE ) ) {
            // use this locale's ordering
            sorter = new PageSorter( LocaleComparator.DEFAULT_LOCALE_COMPARATOR );
        } else if( order.equalsIgnoreCase( PARAM_SORTORDER_HUMAN ) ) {
            // use human ordering
            sorter = new PageSorter( HumanComparator.DEFAULT_HUMAN_COMPARATOR );
        } else {
            try {
                final Collator collator = new RuleBasedCollator( order );
                collator.setStrength( Collator.PRIMARY );
                sorter = new PageSorter( new CollatorComparator( collator ) );
            } catch( final ParseException pe ) {
                LOG.info( "Failed to parse requested collator - using default ordering", pe );
                sorter = context.getEngine().getManager( PageManager.class ).getPageSorter();
            }
        }
    }

}
