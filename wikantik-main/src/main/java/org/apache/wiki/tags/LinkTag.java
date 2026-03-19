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
package org.apache.wiki.tags;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Attachment;
import org.apache.wiki.api.core.ContextEnum;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.exceptions.ProviderException;
import org.apache.wiki.api.providers.WikiProvider;
import org.apache.wiki.attachment.AttachmentManager;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.parser.LinkParsingOperations;
import org.apache.wiki.parser.MarkupParser;
import org.apache.wiki.util.TextUtil;

import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.tagext.BodyContent;
import jakarta.servlet.jsp.tagext.BodyTag;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 *  Provides a generic link tag for all kinds of linking purposes.
 *  <p>
 *  If parameter <i>jsp</i> is defined, constructs a URL pointing to the specified JSP page, under the baseURL known by the Engine.
 *  Any ParamTag name-value pairs contained in the body are added to this URL to provide support for arbitrary JSP calls.
 *  <p>
 *  @since 2.3.50
 */
public class LinkTag extends WikiLinkTag implements ParamHandler, BodyTag {

    private static final long serialVersionUID = 0L;
    private static final Logger LOG = LogManager.getLogger( LinkTag.class );

    private String version;
    private String cssClass;
    private String style;
    private String title;
    private String target;
    private String compareToVersion;
    private String rel;
    private String jsp;
    private String ref;
    private String context = ContextEnum.PAGE_VIEW.getRequestContext();
    private String accesskey;
    private String tabindex;
    private String templatefile;

    private Map<String, String> containedParams;

    private BodyContent bodyContent;

    @Override
    public void initTag() {
        super.initTag();
        version = cssClass = style = title = target = compareToVersion = rel = jsp = ref = accesskey = templatefile = null;
        context = ContextEnum.PAGE_VIEW.getRequestContext();
        containedParams = new HashMap<>();
    }

    public void setTemplatefile( final String key )
    {
        templatefile = key;
    }

    public void setAccessKey( final String key )
    {
        accesskey = key;
    }

    public String getVersion()
    {
        return version;
    }

    public void setVersion( final String arg )
    {
        version = arg;
    }

    public void setCssClass( final String arg )
    {
        cssClass = arg;
    }

    public void setStyle( final String style )
    {
        this.style = style;
    }

    public void setTitle( final String title )
    {
        this.title = title;
    }

    public void setTarget( final String target )
    {
        this.target = target;
    }

    public void setTabindex( final String tabindex )
    {
        this.tabindex = tabindex;
    }

    public void setCompareToVersion( final String ver )
    {
        compareToVersion = ver;
    }

    public void setRel( final String rel )
    {
        this.rel = rel;
    }

    public void setRef( final String ref )
    {
        this.ref = ref;
    }

    public void setJsp( final String jsp )
    {
        this.jsp = jsp;
    }

    public void setContext( final String context )
    {
        this.context = context;
    }

    /**
     * Support for ParamTag supplied parameters in body.
     */
    @Override
    public void setContainedParameter( final String name, final String value ) {
        if( name != null ) {
            if( containedParams == null ) {
                containedParams = new HashMap<>();
            }
            containedParams.put( name, value );
        }
    }


    /**
     *  This method figures out what kind of an URL should be output.  It mirrors heavily on JSPWikiMarkupParser.handleHyperlinks();
     *
     * @return the URL
     * @throws ProviderException
     */
    private String figureOutURL() throws ProviderException {
        String url = null;
        final Engine engine = wikiContext.getEngine();

        if( pageName == null ) {
            final Page page = wikiContext.getPage();
            if( page != null ) {
                pageName = page.getName();
            }
        }

        if( templatefile != null ) {
            final String params = addParamsForRecipient( null, containedParams );
            final String template = engine.getTemplateDir();
            url = engine.getURL( ContextEnum.PAGE_NONE.getRequestContext(), "templates/"+template+"/"+templatefile, params );
        } else if( jsp != null ) {
            final String params = addParamsForRecipient( null, containedParams );
            //url = wikiContext.getURL( ContextEnum.PAGE_NONE.getRequestContext(), jsp, params );
            url = engine.getURL( ContextEnum.PAGE_NONE.getRequestContext(), jsp, params );
        } else if( ref != null ) {
            final int interwikipoint;
            if( new LinkParsingOperations( wikiContext ).isExternalLink(ref) ) {
                url = ref;
            } else if( ( interwikipoint = ref.indexOf( ":" ) ) != -1 ) {
                final String extWiki = ref.substring( 0, interwikipoint );
                final String wikiPage = ref.substring( interwikipoint+1 );

                url = engine.getInterWikiURL( extWiki );
                if( url != null ) {
                    url = TextUtil.replaceString( url, "%s", wikiPage );
                }
            } else if( ref.startsWith("#") ) {
                // Local link
            } else if( TextUtil.isNumber(ref) ) {
                // Reference
            } else {
                final int hashMark;

                final String parms = (version != null) ? "version="+getVersion() : null;

                //  Internal wiki link, but is it an attachment link?
                final Page p = engine.getManager( PageManager.class ).getPage( pageName );
                if( p instanceof Attachment ) {
                    url = wikiContext.getURL( ContextEnum.PAGE_ATTACH.getRequestContext(), pageName );
                } else if( (hashMark = ref.indexOf('#')) != -1 ) {
                    // It's an internal Wiki link, but to a named section

                    final String namedSection = ref.substring( hashMark+1 );
                    String reallink     = ref.substring( 0, hashMark );
                    reallink = MarkupParser.cleanLink( reallink );

                    String matchedLink;
                    String sectref = "";
                    if( ( matchedLink = engine.getFinalPageName( reallink ) ) != null ) {
                        sectref = "section-" + engine.encodeName( matchedLink ) + "-" + namedSection;
                        sectref = "#" + sectref.replace( '%', '_' );
                    } else {
                        matchedLink = reallink;
                    }

                    url = makeBasicURL( context, matchedLink, parms ) + sectref;
                } else {
                    final String reallink = MarkupParser.cleanLink( ref );
                    url = makeBasicURL( context, reallink, parms );
                }
            }
        } else if( pageName != null && !pageName.isEmpty() ) {
            final Page p = engine.getManager( PageManager.class ).getPage( pageName );

            String parms = (version != null) ? "version="+getVersion() : null;

            parms = addParamsForRecipient( parms, containedParams );

            if( p instanceof Attachment ) {
                String ctx = context;
                // Switch context appropriately when attempting to view an
                // attachment, but don't override the context setting otherwise
                if( context == null || context.equals( ContextEnum.PAGE_VIEW.getRequestContext() ) ) {
                    ctx = ContextEnum.PAGE_ATTACH.getRequestContext();
                }
                url = engine.getURL( ctx, pageName, parms );
                //url = wikiContext.getURL( ctx, pageName, parms );
            } else {
                url = makeBasicURL( context, pageName, parms );
            }
        } else {
            final String page = engine.getFrontPage();
            url = makeBasicURL( context, page, null );
        }

        return url;
    }

    private String addParamsForRecipient( final String addTo, final Map< String, String > params ) {
        if( params == null || params.isEmpty()) {
            return addTo;
        }
        final StringBuilder buf = new StringBuilder();
        final Iterator< Map.Entry< String, String > > it = params.entrySet().iterator();
        while( it.hasNext() ) {
            final Map.Entry< String, String > e = it.next();
            final String n = e.getKey();
            final String v = e.getValue();
            buf.append( n );
            buf.append( "=" );
            buf.append( v );
            if( it.hasNext() ) {
                buf.append( "&amp;" );
            }
        }
        if( addTo == null ) {
            return buf.toString();
        }
        if( !addTo.endsWith( "&amp;" ) ) {
            return addTo + "&amp;" + buf;
        }
        return addTo + buf;
    }

    private String makeBasicURL( final String context, final String page, String parms ) {
        final Engine engine = wikiContext.getEngine();

        if( context.equals( ContextEnum.PAGE_DIFF.getRequestContext() ) ) {
            int r1;
            int r2;

            if( DiffLinkTag.VER_LATEST.equals( getVersion() ) ) {
                final Page latest = engine.getManager( PageManager.class ).getPage( page, WikiProvider.LATEST_VERSION );

                r1 = latest.getVersion();
            } else if( DiffLinkTag.VER_PREVIOUS.equals(getVersion()) ) {
                r1 = wikiContext.getPage().getVersion() - 1;
                r1 = Math.max( r1, 1 );
            } else if( DiffLinkTag.VER_CURRENT.equals(getVersion()) ) {
                r1 = wikiContext.getPage().getVersion();
            } else {
                r1 = Integer.parseInt( getVersion() );
            }

            if( DiffLinkTag.VER_LATEST.equals(compareToVersion) ) {
                final Page latest = engine.getManager( PageManager.class ).getPage( page, WikiProvider.LATEST_VERSION );

                r2 = latest.getVersion();
            } else if( DiffLinkTag.VER_PREVIOUS.equals(compareToVersion) ) {
                r2 = wikiContext.getPage().getVersion() - 1;
                r2 = Math.max( r2, 1 );
            } else if( DiffLinkTag.VER_CURRENT.equals(compareToVersion) ) {
                r2 = wikiContext.getPage().getVersion();
            } else {
                r2 = Integer.parseInt( compareToVersion );
            }

            parms = "r1="+r1+"&amp;r2="+r2;
        }

        return engine.getURL( this.context, pageName, parms );
    }

    @Override
    public int doWikiStartTag() throws Exception {
        return EVAL_BODY_BUFFERED;
    }

    @Override
    public int doEndTag() {
        try {
            final Engine engine = wikiContext.getEngine();
            final JspWriter out = pageContext.getOut();
            final String url = figureOutURL();

            final StringBuilder sb = new StringBuilder( 20 );

            sb.append( (cssClass != null)   ? "class=\""+cssClass+"\" " : "" );
            sb.append( (style != null)   ? "style=\""+style+"\" " : "" );
            sb.append( (target != null ) ? "target=\""+target+"\" " : "" );
            sb.append( (title != null )  ? "title=\""+title+"\" " : "" );
            sb.append( (rel != null )    ? "rel=\""+rel+"\" " : "" );
            sb.append( (accesskey != null) ? "accesskey=\""+accesskey+"\" " : "" );
            sb.append( (tabindex != null) ? "tabindex=\""+tabindex+"\" " : "" );

            if( engine.getManager( PageManager.class ).getPage( pageName ) instanceof Attachment ) {
                sb.append( engine.getManager( AttachmentManager.class ).forceDownload( pageName ) ? "download " : "" );
            }

            switch( format ) {
              case URL:
                out.print( url );
                break;
              default:
              case ANCHOR:
                out.print("<a "+ sb +" href=\""+url+"\">");
                break;
            }

            // Add any explicit body content. This is not the intended use of LinkTag, but happens to be the way it has worked previously.
            if( bodyContent != null ) {
                final String linktext = bodyContent.getString().trim();
                out.write( linktext );
            }

            //  Finish off by closing opened anchor
            if( format == ANCHOR ) out.print("</a>");
        } catch( final Exception e ) {
            // Yes, we want to catch all exceptions here, including RuntimeExceptions
            LOG.error( "Tag failed", e );
        }

        return EVAL_PAGE;
    }

    @Override
    public void setBodyContent( final BodyContent bc )
    {
        bodyContent = bc;
    }

    @Override
    public void doInitBody() {
    }

}
