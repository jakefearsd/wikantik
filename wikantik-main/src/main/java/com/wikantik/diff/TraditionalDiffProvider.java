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

package com.wikantik.diff;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.i18n.InternationalizationManager;
import com.wikantik.preferences.Preferences;
import com.wikantik.util.TextUtil;
import org.suigeneris.jrcs.diff.Diff;
import org.suigeneris.jrcs.diff.DifferentiationFailedException;
import org.suigeneris.jrcs.diff.Revision;
import org.suigeneris.jrcs.diff.RevisionVisitor;
import org.suigeneris.jrcs.diff.delta.AddDelta;
import org.suigeneris.jrcs.diff.delta.ChangeDelta;
import org.suigeneris.jrcs.diff.delta.Chunk;
import org.suigeneris.jrcs.diff.delta.DeleteDelta;
import org.suigeneris.jrcs.diff.myers.MyersDiff;

import java.io.IOException;
import java.text.ChoiceFormat;
import java.text.Format;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.Properties;
import java.util.ResourceBundle;


/**
 * This is the JSPWiki 'traditional' diff.  It uses an internal diff engine.
 */
public class TraditionalDiffProvider implements DiffProvider {

    private static final Logger LOG = LogManager.getLogger( TraditionalDiffProvider.class );
    private static final String CSS_DIFF_ADDED = "<tr><td class=\"diffadd\">";
    private static final String CSS_DIFF_REMOVED = "<tr><td class=\"diffrem\">";
    private static final String CSS_DIFF_UNCHANGED = "<tr><td class=\"diff\">";
    private static final String CSS_DIFF_CLOSE = "</td></tr>" + Diff.NL;

    /**
     *  Constructs the provider.
     */
    public TraditionalDiffProvider() {
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.api.providers.WikiProvider#getProviderInfo()
     */
    @Override
    public String getProviderInfo()
    {
        return "TraditionalDiffProvider";
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.api.providers.WikiProvider#initialize(com.wikantik.api.core.Engine, java.util.Properties)
     */
    @Override
    public void initialize( final Engine engine, final Properties properties ) throws NoRequiredPropertyException, IOException {
    }

    /**
     * Makes a diff using the BMSI utility package. We use our own diff printer,
     * which makes things easier.
     * 
     * @param ctx The WikiContext in which the diff should be made.
     * @param p1 The first string
     * @param p2 The second string.
     * 
     * @return Full HTML diff.
     */
    @Override
    public String makeDiffHtml( final Context ctx, final String p1, final String p2 ) {
        final String diffResult;

        try {
            final String[] first  = Diff.stringToArray(TextUtil.replaceEntities(p1));
            final String[] second = Diff.stringToArray(TextUtil.replaceEntities(p2));
            final Revision rev = Diff.diff(first, second, new MyersDiff());

            if( rev == null || rev.size() == 0 ) {
                // No difference
                return "";
            }

            final StringBuffer ret = new StringBuffer(rev.size() * 20); // Guessing how big it will become...

            ret.append( "<table class=\"diff\" border=\"0\" cellspacing=\"0\" cellpadding=\"0\">\n" );
            rev.accept( new RevisionPrint( ctx, ret ) );
            ret.append( "</table>\n" );

            return ret.toString();
        } catch( final DifferentiationFailedException e ) {
            diffResult = "makeDiff failed with DifferentiationFailedException";
            LOG.error( diffResult, e );
        }

        return diffResult;
    }


    private static final class RevisionPrint implements RevisionVisitor {

        private final StringBuffer result;
        private final Context  context;
        private final ResourceBundle rb;

        private RevisionPrint( final Context ctx, final StringBuffer sb ) {
            result = sb;
            context = ctx;
            rb = Preferences.getBundle( ctx, InternationalizationManager.CORE_BUNDLE );
        }

        @Override
        public void visit( final Revision rev ) {
            // GNDN (Goes nowhere, does nothing)
        }

        @Override
        public void visit( final AddDelta delta ) {
            final Chunk changed = delta.getRevised();
            print( changed, rb.getString( "diff.traditional.added" ) );
            changed.toString( result, CSS_DIFF_ADDED, CSS_DIFF_CLOSE );
        }

        @Override
        public void visit( final ChangeDelta delta ) {
            final Chunk changed = delta.getOriginal();
            print(changed, rb.getString( "diff.traditional.changed" ) );
            changed.toString( result, CSS_DIFF_REMOVED, CSS_DIFF_CLOSE );
            delta.getRevised().toString( result, CSS_DIFF_ADDED, CSS_DIFF_CLOSE );
        }

        @Override
        public void visit( final DeleteDelta delta ) {
            final Chunk changed = delta.getOriginal();
            print( changed, rb.getString( "diff.traditional.removed" ) );
            changed.toString( result, CSS_DIFF_REMOVED, CSS_DIFF_CLOSE );
        }

        private void print( final Chunk changed, final String type ) {
            result.append( CSS_DIFF_UNCHANGED );

            final String[] choiceString = {
               rb.getString("diff.traditional.oneline"),
               rb.getString("diff.traditional.lines")
            };
            final double[] choiceLimits = { 1, 2 };

            final MessageFormat fmt = new MessageFormat("");
            fmt.setLocale( Preferences.getLocale(context) );
            final ChoiceFormat cfmt = new ChoiceFormat( choiceLimits, choiceString );
            fmt.applyPattern( type );
            final Format[] formats = { NumberFormat.getInstance(), cfmt, NumberFormat.getInstance() };
            fmt.setFormats( formats );

            final Object[] params = { changed.first() + 1,
                                      changed.size(),
                                      changed.size() };
            result.append( fmt.format(params) );
            result.append( CSS_DIFF_CLOSE );
        }
    }

}
