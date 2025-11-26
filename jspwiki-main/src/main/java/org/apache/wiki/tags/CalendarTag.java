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
import org.apache.wiki.api.core.ContextEnum;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.util.HttpUtil;
import org.apache.wiki.util.TextUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.JspWriter;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;


/**
 *  Provides a nice calendar.  Responds to the following HTTP parameters:
 *  <ul>
 *  <li>calendar.date - If this parameter exists, then the calendar
 *  date is taken from the month and year.  The date must be in ddMMyy format.
 *  <li>weblog.startDate - If calendar.date parameter does not exist,
 *  we then check this date.
 *  </ul>
 *
 *  If neither calendar.date nor weblog.startDate parameters exist,
 *  then the calendar will default to the current month.
 *
 *  @since 2.0
 */
public class CalendarTag extends WikiTagBase {

    private static final long serialVersionUID = 0L;
    private static final Logger LOG = LogManager.getLogger( CalendarTag.class );
    private static final int NUM_PAGES_TO_CHECK = 3;

    private DateTimeFormatter m_pageFormat;
    private DateTimeFormatter m_urlFormat;
    private DateTimeFormatter m_monthUrlFormat;
    private boolean m_addIndex;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern( "ddMMyy" );

    /**
     *  {@inheritDoc}
     */
    @Override
    public void initTag() {
        super.initTag();
        m_pageFormat = m_urlFormat = m_monthUrlFormat = null;
    }

    /**
     *  Sets the page format.  If a page corresponding to the format is found when
     *  the calendar is being rendered, a link to that page is created.  E.g. if the
     *  format is set to <tt>'Main_blogentry_'ddMMyy</tt>, it works nicely in
     *  conjunction to the WeblogPlugin.
     *
     *  @param format The format in the DateTimeFormatter fashion.
     *
     *  @see DateTimeFormatter
     *  @see org.apache.wiki.plugin.WeblogPlugin
     */
    public void setPageformat( final String format ) {
        m_pageFormat = DateTimeFormatter.ofPattern( format );
    }

    /**
     *  Set the URL format.  If the pageformat is not set, all dates are
     *  links to pages according to this format.  The pageformat
     *  takes precedence.
     *
     *  @param format The URL format in the DateTimeFormatter fashion.
     *  @see DateTimeFormatter
     */
    public void setUrlformat( final String format ) {
        m_urlFormat = DateTimeFormatter.ofPattern( format );
    }

    /**
     *  Set the format to be used for links for the months.
     *
     *  @param format The format to set in the DateTimeFormatter fashion.
     *
     *  @see DateTimeFormatter
     */
    public void setMonthurlformat( final String format ) {
        m_monthUrlFormat = DateTimeFormatter.ofPattern( format );
    }

    /**
     *  Sets whether or not the pageFormat contains a page index at the end.
     *  This is the case for the WeblogPlugin.
     *
     *  @param addIndex Whether a page index should be appended to the pageFormat
     *
     *  @see org.apache.wiki.plugin.WeblogPlugin
     */
    public void setAddindex( final boolean addIndex ) {
        m_addIndex = addIndex;
    }

    private String format( final String txt ) {
        final Page p = m_wikiContext.getPage();
        if( p != null ) {
            return TextUtil.replaceString( txt, "%p", p.getName() );
        }
        return txt;
    }

    /**
     *  Returns a link to the given day.
     */
    private String getDayLink( final LocalDate day ) {
        final Engine engine = m_wikiContext.getEngine();
        final String result;

        if( m_pageFormat != null ) {
            final String pagename = day.format( m_pageFormat );

            var somePageExistsOnThisDay = false;
            if( m_addIndex ) {
                // Look at up to 3 pages for whether the page exists. This avoids an issue
                // with the WeblogPlugin when the first blog post(s) of a day gets deleted.
                for( int pageIdx = 1; pageIdx <= NUM_PAGES_TO_CHECK; pageIdx++ ) {
                    if( engine.getManager( PageManager.class ).wikiPageExists( pagename + pageIdx ) ) {
                        somePageExistsOnThisDay = true;
                        break;
                    }
                }
            } else {
                somePageExistsOnThisDay = engine.getManager( PageManager.class ).wikiPageExists( pagename );
            }

            if( somePageExistsOnThisDay ) {
                if( m_urlFormat != null ) {
                    final String url = day.format( m_urlFormat );
                    result = "<td class=\"link\"><a href=\"" + url + "\">" + day.getDayOfMonth() + "</a></td>";
                } else {
                    result = "<td class=\"link\"><a href=\"" + m_wikiContext.getViewURL( pagename ) + "\">" +
                             day.getDayOfMonth() + "</a></td>";
                }
            } else {
                result = "<td class=\"days\">" + day.getDayOfMonth() + "</td>";
            }
        } else if( m_urlFormat != null ) {
            final String url = day.format( m_urlFormat );
            result = "<td><a href=\"" + url + "\">" + day.getDayOfMonth() + "</a></td>";
        } else {
            result = "<td class=\"days\">" + day.getDayOfMonth() + "</td>";
        }

        return format( result );
    }

    private String getMonthLink( final YearMonth yearMonth ) {
        final DateTimeFormatter monthfmt = DateTimeFormatter.ofPattern( "MMMM yyyy" );
        final String result;

        if( m_monthUrlFormat == null ) {
            result = yearMonth.format( monthfmt );
        } else {
            final int firstDay = 1;
            final int lastDay = yearMonth.lengthOfMonth();
            final LocalDate lastDayOfMonth = yearMonth.atDay( lastDay );

            String url = lastDayOfMonth.format( m_monthUrlFormat );
            url = TextUtil.replaceString( url, "%d", Integer.toString( lastDay - firstDay + 1 ) );

            result = "<a href=\"" + url + "\">" + yearMonth.format( monthfmt ) + "</a>";
        }

        return format( result );
    }

    private String getMonthNaviLink( final YearMonth targetMonth, final String txt, String queryString ) {
        final String result;
        queryString = TextUtil.replaceEntities( queryString );

        // Get the first day of next month from today
        final YearMonth nextMonth = YearMonth.now().plusMonths( 1 );

        if( targetMonth.isBefore( nextMonth ) ) {
            final Page thePage = m_wikiContext.getPage();
            final String pageName = thePage.getName();

            final String calendarDate = targetMonth.atDay( 1 ).format( DATE_FORMAT );
            String url = m_wikiContext.getURL( ContextEnum.PAGE_VIEW.getRequestContext(), pageName, "calendar.date=" + calendarDate );
            final int queryStringLength = queryString.length();
            if( queryStringLength > 0 ) {
                //
                // Ensure that the 'calendar.date=ddMMyy' has been removed from the queryString
                //
                // FIXME: Might be useful to have an entire library of
                //        routines for this.  Will fail if it's not calendar.date
                //        but something else.

                final int pos1 = queryString.indexOf( "calendar.date=" );
                if( pos1 >= 0 ) {
                    String tmp = queryString.substring( 0, pos1 );
                    // FIXME: Will this fail when we use & instead of &amp?
                    // FIXME: should use some parsing routine
                    final int pos2 = queryString.indexOf( "&", pos1 ) + 1;
                    if( ( pos2 > 0 ) && ( pos2 < queryStringLength ) ) {
                        tmp = tmp + queryString.substring( pos2 );
                    }
                    queryString = tmp;
                }

                if( queryStringLength > 0 ) {
                    url = url + "&amp;" + queryString;
                }
            }
            result = "<td><a href=\"" + url + "\">" + txt + "</a></td>";
        } else {
            result = "<td> </td>";
        }

        return format( result );
    }

    /**
     *  {@inheritDoc}
     */
    @Override
    public final int doWikiStartTag() throws IOException {
        final Engine engine = m_wikiContext.getEngine();
        final JspWriter out = pageContext.getOut();

        LocalDate currentDate = LocalDate.now();

        //
        //  Check if there is a parameter in the request to set the date.
        //
        String calendarDate = pageContext.getRequest().getParameter( "calendar.date" );
        if( calendarDate == null ) {
            calendarDate = pageContext.getRequest().getParameter( "weblog.startDate" );
        }

        if( calendarDate != null ) {
            try {
                currentDate = LocalDate.parse( calendarDate, DATE_FORMAT );
            } catch( final DateTimeParseException e ) {
                LOG.warn( "date format wrong: " + calendarDate );
            }
        }

        final YearMonth yearMonth = YearMonth.from( currentDate );
        final YearMonth prevMonth = yearMonth.minusMonths( 1 );
        final YearMonth nextMonth = yearMonth.plusMonths( 1 );

        out.write( "<table class=\"calendar\">\n" );

        final HttpServletRequest httpServletRequest = m_wikiContext.getHttpRequest();
        final String queryString = HttpUtil.safeGetQueryString( httpServletRequest, engine.getContentEncoding() );
        out.write( "<tr>" +
                   getMonthNaviLink( prevMonth, "&lt;&lt;", queryString ) +
                   "<td colspan=5 class=\"month\">" +
                   getMonthLink( yearMonth ) +
                   "</td>" +
                   getMonthNaviLink( nextMonth, "&gt;&gt;", queryString ) +
                   "</tr>\n"
                 );

        // Find the first day to display (Monday of the week containing the 1st of the month)
        LocalDate day = yearMonth.atDay( 1 ).with( TemporalAdjusters.previousOrSame( DayOfWeek.MONDAY ) );

        out.write( "<tr><td class=\"weekdays\">Mon</td>" +
                   "<td class=\"weekdays\">Tue</td>" +
                   "<td class=\"weekdays\">Wed</td>" +
                   "<td class=\"weekdays\">Thu</td>" +
                   "<td class=\"weekdays\">Fri</td>" +
                   "<td class=\"weekdays\">Sat</td>" +
                   "<td class=\"weekdays\">Sun</td></tr>\n" );

        boolean noMoreDates = false;
        while( !noMoreDates ) {
            out.write( "<tr>" );

            for( int i = 0; i < 7; i++ ) {
                if( YearMonth.from( day ).equals( yearMonth ) ) {
                    out.write( getDayLink( day ) );
                } else {
                    out.write( "<td class=\"othermonth\">" + day.getDayOfMonth() + "</td>" );
                }

                day = day.plusDays( 1 );
            }

            if( !YearMonth.from( day ).equals( yearMonth ) ) {
                noMoreDates = true;
            }

            out.write( "</tr>\n" );
        }

        out.write( "</table>\n" );

        return EVAL_BODY_INCLUDE;
    }

}
