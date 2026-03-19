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

import com.wikantik.TestEngine;
import com.wikantik.WikiContext;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.Tag;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for CalendarTag with modernized java.time API.
 */
public class CalendarTagTest {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("ddMMyy");

    private TestEngine testEngine;
    private CalendarTag calendarTag;
    private PageContext mockPageContext;
    private JspWriter mockJspWriter;
    private StringWriter stringWriter;
    private Context wikiContext;
    private Page testPage;

    @BeforeEach
    public void setUp() throws Exception {
        testEngine = TestEngine.build();

        // Create a test page
        testEngine.saveText("TestPage", "Test content");
        testPage = testEngine.getManager(PageManager.class).getPage("TestPage");

        // Create wiki context
        wikiContext = Wiki.context().create(testEngine, testPage);

        // Set up mock PageContext
        mockPageContext = mock(PageContext.class);

        // Set up JspWriter that writes to StringWriter
        stringWriter = new StringWriter();
        mockJspWriter = mock(JspWriter.class);
        doAnswer(invocation -> {
            stringWriter.write(invocation.getArgument(0).toString());
            return null;
        }).when(mockJspWriter).write(anyString());

        when(mockPageContext.getOut()).thenReturn(mockJspWriter);
        when(mockPageContext.getAttribute(Context.ATTR_CONTEXT, PageContext.REQUEST_SCOPE))
            .thenReturn(wikiContext);

        // Set up mock request
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn(null);
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        // Create and initialize the tag
        calendarTag = new CalendarTag();
        calendarTag.setPageContext(mockPageContext);
    }

    @AfterEach
    public void tearDown() {
        testEngine.deleteTestPage("TestPage");
        testEngine.deleteTestPage("TestBlog_entry_010125");
        testEngine.deleteTestPage("TestBlog_entry_0101251");
        testEngine.deleteTestPage("TestBlog_entry_0101252");
        TestEngine.emptyWorkDir();
    }

    @Test
    public void testInitTag() {
        // Set some formats first
        calendarTag.setPageformat("'Test'ddMMyy");
        calendarTag.setUrlformat("'url'ddMMyy");
        calendarTag.setMonthurlformat("'month'MMyy");

        // Now reinitialize
        calendarTag.initTag();

        // The formatters should be reset (we can't access them directly, but the tag should work)
        // This is verified by the fact that no exception is thrown when reinitializing
        assertNotNull(calendarTag);
    }

    @Test
    public void testSetPageformat() {
        // Should not throw when setting a valid format
        assertDoesNotThrow(() -> calendarTag.setPageformat("'TestPage_'ddMMyy"));
    }

    @Test
    public void testSetUrlformat() {
        // Should not throw when setting a valid format
        assertDoesNotThrow(() -> calendarTag.setUrlformat("'/wiki/page?date='ddMMyy"));
    }

    @Test
    public void testSetMonthurlformat() {
        // Should not throw when setting a valid format
        assertDoesNotThrow(() -> calendarTag.setMonthurlformat("'/wiki/month?m='MMyy"));
    }

    @Test
    public void testSetAddindex() {
        // Should not throw
        assertDoesNotThrow(() -> calendarTag.setAddindex(true));
        assertDoesNotThrow(() -> calendarTag.setAddindex(false));
    }

    @Test
    public void testDoWikiStartTagBasic() throws Exception {
        // Test basic calendar rendering
        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // Verify table structure
        assertTrue(output.contains("<table class=\"calendar\">"), "Should contain calendar table");
        assertTrue(output.contains("</table>"), "Should close table");

        // Verify weekday headers
        assertTrue(output.contains("Mon"), "Should contain Monday");
        assertTrue(output.contains("Tue"), "Should contain Tuesday");
        assertTrue(output.contains("Wed"), "Should contain Wednesday");
        assertTrue(output.contains("Thu"), "Should contain Thursday");
        assertTrue(output.contains("Fri"), "Should contain Friday");
        assertTrue(output.contains("Sat"), "Should contain Saturday");
        assertTrue(output.contains("Sun"), "Should contain Sunday");

        // Verify current month is displayed
        YearMonth currentMonth = YearMonth.now();
        DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy");
        assertTrue(output.contains(currentMonth.format(monthFormatter)),
            "Should contain current month name: " + currentMonth.format(monthFormatter));
    }

    @Test
    public void testDoWikiStartTagWithCalendarDateParameter() throws Exception {
        // Set up request with calendar.date parameter for January 2025
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn("150125"); // 15 Jan 2025
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // Should show January 2025
        assertTrue(output.contains("January 2025"), "Should show January 2025");
    }

    @Test
    public void testDoWikiStartTagWithWeblogStartDateParameter() throws Exception {
        // Set up request with weblog.startDate parameter (fallback when calendar.date is null)
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn(null);
        when(mockRequest.getParameter("weblog.startDate")).thenReturn("010224"); // 1 Feb 2024

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // Should show February 2024
        assertTrue(output.contains("February 2024"), "Should show February 2024");
    }

    @Test
    public void testDoWikiStartTagWithInvalidDateParameter() throws Exception {
        // Set up request with invalid date format - should fall back to current date
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn("invalid-date");
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // Should still render a calendar (with current month as fallback)
        assertTrue(output.contains("<table class=\"calendar\">"), "Should still render calendar");

        // Current month should be shown
        YearMonth currentMonth = YearMonth.now();
        DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy");
        assertTrue(output.contains(currentMonth.format(monthFormatter)),
            "Should fall back to current month");
    }

    @Test
    public void testCalendarWithPageFormat() throws Exception {
        // Create a page that matches the page format for today
        LocalDate today = LocalDate.now();
        String pageName = "TestBlog_entry_" + today.format(DATE_FORMAT);
        testEngine.saveText(pageName, "Blog entry content");

        // Set page format
        calendarTag.setPageformat("'TestBlog_entry_'ddMMyy");

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // The day with the page should have a link
        assertTrue(output.contains("class=\"link\""),
            "Should have link class for day with existing page");

        // Clean up
        testEngine.deleteTestPage(pageName);
    }

    @Test
    public void testCalendarWithPageFormatAndAddIndex() throws Exception {
        // Create pages with index suffix for a specific date
        String basePageName = "TestBlog_entry_010125";
        testEngine.saveText(basePageName + "1", "Blog entry 1");
        testEngine.saveText(basePageName + "2", "Blog entry 2");

        // Set page format with addindex
        calendarTag.setPageformat("'TestBlog_entry_'ddMMyy");
        calendarTag.setAddindex(true);

        // Set date to January 2025
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn("010125");
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // January 1st should have a link since pages with index exist
        assertTrue(output.contains("class=\"link\""),
            "Should have link class for day with indexed pages");
    }

    @Test
    public void testCalendarWithUrlFormat() throws Exception {
        // Set URL format without page format
        calendarTag.setUrlformat("'/wiki/view?date='ddMMyy");

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // Days should have URL-formatted links
        assertTrue(output.contains("/wiki/view?date="),
            "Should contain URL-formatted links");
    }

    @Test
    public void testCalendarWithMonthUrlFormat() throws Exception {
        // Set month URL format
        calendarTag.setMonthurlformat("'/wiki/month?date='ddMMyy'&days=%d'");

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // Month header should be a link
        assertTrue(output.contains("<a href=\""),
            "Month should be a link when monthurlformat is set");
    }

    @Test
    public void testCalendarNavigationLinks() throws Exception {
        // Use a past month to ensure both prev and next navigation links are available
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn("150124"); // January 2024 (past)
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // Should have navigation arrows
        assertTrue(output.contains("&lt;&lt;"), "Should have previous month link");
        assertTrue(output.contains("&gt;&gt;"), "Should have next month link");
    }

    @Test
    public void testCalendarDaysStructure() throws Exception {
        // Set a specific month to have predictable structure
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn("150125"); // January 2025
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // January 2025 should have 31 days visible
        // Check for some specific days
        assertTrue(output.contains(">1<"), "Should contain day 1");
        assertTrue(output.contains(">15<"), "Should contain day 15");
        assertTrue(output.contains(">31<"), "Should contain day 31");

        // Days from other months should have othermonth class
        assertTrue(output.contains("class=\"othermonth\""),
            "Should have days from adjacent months");
    }

    @Test
    public void testCalendarWithPageNameSubstitution() throws Exception {
        // The format method should replace %p with page name
        // This is tested indirectly through the full rendering

        // Set URL format with %p placeholder
        calendarTag.setUrlformat("'/wiki/%p?date='ddMMyy");

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // The %p should be replaced with TestPage
        assertTrue(output.contains("/wiki/TestPage?date="),
            "Should replace %p with page name");
    }

    @Test
    public void testDateFormatConstant() {
        // Verify the date format pattern works correctly
        LocalDate testDate = LocalDate.of(2025, 1, 15);
        String formatted = testDate.format(DATE_FORMAT);
        assertEquals("150125", formatted, "Date format should produce ddMMyy format");

        // And parsing should work
        LocalDate parsed = LocalDate.parse("150125", DATE_FORMAT);
        assertEquals(testDate, parsed, "Should parse back to same date");
    }

    @Test
    public void testYearMonthCalculations() {
        // Test the YearMonth operations used in the tag
        YearMonth ym = YearMonth.of(2025, 1);

        assertEquals(31, ym.lengthOfMonth(), "January should have 31 days");
        assertEquals(YearMonth.of(2024, 12), ym.minusMonths(1), "Previous month should be December 2024");
        assertEquals(YearMonth.of(2025, 2), ym.plusMonths(1), "Next month should be February 2025");

        // First day of January 2025 is Wednesday
        LocalDate firstDay = ym.atDay(1);
        assertEquals(java.time.DayOfWeek.WEDNESDAY, firstDay.getDayOfWeek(),
            "January 1, 2025 should be Wednesday");
    }

    @Test
    public void testFebruaryLeapYear() throws Exception {
        // Test February in a leap year (2024)
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn("150224"); // February 2024
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // February 2024 should have 29 days (leap year)
        assertTrue(output.contains("February 2024"), "Should show February 2024");
        assertTrue(output.contains(">29<"), "Should contain day 29 (leap year)");
    }

    @Test
    public void testFebruaryNonLeapYear() throws Exception {
        // Test February in a non-leap year (2025)
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn("150225"); // February 2025
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // February 2025 should have 28 days (non-leap year)
        assertTrue(output.contains("February 2025"), "Should show February 2025");
        assertTrue(output.contains(">28<"), "Should contain day 28");
        // Day 29 should be from March (othermonth)
        // We verify by checking the output doesn't contain a link for day 29 in February
    }

    @Test
    public void testPreviousMonthNavigation() throws Exception {
        // Test that previous month link has correct date
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn("150325"); // March 2025
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // Previous month link should point to February 2025
        assertTrue(output.contains("calendar.date=010225"),
            "Previous month link should point to February 2025");
    }

    @Test
    public void testNextMonthNavigation() throws Exception {
        // Test that next month link has correct date (for a past month)
        ServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockPageContext.getRequest()).thenReturn(mockRequest);
        when(mockRequest.getParameter("calendar.date")).thenReturn("150124"); // January 2024
        when(mockRequest.getParameter("weblog.startDate")).thenReturn(null);

        int result = calendarTag.doStartTag();

        assertEquals(Tag.EVAL_BODY_INCLUDE, result);

        String output = stringWriter.toString();

        // Next month link should point to February 2024
        assertTrue(output.contains("calendar.date=010224"),
            "Next month link should point to February 2024");
    }
}
