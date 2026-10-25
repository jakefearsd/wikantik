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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Narrow allow-list filter that makes PlantUML's SVG safe to splice inline into page HTML. Page HTML is not
 * re-sanitized after drawings are inserted, so everything this class emits reaches readers verbatim.
 *
 * <p>The element and attribute allow-lists below are the union of what PlantUML 1.2026.8 emits for the
 * fixtures in {@code src/test/resources/svg} (class, activity, sequence, use case, component, state, object,
 * deployment, timing, C4-style, and a gradient/shadow/dashed/creole sample). Anything not on them is dropped:
 * unknown elements with their whole subtree, unknown attributes singly. The output is serialized by hand so
 * that every text node and attribute value is escaped; author label text can never become markup.
 */
public final class SvgScrubber {

    private static final Logger LOG = LogManager.getLogger( SvgScrubber.class );

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    /** Elements PlantUML emits. Deliberately excludes style, script, image, use, foreignObject, animate*, set. */
    static final Set< String > ELEMENTS = Set.of(
        "svg", "defs", "g", "a", "title", "text", "rect", "ellipse", "line", "path", "polygon",
        "linearGradient", "stop",
        "filter", "feGaussianBlur", "feColorMatrix", "feOffset", "feBlend" );

    /** Attributes PlantUML emits (any on* attribute is refused regardless). href/xlink:href/target/style/id are special-cased. */
    static final Set< String > ATTRIBUTES = Set.of(
        "class", "contentStyleType", "cx", "cy", "d", "dx", "dy", "fill", "fill-opacity", "filter", "font-family",
        "font-size", "font-style", "font-weight", "height", "in", "in2", "lengthAdjust", "mode", "offset", "points",
        "preserveAspectRatio", "result", "rx", "ry", "stdDeviation", "stop-color", "text-decoration", "textLength",
        "title", "type", "values", "version", "viewBox", "width", "x", "x1", "x2", "y", "y1", "y2", "zoomAndPan",
        "xlink:actuate", "xlink:show", "xlink:title", "xlink:type",
        "data-diagram-type", "data-link-type", "data-qualified-name", "data-source-line",
        "data-visibility-modifier", "data-entity", "data-uid" );

    private static final Set< String > TARGETS = Set.of( "_top", "_blank" );
    private static final Pattern PREFIX = Pattern.compile( "[A-Za-z][A-Za-z0-9_-]*" );
    private static final Pattern URL_CALL = Pattern.compile( "url\\(", Pattern.CASE_INSENSITIVE );
    private static final Pattern LOCAL_URL = Pattern.compile( "url\\(#([A-Za-z0-9_.:-]+)\\)", Pattern.CASE_INSENSITIVE );
    private static final List< String > STYLE_FORBIDDEN = List.of( "expression", "@import", "\\", "javascript", "-moz-binding", "behavior" );

    /** Thrown when the input is not a well-formed, DOCTYPE-free SVG document. */
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Rejected( final String message, final Throwable cause ) {
            super( message, cause );
        }
    }

    private SvgScrubber() {
    }

    /**
     * Scrubs {@code svg} and returns inline-safe SVG markup (no XML declaration, no DOCTYPE).
     *
     * @param svg      PlantUML's raw SVG
     * @param idPrefix prefix applied to every id and local reference; must match {@code [A-Za-z][A-Za-z0-9_-]*}
     * @return the scrubbed SVG
     * @throws Rejected                 when the input is unparsable, has a DOCTYPE, or its root is not {@code svg}
     * @throws IllegalArgumentException when the prefix is unsafe
     */
    public static String scrub( final String svg, final String idPrefix ) {
        if( idPrefix == null || !PREFIX.matcher( idPrefix ).matches() ) {
            throw new IllegalArgumentException( "unsafe id prefix: " + idPrefix );
        }
        final Document doc = parse( svg );
        final Element root = doc.getDocumentElement();
        if( root == null || !"svg".equals( root.getTagName() ) ) {
            throw new Rejected( "root element is not svg", null );
        }
        final StringBuilder out = new StringBuilder( svg.length() );
        writeElement( root, idPrefix, out );
        return out.toString();
    }

    private static Document parse( final String svg ) {
        if( svg == null || svg.isBlank() ) {
            throw new Rejected( "empty svg", null );
        }
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature( XMLConstants.FEATURE_SECURE_PROCESSING, true );
            f.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", true );
            f.setFeature( "http://xml.org/sax/features/external-general-entities", false );
            f.setFeature( "http://xml.org/sax/features/external-parameter-entities", false );
            f.setFeature( "http://apache.org/xml/features/nonvalidating/load-external-dtd", false );
            f.setXIncludeAware( false );
            f.setExpandEntityReferences( false );
            f.setNamespaceAware( false );
            f.setCoalescing( true );
            f.setIgnoringComments( true );
            final DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler( null );
            return b.parse( new InputSource( new StringReader( svg ) ) );
        } catch( final SAXException | IOException e ) {
            LOG.warn( "Rejected unparsable PlantUML SVG: {}", e.toString() );
            throw new Rejected( "unparsable svg: " + e.getMessage(), e );
        } catch( final ParserConfigurationException e ) {
            LOG.warn( "XML parser cannot be hardened; refusing SVG: {}", e.toString() );
            throw new Rejected( "xml parser cannot be hardened", e );
        }
    }

    private static void writeElement( final Element el, final String prefix, final StringBuilder out ) {
        final String name = el.getTagName();
        out.append( '<' ).append( name );
        final NamedNodeMap attrs = el.getAttributes();
        final boolean root = el.getParentNode() instanceof Document;
        for( int i = 0; i < attrs.getLength(); i++ ) {
            final String an = attrs.item( i ).getNodeName();
            final String value = scrubAttribute( an, attrs.item( i ).getNodeValue(), root, prefix );
            if( value != null ) {
                out.append( ' ' ).append( an ).append( "=\"" );
                escape( value, true, out );
                out.append( '"' );
            }
        }
        final List< Node > kept = new ArrayList<>();
        for( Node c = el.getFirstChild(); c != null; c = c.getNextSibling() ) {
            if( c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE
                || c.getNodeType() == Node.ELEMENT_NODE && ELEMENTS.contains( ( (Element) c ).getTagName() ) ) {
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

    /** @return the (possibly rewritten) value to emit, or {@code null} to drop the attribute */
    private static String scrubAttribute( final String name, final String value, final boolean root, final String prefix ) {
        if( name.toLowerCase( Locale.ROOT ).startsWith( "on" ) ) {
            return null;
        }
        switch( name ) {
            case "xmlns":
                return root && SVG_NS.equals( value ) ? value : null;
            case "xmlns:xlink":
                return root && XLINK_NS.equals( value ) ? value : null;
            case "id":
                return prefix + value;
            case "href":
            case "xlink:href":
                return scrubHref( value, prefix );
            case "target":
                return TARGETS.contains( value ) ? value : null;
            case "style":
                return scrubStyle( value, prefix );
            default:
                return ATTRIBUTES.contains( name ) ? rewriteUrls( value, prefix ) : null;
        }
    }

    private static String scrubHref( final String value, final String prefix ) {
        if( value.startsWith( "#" ) ) {
            return "#" + prefix + value.substring( 1 );
        }
        final String lower = value.toLowerCase( Locale.ROOT );
        if( value.startsWith( "/wiki/" ) || lower.startsWith( "http://" ) || lower.startsWith( "https://" ) ) {
            return value;
        }
        return null;
    }

    private static String scrubStyle( final String value, final String prefix ) {
        final String lower = value.toLowerCase( Locale.ROOT );
        for( final String bad : STYLE_FORBIDDEN ) {
            if( lower.contains( bad ) ) {
                return null;
            }
        }
        return rewriteUrls( value, prefix );
    }

    /** Prefixes every {@code url(#x)}; returns null when any {@code url(} is anything else. */
    private static String rewriteUrls( final String value, final String prefix ) {
        final int calls = (int) URL_CALL.matcher( value ).results().count();
        if( calls == 0 ) {
            return value;
        }
        final Matcher m = LOCAL_URL.matcher( value );
        final StringBuilder sb = new StringBuilder();
        int found = 0;
        while( m.find() ) {
            found++;
            m.appendReplacement( sb, Matcher.quoteReplacement( "url(#" + prefix + m.group( 1 ) + ")" ) );
        }
        if( found != calls ) {
            return null;
        }
        m.appendTail( sb );
        return sb.toString();
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
}
