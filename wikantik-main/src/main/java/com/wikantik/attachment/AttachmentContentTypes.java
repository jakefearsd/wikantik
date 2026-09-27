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

import com.wikantik.api.core.Context;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Set;

/**
 *  MIME-type resolution and the inline-serving allowlist for attachment downloads.
 */
final class AttachmentContentTypes {

    /**
     * MIME types safe to serve {@code inline} on the wiki's own origin. Everything
     * else is served as a download. This is an ALLOWLIST on purpose: an active-content
     * denylist keyed on file extension always misses variants ({@code .xht},
     * {@code .svgz}, {@code .mhtml}, …), and any such file served inline with its real
     * content type executes script on the wiki origin (stored XSS). Deciding from the
     * resolved MIME type and defaulting to attachment closes that whole class.
     */
    private static final Set< String > INLINE_SAFE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp", "image/x-icon",
            "application/pdf", "text/plain",
            "audio/mpeg", "audio/ogg", "audio/wav",
            "video/mp4", "video/webm", "video/ogg"
    );

    private AttachmentContentTypes() {
    }

    /** True when the resolved MIME type (ignoring any {@code ;charset=…}) is on the inline allowlist. */
    static boolean isInlineSafeType( final String mimeType ) {
        if ( mimeType == null ) {
            return false;
        }
        final int semi = mimeType.indexOf( ';' );
        final String base = ( semi >= 0 ? mimeType.substring( 0, semi ) : mimeType ).trim().toLowerCase( Locale.ROOT );
        return INLINE_SAFE_MIME_TYPES.contains( base );
    }

    /**
     *  Returns the mime type for this particular file.  Case does not matter.
     *
     * @param ctx WikiContext; required to access the ServletContext of the request.
     * @param fileName The name to check for.
     * @return A valid mime type, or application/binary, if not recognized
     */
    static String mimeTypeOf( final Context ctx, final String fileName ) {
        String mimetype = null;

        final HttpServletRequest req = ctx.getHttpRequest();
        if( req != null ) {
            final ServletContext servletContext = req.getSession().getServletContext();

            if( servletContext != null ) {
                mimetype = servletContext.getMimeType( fileName.toLowerCase( Locale.ROOT ) );
            }
        }

        if( mimetype == null ) {
            mimetype = "application/binary";
        }

        return mimetype;
    }
}
