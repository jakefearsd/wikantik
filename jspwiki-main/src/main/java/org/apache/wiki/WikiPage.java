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
package org.apache.wiki;

import org.apache.wiki.api.core.Acl;
import org.apache.wiki.api.core.AclEntry;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.providers.PageProvider;
import org.apache.wiki.auth.acl.AclImpl;
import org.apache.wiki.pages.PageManager;

import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;


/**
 *  Simple wrapper class for the Wiki page attributes.  The Wiki page content is moved around in Strings, though.
 */
// FIXME: We need to rethink how metadata is being used - probably the author, date, etc. should also be part of the metadata.  We also
//        need to figure out the metadata lifecycle.
public class WikiPage implements Page {

    private final String     name;
    private final Engine     engine;
    private       String     wiki;
    private Date             lastModified;
    private long             fileSize = -1;
    private int              version = PageProvider.LATEST_VERSION;
    private String           author;
    private Map< String, Object > attributes = new HashMap<>();

    private Acl accessList;

    /**
     * Create a new WikiPage using a given engine and name.
     *
     * @param engine The Engine that owns this page.
     * @param name   The name of the page.
     */
    public WikiPage( final Engine engine, final String name ) {
        this.engine = engine;
        this.name = name;
        this.wiki = engine.getApplicationName();
    }

    /**
     * Returns the name of the page.
     *
     * @return The page name.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * A WikiPage may have a number of attributes, which might or might not be available.  Typically attributes are things that do not need
     * to be stored with the wiki page to the page repository, but are generated on-the-fly.  A provider is not required to save them, but
     * they can do that if they really want.
     *
     * @param key The key using which the attribute is fetched
     * @return The attribute.  If the attribute has not been set, returns null.
     */
    @Override
    @SuppressWarnings( "unchecked" )
    public < T > T getAttribute( final String key ) {
        return ( T )attributes.get( key );
    }

    /**
     * Sets an metadata attribute.
     *
     * @param key       The key for the attribute used to fetch the attribute later on.
     * @param attribute The attribute value
     * @see #getAttribute(String)
     */
    @Override
    public void setAttribute( final String key, final Object attribute ) {
        attributes.put( key, attribute );
    }

    /**
     * Returns the full attributes Map, in case external code needs to iterate through the attributes.
     *
     * @return The attribute Map.  Please note that this is a direct
     * reference, not a copy.
     */
    @Override
    public Map< String, Object > getAttributes() {
        return attributes;
    }

    /**
     * Removes an attribute from the page, if it exists.
     *
     * @param key The key for the attribute
     * @return If the attribute existed, returns the object.
     * @since 2.1.111
     */
    @Override
    @SuppressWarnings( "unchecked" )
    public < T > T removeAttribute( final String key ) {
        return ( T )attributes.remove( key );
    }

    /**
     * Returns the date when this page was last modified.
     *
     * @return The last modification date
     */
    @Override
    public Date getLastModified() {
        return lastModified;
    }

    /**
     * Sets the last modification date.  In general, this is only changed by the provider.
     *
     * @param date The date
     */
    @Override
    public void setLastModified( final Date date ) {
        lastModified = date;
    }

    /**
     * Sets the page version.  In general, this is only changed by the provider.
     *
     * @param version The version number
     */
    @Override
    public void setVersion( final int version ) {
        this.version = version;
    }

    /**
     * Returns the version that this WikiPage instance represents.
     *
     * @return the version number of this page.
     */
    @Override
    public int getVersion() {
        return version;
    }

    /**
     * Returns the size of the page.
     *
     * @return the size of the page.
     * @since 2.1.109
     */
    @Override
    public long getSize() {
        return fileSize;
    }

    /**
     * Sets the size.  Typically called by the provider only.
     *
     * @param size The size of the page.
     * @since 2.1.109
     */
    @Override
    public void setSize( final long size ) {
        fileSize = size;
    }

    /**
     * Returns the Acl for this page.  May return <code>null</code>, in case there is no Acl defined, or it has not yet been set by
     * {@link #setAcl(Acl)}.
     *
     * @return The access control list.  May return null, if there is no acl.
     */
    @Override
    public Acl getAcl() {
        return accessList;
    }

    /**
     * Sets the Acl for this page. Note that method does <em>not</em> persist the Acl itself to back-end storage or in page markup;
     * it merely sets the internal field that stores the Acl. To persist the Acl, callers should invoke
     * {@link org.apache.wiki.auth.acl.AclManager#setPermissions(Page, org.apache.wiki.api.core.Acl)}.
     *
     * @param acl The Acl to set
     */
    @Override
    public void setAcl( final Acl acl ) {
        accessList = acl;
    }

    /**
     * Sets the author of the page.  Typically called only by the provider.
     *
     * @param author The author name.
     */
    @Override
    public void setAuthor( final String author ) {
        this.author = author;
    }

    /**
     * Returns author name, or null, if no author has been defined.
     *
     * @return Author name, or possibly null.
     */
    @Override
    public String getAuthor() {
        return author;
    }

    /**
     * Returns the wiki name for this page
     *
     * @return The name of the wiki.
     */
    @Override
    public String getWiki() {
        return wiki;
    }

    /**
     * This method will remove all metadata from the page.
     */
    @Override
    public void invalidateMetadata() {
        hasMetadata = false;
        setAcl( null );
        attributes.clear();
    }

    private boolean hasMetadata;

    /**
     * Returns <code>true</code> if the page has valid metadata; that is, it has been parsed. Note that this method is a kludge to
     * support our pre-3.0 metadata system, and as such will go away with the new API.
     *
     * @return true, if the page has metadata.
     */
    @Override
    public boolean hasMetadata() {
        return hasMetadata;
    }

    /**
     * Sets the metadata flag to true.  Never call.
     */
    @Override
    public void setHasMetadata() {
        hasMetadata = true;
    }

    /**
     * Returns a debug-suitable version of the page.
     *
     * @return A debug string.
     */
    @Override
    public String toString() {
        return "WikiPage [" + wiki + ":" + name + ",ver=" + version + ",mod=" + lastModified + "]";
    }

    /**
     *  Creates a deep clone of a WikiPage.  Strings are not cloned, since they're immutable.  Attributes are not cloned, only the internal
     *  HashMap (so if you modify the contents of a value of an attribute, these will reflect back to everyone).
     *  
     *  @return A deep clone of the WikiPage
     */
    @Override
    public WikiPage clone() {
        try {
            final WikiPage p = (WikiPage) super.clone();
            // Deep-clone mutable fields
            if( lastModified != null ) {
                p.lastModified = (Date) lastModified.clone();
            }
            // Clone the attributes map (shallow copy of values, same as before)
            p.attributes = new HashMap<>( attributes );

            if( accessList != null ) {
                p.accessList = new AclImpl();
                for( final Enumeration< AclEntry > entries = accessList.aclEntries(); entries.hasMoreElements(); ) {
                    final AclEntry e = entries.nextElement();
                    p.accessList.addEntry( e );
                }
            }
            return p;
        } catch( final CloneNotSupportedException e ) {
            throw new AssertionError( "WikiPage implements Cloneable", e );
        }
    }
    
    /**
     *  Compares a page with another by name using the defined PageNameComparator.  If the same name, compares their versions.
     *  
     *  @param page The page to compare against
     *  @return -1, 0 or 1
     */
    @Override
    public int compareTo( final Page page ) {
        if( this == page ) {
            return 0; // the same object
        }

        int res = engine.getManager( PageManager.class ).getPageSorter().compare( this.getName(), page.getName() );
        if( res == 0 ) {
            res = this.getVersion() - page.getVersion();
        }
        return res;
    }
    
    /**
     *  A page is equal to another page if its name and version are equal.
     *  
     *  {@inheritDoc}
     */
    public boolean equals( final Object o ) {
        if( o instanceof WikiPage wp ) {
            if( wp.getName().equals( getName() ) ) {
                return wp.getVersion() == getVersion();
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return name.hashCode() * version;
    }

}
