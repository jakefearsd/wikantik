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
package com.wikantik.render;

import com.wikantik.api.core.Context;
import com.wikantik.parser.WikiDocument;
import com.wikantik.parser.markdown.BudgetedText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * The render seam's guard for #105: a parse budget that runs out while a parsed document is rendered never escapes the
 * render. The parse disarms its budget once it returns, so reaching the fallback is a defect, not a crafted page: it is
 * logged, the page's render cache is evicted (the cached document is the one that ran out), and the document's text is
 * parsed again, which goes through the parse's own fallbacks.
 */
final class RenderBudgetGuard {

    private static final Logger LOG = LogManager.getLogger( RenderBudgetGuard.class );

    /** A step of the render that may fail with an {@link IOException}. */
    @FunctionalInterface
    interface IoFunction< T, R > {
        R apply( T t ) throws IOException;
    }

    private RenderBudgetGuard() {
    }

    /**
     * @param context the render context
     * @param doc     the parsed document, perhaps shared through the document cache
     * @param render  renders a document to HTML
     * @param evict   evicts the page's render caches
     * @param reparse parses a page text again
     * @return the HTML of {@code doc}, or of a fresh parse of its text when rendering it ran out of budget
     * @throws IOException when rendering fails
     */
    static String render( final Context context, final WikiDocument doc, final IoFunction< WikiDocument, String > render,
                          final Runnable evict, final IoFunction< String, WikiDocument > reparse ) throws IOException {
        try {
            return render.apply( doc );
        } catch ( final BudgetedText.Exhausted e ) {
            final String page = context.getRealPage().getName();
            LOG.warn( "render budget: rendering the parsed document of page {} ran out of its parse budget ({}); "
                    + "parsing its text again", page, e.getMessage() );
            evict.run();
            final String pagedata = doc.getPageData();
            if ( pagedata == null ) {
                LOG.warn( "render budget: page {} has no text to parse again; rendered empty", page );
                return "";
            }
            return render.apply( reparse.apply( pagedata ) );
        }
    }
}
