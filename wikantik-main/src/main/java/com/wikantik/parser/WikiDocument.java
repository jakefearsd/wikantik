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
package com.wikantik.parser;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import org.jdom2.Document;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 *  Stores the DOM tree of a rendered WikiPage. This class extends the org.jdom.Document to provide some extra metadata
 *  specific to JSPWiki.
 *  <p>
 *  The document is not stored as metadata in the WikiPage because otherwise it could not be cached separately.
 *  
 *  @since  2.4
 */
public class WikiDocument extends Document {

    private static final long serialVersionUID = 1L;

    private final Page page;
    private String wikiText;
    private String pageDataHash;
    private WeakReference< Context > context;
    
    /**
     *  Creates a new WikiDocument for a specific page.
     * 
     *  @param page The page to which this document refers to.
     */
    public WikiDocument( final Page page )
    {
        this.page = page;
    }
    
    /**
     *  Set the WikiMarkup for this document.
     *  Also computes and stores a hash for efficient cache validation.
     *
     *  @param data The WikiMarkup
     */
    public void setPageData( final String data )
    {
        wikiText = data;
        pageDataHash = computeHash( data );
    }

    /**
     *  Returns the wikimarkup used to render this document.
     *
     *  @return The WikiMarkup
     */
    public String getPageData()
    {
        return wikiText;
    }

    /**
     *  Returns the hash of the wiki markup used to render this document.
     *  This is used for efficient cache validation instead of full string comparison.
     *
     *  @return The hash of the WikiMarkup, or null if no data was set
     */
    public String getPageDataHash()
    {
        return pageDataHash;
    }

    /**
     *  Computes a SHA-256 hash of the given data for cache validation.
     *  SHA-256 is used instead of MD5 for better collision resistance.
     *
     *  @param data The data to hash
     *  @return The hex-encoded hash string, or null if data is null
     */
    private static String computeHash( final String data )
    {
        if( data == null ) {
            return null;
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
            final byte[] hashBytes = digest.digest( data.getBytes( StandardCharsets.UTF_8 ) );
            final StringBuilder hexString = new StringBuilder( hashBytes.length * 2 );
            for( final byte hashByte : hashBytes ) {
                final String hex = Integer.toHexString( 0xff & hashByte );
                if( hex.length() == 1 ) {
                    hexString.append( '0' );
                }
                hexString.append( hex );
            }
            return hexString.toString();
        } catch( final Exception e ) {
            // Fall back to null - will cause full string comparison
            return null;
        }
    }

    /**
     *  Computes a hash of the given page data for comparison.
     *  This is a static utility method for use in cache validation.
     *
     *  @param pagedata The page data to hash
     *  @return The hash string
     */
    public static String hashPageData( final String pagedata )
    {
        return computeHash( pagedata );
    }
    
    /**
     *  Return the WikiPage for whom this WikiDocument exists.
     *  
     *  @return The WikiPage
     */
    public Page getPage()
    {
        return page;
    }

    /**
     *  Set the WikiContext in which the WikiDocument is rendered. The WikiContext is stored in a WeakReference, which means that it
     *  can be garbagecollected away.  This is to allow for caching of the WikiDocument without having to carry the WikiContext around
     *  for a long time.
     *  
     *  @param ctx A WikiContext.
     */
    public void setContext( final Context ctx )
    {
        context = new WeakReference<>( ctx );
    }
    
    /**
     * Returns the wiki context for this document. This method
     * may return <code>null</code> if the associated wiki session
     * had previously been garbage-collected.
     * @return the wiki context
     */
    public Context getContext()
    {
        return context.get();
    }

}
