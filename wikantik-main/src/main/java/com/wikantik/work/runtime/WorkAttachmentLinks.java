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
package com.wikantik.work.runtime;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an attachment token in work text into the download route of that item's attachment. The token is
 * {@code work-attachment:<digits>} and nothing else; the produced URL is always
 * {@code <context>/api/work/items/<the item's key>/attachments/<digits>}, so text can never point at another item's
 * attachment or at an arbitrary URL through it. It is a pure rewrite of the URL text: it checks nothing about whether
 * the attachment exists (the route does, and answers the same 404 for every miss).
 */
public final class WorkAttachmentLinks {

    private static final Pattern TOKEN = Pattern.compile( "^work-attachment:([0-9]{1,18})$" );
    private static final Pattern ITEM_KEY = Pattern.compile( "^[A-Z][A-Z0-9]*-[0-9]+$" );

    private WorkAttachmentLinks() {
    }

    /**
     * @param contextPath the wiki's base URL / context path, without a trailing slash is not required
     * @param itemKey     the key of the item whose text is rendered; null or not an item key means no rewrite at all
     * @return a function giving the route for an exact token, else null
     */
    public static UnaryOperator< String > rewriter( final String contextPath, final String itemKey ) {
        if ( itemKey == null || !ITEM_KEY.matcher( itemKey ).matches() ) {
            return url -> null;
        }
        final String base = ( contextPath == null ? "" : contextPath.replaceAll( "/+$", "" ) )
            + "/api/work/items/" + itemKey + "/attachments/";
        return url -> {
            if ( url == null ) {
                return null;
            }
            final Matcher m = TOKEN.matcher( url );
            return m.matches() ? base + m.group( 1 ) : null;
        };
    }
}
