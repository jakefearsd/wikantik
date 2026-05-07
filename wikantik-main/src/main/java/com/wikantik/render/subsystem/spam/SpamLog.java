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
package com.wikantik.render.subsystem.spam;

import com.wikantik.InternalWikiException;
import com.wikantik.api.core.Context;
import com.wikantik.util.HttpUtil;
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared logging + UID utilities for the spam-filter subsystem.
 * Extracted in Phase 6 Checkpoint 3 of the wikantik-main decomposition to
 * avoid duplication across the four helper impls and the {@code SpamFilter} facade.
 */
public final class SpamLog {

    public static final int REJECT = 0;
    public static final int ACCEPT = 1;
    public static final int NOTE   = 2;

    private static final Logger C_SPAMLOG = LogManager.getLogger( "SpamLog" );
    private static final Random RANDOM    = ThreadLocalRandom.current();

    private SpamLog() { }

    /**
     * Writes a spam-log entry and returns a short unique incident ID.
     *
     * @param ctx     page context
     * @param type    {@link #REJECT}, {@link #ACCEPT}, or {@link #NOTE}
     * @param source  label for the detection source (e.g. "Regexp", "Akismet", "-")
     * @param message the change text or reason string
     * @return a 6-character uppercase incident ID
     */
    public static String log( final Context ctx, final int type, final String source, String message ) {
        message = TextUtil.replaceString( message, "\r\n", "\\r\\n" );
        message = TextUtil.replaceString( message, "\"", "\\\"" );
        final String uid  = uniqueID();
        final String page = ctx.getPage().getName();
        final String addr = ctx.getHttpRequest() != null ? HttpUtil.getRemoteAddress( ctx.getHttpRequest() ) : "-";
        final String reason = switch( type ) {
            case REJECT -> "REJECTED";
            case ACCEPT -> "ACCEPTED";
            case NOTE   -> "NOTE";
            default     -> throw new InternalWikiException( "Illegal type " + type );
        };
        C_SPAMLOG.info( "{} {} {} {} \"{}\" {}", reason, source, uid, addr, page, message );
        return uid;
    }

    /**
     * Returns a random six-character uppercase incident ID.
     */
    public static String uniqueID() {
        final StringBuilder sb = new StringBuilder();
        for( int i = 0; i < 6; i++ ) {
            final char randomChar = ( char )( 'A' + RANDOM.nextInt( 26 ) );
            sb.append( randomChar );
        }
        return sb.toString();
    }
}
