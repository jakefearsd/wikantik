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
import com.wikantik.api.core.Attachment;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.event.WikiEventManager;
import com.wikantik.event.WikiPageRenameEvent;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.page.subsystem.lifecycle.PageLifecycle;
import com.wikantik.parser.MarkupParser;
import com.wikantik.util.TextUtil;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Provides page renaming functionality. Note that there used to be a similarly named class in 2.6, but due to unclear copyright, the
 * class was completely rewritten from scratch for 2.8.
 *
 * @since 2.8
 */
public class DefaultPageRenamer implements PageRenamer {

    private static final Logger LOG = LogManager.getLogger( DefaultPageRenamer.class );

    /** Pre-compiled pattern for CamelCase wiki words (avoids re-compilation on every rename). */
    private static final Pattern CAMELCASE_PATTERN =
            Pattern.compile( "\\p{Lu}+\\p{Ll}+\\p{Lu}+[\\p{L}\\p{Digit}]*" );

    /** Pre-compiled pattern for Markdown links: [text](target). */
    private static final Pattern MARKDOWN_LINK_PATTERN =
            Pattern.compile( "\\[([^\\]]*)]\\(([^)]*)\\)" );

    private boolean camelCase;
    
    /**
     *  Renames a page.
     *  
     *  @param context The current context.
     *  @param renameFrom The name from which to rename.
     *  @param renameTo The new name.
     *  @param changeReferrers If true, also changes all the referrers.
     *  @return The final new name (in case it had to be modified)
     *  @throws WikiException If the page cannot be renamed.
     */
    @Override
    public String renamePage( final Context context, final String renameFrom, final String renameTo, final boolean changeReferrers ) throws WikiException {
        //  Sanity checks first
        if( renameFrom == null || renameFrom.isEmpty() ) {
            throw new WikiException( "From name may not be null or empty" );
        }
        if( renameTo == null || renameTo.isEmpty() ) {
            throw new WikiException( "To name may not be null or empty" );
        }
       
        //  Clean up the "to" -name so that it does not contain anything illegal
        final String renameToClean = MarkupParser.cleanLink( renameTo.trim() );
        if( renameToClean.equals( renameFrom ) ) {
            throw new WikiException( "You cannot rename the page to itself" );
        }
        
        //  The move runs holding the old and the new name's save locks (RenameMove): a save of the old page cannot land
        //  between the read of its text and the re-write under the new name.
        final Engine engine = context.getEngine();
        final RenameMove.Moved moved = RenameMove.move( context, renameFrom, renameToClean,
                from -> getReferencesToChange( from, engine ) );
        final Page fromPage = moved.fromPage();
        final Page toPage = moved.toPage();
        final Set< String > referrers = moved.referrers();
        final Set< String > newNameReferrers = moved.newNameReferrers();

        //  Update the references
        com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge.fromLegacyEngine( engine ).referenceManager().pageRemoved( fromPage );
        com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge.fromLegacyEngine( engine ).referenceManager().updateReferences( toPage );

        //  Update referrers
        if( changeReferrers ) {
            updateReferrers( context, fromPage, toPage, referrers );
        }

        //  re-index the page including its attachments
        com.wikantik.search.subsystem.SearchSubsystemBridge.fromLegacyEngine( engine ).searchManager().reindexPage( toPage );

        final Collection< Attachment > attachmentsNewName = PageSubsystemBridge.fromLegacyEngine( engine ).attachments().listAttachments( toPage );
        for( final Attachment att:attachmentsNewName ) {
            final Page toAttPage = PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPage( att.getName() );
            // add reference to attachment under new page name
            com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge.fromLegacyEngine( engine ).referenceManager().updateReferences( toAttPage );
            com.wikantik.search.subsystem.SearchSubsystemBridge.fromLegacyEngine( engine ).searchManager().reindexPage( att );
        }

        RenameRenderEviction.evict( engine, renameFrom, renameToClean, referrers, newNameReferrers );
        firePageRenameEvent( renameFrom, renameToClean );

        //  Done, return the new name.
        return renameToClean;
    }

    /**
     * Fires a WikiPageRenameEvent to all registered listeners. Currently not used internally by JSPWiki itself, but you can use it for
     * something else.
     *
     * @param oldName the former page name
     * @param newName the new page name
     */
    @Override
    public void firePageRenameEvent( final String oldName, final String newName ) {
        if( WikiEventManager.isListening(this) ) {
            new com.wikantik.core.subsystem.DefaultWikiEventBus().fireEvent(this, new WikiPageRenameEvent(this, oldName, newName ) );
        }
    }

    /**
     *  This method finds all the pages which have anything to do with the fromPage and
     *  change any referrers it can figure out in that page.
     *  
     *  @param context WikiContext in which we operate
     *  @param fromPage The old page
     *  @param toPage The new page
     */
    private void updateReferrers( final Context context, final Page fromPage, final Page toPage, final Set< String > referrers ) {
        if( referrers.isEmpty() ) { // No referrers
            return;
        }

        final Engine engine = context.getEngine();
        final PageLifecycle lifecycle = PageSubsystemBridge.fromLegacyEngine( engine ).pageLifecycle();
        for( String pageName : referrers ) {
            //  In case the page was just changed from under us, let's do this small kludge.
            if( pageName.equals( fromPage.getName() ) ) {
                pageName = toPage.getName();
            }
            final String referrer = pageName;
            RenameReferrerWrite.write( engine, lifecycle, referrer, fromPage.getName() + " ==> " + toPage.getName(),
                    () -> rewriteReferrer( context, referrer, fromPage, toPage ) );
        }
    }

    /** Under the referrer's save lock: rewrites its links to the renamed page; the page written, or null when unchanged. */
    private Page rewriteReferrer( final Context context, final String pageName, final Page fromPage, final Page toPage )
            throws ProviderException {
        final Engine engine = context.getEngine();
        //  An unreadable referrer yields "" here, which no rewrite changes, so it is never written.
        final String sourceText = RenameTextReader.referrerText( engine, pageName );
        final Page referrerPage = PageSubsystemBridge.fromLegacyEngine( engine ).pages().getPage( pageName );
        String newText = replaceReferrerString(sourceText, fromPage.getName(), toPage.getName() );
        newText = rewriteWikiLinks( newText, fromPage.getName(), toPage.getName() );

        camelCase = TextUtil.getBooleanProperty( CoreSubsystemBridge.fromLegacyEngine( engine ).properties().asProperties(), MarkupParser.PROP_CAMELCASELINKS, camelCase );
        if( camelCase ) {
            newText = replaceCCReferrerString(newText, fromPage.getName(), toPage.getName() );
        }

        if( sourceText.equals( newText ) ) {
            return null;
        }
        referrerPage.setAttribute( Page.CHANGENOTE, fromPage.getName()+" ==> "+toPage.getName() );
        referrerPage.setAuthor( AuthorKeys.authorKeyFor( context.getWikiSession() ) );
        PageSubsystemBridge.fromLegacyEngine( engine ).pages().putPageText( referrerPage, newText );
        return referrerPage;
    }

    private Set<String> getReferencesToChange( final Page fromPage, final Engine engine ) {
        final Set< String > referrers = new TreeSet<>();
        referrers.addAll( com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge.fromLegacyEngine( engine ).referenceManager().findReferrers( fromPage.getName() ) );

        try {
            final List< Attachment > attachments = PageSubsystemBridge.fromLegacyEngine( engine ).attachments().listAttachments( fromPage );
            for( final Attachment att : attachments  ) {
                referrers.addAll( com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge.fromLegacyEngine( engine ).referenceManager().findReferrers( att.getName() ) );
            }
        } catch( final ProviderException e ) {
            // We will continue despite this error
            LOG.error( "Provider error while fetching attachments for rename", e );
        }
        return referrers;
    }

    /**
     *  Replaces camelcase links.
     */
    private String replaceCCReferrerString( final String sourceText, final String from, final String to ) {
        final StringBuilder sb = new StringBuilder( sourceText.length()+32 );
        final Matcher matcher = CAMELCASE_PATTERN.matcher( sourceText );
        int start = 0;
        
        while( matcher.find( start ) ) {
            final String match = matcher.group();
            sb.append( sourceText, start, matcher.start() );
            final int lastOpenBrace = sourceText.lastIndexOf( '[', matcher.start() );
            final int lastCloseBrace = sourceText.lastIndexOf( ']', matcher.start() );
            
            if( match.equals( from ) && lastCloseBrace >= lastOpenBrace ) {
                sb.append( to );
            } else {
                sb.append( match );
            }
            
            start = matcher.end();
        }
        
        sb.append( sourceText.substring( start ) );
        
        return sb.toString();
    }

    /** Rewrites native {@code [[ ]]} links and embeds naming {@code from}; see {@link WikiLinkRenamer}. */
    static String rewriteWikiLinks( final String text, final String from, final String to ) {
        return WikiLinkRenamer.rewrite( text, from, to );
    }

    private String replaceReferrerString( final String sourceText, final String from, final String to ) {
        final StringBuilder sb = new StringBuilder( sourceText.length() + 32 );

        final Matcher matcher = MARKDOWN_LINK_PATTERN.matcher( sourceText );
        int start = 0;

        while( matcher.find( start ) ) {
            String text = matcher.group( 1 );
            String link = matcher.group( 2 );

            link = replaceSingleLink( link, from, to );

            // Also update display text if it matches the old page name
            text = TextUtil.replaceString( text, from, to );

            sb.append( sourceText, start, matcher.start() );
            sb.append('[').append( text ).append( "](" ).append( link ).append(')');

            start = matcher.end();
        }

        sb.append( sourceText.substring( start ) );

        return sb.toString();
    }

    /**
     *  This method does a correct replacement of a single link, taking into account anchors and attachments.
     */
    private String replaceSingleLink(final String original, final String from, final String newlink ) {
        final int hash = original.indexOf( '#' );
        final int slash = original.indexOf( '/' );
        String realLink = original;

        if( hash != -1 ) {
            realLink = original.substring( 0, hash );
        }
        if( slash != -1 ) {
            realLink = original.substring( 0,slash );
        }

        realLink = MarkupParser.cleanLink( realLink );
        final String oldStyleRealLink = MarkupParser.wikifyLink( realLink );
        
        //WikiPage realPage  = context.getEngine().getPage( reallink );
        // WikiPage p2 = context.getEngine().getPage( from );

        //
        //  Yes, these point to the same page.
        //
        if( realLink.equals( from ) || original.equals( from ) || oldStyleRealLink.equals( from ) ) {
            return newlink + ( ( hash > 0 ) ? original.substring( hash ) : "" ) + ( ( slash > 0 ) ? original.substring( slash ) : "" ) ;
        }
        
        return original;
    }

}
