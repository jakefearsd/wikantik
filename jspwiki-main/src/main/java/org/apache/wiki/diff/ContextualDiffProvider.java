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

package org.apache.wiki.diff;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.exceptions.NoRequiredPropertyException;
import org.apache.wiki.util.TextUtil;
import org.suigeneris.jrcs.diff.Diff;
import org.suigeneris.jrcs.diff.DifferentiationFailedException;
import org.suigeneris.jrcs.diff.Revision;
import org.suigeneris.jrcs.diff.RevisionVisitor;
import org.suigeneris.jrcs.diff.delta.AddDelta;
import org.suigeneris.jrcs.diff.delta.ChangeDelta;
import org.suigeneris.jrcs.diff.delta.Chunk;
import org.suigeneris.jrcs.diff.delta.DeleteDelta;
import org.suigeneris.jrcs.diff.delta.Delta;
import org.suigeneris.jrcs.diff.myers.MyersDiff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.stream.Collectors;


/**
 * A seriously better diff provider, which highlights changes word-by-word using CSS.
 *
 * Suggested by John Volkar.
 */
public class ContextualDiffProvider implements DiffProvider {

    private static final Logger LOG = LogManager.getLogger( ContextualDiffProvider.class );

    /**
     *  A jspwiki.properties value to define how many characters are shown around the change context.
     *  The current value is <tt>{@value}</tt>.
     */
    public static final String PROP_UNCHANGED_CONTEXT_LIMIT = "jspwiki.contextualDiffProvider.unchangedContextLimit";

    //TODO all of these publics can become jspwiki.properties entries...
    //TODO span title= can be used to get hover info...

    public boolean emitChangeNextPreviousHyperlinks = true;

    //Don't use spans here the deletion and insertions are nested in this...
    public static String CHANGE_START_HTML = ""; //This could be a image '>' for a start marker
    public static String CHANGE_END_HTML = ""; //and an image for an end '<' marker
    public static String DIFF_START = "<div class=\"diff-wikitext\">";
    public static String DIFF_END = "</div>";

    // Unfortunately we need to do dumb HTML here for RSS feeds.

    public static String INSERTION_START_HTML = "<font color=\"#8000FF\"><span class=\"diff-insertion\">";
    public static String INSERTION_END_HTML = "</span></font>";
    public static String DELETION_START_HTML = "<strike><font color=\"red\"><span class=\"diff-deletion\">";
    public static String DELETION_END_HTML = "</span></font></strike>";
    private static final String ANCHOR_PRE_INDEX = "<a name=\"change-";
    private static final String ANCHOR_POST_INDEX = "\" />";
    private static final String BACK_PRE_INDEX = "<a class=\"diff-nextprev\" title=\"Go to previous change\" href=\"#change-";
    private static final String BACK_POST_INDEX = "\">&lt;&lt;</a>";
    private static final String FORWARD_PRE_INDEX = "<a class=\"diff-nextprev\" title=\"Go to next change\" href=\"#change-";
    private static final String FORWARD_POST_INDEX = "\">&gt;&gt;</a>";
    public static String ELIDED_HEAD_INDICATOR_HTML = "<br/><br/><b>...</b>";
    public static String ELIDED_TAIL_INDICATOR_HTML = "<b>...</b><br/><br/>";
    public static String LINE_BREAK_HTML = "<br />";
    public static String ALTERNATING_SPACE_HTML = "&nbsp;";

    // This one, I will make property file based...
    private static final int LIMIT_MAX_VALUE = (Integer.MAX_VALUE /2) - 1;
    private int unchangedContextLimit = LIMIT_MAX_VALUE;


    /**
     *  Constructs this provider.
     */
    public ContextualDiffProvider()
    {}

    /**
     * @see org.apache.wiki.api.providers.WikiProvider#getProviderInfo()
     * 
     * {@inheritDoc}
     */
    @Override
    public String getProviderInfo()
    {
        return "ContextualDiffProvider";
    }

    /**
     * @see org.apache.wiki.api.providers.WikiProvider#initialize(org.apache.wiki.api.core.Engine, java.util.Properties)
     *      
     * {@inheritDoc}
     */
    @Override
    public void initialize( final Engine engine, final Properties properties) throws NoRequiredPropertyException, IOException {
        final String configuredLimit = properties.getProperty( PROP_UNCHANGED_CONTEXT_LIMIT, Integer.toString( LIMIT_MAX_VALUE ) );
        int limit = LIMIT_MAX_VALUE;
        try {
            limit = Integer.parseInt( configuredLimit );
        } catch( final NumberFormatException e ) {
            LOG.warn("Failed to parseInt " + PROP_UNCHANGED_CONTEXT_LIMIT + "=" + configuredLimit + " Will use a huge number as limit.", e );
        }
        unchangedContextLimit = limit;
    }



    /**
     * Do a colored diff of the two regions. This. is. serious. fun. ;-)
     *
     * @see org.apache.wiki.diff.DiffProvider#makeDiffHtml(Context, String, String)
     * 
     * {@inheritDoc}
     */
    @Override
    public synchronized String makeDiffHtml( final Context ctx, final String wikiOld, final String wikiNew ) {
        //
        // Sequencing handles lineterminator to <br /> and every-other consequtive space to a &nbsp;
        //
        final String[] alpha = sequence( TextUtil.replaceEntities( wikiOld ) );
        final String[] beta  = sequence( TextUtil.replaceEntities( wikiNew ) );

        final Revision rev;
        try {
            rev = Diff.diff( alpha, beta, new MyersDiff() );
        } catch( final DifferentiationFailedException dfe ) {
            LOG.error( "Diff generation failed", dfe );
            return "Error while creating version diff.";
        }

        final int revSize = rev.size();
        final StringBuffer sb = new StringBuffer();

        sb.append( DIFF_START );

        //
        // The MyersDiff is a bit dumb by converting a single line multi-word diff into a series
        // of Changes. The ChangeMerger pulls them together again...
        //
        final ChangeMerger cm = new ChangeMerger( sb, alpha, revSize );
        rev.accept( cm );
        cm.shutdown();
        sb.append( DIFF_END );
        return sb.toString();
    }

    /**
     * Take the string and create an array from it, split it first on newlines, making
     * sure to preserve the newlines in the elements, split each resulting element on
     * spaces, preserving the spaces.
     *
     * All this preseving of newlines and spaces is so the wikitext when diffed will have fidelity
     * to it's original form.  As a side affect we see edits of purely whilespace.
     */
    private String[] sequence( final String wikiText ) {
        final String[] linesArray = Diff.stringToArray( wikiText );
        final List< String > list = new ArrayList<>();
        for( final String line : linesArray ) {

            String lastToken = null;
            String token;
            // StringTokenizer might be discouraged but it still is perfect here...
            for( final StringTokenizer st = new StringTokenizer( line, " ", true ); st.hasMoreTokens(); ) {
                token = st.nextToken();

                if( " ".equals( lastToken ) && " ".equals( token ) ) {
                    token = ALTERNATING_SPACE_HTML;
                }

                list.add( token );
                lastToken = token;
            }

            list.add( LINE_BREAK_HTML ); // Line Break
        }

        return list.toArray( new String[ 0 ] );
    }

    /**
     * This helper class does the housekeeping for merging
     * our various changes down and also makes sure that the
     * whole change process is threadsafe by encapsulating
     * all necessary variables.
     */
    private final class ChangeMerger implements RevisionVisitor {
        private final StringBuffer sb;

        /** Keeping score of the original lines to process */
        private final int max;

        private int index;

        /** Index of the next element to be copied into the output. */
        private int firstElem;

        /** Link Anchor counter */
        private int count = 1;

        /** State Machine Mode */
        private int mode = -1; /* -1: Unset, 0: Add, 1: Del, 2: Change mode */

        /** Buffer to coalesce the changes together */
        private StringBuffer origBuf;

        private StringBuffer newBuf;

        /** Reference to the source string array */
        private final String[] origStrings;

        private ChangeMerger( final StringBuffer newSb, final String[] sourceStrings, final int maxVal ) {
            this.sb = newSb;
            this.origStrings = sourceStrings != null ? sourceStrings.clone() : null;
            this.max = maxVal;

            origBuf = new StringBuffer();
            newBuf = new StringBuffer();
        }

        private void updateState( final Delta delta ) {
            index++;
            final Chunk orig = delta.getOriginal();
            if( orig.first() > firstElem ) {
                // We "skip" some lines in the output.
                // So flush out the last Change, if one exists.
                flushChanges();

                // Allow us to "skip" large swaths of unchanged text, show a "limited" amound of
                // unchanged context so the changes are shown in
                if( ( orig.first() - firstElem ) > 2 * unchangedContextLimit ) {
                    if (firstElem > 0) {
                        final int endIndex = Math.min( firstElem + unchangedContextLimit, origStrings.length -1 );

                        sb.append(Arrays.stream(origStrings, firstElem, endIndex).collect(Collectors.joining("", "", ELIDED_TAIL_INDICATOR_HTML)));

                    }

                    sb.append( ELIDED_HEAD_INDICATOR_HTML );

                    final int startIndex = Math.max(orig.first() - unchangedContextLimit, 0);
                    sb.append(Arrays.stream(origStrings, startIndex, orig.first()).collect(Collectors.joining()));

                } else {
                    // No need to skip anything, just output the whole range...
                    sb.append(Arrays.stream(origStrings, firstElem, orig.first()).collect(Collectors.joining()));
                }
            }
            firstElem = orig.last() + 1;
        }

        @Override
        public void visit( final Revision rev ) {
            // GNDN (Goes nowhere, does nothing)
        }

        @Override
        public void visit( final AddDelta delta ) {
            updateState( delta );

            // We have run Deletes up to now. Flush them out.
            if( mode == 1 ) {
                flushChanges();
                mode = -1;
            }
            // We are in "neutral mode". Start a new Change
            if( mode == -1 ) {
                mode = 0;
            }

            // We are in "add mode".
            if( mode == 0 || mode == 2 ) {
                addNew( delta.getRevised() );
                mode = 1;
            }
        }

        @Override
        public void visit( final ChangeDelta delta ) {
            updateState( delta );

            // We are in "neutral mode". A Change might be merged with an add or delete.
            if( mode == -1 ) {
                mode = 2;
            }

            // Add the Changes to the buffers.
            addOrig( delta.getOriginal() );
            addNew( delta.getRevised() );
        }

        @Override
        public void visit( final DeleteDelta delta ) {
            updateState( delta );

            // We have run Adds up to now. Flush them out.
            if( mode == 0 ) {
                flushChanges();
                mode = -1;
            }
            // We are in "neutral mode". Start a new Change
            if( mode == -1 ) {
                mode = 1;
            }

            // We are in "delete mode".
            if( mode == 1 || mode == 2 ) {
                addOrig( delta.getOriginal() );
                mode = 1;
            }
        }

        public void shutdown() {
            index = max + 1; // Make sure that no hyperlink gets created
            flushChanges();

            if( firstElem < origStrings.length ) {
                // If there's more than the limit of the orginal left just emit limit and elided...
                if( ( origStrings.length - firstElem ) > unchangedContextLimit ) {
                    final int endIndex = Math.min( firstElem + unchangedContextLimit, origStrings.length -1 );
                    sb.append(Arrays.stream(origStrings, firstElem, endIndex).collect(Collectors.joining("", "", ELIDED_TAIL_INDICATOR_HTML)));

                } else {
                // emit entire tail of original...
                    sb.append(Arrays.stream(origStrings, firstElem, origStrings.length).collect(Collectors.joining()));
                }
            }
        }

        private void addOrig( final Chunk chunk ) {
            if( chunk != null ) {
                chunk.toString( origBuf );
            }
        }

        private void addNew( final Chunk chunk ) {
            if( chunk != null ) {
                chunk.toString( newBuf );
            }
        }

        private void flushChanges() {
            if( newBuf.length() + origBuf.length() > 0 ) {
                // This is the span element which encapsulates anchor and the change itself
                sb.append( CHANGE_START_HTML );

                // Do we want to have a "back link"?
                if( emitChangeNextPreviousHyperlinks && count > 1 ) {
                    sb.append( BACK_PRE_INDEX );
                    sb.append( count - 1 );
                    sb.append( BACK_POST_INDEX );
                }

                // An anchor for the change.
                if (emitChangeNextPreviousHyperlinks) {
                    sb.append( ANCHOR_PRE_INDEX );
                    sb.append( count++ );
                    sb.append( ANCHOR_POST_INDEX );
                }

                // ... has been added
                if( newBuf.length() > 0 ) {
                    sb.append( INSERTION_START_HTML );
                    sb.append( newBuf );
                    sb.append( INSERTION_END_HTML );
                }

                // .. has been removed
                if( origBuf.length() > 0 ) {
                    sb.append( DELETION_START_HTML );
                    sb.append( origBuf );
                    sb.append( DELETION_END_HTML );
                }

                // Do we want a "forward" link?
                if( emitChangeNextPreviousHyperlinks && (index < max) ) {
                    sb.append( FORWARD_PRE_INDEX );
                    sb.append( count ); // Has already been incremented.
                    sb.append( FORWARD_POST_INDEX );
                }

                sb.append( CHANGE_END_HTML );

                // Nuke the buffers.
                origBuf = new StringBuffer();
                newBuf = new StringBuffer();
            }

            // After a flush, everything is reset.
            mode = -1;
        }
    }

}
