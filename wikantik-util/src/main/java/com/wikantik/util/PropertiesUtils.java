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

import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * some useful methods for properties
 *
 * @version 1.0
 */
public final class PropertiesUtils {

    private static final String OTHER_WHITESPACE = "\t\r\n\014";
    private static final char[] HEXDIGIT = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F' };

    /** Private constructor to prevent instantiation. */
    private PropertiesUtils()
    {}

    /**
     * <p>
     * like Properties.store, but stores the properties in sorted order
     * </p>
     *
     * @param properties the properties object
     * @return String the properties, nicely formatted 
     */
    public static String toSortedString(final Properties properties )
    {
        @SuppressWarnings( { "unchecked", "rawtypes" } ) final TreeMap< String, String > treemap = new TreeMap( properties );
        final StringBuilder string = new StringBuilder();
        final Iterator< Map.Entry< String, String > > iterator = treemap.entrySet().iterator();
        while( iterator.hasNext() )
        {
            final Map.Entry< String, String > entry = iterator.next();
            final String key = entry.getKey();
            final String value = entry.getValue() == null ? "null" : entry.getValue();
            string.append(toLine(key, value)).append('\n');
        }
        return string.toString();
    }

    /**
     * Generates a property file line from a supplied key and value.
     * @param key the property's key
     * @param value the property's value
     * @return the converted string
     */
    public static String toLine(final String key, final String value )
    {
        return saveConvert( key, true ) + '=' + saveConvert( value, false );
    }

    /**
     * Encodes a property file string from a supplied key/value line.
     * @param string the string to encode
     * @param encodeWhiteSpace <code>true</code> if whitespace should be encoded also
     * @return the converted string
     */
    public static String saveConvert(final String string, final boolean encodeWhiteSpace )
    {
        final int length = string.length();
        final StringBuilder stringbuffer = new StringBuilder( length * 2 );
        for( int charIndex = 0; charIndex < length; charIndex++ )
        {
            final char currentChar = string.charAt( charIndex );
            switch( currentChar )
            {
                case ' ':
                    if( charIndex == 0 || encodeWhiteSpace )
                    {
                        stringbuffer.append( '\\' );
                    }
                    stringbuffer.append( ' ' );
                    break;
                case '\\':
                    stringbuffer.append( '\\' );
                    stringbuffer.append( '\\' );
                    break;
                case '\t':
                    stringbuffer.append( '\\' );
                    stringbuffer.append( 't' );
                    break;
                case '\n':
                    stringbuffer.append( '\\' );
                    stringbuffer.append( 'n' );
                    break;
                case '\r':
                    stringbuffer.append( '\\' );
                    stringbuffer.append( 'r' );
                    break;
                case '\014':
                    stringbuffer.append( '\\' );
                    stringbuffer.append( 'f' );
                    break;
                default:
                    if( currentChar < 32 || currentChar > 126 )
                    {
                        stringbuffer.append( '\\' );
                        stringbuffer.append( 'u' );
                        stringbuffer.append( toHex( currentChar >> 12 & 0xf ) );
                        stringbuffer.append( toHex( currentChar >> 8 & 0xf ) );
                        stringbuffer.append( toHex( currentChar >> 4 & 0xf ) );
                        stringbuffer.append( toHex( currentChar & 0xf ) );
                    }
                    else
                    {
                        if( OTHER_WHITESPACE.indexOf( currentChar ) != -1 )
                        {
                            stringbuffer.append( '\\' );
                        }
                        stringbuffer.append( currentChar );
                    }
            }
        }
        return stringbuffer.toString();
    }

    private static char toHex(final int i )
    {
        return HEXDIGIT[i & 0xf];
    }
}
