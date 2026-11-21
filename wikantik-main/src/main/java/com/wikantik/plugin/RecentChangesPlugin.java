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
import com.wikantik.WikiContext;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.attachment.Attachment;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.auth.identity.PublicIdentities;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.preferences.Preferences;
import com.wikantik.preferences.Preferences.TimeFormat;
import com.wikantik.util.TextUtil;
import com.wikantik.util.XHTML;
import com.wikantik.util.XhtmlUtil;
import org.jdom2.Element;
import java.util.Locale;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Map;


/**
 *  Returns the Recent Changes in the wiki being a date-sorted list of page names.
 *
 *  <p>Parameters: </p>
 *  <ul>
 *  <li><b>since</b> - show changes from the last n days, for example since=5 shows only the pages that were changed in the last five days</li>
 *  <li><b>format</b> - (full|compact) : if "full", then display a long version with all possible info. If "compact", then be as compact as possible.</li>
 *  <li><b>timeFormat</b> - the time format to use, the default is "HH:mm:ss"</li>
 *  <li><b>dateFormat</b> - the date format to use, the default is "dd.MM.yyyy"</li>
 *  </ul>
 *
 *  <p>Only pages the viewer of the hosting page may <em>view</em> are listed (an attachment follows its parent page's ACL).
 *  Because the listing therefore depends on who is looking, the render is flagged
 *  {@link Context#VAR_VIEWER_SENSITIVE} and is never stored in the shared render caches.</p>
 */
public class RecentChangesPlugin extends AbstractReferralPlugin implements Plugin {
	
    private static final Logger LOG = LogManager.getLogger( RecentChangesPlugin.class );
    
    /** Parameter name for the separator format.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_FORMAT      = "format";
    /** Parameter name for the separator timeFormat.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_TIME_FORMAT = "timeFormat";
    /** Parameter name for the separator dateFormat.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_DATE_FORMAT = "dateFormat";

    /** How many days we show by default. */
    private static final int   DEFAULT_DAYS = 100*365;
    public static final String DEFAULT_TIME_FORMAT ="HH:mm:ss";
    public static final String DEFAULT_DATE_FORMAT ="dd.MM.yyyy";


    /** How a listing is laid out: "full" shows author and change note, "compact" is as small as possible. */
    record Layout( String spacing, String tableWidth, boolean showAuthor, boolean showChangenote ) {
        static final Layout FULL = new Layout( "4", "4", true, true );
        static final Layout COMPACT = new Layout( "0", "2", false, false );

        static Layout of( final String format ) {
            return "compact".equals( format ) ? COMPACT : FULL;
        }
    }

    /** One change line: the page or attachment name cell and the time cell differ by kind. */
    sealed interface ChangeRow permits PageRow, AttachmentRow {
        Page page();

        /** The link cell, in the order the cell's children are emitted. */
        Element nameCell( Context context, Engine engine );

        /** The "last change" cell. */
        Element timeCell( Context context, DateFormat tfmt );

        static ChangeRow of( final Page page ) {
            return page instanceof Attachment ? new AttachmentRow( page ) : new PageRow( page );
        }

        default Element titleLink( final Context context, final Engine engine, final ContextEnum target ) {
            final String href = context.getURL( target.getRequestContext(), page().getName() );
            return XhtmlUtil.link( href, com.wikantik.render.subsystem.RenderingSubsystemBridge.fromLegacyEngine( engine )
                                                                                               .renderingManager().beautifyTitle( page().getName() ) );
        }
    }

    record PageRow( Page page ) implements ChangeRow {
        @Override
        public Element nameCell( final Context context, final Engine engine ) {
            final Element col = XhtmlUtil.element( XHTML.td );
            col.setAttribute( XHTML.ATTR_width, "30%" );
            col.addContent( titleLink( context, engine, ContextEnum.PAGE_VIEW ) );
            return col;
        }

        @Override
        public Element timeCell( final Context context, final DateFormat tfmt ) {
            final Element infocol = XhtmlUtil.element( XHTML.td );
            infocol.setAttribute( XHTML.ATTR_class, "lastchange" );
            infocol.addContent( XhtmlUtil.link( context.getURL( WikiContext.DIFF, page().getName(), "r1=-1" ),
                                                tfmt.format( page().getLastModified() ) ) );
            return infocol;
        }
    }

    record AttachmentRow( Page page ) implements ChangeRow {
        @Override
        public Element nameCell( final Context context, final Engine engine ) {
            final Element col = XhtmlUtil.element( XHTML.td );
            col.setAttribute( XHTML.ATTR_width, "30%" );
            col.addContent( titleLink( context, engine, ContextEnum.PAGE_ATTACH ) );

            // The direct link to the attachment info.
            final Element link = XhtmlUtil.link( context.getURL( WikiContext.INFO, page().getName() ), null );
            link.setAttribute( XHTML.ATTR_class, "infolink" );
            link.addContent( XhtmlUtil.img( context.getURL( WikiContext.NONE, "images/attachment_small.png" ), null ) );
            col.addContent( link );
            return col;
        }

        @Override
        public Element timeCell( final Context context, final DateFormat tfmt ) {
            final Element td = XhtmlUtil.element( XHTML.td, tfmt.format( page().getLastModified() ) );
            td.setAttribute( XHTML.ATTR_class, "lastchange" );
            return td;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String execute( final Context context, final Map< String, String > params ) throws PluginException {
        final int since = TextUtil.parseIntParameter( params.get( "since" ), DEFAULT_DAYS );
        final Engine engine = context.getEngine();

        // The listing is filtered by the viewer's view permission, so it differs per viewer; keep it out of the
        // shared, principal-less render caches (PluginContent also flags every executed plugin; this keeps the
        // guarantee local to the plugin for callers that reach it by another route).
        context.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE );

        final Layout layout = Layout.of( params.get( PARAM_FORMAT ) );

        final Calendar sincedate = new GregorianCalendar();
        sincedate.add( Calendar.DAY_OF_MONTH, -since );

        LOG.debug("Calculating recent changes from {}", sincedate.getTime());

        final Collection< Page > changes = visibleChanges( context, params, sincedate.getTime() );
        if( changes == null ) {
            return "";
        }

        Date olddate = new Date( 0 );
        final DateFormat fmt = getDateFormat( context, params );
        final DateFormat tfmt = getTimeFormat( context, params );
        final PublicIdentity identity = PublicIdentities.forEngine( engine );

        final Element rt = XhtmlUtil.element( XHTML.table );
        rt.setAttribute( XHTML.ATTR_class, "recentchanges" );
        rt.setAttribute( XHTML.ATTR_cellpadding, layout.spacing() );

        for( final Page pageref : changes ) {
            final Date lastmod = pageref.getLastModified();
            if( !isSameDay( lastmod, olddate ) ) {
                rt.addContent( dateHeaderRow( layout, fmt.format( lastmod ) ) );
                olddate = lastmod;
            }
            rt.addContent( row( context, engine, identity, layout, tfmt, ChangeRow.of( pageref ) ) );
        }
        return XhtmlUtil.serialize( rt, XhtmlUtil.EXPAND_EMPTY_NODES );
    }

    /** Changes since the given date that the viewer may view, minus system pages. */
    private Collection< Page > visibleChanges( final Context context, final Map< String, String > params, final Date since ) throws PluginException {
        final Engine engine = context.getEngine();
        Collection< Page > changes = PageSubsystemBridge.fromLegacyEngine( engine ).pages().getRecentChanges( since );
        super.initialize( context, params );
        // Cut down to the pages this viewer may view (the shared filter applies the view check).
        changes = filterWikiPageCollection( changes );

        final SystemPageRegistry systemPageRegistry = CoreSubsystemBridge.fromLegacyEngine( engine ).systemPageRegistry();
        if ( systemPageRegistry != null ) {
            changes = changes.stream()
                    .filter( p -> !systemPageRegistry.isSystemPage( p.getName() ) )
                    .toList();
        }
        return changes;
    }

    private static Element dateHeaderRow( final Layout layout, final String formattedDate ) {
        final Element row = XhtmlUtil.element( XHTML.tr );
        final Element col = XhtmlUtil.element( XHTML.td );
        col.setAttribute( XHTML.ATTR_colspan, layout.tableWidth() );
        col.setAttribute( XHTML.ATTR_class, "date" );
        col.addContent( XhtmlUtil.element( XHTML.b, formattedDate ) );
        row.addContent( col );
        return row;
    }

    private static Element row( final Context context, final Engine engine, final PublicIdentity identity, final Layout layout,
                                final DateFormat tfmt, final ChangeRow change ) {
        final Page pageref = change.page();
        final Element row = XhtmlUtil.element( XHTML.tr );
        row.addContent( change.nameCell( context, engine ) );
        row.addContent( change.timeCell( context, tfmt ) );

        if( layout.showAuthor() ) {
            final String author = pageref.getAuthor();
            final Element authorinfo = XhtmlUtil.element( XHTML.td );
            authorinfo.setAttribute( XHTML.ATTR_class, "author" );
            if( author != null ) {
                // The display name as plain text, never a link: the stored value is a login for pages saved
                // since 2.4.58 and a href built from it would hand it to every reader.
                authorinfo.addContent( IdentityDisplay.ofAuthor( identity, author ) );
            } else {
                authorinfo.addContent( Preferences.getBundle( context, InternationalizationManager.CORE_BUNDLE )
                        .getString( "common.unknownauthor" ) );
            }
            row.addContent( authorinfo );
        }

        if( layout.showChangenote() ) {
            // "verified by <login>" (mark_page_verified) names the verifier by display name; other notes as written.
            final String changenote = IdentityDisplay.changeNote( identity, pageref.getAttribute( Page.CHANGENOTE ) );
            final Element tdChangenote = XhtmlUtil.element( XHTML.td, changenote );
            tdChangenote.setAttribute( XHTML.ATTR_class, "changenote" );
            row.addContent( tdChangenote );
        }
        return row;
    }

    
    private boolean isSameDay( final Date a, final Date b ) {
        final Calendar aa = Calendar.getInstance(); aa.setTime( a );
        final Calendar bb = Calendar.getInstance(); bb.setTime( b );

        return aa.get( Calendar.YEAR ) == bb.get( Calendar.YEAR ) 
            && aa.get( Calendar.DAY_OF_YEAR ) == bb.get( Calendar.DAY_OF_YEAR );
    }
    

    // Note: the default behavior should be to return the default format for the default
    // locale, but that is at odds with the 1st version of this plugin. We seek to preserve the
    // behaviour of that first version, so to get the default format, the user must explicitly do
    // something like: dateFormat='' timeformat='' which is a odd, but probably okay.
    private DateFormat getTimeFormat( final Context context, final Map< String, String > params ) {
        final String formatString = get( params, DEFAULT_TIME_FORMAT, PARAM_TIME_FORMAT );
        if( StringUtils.isBlank( formatString ) ) {
            return Preferences.getDateFormat( context, TimeFormat.TIME );
        }

        return new SimpleDateFormat( formatString, Locale.ROOT );
    }

    private DateFormat getDateFormat( final Context context, final Map< String, String > params ) {
        final String formatString = get( params, DEFAULT_DATE_FORMAT, PARAM_DATE_FORMAT );
        if( StringUtils.isBlank( formatString ) ) {
            return Preferences.getDateFormat( context, TimeFormat.DATE );
        }

        return new SimpleDateFormat( formatString, Locale.ROOT );
    }
    
    private String get( final Map< String, String > params, final String defaultValue, final String paramName ) {
        final String value = params.get( paramName );
        return value == null ? defaultValue : value;
    }
    
}