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
package com.wikantik.tags;

import java.io.IOException;

import jakarta.servlet.jsp.jstl.fmt.LocaleSupport;

import com.wikantik.util.TextUtil;


/**
 *  Calculate pagination string. Used for page-info and search results
 *
 *  <P><B>Attributes</B></P>
 *  <UL>
 *    <LI> start - start item of the page to be highlighted
 *    <LI> total - total number of items
 *    <LI> pagesize - total number of items per page
 *    <LI> maxlinks - number of page links to be generated
 *    <LI> fmtkey - pagination prefix of the i18n resource keys. Following keys are used:
 *    <br>fmtkey="info.pagination"
 *      <UL>
 *        <LI> info.pagination.first=<span class="first">First</span>
 *        <LI> info.pagination.last=<span class="last">Last</span>
 *        <LI> info.pagination.previous=<span class="prev">Previous</span>
 *        <LI> info.pagination.next=<span class="next">Next</span>
 *        <LI> info.pagination.all=<span class="all">all</span>
 *        <LI> info.pagination.total=&nbsp;(Total items: {0} ) 
 *        <LI> info.pagination.show.title=Show items from {0} to {1}
 *        <LI> info.pagination.showall.title=Show all items
 *      </UL>
 *  </UL>
 *  <P>Following optional attributes can be parameterized with '%s' (item count)</P>
 *  <UL>
 *    <LI> href - href of each page link. (optional)
 *    <LI> onclick - onclick of each page link. (optional)
 *  </UL>
 *
 *  @since 2.5.109
 */
public class SetPaginationTag
    extends WikiTagBase
{
    private static final long serialVersionUID = 0L;
    private static final int ALLITEMS = -1;

    private int start;
    private int total;
    private int pagesize;
    private int maxlinks;
    private String fmtkey;
    private String href;
    private String onclick;

    @Override
    public void initTag()
    {
        super.initTag();
        start = 0;
        total = 0;
        pagesize = 20;
        maxlinks = 9;
        fmtkey = null;
        href = null;
        onclick = null;
    }

    public void setStart(final int arg)
    {
        start = arg;
    }

    public void setTotal(final int arg)
    {
        total = arg;
    }

    public void setPagesize(final int arg)
    {
        pagesize = arg;
    }

    public void setMaxlinks(final int arg)
    {
        maxlinks = arg;
        if( maxlinks % 2 == 0 ) maxlinks--; /* must be odd */
    }

    public void setFmtkey(final String arg)
    {
        fmtkey = arg;
    }

    public void setHref(final String arg)
    {
        href = arg;
    }

    public void setOnclick(final String arg)
    {
        onclick = arg;
    }


    // 0 20 40 60
    // 0 20 40 60 80 next last
    // first previous 20 40 *60* 80 100 next last
    // fist previous 40 60 80 100 120
    @Override
    public int doWikiStartTag()
        throws IOException
    {
        if( total <= pagesize ) return SKIP_BODY;

        final StringBuilder pagination = new StringBuilder();

        if( start > total ) start = total;
        if( start < ALLITEMS ) start = 0;

        final int maxs = pagesize * maxlinks;
        final int mids = pagesize * ( maxlinks / 2 );

        pagination.append( "<div class='pagination'>");

        pagination.append( LocaleSupport.getLocalizedMessage( pageContext, fmtkey ) ).append( " " );

        int cursor = 0;
        int cursormax = total;

        if( total > maxs )   //need to calculate real window ends
        {
          if( start > mids ) cursor = start - mids;
          if( (cursor + maxs) > total )
            cursor = ( ( 1 + total/pagesize ) * pagesize ) - maxs ;

          cursormax = cursor + maxs;
        }


        if( ( start == ALLITEMS ) || (cursor > 0) )
        {
            appendLink ( pagination, 0, fmtkey + ".first" );
        }


        if( (start != ALLITEMS ) && (start-pagesize >= 0) )
        {
            appendLink( pagination, start-pagesize, fmtkey + ".previous" );
        }

        if( start != ALLITEMS )
        {
          while( cursor < cursormax )
          {
            if( cursor == start )
            {
              pagination.append( "<span class='cursor'>" );
              pagination.append( 1 + cursor/pagesize );
              pagination.append( "</span>" );
            }
            else
            {
              appendLink( pagination, cursor, 1+cursor/pagesize );
            }
            cursor += pagesize;
          }
        }


        if( (start != ALLITEMS ) && (start + pagesize < total) )
        {
            appendLink( pagination, start+pagesize, fmtkey + ".next" );

        if( (start == ALLITEMS ) || (cursormax < total) )
          appendLink ( pagination, ( (total/pagesize) * pagesize ), fmtkey + ".last" );
        }

        if( start == ALLITEMS )
        {
          pagination.append( "<span class='cursor'>" );
          pagination.append( LocaleSupport.getLocalizedMessage(pageContext, fmtkey + ".all" ) );
          pagination.append( "</span>&nbsp;&nbsp;" );
        }
        else
        {
          appendLink ( pagination, ALLITEMS, fmtkey + ".all" );
        }

        //(Total items: " + total + ")" );
        pagination.append( LocaleSupport.getLocalizedMessage(pageContext, fmtkey + ".total",
                           new Object[]{ total } ) );

        pagination.append( "</div>" );


        /* +++ processing done +++ */

        final String paginationHtml = pagination.toString();

        pageContext.getOut().println( paginationHtml );

        pageContext.setAttribute( "pagination", paginationHtml ); /* and cache for later use in page context */

        return SKIP_BODY;
    }


    /**
     * Generate pagination links <a href='' title='' onclick=''>text</a>
     * for pagination blocks starting a page.
     * Uses href and onclick as attribute patterns
     * '%s' in the patterns are replaced with page offset
     *
     * @param sb  : stringbuilder to write output to
     * @param page : start of page block
     *
     **/
    private void appendLink(final StringBuilder sb, final int page, final String fmttextkey )
    {
        appendLink2( sb, page, LocaleSupport.getLocalizedMessage( pageContext, fmttextkey ) );
    }
    private void appendLink(final StringBuilder sb, final int page, final int paginationblock )
    {
        appendLink2( sb, page, Integer.toString( paginationblock ) );
    }
    private void appendLink2(final StringBuilder sb, final int page, final String text )
    {
        sb.append( "<a title=\"" );
        if( page == ALLITEMS )
        {
            sb.append( LocaleSupport.getLocalizedMessage( pageContext, fmtkey + ".showall.title" ) );
        }
        else
        {
            sb.append( LocaleSupport.getLocalizedMessage( pageContext, fmtkey + ".show.title",
                       new Object[]{ page + 1, page + pagesize } ) );
        }
        sb.append( "\" " );

        if( href != null )
        {
            sb.append( "href=\"" );
            sb.append( TextUtil.replaceString( href, "%s", Integer.toString( page ) ) );
            sb.append( "\" " );
        }

        if( onclick != null )
        {
            sb.append( "onclick=\"" );
            sb.append( TextUtil.replaceString( onclick, "%s", Integer.toString( page ) ) );
            sb.append( "\" " );
        }

        sb.append( ">" );
        sb.append( text );
        sb.append( "</a>" );
    }

}
