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
package com.wikantik.rest;

import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.rest.CollabWire.Answer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * {@code POST /publish} past the permission check: the session's publish (section 7.3) with the save's warning sinks
 * cleared before and drained after (the save runs on this thread, as {@code PageResource.doPut}'s does), and its
 * answers on the wire: 200 {@code {pageVersion, warnings, mathWarnings}}; 422 with exactly {@link PageResource}'s
 * bodies for the save pipeline's validation refusals (E19); every other refusal by its reason.
 */
final class CollabPublish {

    private static final Logger LOG = LogManager.getLogger( CollabPublish.class );

    private CollabPublish() {
    }

    static Answer run( final CollabSession s, final String participantId, final String authorKey, final int version,
                       final String changeNote, final String markupSyntax, final String pageName,
                       final BiConsumer< Integer, String > observe ) {
        FrontmatterWarningSink.clear();
        ContentWarningSink.clear();
        final PublishResponse r;
        try {
            r = s.publish( participantId, authorKey, version, changeNote, markupSyntax );
        } catch( final RuntimeException e ) {
            LOG.error( "collab session {}: publish of {} by {} failed: {}", s.id(), pageName, participantId,
                e.getMessage(), e );
            clearSinks();
            observe.accept( 500, "writer-failed" );
            return CollabWire.reason( 500, "writer-failed" );
        }
        observe.accept( r.status(), r.reason() );
        if( r.status() == 200 ) {
            return CollabWire.published( r.pageVersion(), FrontmatterWarningSink.drain( pageName ),
                ContentWarningSink.drain() );
        }
        clearSinks();
        if( r.status() == 422 ) {
            return refused( s, pageName, r.failure() );
        }
        if( r.status() == 500 ) {
            LOG.error( "collab session {}: the save of {} failed: {}", s.id(), pageName,
                r.failure() == null ? "no cause" : r.failure().toString(), r.failure() );
        }
        return CollabWire.refusal( r.status(), r.reason(), r.detail(), r.pageVersion() );
    }

    private static void clearSinks() {
        FrontmatterWarningSink.clear();
        ContentWarningSink.clear();
    }

    /** The 422 body: PageResource's for the three validation refusals, else a reason (the cause is logged, not sent). */
    private static Answer refused( final CollabSession s, final String pageName, final Exception failure ) {
        if( failure instanceof FrontmatterValidationException e ) {
            return CollabWire.of( 422, PageResource.validationErrorBody( e ) );
        }
        if( failure instanceof AccessRuleShapeException e ) {
            return CollabWire.of( 422, PageResource.accessRuleShapeErrorBody( e ) );
        }
        if( failure instanceof ContentValidationException e ) {
            return CollabWire.of( 422, PageResource.mathValidationErrorBody( e ) );
        }
        LOG.warn( "collab session {}: the save pipeline refused {}: {}", s.id(), pageName,
            failure == null ? "no cause" : failure.toString() );
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "error", "save_refused" );
        body.put( CollabWire.REASON, "writer-refused" );
        body.put( CollabWire.MESSAGE, "The save pipeline refused the page." );
        return CollabWire.of( 422, body );
    }
}
