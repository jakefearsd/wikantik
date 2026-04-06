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
package com.wikantik.plugin;

import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.parser.MarkupParser;
import com.wikantik.util.TextUtil;

import java.util.Map;


/**
 *  Provides an image plugin for better control than is possible with a simple image inclusion.
 *  <br> Most parameters are equivalents of the html image attributes.
 *
 *  <p>Parameters : </p>
 *  <ul>
 *  <li><b>src</b> - the source (a URL) of the image (required parameter)</li>
 *  <li><b>align</b> - the alignment of the image</li>
 *  <li><b>height</b> - the height of the image</li>
 *  <li><b>width</b> - the width of the image</li>
 *  <li><b>alt</b> - alternate text</li>
 *  <li><b>caption</b> - the caption for the image</li>
 *  <li><b>link</b> - the hyperlink for the image</li>
 *  <li><b>target</b> - the target (frame) to be used for opening the image</li>
 *  <li><b>style</b> - the style attribute of the image</li>
 *  <li><b>class</b> - the associated class for the image</li>
 *  <li><b>border</b> - the border for the image</li>
 *  <li><b>title</b> - the title for the image, can be presented as a tooltip to the user</li>
 *  </ul>
 *
 *  @since 2.1.4.
 */
// FIXME: It is not yet possible to do wiki internal links.  In order to do this cleanly, a TranslatorReader revamp is needed.
public class Image implements Plugin {

    /** The parameter name for setting the src.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_SRC      = "src";
    /** The parameter name for setting the align parameter.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_ALIGN    = "align";
    /** The parameter name for setting the height.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_HEIGHT   = "height";
    /** The parameter name for setting the width.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_WIDTH    = "width";
    /** The parameter name for setting the alt.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_ALT      = "alt";
    /** The parameter name for setting the caption.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_CAPTION  = "caption";
    /** The parameter name for setting the link.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_LINK     = "link";
    /** The parameter name for setting the target.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_TARGET   = "target";
    /** The parameter name for setting the style.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_STYLE    = "style";
    /** The parameter name for setting the class.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_CLASS    = "class";
    /** The parameter name for setting the border.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_BORDER   = "border";
    /** The parameter name for setting the title.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_TITLE    = "title";

    /**
     *  This method is used to clean away things like quotation marks which
     *  a malicious user could use to stop processing and insert javascript.
     */
    private static String getCleanParameter( final Map< String, String > params, final String paramId ) {
        return TextUtil.replaceEntities( params.get( paramId ) );
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public String execute( final Context context, final Map<String, String> params ) throws PluginException {
        String src           = getCleanParameter( params, PARAM_SRC );
        final String align   = getCleanParameter( params, PARAM_ALIGN );
        final String ht      = getCleanParameter( params, PARAM_HEIGHT );
        final String wt      = getCleanParameter( params, PARAM_WIDTH );
        final String alt     = getCleanParameter( params, PARAM_ALT );
        final String caption = getCleanParameter( params, PARAM_CAPTION );
        final String link    = getCleanParameter( params, PARAM_LINK );
        String target        = getCleanParameter( params, PARAM_TARGET );
        final String style   = getCleanParameter( params, PARAM_STYLE );
        final String cssclass= getCleanParameter( params, PARAM_CLASS );
        final String border  = getCleanParameter( params, PARAM_BORDER );
        final String title   = getCleanParameter( params, PARAM_TITLE );

        if( src == null ) {
            throw new PluginException("Parameter 'src' is required for Image plugin");
        }

        if( target != null && !validTargetValue(target) ) {
            target = null;
        }

        src = resolveAttachmentSrc( context, src );

        final StringBuilder result = new StringBuilder();
        buildTableOpen( result, title, align );

        if( caption != null ) {
            result.append( "<caption>" ).append( caption ).append( "</caption>\n" );
        }

        buildCellOpen( result, cssclass, style );
        buildLinkOpen( result, link, target );

        src = sanitizeSrc( context, src );
        buildImgTag( result, src, ht, wt, alt, border );

        if( link != null ) {
            result.append("</a>");
        }
        result.append("</td></tr>\n");
        result.append("</table>\n");

        return result.toString();
    }

    private static String resolveAttachmentSrc( final Context context, final String src ) throws PluginException {
        try {
            final AttachmentManager mgr = context.getEngine().getManager( AttachmentManager.class );
            final Attachment att = mgr.getAttachmentInfo( context, src );
            if( att != null ) {
                return context.getURL( ContextEnum.PAGE_ATTACH.getRequestContext(), att.getName() );
            }
            return src;
        } catch( final ProviderException e ) {
            throw new PluginException( "Attachment info failed: " + e.getMessage() );
        }
    }

    private static String sanitizeSrc( final Context context, final String src ) {
        if( !context.getBooleanWikiProperty( MarkupParser.PROP_ALLOWHTML, false ) ) {
            if( src.startsWith( "data:" ) || src.startsWith( "javascript:" ) ) {
                return "http://invalid_url" + src;
            }
        }
        return src;
    }

    private static void buildTableOpen( final StringBuilder sb, final String title, final String align ) {
        sb.append( "<table border=\"0\" class=\"imageplugin\"" );
        appendAttr( sb, "title", title );

        if( align != null ) {
            if( "center".equals( align ) ) {
                sb.append( " style=\"margin-left: auto; margin-right: auto; text-align:center; vertical-align:middle;\"" );
            } else {
                sb.append( " style=\"float:" ).append( align ).append( ";\"" );
            }
        }
        sb.append( ">\n" );
    }

    private static void buildCellOpen( final StringBuilder sb, final String cssclass, final String style ) {
        sb.append( "<tr><td" );
        appendAttr( sb, "class", cssclass );

        if( style != null ) {
            sb.append( " style=\"" ).append( style );
            if( !style.endsWith( ";" ) ) {
                sb.append( ";" );
            }
            sb.append( "\"" );
        }
        sb.append( ">" );
    }

    private static void buildLinkOpen( final StringBuilder sb, final String link, final String target ) {
        if( link != null ) {
            sb.append( "<a href=\"" ).append( TextUtil.replaceEntities( link ) ).append( "\"" );
            appendAttr( sb, "target", target );
            sb.append( ">" );
        }
    }

    private static void buildImgTag( final StringBuilder sb, final String src,
                                      final String ht, final String wt,
                                      final String alt, final String border ) {
        sb.append( "<img src=\"" ).append( TextUtil.replaceEntities( src ) ).append( "\"" );
        appendAttr( sb, "height", ht );
        appendAttr( sb, "width", wt );
        appendAttr( sb, "alt", alt );
        appendAttr( sb, "border", border );
        sb.append( " />" );
    }

    private static void appendAttr( final StringBuilder sb, final String name, final String value ) {
        if( value != null ) {
            sb.append( " " ).append( name ).append( "=\"" ).append( TextUtil.replaceEntities( value ) ).append( "\"" );
        }
    }

    private boolean validTargetValue( final String s ) {
        if( s.equals("_blank")
            || s.equals("_self")
            || s.equals("_parent")
            || s.equals("_top") ) {
            return true;
        } else if( !s.isEmpty() ) { // check [a-zA-z]
            final char c = s.charAt(0);
            return Character.isLowerCase(c) || Character.isUpperCase(c);
        }
        return false;
    }

}
