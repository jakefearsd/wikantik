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
package com.wikantik.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Classpath resource scanning behind {@link ClassUtil#classpathEntriesUnder(String)}.
 */
final class ClasspathScanner {

    private static final Logger LOG = LogManager.getLogger( ClasspathScanner.class );

    private ClasspathScanner() {}

    static List< String > classpathEntriesUnder( final String rootPackage ) {
        final List< String > results = new ArrayList<>();
        Enumeration< URL > en = null;
        if( StringUtils.isNotEmpty( rootPackage ) ) {
            try {
                en = ClasspathScanner.class.getClassLoader().getResources( rootPackage );
            } catch( final IOException e ) {
                LOG.error( e.getMessage(), e );
            }
        }
        
        while( en != null && en.hasMoreElements() ) {
            final URL url = en.nextElement();
            try {
                if( "jar".equals( url.getProtocol() ) ) {
                    jarEntriesUnder( results, ( JarURLConnection )url.openConnection(), rootPackage );
                } else if( "file".equals( url.getProtocol() ) ) {
                    fileEntriesUnder( results, new File( url.getFile() ), rootPackage );
                }
                
            } catch( final IOException ioe ) {
                LOG.error( ioe.getMessage(), ioe );
            }
        }
        return results;
    }
    
    /**
     * Searchs for all the files in classpath under a given package, for a given {@link File}. If the 
     * {@link File} is a directory all files inside it are stored, otherwise the {@link File} itself is
     * stored
     * 
     * @param results collection in which the found entries are stored
     * @param file given {@link File} to search in.
     * @param rootPackage base package.
     */
    static void fileEntriesUnder( final List< String > results, final File file, final String rootPackage ) {
        LOG.debug( "scanning [{}]", file.getName() );
        if( file.isDirectory() ) {
            final Iterator< File > files = FileUtils.iterateFiles( file, null, true );
            while( files.hasNext() ) {
                final File subfile = files.next();
                // store an entry similar to the jarSearch(..) below ones
                final String entry = subfile.getAbsolutePath().replace( file.getAbsolutePath() + File.separatorChar, "" );
                results.add( rootPackage + "/" + entry );
            }
        } else {
            results.add( file.getName() );
        }
    }
    
    /**
     * Searchs for all the files in classpath under a given package, for a given {@link JarURLConnection}.
     * 
     * @param results collection in which the found entries are stored
     * @param jurlcon given {@link JarURLConnection} to search in.
     * @param rootPackage base package.
     */
    static void jarEntriesUnder( final List< String > results, final JarURLConnection jurlcon, final String rootPackage ) {
        try( JarFile jar = jurlcon.getJarFile() ) {
            LOG.debug( "scanning [{}]", jar.getName() );
            final Enumeration< JarEntry > entries = jar.entries();
            while( entries.hasMoreElements() ) {
                final JarEntry entry = entries.nextElement();
                if( entry.getName().startsWith( rootPackage ) && !entry.isDirectory() ) {
                    results.add( entry.getName() );
                }
            }
        } catch( final IOException ioe ) {
            LOG.error( ioe.getMessage(), ioe );
        }
    }
}
