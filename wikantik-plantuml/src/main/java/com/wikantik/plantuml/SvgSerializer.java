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
package com.wikantik.plantuml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;

import static com.wikantik.plantuml.SvgAllowList.ELEMENTS;
import static com.wikantik.plantuml.SvgAttributeRules.scrubAttribute;
import static com.wikantik.plantuml.SvgUrlRules.imageHref;

/** Hand-written serializer: drops disallowed elements and escapes every text node and attribute value. */
final class SvgSerializer {

    private static boolean elementAllowed( final Element el ) {
        if( !ELEMENTS.contains( el.getTagName() ) ) {
            return false;
        }
        return !"image".equals( el.getTagName() ) || imageHref( el ) != null;
    }

    static void writeElement( final Element el, final String prefix, final StringBuilder out ) {
        final String name = el.getTagName();
        out.append( '<' ).append( name );
        final NamedNodeMap attrs = el.getAttributes();
        final boolean root = el.getParentNode() instanceof Document;
        for( int i = 0; i < attrs.getLength(); i++ ) {
            final String an = attrs.item( i ).getNodeName();
            final String value = scrubAttribute( name, an, attrs.item( i ).getNodeValue(), root, prefix );
            if( value != null ) {
                out.append( ' ' ).append( an ).append( "=\"" );
                escape( value, true, out );
                out.append( '"' );
            }
        }
        final List< Node > kept = new ArrayList<>();
        for( Node c = el.getFirstChild(); c != null; c = c.getNextSibling() ) {
            if( c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE
                || c.getNodeType() == Node.ELEMENT_NODE && elementAllowed( (Element) c ) ) {
                kept.add( c );
            }
        }
        if( kept.isEmpty() ) {
            out.append( "/>" );
            return;
        }
        out.append( '>' );
        for( final Node c : kept ) {
            if( c.getNodeType() == Node.ELEMENT_NODE ) {
                writeElement( (Element) c, prefix, out );
            } else {
                escape( c.getNodeValue(), false, out );
            }
        }
        out.append( "</" ).append( name ).append( '>' );
    }

    private static void escape( final String s, final boolean attribute, final StringBuilder out ) {
        for( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            switch( c ) {
                case '&' -> out.append( "&amp;" );
                case '<' -> out.append( "&lt;" );
                case '>' -> out.append( "&gt;" );
                case '"' -> out.append( attribute ? "&quot;" : "\"" );
                case '\n' -> out.append( attribute ? "&#10;" : "\n" );
                case '\r' -> out.append( attribute ? "&#13;" : "&#13;" );
                case '\t' -> out.append( attribute ? "&#9;" : "\t" );
                default -> out.append( c );
            }
        }
    }
    private SvgSerializer() {
    }
}
