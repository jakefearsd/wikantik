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

package com.wikantik.pages;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.util.ClassUtil;
import com.wikantik.util.comparators.JavaNaturalComparator;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Wrapper class for managing and using the PageNameComparator.
 * <p>
 * <b>Note</b> - this class is deliberately not null safe. Never call any of the methods with a null argument!
 */
public class PageSorter implements Comparator< String > {
    
    private static final Logger LOG = LogManager.getLogger( PageSorter.class );

    // The name of the property that specifies the desired page name comparator
    protected static final String PROP_PAGE_NAME_COMPARATOR = "wikantik.pageNameComparator.class";

    private Comparator< String > comparator;

    /** Default constructor uses Java "natural" ordering. */
    public PageSorter() {
        comparator = JavaNaturalComparator.DEFAULT_JAVA_COMPARATOR;
    }

    /**
     * Construct with a particular comparator.
     * 
     * @param newComparator the Comparator to use
     */
    public PageSorter( final Comparator<String> newComparator ) {
        this.comparator = newComparator;
    }

    /**
     * Compare two page names (String version).
     * 
     * @param pageName1 the first page name
     * @param pageName2 the second page name
     * @return see java.util.Comparator
     * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
     */
    @Override
    public int compare( final String pageName1, final String pageName2 ) {
        return comparator.compare( pageName1, pageName2 );
    }

    @Override
    public boolean equals( final Object o ) {
        if( !( o instanceof PageSorter that ) ) {
            return false; // Definitely not equal
        }
        if( this == that || comparator == that.comparator ) {
            return true; // Essentially the same object
        }
        return comparator.equals( that.comparator );
    }

    @Override
    public int hashCode() {
        return Objects.hashCode( comparator );
    }

    /**
     * Called by Engine to initialise this instance. Tries to use class given by the PROP_PAGE_NAME_COMPARATOR property as the page name
     * comparator. Uses a default comparator if this property is not set or there is any problem loading the specified class.
     * 
     * @param props this Engine's properties.
     */
    public void initialize( final Properties props ) {
        // Default is Java natural order
        comparator = JavaNaturalComparator.DEFAULT_JAVA_COMPARATOR;
        final String className = props.getProperty( PROP_PAGE_NAME_COMPARATOR );
        if( className != null && !className.isEmpty() ) {
            try {
                final Class< Comparator< String > > cl = ClassUtil.findClass( "com.wikantik.util.comparators", className );
                comparator = ClassUtil.buildInstance( cl );
            } catch( final Exception e ) {
                LOG.error( "Falling back to default \"natural\" comparator", e );
            }
        }
    }

    /**
     * Sorts the specified list into ascending order based on the PageNameComparator. The actual sort is done using {@code List.sort()}.
     * 
     * @param nameList the page names to be sorted
     */
    public void sort( final List< String > nameList ) {
        nameList.sort( comparator );
    }

    /**
     * Sorts the specified array into ascending order based on the PageNameComparator. The actual sort is done using {@code Arrays.sort()}.
     * 
     * @param nameArray the page names to be sorted
     */
    public void sort( final String[] nameArray ) {
        Arrays.sort( nameArray, comparator );
    }

}
