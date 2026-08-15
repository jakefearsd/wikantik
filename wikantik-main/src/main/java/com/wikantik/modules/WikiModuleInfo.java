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
package com.wikantik.modules;

import com.wikantik.util.FileUtil;
import org.jdom2.Element;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 *  A WikiModule describes whatever JSPWiki plugin there is: it can be a plugin, an editor, a filter, etc.
 *
 *  @since 2.4
 */
public class WikiModuleInfo implements Comparable< WikiModuleInfo > {
    protected String name;
    protected String description;
    protected String scriptLocation;
    protected String scriptText;
    protected String stylesheetLocation;
    protected String stylesheetText;
    protected String author;
    protected URL    resource;
    protected String minVersion;
    protected String maxVersion;

    /**
     *  Create a new info container.
     *  
     *  @param newName The name of the module.
     */
    public WikiModuleInfo( final String newName ) {
        this.name = newName;
    }
    
    /**
     *  The WikiModuleInfo is equal to another WikiModuleInfo, if the name is equal.  All objects are unique across JSPWiki.
     *  
     *  @param obj {@inheritDoc}
     *  @return {@inheritDoc}
     */
    @Override
    public boolean equals( final Object obj) {
        return obj instanceof WikiModuleInfo info && info.name.equals( name );
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /**
     *  Initializes the ModuleInfo from some standard XML elements which are under the given element.
     *  
     *  @param el The element to parse.
     */
    protected void initializeFromXML( final Element el ) {
        author = el.getChildText( "author" );
        description = el.getChildText( "description" );
        maxVersion = el.getChildText( "maxVersion" );
        minVersion = el.getChildText( "minVersion" );
        scriptLocation = el.getChildText( "script" );
        stylesheetLocation = el.getChildText( "stylesheet" );
    }

    /**
     *  Returns the common name for this particular module.  Note that this is not the class name, nor is it an alias.
     *  For different modules the name may have different meanings.
     *  <p>
     *  Every module defines a name, so this method should never return null.
     *  
     *  @return A module name.
     */
    public String getName() {
        return name;
    }
    
    /**
     *  The description of what this module does.
     *  
     *  @return A module description.
     */
    public String getDescription() {
        return description;
    }

    /**
     *  Returns the name of the author of this plugin (if defined).
     * @return Author name, or null.
     */
    public String getAuthor() {
        return author;
    }

    /**
     *  Returns the minimum version of JSPWiki that this module supports.
     *  
     *  @return The minimum version.
     */
    public String getMinVersion() {
        return minVersion;
    }
    
    /**
     *  Returns the maximum version of JSPWiki that this module supports.
     *  
     *  @return The maximum version.
     */
    public String getMaxVersion() {
        return maxVersion;
    }

    /**
     *  Attempts to locate a resource from a JAR file and returns it as a string.
     *  
     *  @param resourceLocation an URI of the resource
     *  @return The content of the file
     *  
     *  @throws IOException if the JAR file or the resource cannot be read
     */
    protected String getTextResource( final String resourceLocation ) throws IOException {
        if( resource == null ) {
            return "";
        }
    
        // The text of this resource should be loaded from the same
        //   jar-file as the wikantik_module.xml -file! This is because 2 plugins
        //   could have the same name of the resourceLocation!
        //   (2 plugins could have their stylesheet-files in 'ini/wikantik.css')
    
        // So try to construct a resource that loads this resource from the same jar-file.
        String spec = resource.toString();
    
        // Replace the 'PLUGIN_RESOURCE_LOCATION' with the requested resourceLocation.
        final int length = BaseModuleManager.PLUGIN_RESOURCE_LOCATION.length();
        spec = spec.substring( 0, spec.length() - length ) + resourceLocation;
    
        final URL url = URI.create( spec ).toURL();
        try( BufferedInputStream in = new BufferedInputStream( url.openStream() );
             ByteArrayOutputStream out = new ByteArrayOutputStream(1024) ) {
            FileUtil.copyContents( in, out );
            return out.toString( StandardCharsets.UTF_8 );
        }
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public int compareTo( final WikiModuleInfo mod ) {
        return name.compareTo( mod.getName() );
    }

}
