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
package com.wikantik.preferences;

import org.apache.commons.lang3.LocaleUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.InternalWikiException;
import com.wikantik.api.core.Context;
import com.wikantik.i18n.InternationalizationManager;

import jakarta.servlet.http.HttpServletRequest;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.TimeZone;


/**
 *  Represents an object which is used to store user preferences.
 */
public class Preferences extends HashMap< String,String > {

    private static final long serialVersionUID = 1L;

    /**
     * The name under which a Preferences object is stored in the HttpSession. Its value is {@value}.
     */
    public static final String SESSIONPREFS = "prefs";

    private static final Logger LOG = LogManager.getLogger( Preferences.class );

    /**
     *  Returns a preference value programmatically.
     *
     *  @param wikiContext the current wiki context
     *  @param name the preference key
     *  @return the preference value, or null if not found
     */
    public static String getPreference( final Context wikiContext, final String name ) {
        final HttpServletRequest request = wikiContext.getHttpRequest();
        if ( request == null ) {
            return null;
        }

        final Preferences prefs = (Preferences)request.getSession().getAttribute( SESSIONPREFS );
        if( prefs != null ) {
            return prefs.get( name );
        }

        return null;
    }

    /**
     * Get Locale according to user-preference settings or the user browser locale
     *
     * @param context The context to examine.
     * @return a Locale object.
     * @since 2.8
     */
    public static Locale getLocale( final Context context ) {
        Locale loc = null;

        final String langSetting = getPreference( context, "Language" );

        // parse language and construct valid Locale object
        if( langSetting != null ) {
            String language = "";
            String country  = "";
            String variant  = "";

            final String[] res = StringUtils.split( langSetting, "-_" );
            final int resLength = res.length;
            if( resLength > 2 ) {
                variant = res[ 2 ];
            }
            if( resLength > 1 ) {
                country = res[ 1 ];
            }
            if( resLength > 0 ) {
                language = res[ 0 ];
                loc = Locale.of( language, country, variant );
            }
        }

        // see if default locale is set server side
        if( loc == null ) {
            final String locale = context.getEngine().getWikiProperties().getProperty( "wikantik.preferences.default-locale" );
            try {
                loc = LocaleUtils.toLocale( locale );
            } catch( final IllegalArgumentException iae ) {
                LOG.error( iae.getMessage() );
            }
        }

        // otherwise try to find out the browser's preferred language setting, or use the JVM's default
        if( loc == null ) {
            final HttpServletRequest request = context.getHttpRequest();
            loc = ( request != null ) ? request.getLocale() : Locale.getDefault();
        }

        LOG.debug( "using locale {}", loc.toString() );
        return loc;
    }

    /**
     * Locates the i18n ResourceBundle given.  This method interprets the request locale, and uses that to figure out which language the
     * user wants.
     *
     * @param context {@link Context} holding the user's locale
     * @param bundle  The name of the bundle you are looking for.
     * @return A localized string (or from the default language, if not found)
     * @throws MissingResourceException If the bundle cannot be found
     * @see com.wikantik.i18n.InternationalizationManager
     */
    public static ResourceBundle getBundle( final Context context, final String bundle ) throws MissingResourceException {
        final Locale loc = getLocale( context );
        final InternationalizationManager i18n = context.getEngine().getManager( InternationalizationManager.class );
        return i18n.getBundle( bundle, loc );
    }

    /**
     * Get SimpleTimeFormat according to user browser locale and preferred time formats. If not found, it will revert to whichever format
     * is set for the default.
     *
     * @param context WikiContext to use for rendering.
     * @param tf Which version of the dateformat you are looking for?
     * @return A SimpleTimeFormat object which you can use to render
     * @since 2.8
     */
    public static SimpleDateFormat getDateFormat( final Context context, final TimeFormat tf ) {
        final InternationalizationManager imgr = context.getEngine().getManager( InternationalizationManager.class );
        final Locale clientLocale = getLocale( context );
        final String prefTimeZone = getPreference( context, "TimeZone" );
        String prefDateFormat;

        LOG.debug("Checking for preferences...");
        switch( tf ) {
            case DATETIME:
                prefDateFormat = getPreference( context, "DateFormat" );
                LOG.debug("Preferences fmt = {}", prefDateFormat);
                if( prefDateFormat == null ) {
                    prefDateFormat = imgr.get( InternationalizationManager.CORE_BUNDLE, clientLocale,"common.datetimeformat" );
                    LOG.debug("Using locale-format = {}", prefDateFormat);
                }
                break;

            case TIME:
                prefDateFormat = imgr.get( "common.timeformat" );
                break;

            case DATE:
                prefDateFormat = imgr.get( "common.dateformat" );
                break;

            default:
                throw new InternalWikiException( "Got a TimeFormat for which we have no value!" );
        }

        try {
            final SimpleDateFormat fmt = new SimpleDateFormat( prefDateFormat, clientLocale );
            if( prefTimeZone != null ) {
                final TimeZone tz = TimeZone.getTimeZone( prefTimeZone );
                // TimeZone tz = TimeZone.getDefault();
                // tz.setRawOffset(Integer.parseInt(prefTimeZone));
                fmt.setTimeZone( tz );
            }

            return fmt;
        } catch( final Exception e ) {
            return null;
        }
    }

    /**
     * A simple helper function to render a date based on the user preferences. This is useful for example for all plugins.
     *
     * @param context  The context which is used to get the preferences
     * @param date     The date to render.
     * @param tf       In which format the date should be rendered.
     * @return A ready-rendered date.
     * @since 2.8
     */
    public static String renderDate( final Context context, final Date date, final TimeFormat tf ) {
        final DateFormat df = getDateFormat( context, tf );
        return df.format( date );
    }

    /**
     *  Is used to choose between the different date formats that JSPWiki supports.
     *  <ul>
     *   <li>TIME: A time format, without  date</li>
     *   <li>DATE: A date format, without a time</li>
     *   <li>DATETIME: A date format, with a time</li>
     *  </ul>
     *
     *  @since 2.8
     */
    public enum TimeFormat {
        /** A time format, no date. */
        TIME,

        /** A date format, no time. */
        DATE,

        /** A date+time format. */
        DATETIME
    }

}
