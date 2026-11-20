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
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.exceptions.FrontmatterValidationException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The client-facing error shapes of a refused or failed page save: the 422 bodies for frontmatter, access-rule and
 * math validation failures, and the sanitized text of a 500. Shared by every save surface (page PUT/PATCH, blocks,
 * collab publish) through {@link PageResource}.
 */
final class PageSaveErrors {

    private PageSaveErrors() {
    }

    /** Builds the HTTP 422 body for a frontmatter validation failure: {@code { error, violations }}. */
    static Map< String, Object > validationErrorBody( final FrontmatterValidationException e ) {
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "error", "frontmatter_validation_failed" );
        body.put( "violations", e.violations() );
        return body;
    }

    /**
     * Builds the HTTP 422 body for a save that adds an unreadable access rule: {@code { error, violations, total }}, each
     * violation {@code { code: "ACCESS_RULE_SHAPE", shape, line | field, text, message }}: {@code line} is a line of the
     * body as the editor shows it, {@code field} the frontmatter field of a rule in the frontmatter. {@code violations}
     * holds at most {@link AccessRuleShapeException#MAX_VIOLATIONS}; {@code total} counts them all.
     */
    static Map< String, Object > accessRuleShapeErrorBody( final AccessRuleShapeException e ) {
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "error", "access_rule_shape" );
        body.put( "violations", e.violations() );
        body.put( "total", e.total() );
        return body;
    }

    /** Builds the HTTP 422 body for a math-content validation failure: {@code { error, violations }}. */
    static Map< String, Object > mathValidationErrorBody( final ContentValidationException e ) {
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "error", "math_validation_failed" );
        body.put( "violations", e.violations() );
        return body;
    }

    /**
     * D5: returns a client-safe error message for a failed page save. Stringifies the
     * cause for logging callers but never reveals filesystem paths, JDBC URLs, or
     * stack frames in the response body.
     */
    static String sanitizeSaveErrorMessage( final Throwable t ) {
        // Walk the cause chain looking for a recognisable validation reason; if we
        // find one, surface a short message. Otherwise fall back to a generic string
        // that hides absolute paths and class names.
        Throwable cur = t;
        while ( cur != null ) {
            if ( cur instanceof java.io.IOException ) {
                return "Error saving page: storage I/O failure (see server log).";
            }
            cur = cur.getCause();
        }
        // Fallback: trust only the top-level message and only if it doesn't look like
        // a path or class name.
        final String msg = t.getMessage();
        if ( msg == null || msg.isEmpty() ) {
            return "Error saving page: internal error (see server log).";
        }
        if ( msg.contains( "/" ) || msg.contains( "\\" ) || msg.contains( "." ) && msg.contains( "Exception" ) ) {
            return "Error saving page: internal error (see server log).";
        }
        return "Error saving page: " + msg;
    }
}
