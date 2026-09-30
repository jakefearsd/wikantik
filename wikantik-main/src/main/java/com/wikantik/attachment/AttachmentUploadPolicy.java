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

import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.util.TextUtil;

import java.util.Locale;
import java.util.Properties;

/**
 * The operator's attachment upload policy — {@code wikantik.attachment.maxsize}, {@code .allowed},
 * {@code .forbidden} — applied at every user-upload entry point ({@link AttachmentServlet} and the
 * REST {@code AttachmentResource}). Deliberately not applied inside {@code storeAttachment}, so
 * connector/ingest syncs that store source attachments are not subject to an upload policy.
 * Extension patterns are case-insensitive suffixes; a forbidden match always rejects; an empty
 * allow list allows every non-forbidden name.
 */
public final class AttachmentUploadPolicy {

    private final String[] allowedPatterns;
    private final String[] forbiddenPatterns;
    private final long maxSize;

    public AttachmentUploadPolicy( final String[] allowedPatterns, final String[] forbiddenPatterns, final long maxSize ) {
        this.allowedPatterns = allowedPatterns != null ? lower( allowedPatterns ) : new String[ 0 ];
        this.forbiddenPatterns = forbiddenPatterns != null ? lower( forbiddenPatterns ) : new String[ 0 ];
        this.maxSize = maxSize;
    }

    public static AttachmentUploadPolicy fromProperties( final Properties props ) {
        return new AttachmentUploadPolicy(
                patterns( TextUtil.getStringProperty( props, AttachmentManager.PROP_ALLOWEDEXTENSIONS, null ) ),
                patterns( TextUtil.getStringProperty( props, AttachmentManager.PROP_FORBIDDENEXTENSIONS, null ) ),
                TextUtil.getIntegerProperty( props, AttachmentManager.PROP_MAXSIZE, Integer.MAX_VALUE ) );
    }

    private static String[] patterns( final String value ) {
        if ( value == null || value.isBlank() ) {
            return new String[ 0 ];
        }
        return value.trim().split( "\\s+" );
    }

    private static String[] lower( final String[] in ) {
        final String[] out = new String[ in.length ];
        for ( int i = 0; i < in.length; i++ ) {
            out[ i ] = in[ i ] == null ? "" : in[ i ].toLowerCase( Locale.ROOT );
        }
        return out;
    }

    public long maxSize() {
        return maxSize;
    }

    public boolean isSizeAllowed( final long size ) {
        return size <= maxSize;
    }

    public boolean isTypeAllowed( final String name ) {
        if ( name == null || name.isEmpty() ) {
            return false;
        }
        final String lower = name.toLowerCase( Locale.ROOT );
        for ( final String forbidden : forbiddenPatterns ) {
            if ( !forbidden.isEmpty() && lower.endsWith( forbidden ) ) {
                return false;
            }
        }
        for ( final String allowed : allowedPatterns ) {
            if ( !allowed.isEmpty() && lower.endsWith( allowed ) ) {
                return true;
            }
        }
        return allowedPatterns.length == 0;
    }
}
