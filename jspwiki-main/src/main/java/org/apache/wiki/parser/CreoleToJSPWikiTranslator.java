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
package org.apache.wiki.parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>Translates Creole markp to JSPWiki markup. Simple translator uses regular expressions.
 * See http://www.wikicreole.org for the WikiCreole spec.</p>
 *
 * <p>This translator can be configured through properties defined in
 * jspwiki.properties starting with "creole.*". See the
 * jspwiki.properties file for an explanation of the properties</p>
 *
 * <p><b>WARNING</b>: This is an experimental feature, and known to be
 * broken.  Use at your own risk.</o>
 *
 *
 * @see <a href="http://www.wikicreole.org/">Wiki Creole Spec</a>
 */
public class CreoleToJSPWikiTranslator
{

    // These variables are expanded so that admins
    // can display information about the current installed
    // pagefilter
    //
    // The syntax is the same as a wiki var. Unlike a wiki
    // war though, the CreoleTranslator itself
    //
    // [{$creolepagefilter.version}]
    // [{$creolepagefilter.creoleversion}]
    // [{$creolepagefilter.linebreak}] -> bloglike/wikilike

    /** The version of the filter. */
    public static final String VAR_VERSION = "1.0.3";

    /** The version of Creole that this filter supports. */
    public static final String VAR_CREOLE_VERSION = "1.0";

    /** The linebreak style "bloglike". */
    public static final String VAR_LINEBREAK_BLOGLIKE = "bloglike";

    /** The linebreak style "c2like". */
    public static final String VAR_LINEBREAK_C2LIKE = "c2like";

    private static final String CREOLE_BOLD = "\\*\\*((?s:.)*?)(\\*\\*|(\n\n|\r\r|\r\n\r\n))";

    private static final String JSPWIKI_BOLD = "__$1__$3";

    private static final String CREOLE_ITALIC = "//((?s:.)*?)(//|(\n\n|\r\r|\r\n\r\n))";

    private static final String JSPWIKI_ITALIC = "''$1''$3";

    private static final String CREOLE_SIMPLELINK = "\\[\\[([^\\]]*?)\\]\\]";

    private static final String JSPWIKI_SIMPLELINK = "[$1]";

    private static final String CREOLE_LINK = "\\[\\[([^\\]]*?)\\|([^\\[\\]]*?)\\]\\]";

    private static final String JSPWIKI_LINK = "[$2|$1]";

    private static final String CREOLE_HEADER_0 = "(\n|\r|\r\n|^)=([^=\\r\\n]*)={0,2}";

    private static final String JSPWIKI_HEADER_0 = "$1!!!$2";

    private static final String CREOLE_HEADER_1 = "(\n|\r|\r\n|^)==([^=\\r\\n]*)={0,2}";

    private static final String JSPWIKI_HEADER_1 = "$1!!!$2";

    private static final String CREOLE_HEADER_2 = "(\n|\r|\r\n|^)===([^=\\r\\n]*)={0,3}";

    private static final String JSPWIKI_HEADER_2 = "$1!!$2";

    private static final String CREOLE_HEADER_3 = "(\n|\r|\r\n|^)====([^=\\r\\n]*)={0,4}";

    private static final String JSPWIKI_HEADER_3 = "$1!$2";

    private static final String CREOLE_HEADER_4 = "(\n|\r|\r\n|^)=====([^=\\r\\n]*)={0,5}";

    private static final String JSPWIKI_HEADER_4 = "$1__$2__";

    private static final String CREOLE_SIMPLEIMAGE = "\\{\\{([^\\}]*?)\\}\\}";

    private static final String JSPWIKI_SIMPLEIMAGE = "[{Image src='$1'}]";

    private static final String CREOLE_IMAGE = "\\{\\{([^\\}]*?)\\|([^\\}]*?)\\}\\}";

    private static final String JSPWIKI_IMAGE = "[{Image src='$1' caption='$2'}]";

    private static final String CREOLE_IMAGE_LINK = "\\[\\[(.*?)\\|\\{\\{(.*?)\\}\\}\\]\\]";

    private static final String JSPWIKI_IMAGE_LINK = "[{Image src='$2' link='$1'}]";

    private static final String CREOLE_IMAGE_LINK_DESC = "\\[\\[(.*?)\\|\\{\\{(.*?)\\|(.*?)\\}\\}\\]\\]";

    private static final String JSPWIKI_IMAGE_LINK_DESC = "[{Image src='$2' link='$1' caption='$3'}]";

    private static final String PREFORMATTED_PROTECTED = "\\Q{{{\\E.*?\\Q}}}\\E";

    //private static final String CREOLE_LINEBREAKS = "([^\\s\\\\])(\r\n|\r|\n)+(?=[^\\s\\*#])";

    //private static final String JSPWIKI_LINEBREAKS = "$1\\\\\\\\$2";

    private static final String CREOLE_TABLE = "(\n|\r|\r\n|^)(\\|[^\n\r]*)\\|(\\t| )*(\n|\r|\r\n|$)";

    private static final String CREOLE_PLUGIN = "\\<\\<((?s:.)*?)\\>\\>";

    private static final String JSPWIKI_PLUGIN = "[{$1}]";

    private static final String WWW_URL = "(\\[\\[)\\s*(www\\..*?)(\\]\\])";

    private static final String HTTP_URL = "$1http://$2$3";

    private static final String CREOLE_IMAGE_X = "\\{\\{(.*?)((\\|)(.*?)){0,1}((\\|)(.*?)){0,1}\\}\\}";

    private static final String JSPWIKI_IMAGE_X = "[{\u2016 src='$1' caption='$4' \u2015}]";

    private static final String CREOLE_LINK_IMAG_X = "\\[\\[([^|]*)\\|\\{\\{([^|]*)((\\|)([^|]*)){0,1}((\\|)([^}]*)){0,1}\\}\\}\\]\\]";

    private static final String JSPWIKI_LINK_IMAGE_X = "[{\u2016 src='$2' link='$1' caption='$5' \u2015}]";

    private static final String JSPWIKI_TABLE = "$1$2$4";

    /* TODO Is it possible to use just protect :// ? */
    private static final String URL_PROTECTED = "http://|ftp://|https://";

    private static final String TABLE_HEADER_PROTECTED = "((\n|\r|\r\n|^)(\\|.*?)(\n|\r|\r\n|$))";

    private static final String SIGNATURE = "--~~~";

    private static final String SIGNATURE_AND_DATE = "--~~~~";

    private static final String DEFAULT_DATEFORMAT = "yyyy-MM-dd";

    private static final String ESCAPE_PROTECTED = "~(\\*\\*|~|//|-|#|\\{\\{|}}|\\\\|~\\[~~[|]]|----|=|\\|)";

    private static final Map<String, String> c_protectionMap = new HashMap<>();

    /**
     * Cache for compiled patterns. Pattern compilation is expensive, so we cache patterns
     * that are used repeatedly with the same regex string.
     */
    private static final Map<String, Pattern> COMPILED_PATTERNS = new ConcurrentHashMap<>();

    // Pre-compiled patterns for frequently used regex patterns (with MULTILINE | DOTALL flags)
    private static final Pattern PATTERN_PREFORMATTED = Pattern.compile( PREFORMATTED_PROTECTED, Pattern.MULTILINE | Pattern.DOTALL );
    private static final Pattern PATTERN_URL = Pattern.compile( URL_PROTECTED, Pattern.MULTILINE | Pattern.DOTALL );
    private static final Pattern PATTERN_ESCAPE = Pattern.compile( ESCAPE_PROTECTED, Pattern.MULTILINE | Pattern.DOTALL );
    private static final Pattern PATTERN_PLUGIN = Pattern.compile( CREOLE_PLUGIN, Pattern.MULTILINE | Pattern.DOTALL );
    private static final Pattern PATTERN_TABLE_HEADER = Pattern.compile( TABLE_HEADER_PROTECTED, Pattern.MULTILINE | Pattern.DOTALL );

    // Pre-compiled patterns for replaceImageArea (with MULTILINE | DOTALL flags)
    private static final Pattern PATTERN_CREOLE_LINK_IMAGE_X = Pattern.compile( CREOLE_LINK_IMAG_X, Pattern.MULTILINE | Pattern.DOTALL );
    private static final Pattern PATTERN_CREOLE_IMAGE_X = Pattern.compile( CREOLE_IMAGE_X, Pattern.MULTILINE | Pattern.DOTALL );

    // Pre-compiled patterns for simple replacements in replaceImageArea
    private static final Pattern PATTERN_HORIZONTAL_BAR = Pattern.compile( "\u2015" );
    private static final Pattern PATTERN_DOUBLE_VERTICAL_BAR = Pattern.compile( "\u2016" );
    private static final Pattern PATTERN_EMPTY_CAPTION = Pattern.compile( "caption=''" );
    private static final Pattern PATTERN_WHITESPACE = Pattern.compile( "\\s+" );

    private        ArrayList<String> m_hashList = new ArrayList<>();

    /**
     * Returns a cached compiled Pattern or compiles and caches a new one.
     * This avoids repeated pattern compilation for the same regex string.
     *
     * @param regex The regex pattern string
     * @param flags The pattern flags (e.g., Pattern.MULTILINE | Pattern.DOTALL)
     * @return The compiled Pattern
     */
    private static Pattern getOrCompilePattern(final String regex, final int flags) {
        final String cacheKey = regex + ":" + flags;
        return COMPILED_PATTERNS.computeIfAbsent(cacheKey, k -> Pattern.compile(regex, flags));
    }

    /**
     *  Translates signature markup (--~~~ and --~~~~) to wiki format with username and optional date.
     *
     * @param wikiProps A property set
     * @param content The content to translate
     * @param username The username in the signature
     * @return Content with translated signatures
     */
    public String translateSignature(final Properties wikiProps, final String content, final String username)
    {
        String dateFormat = wikiProps.getProperty("creole.dateFormat");

        if (dateFormat == null)
        {
            dateFormat = DEFAULT_DATEFORMAT;
        }

        DateTimeFormatter df;
        try
        {
            df = DateTimeFormatter.ofPattern(dateFormat);
        }
        catch (final Exception e)
        {
            e.printStackTrace();
            df = DateTimeFormatter.ofPattern(DEFAULT_DATEFORMAT);
        }

        String result = content;
        result = protectMarkup(result, PREFORMATTED_PROTECTED, "", "");
        result = protectMarkup(result, URL_PROTECTED, "", "");

        final ZonedDateTime now = ZonedDateTime.now();
        result = translateElement(result, SIGNATURE_AND_DATE, "-- [[" + username + "]], " + now.format(df));
        result = translateElement(result, SIGNATURE, "-- [[" + username + "]]");
        result = unprotectMarkup(result, false);
        return result;
    }

    /**
     *  Translates Creole markup to JSPWiki markup
     *
     *  @param wikiProps A set of Wiki Properties
     *  @param content Creole markup
     *  @return Wiki markup
     */
    public String translate(final Properties wikiProps, final String content)
    {
        final boolean blogLineBreaks = false;
        /*
        // BROKEN, breaks on different platforms.
        String tmp = wikiProps.getProperty("creole.blogLineBreaks");
        if (tmp != null)
        {
            if (tmp.trim().equals("true"))
                blogLineBreaks = true;
        }
        */
        final String imagePlugin = wikiProps.getProperty("creole.imagePlugin.name");

        String result = content;
        //
        // Breaks on OSX.  It is never a good idea to tamper with the linebreaks.  JSPWiki always
        // stores linebreaks as \r\n, regardless of the platform.
        //result = result.replace("\r\n", "\n");
        //result = result.replace("\r", "\n");

        /* Now protect the rest */
        result = protectMarkup(result);
        result = translateLists(result, "*", "-", "Nothing");
        result = translateElement(result, CREOLE_BOLD, JSPWIKI_BOLD);
        result = translateElement(result, CREOLE_ITALIC, JSPWIKI_ITALIC);
        result = translateElement(result, WWW_URL, HTTP_URL);

        if (imagePlugin != null && !imagePlugin.equals(""))
        {
            result = this.replaceImageArea(wikiProps, result, CREOLE_LINK_IMAG_X, JSPWIKI_LINK_IMAGE_X, 6, imagePlugin);
            result = this.replaceImageArea(wikiProps, result, CREOLE_IMAGE_X, JSPWIKI_IMAGE_X, 5, imagePlugin);
        }
        result = translateElement(result, CREOLE_IMAGE_LINK_DESC, JSPWIKI_IMAGE_LINK_DESC);
        result = translateElement(result, CREOLE_IMAGE_LINK, JSPWIKI_IMAGE_LINK);
        result = translateElement(result, CREOLE_LINK, JSPWIKI_LINK);
        result = translateElement(result, CREOLE_SIMPLELINK, JSPWIKI_SIMPLELINK);
        result = translateElement(result, CREOLE_HEADER_4, JSPWIKI_HEADER_4);
        result = translateElement(result, CREOLE_HEADER_3, JSPWIKI_HEADER_3);
        result = translateElement(result, CREOLE_HEADER_2, JSPWIKI_HEADER_2);
        result = translateElement(result, CREOLE_HEADER_1, JSPWIKI_HEADER_1);
        result = translateElement(result, CREOLE_HEADER_0, JSPWIKI_HEADER_0);
        result = translateElement(result, CREOLE_IMAGE, JSPWIKI_IMAGE);
        result = translateLists(result, "-", "*", "#");
        result = translateElement(result, CREOLE_SIMPLEIMAGE, JSPWIKI_SIMPLEIMAGE);
        result = translateElement(result, CREOLE_TABLE, JSPWIKI_TABLE);
        result = replaceArea(result, TABLE_HEADER_PROTECTED, "\\|=([^\\|]*)=|\\|=([^\\|]*)", "||$1$2");

        /*
        if (blogLineBreaks)
        {
            result = translateElement(result, CREOLE_LINEBREAKS, JSPWIKI_LINEBREAKS);
        }
        */
        result = unprotectMarkup(result, true);

        result = translateVariables(result, blogLineBreaks);
        //result = result.replace("\n", System.getProperty("line.separator"));
        return result;
    }

    /** Translates lists. */
    private static String translateLists(final String content, final String sourceSymbol, final String targetSymbol, final String sourceSymbol2)
    {
        final String[] lines = content.split("\n");
        final StringBuilder result = new StringBuilder();
        int counter = 0;
        int inList = -1;
        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i];
            String actSourceSymbol = "";
            while ((line.startsWith(sourceSymbol) || line.startsWith(sourceSymbol2))
                   && (actSourceSymbol.equals("") || line.substring(0, 1).equals(actSourceSymbol)))
            {
                actSourceSymbol = line.substring(0, 1);
                line = line.substring( 1 );
                counter++;
            }
            if ((inList == -1 && counter != 1) || (inList != -1 && inList + 1 < counter))
            {
                result.append(actSourceSymbol.repeat(Math.max(0, counter)));
                inList = -1;
            }
            else
            {
                for (int c = 0; c < counter; c++)
                {
                    if (actSourceSymbol.equals(sourceSymbol2))
                    {
                        result.append(sourceSymbol2);
                    }
                    else
                    {
                        result.append(targetSymbol);
                    }
                }
                inList = counter;
            }
            result.append(line);
            if (i < lines.length - 1)
            {
                result.append("\n");
            }
            counter = 0;
        }

        // Fixes testExtensions5
        if( content.endsWith( "\n" ) && result.charAt( result.length()-1 ) != '\n' )
        {
            result.append( '\n' );
        }

        return result.toString();
    }

    private String translateVariables(String result, final boolean blogLineBreaks)
    {
        result = result.replace("[{$creolepagefilter.version}]", VAR_VERSION);
        result = result.replace("[{$creolepagefilter.creoleversion}]", VAR_CREOLE_VERSION);
        final String linebreaks = blogLineBreaks ? VAR_LINEBREAK_BLOGLIKE : VAR_LINEBREAK_C2LIKE;
        result = result.replace("[{$creolepagefilter.linebreak}]", linebreaks);
        return result;
    }

    /**
     * Undoes the protection. This is done by replacing the md5 hashes by the
     * original markup.
     *
     * @see #protectMarkup(String)
     */
    private String unprotectMarkup(String content, final boolean replacePlugins)
    {
        final Object[] it = this.m_hashList.toArray();

        for (int i = it.length - 1; i >= 0; i--)
        {
            final String hash = (String) it[i];
            final String protectedMarkup = c_protectionMap.get(hash);
            content = content.replace(hash, protectedMarkup);
            if ((protectedMarkup.length() < 3 || (protectedMarkup.length() > 2 &&
                !protectedMarkup.startsWith("{{{")))&&replacePlugins)
                content = translateElement(content, CREOLE_PLUGIN, JSPWIKI_PLUGIN);

        }
        return content;
    }

    /**
     * Protects markup that should not be processed. For now this includes:
     * <ul>
     * <li>Preformatted sections, they should be ignored</li>
     * </li>
     * <li>Protocol strings like <code>http://</code>, they cause problems
     * because of the <code>//</code> which is interpreted as italic</li>
     * </ul>
     * This protection is a simple method to keep the regular expressions for
     * the other markup simple. Internally the protection is done by replacing
     * the protected markup with the the md5 hash of the markup.
     *
     * @param content
     * @return The content with protection
     */
    private String protectMarkup(String content)
    {
        c_protectionMap.clear();
        m_hashList = new ArrayList<>();
        content = protectMarkupWithPattern(content, PATTERN_PREFORMATTED, "", "");
        content = protectMarkupWithPattern(content, PATTERN_URL, "", "");
        content = protectMarkupWithPattern(content, PATTERN_ESCAPE, "", "");
        content = protectMarkupWithPattern(content, PATTERN_PLUGIN, "", "");

        // content = protectMarkup(content, LINE_PROTECTED);
        // content = protectMarkup(content, SIGNATURE_PROTECTED);
        return content;
    }

    private ArrayList< String[] > readPlaceholderProperties(final Properties wikiProps)
    {
        final Set< Object > keySet = wikiProps.keySet();
        final Object[] keys = keySet.toArray();
        final ArrayList<String[]> result = new ArrayList<>();

        for (final Object o : keys) {
            final String key = o + "";
            final String value = wikiProps.getProperty(o + "");
            if (key.contains("creole.imagePlugin.para.%")) {
                final String[] pair = new String[2];
                pair[0] = key.replaceAll("creole.imagePlugin.para.%", "");
                pair[1] = value;
                result.add(pair);
            }
        }
        return result;
    }

    private String replaceImageArea(final Properties wikiProps, final String content, final String markupRegex, final String replaceContent, final int groupPos,
                                    final String imagePlugin)
    {
        final Pattern pattern = getOrCompilePattern(markupRegex, Pattern.MULTILINE | Pattern.DOTALL);
        final Matcher matcher = pattern.matcher(content);
        final StringBuilder contentCopy = new StringBuilder(content);

        final ArrayList< String[] > plProperties = readPlaceholderProperties(wikiProps);

        // Pre-compile patterns used in the loop
        final Pattern pipeOrWhitespace = getOrCompilePattern("\\||\\s", 0);
        final Pattern quoteStrip = getOrCompilePattern("^(\"|')(.*)(\"|')$", 0);
        final Pattern checkPattern = getOrCompilePattern("(.*?)%(.*?)<check>(.*?)</check>", 0);

        // Track offset adjustments as we modify the string
        int offset = 0;
        while (matcher.find())
        {
            String protectedMarkup = matcher.group(0);
            final String paramsField = matcher.group(groupPos);
            final StringBuilder paramsString = new StringBuilder();

            if (paramsField != null)
            {
                final String[] params = paramsField.split(",");

                for (final String s : params) {
                    final String param = pipeOrWhitespace.matcher(s).replaceAll("").toUpperCase();

                    // Replace placeholder params
                    for (final String[] pair : plProperties) {
                        final String key = pair[0];
                        final String value = pair[1];
                        final Pattern keyPattern = getOrCompilePattern("(?i)([0-9]+)" + key, 0);
                        String code = keyPattern.matcher(param).replaceAll(value + "<check>" + "$1" + "</check>");
                        code = checkPattern.matcher(code).replaceAll("$1$3$2");
                        if (!code.equals(param)) {
                            paramsString.append(code);
                        }
                    }

                    // Check if it is a number
                    try {
                        Integer.parseInt(param);
                        paramsString.append(" width='").append(param).append("px'");
                    } catch (final Exception e) {

                        if (wikiProps.getProperty("creole.imagePlugin.para." + param) != null)
                            paramsString.append(" ").append(quoteStrip.matcher(
                                    wikiProps.getProperty("creole.imagePlugin.para." + param)).replaceAll("$2"));
                    }
                }
            }
            final int originalStart = matcher.start() + offset;
            final int originalLength = protectedMarkup.length();

            protectedMarkup = translateElement(protectedMarkup, markupRegex, replaceContent);
            // Use pre-compiled patterns for the chain of replacements
            protectedMarkup = PATTERN_HORIZONTAL_BAR.matcher(protectedMarkup).replaceAll(Matcher.quoteReplacement(paramsString.toString()));
            protectedMarkup = PATTERN_DOUBLE_VERTICAL_BAR.matcher(protectedMarkup).replaceAll(Matcher.quoteReplacement(imagePlugin));
            protectedMarkup = PATTERN_EMPTY_CAPTION.matcher(protectedMarkup).replaceAll("");
            protectedMarkup = PATTERN_WHITESPACE.matcher(protectedMarkup).replaceAll(" ");

            contentCopy.replace(originalStart, originalStart + originalLength, protectedMarkup);
            offset += protectedMarkup.length() - originalLength;
        }
        return contentCopy.toString();
    }

    private String replaceArea(final String content, final String markupRegex, final String replaceSource, final String replaceTarget)
    {
        final Pattern pattern = getOrCompilePattern(markupRegex, Pattern.MULTILINE | Pattern.DOTALL);
        final Pattern replacePattern = getOrCompilePattern(replaceSource, 0);
        final Matcher matcher = pattern.matcher(content);
        final StringBuilder contentCopy = new StringBuilder(content);

        // Track offset adjustments as we modify the string
        int offset = 0;
        while (matcher.find())
        {
            final String originalMatch = matcher.group(0);
            final String replacement = replacePattern.matcher(originalMatch).replaceAll(replaceTarget);
            final int pos = matcher.start() + offset;
            contentCopy.replace(pos, pos + originalMatch.length(), replacement);
            offset += replacement.length() - originalMatch.length();
        }
        return contentCopy.toString();
    }

    /**
     * Protects a specific markup using a pre-compiled pattern.
     * This is the preferred method to avoid repeated pattern compilation.
     *
     * @see #protectMarkup(String)
     */
    private String protectMarkupWithPattern(final String content, final Pattern pattern, final String replaceSource, final String replaceTarget)
    {
        final Matcher matcher = pattern.matcher(content);
        final StringBuffer result = new StringBuffer();
        while (matcher.find())
        {
            String protectedMarkup = matcher.group();
            if (!replaceSource.isEmpty()) {
                protectedMarkup = protectedMarkup.replaceAll(replaceSource, replaceTarget);
            }
            try
            {
                final MessageDigest digest = MessageDigest.getInstance("MD5");
                digest.reset();
                digest.update(protectedMarkup.getBytes(StandardCharsets.UTF_8));
                final String hash = bytesToHash(digest.digest());
                matcher.appendReplacement(result, hash);
                c_protectionMap.put(hash, protectedMarkup);
                m_hashList.add(hash);
            }
            catch (final NoSuchAlgorithmException e)
            {
                // FIXME: Should log properly
                e.printStackTrace();
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Protects a specific markup (legacy method that compiles pattern on each call).
     * Used for dynamic patterns that cannot be pre-compiled.
     *
     * @see #protectMarkup(String)
     */
    private String protectMarkup(final String content, final String markupRegex, final String replaceSource, final String replaceTarget)
    {
        final Pattern pattern = getOrCompilePattern(markupRegex, Pattern.MULTILINE | Pattern.DOTALL);
        return protectMarkupWithPattern(content, pattern, replaceSource, replaceTarget);
    }

    private String bytesToHash(final byte[] b)
    {
        final StringBuilder hash = new StringBuilder();
        for (final byte value : b) {
            hash.append(Integer.toString((value & 0xff) + 0x100, 16).substring(1));
        }
        return hash.toString();
    }

    private String translateElement(final String content, final String fromMarkup, final String toMarkup)
    {
        final Pattern pattern = getOrCompilePattern(fromMarkup, Pattern.MULTILINE);
        final Matcher matcher = pattern.matcher(content);
        final StringBuffer result = new StringBuffer();

        while (matcher.find())
        {
            matcher.appendReplacement(result, toMarkup);
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
