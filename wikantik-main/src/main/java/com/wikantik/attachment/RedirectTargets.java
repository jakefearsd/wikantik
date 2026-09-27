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
package com.wikantik.attachment;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 *  Allowlist check for user-supplied redirect targets (the attachment servlet's {@code nextpage}).
 */
final class RedirectTargets {

    private static final Logger LOG = LogManager.getLogger( RedirectTargets.class );

    private RedirectTargets() {
    }

    /**
     *  Returns {@code nextPage} if it is a safe same-origin redirect target, else {@code errorPage}.
     *  Fixes [JSPWIKI-46].
     *
     *  <p>This is an ALLOWLIST on purpose: {@code engine.getBaseURL()} returns the servlet
     *  <em>context path</em>, which is {@code ""} for the ROOT deployment this app runs
     *  under in production — so a same-origin-prefix denylist check never rejects anything.
     *  A denylist also misses protocol-relative ({@code //evil.com}) and backslash-based
     *  ({@code /\evil.com}, {@code \\evil.com}) variants, since none of those contain
     *  {@code "://"}.
     *
     *  @param nextPage  the user-supplied redirect target; may be {@code null}
     *  @param errorPage the fallback target
     *  @return {@code nextPage} when safe, otherwise {@code errorPage}
     */
    static String validateNextPage( final String nextPage, final String errorPage ) {
        if( !isSameOriginRelativePath( nextPage ) ) {
            LOG.warn( "Detected phishing attempt by redirecting to an unsecure location: {}", nextPage );
            return errorPage;
        }
        return nextPage;
    }

    /**
     *  True when {@code value} is safe to use as a same-origin redirect target: a relative
     *  path that starts with exactly one {@code /} (not {@code //} or {@code /\}), contains
     *  no backslash, no scheme (no {@code :}), and no control character (including CR/LF,
     *  which could otherwise be used for response-header/log injection).
     *
     *  @param value the candidate redirect target; may be {@code null}
     *  @return {@code true} iff the value can only resolve to this origin
     */
    static boolean isSameOriginRelativePath( final String value ) {
        if( value == null || value.isEmpty() || value.charAt( 0 ) != '/' ) {
            return false;
        }
        if( value.length() > 1 && ( value.charAt( 1 ) == '/' || value.charAt( 1 ) == '\\' ) ) {
            return false;
        }
        if( value.indexOf( '\\' ) >= 0 || value.indexOf( ':' ) >= 0 ) {
            return false;
        }
        for( int i = 0; i < value.length(); i++ ) {
            if( Character.isISOControl( value.charAt( i ) ) ) {
                return false;
            }
        }
        return true;
    }
}
