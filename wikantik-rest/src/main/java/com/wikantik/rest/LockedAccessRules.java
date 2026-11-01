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

import com.wikantik.auth.acl.AccessRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why a page's access rules lock it (#104), as the page API and the admin locked-pages report show it to administrators.
 * The reasons quote the rule text, logins included, so this is for administrators only.
 */
final class LockedAccessRules {

    private LockedAccessRules() {
    }

    /**
     * @param rawText a page text, frontmatter included; may be {@code null}
     * @return {@code { locked: true, reasons: [{shape, line, text}] }} when {@link AccessRules#read} locks it, else
     *         {@code null}
     */
    static Map< String, Object > of( final String rawText ) {
        if ( !( AccessRules.read( rawText ) instanceof AccessRules.Locked locked ) ) {
            return null;
        }
        final List< Map< String, Object > > reasons = new ArrayList<>();
        for ( final AccessRules.Reason reason : locked.reasons() ) {
            final Map< String, Object > r = new LinkedHashMap<>();
            r.put( "shape", reason.shape().name() );
            r.put( "line", reason.line() );
            r.put( "text", reason.text() );
            reasons.add( r );
        }
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "locked", true );
        out.put( "reasons", reasons );
        return out;
    }
}
