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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;


public class TextUtilTest {

    @Test
    public void testGenerateRandomPassword() {
        for( int i = 0; i < 1000; i++ ) {
            Assertions.assertEquals( TextUtil.PASSWORD_LENGTH, TextUtil.generateRandomPassword().length(), "pw" );
        }
    }

    @Test
    public void testEncodeName_1() {
        final String name = "Hello/World";
        Assertions.assertEquals( "Hello/World", TextUtil.urlEncode(name, StandardCharsets.ISO_8859_1.name()) );
    }

    @Test
    public void testEncodeName_2() {
        final String name = "Hello~World";
        Assertions.assertEquals( "Hello%7EWorld", TextUtil.urlEncode(name,StandardCharsets.ISO_8859_1.name()) );
    }

    @Test
    public void testEncodeName_3() {
        final String name = "Hello/World ~";
        Assertions.assertEquals( "Hello/World+%7E", TextUtil.urlEncode(name,StandardCharsets.ISO_8859_1.name()) );
    }

    @Test
    public void testDecodeName_1() {
        final String name = "Hello/World+%7E+%2F";
        Assertions.assertEquals( "Hello/World ~ /", TextUtil.urlDecode(name,StandardCharsets.ISO_8859_1.name()) );
    }

    @Test
    public void testEncodeNameUTF8_1() {
        final String name = "\u0041\u2262\u0391\u002E";
        Assertions.assertEquals( "A%E2%89%A2%CE%91.", TextUtil.urlEncodeUTF8(name) );
    }

    @Test
    public void testEncodeNameUTF8_2() {
        final String name = "\uD55C\uAD6D\uC5B4";
        Assertions.assertEquals( "%ED%95%9C%EA%B5%AD%EC%96%B4", TextUtil.urlEncodeUTF8(name) );
    }

    @Test
    public void testEncodeNameUTF8_3() {
        final String name = "\u65E5\u672C\u8A9E";
        Assertions.assertEquals( "%E6%97%A5%E6%9C%AC%E8%AA%9E", TextUtil.urlEncodeUTF8(name) );
    }

    @Test
    public void testEncodeNameUTF8_4() {
        final String name = "Hello World";
        Assertions.assertEquals( "Hello+World", TextUtil.urlEncodeUTF8(name) );
    }

    @Test
    public void testDecodeNameUTF8_1() {
        final String name = "A%E2%89%A2%CE%91.";
        Assertions.assertEquals( "\u0041\u2262\u0391\u002E", TextUtil.urlDecodeUTF8(name) );
    }

    @Test
    public void testDecodeNameUTF8_2() {
        final String name = "%ED%95%9C%EA%B5%AD%EC%96%B4";
        Assertions.assertEquals( "\uD55C\uAD6D\uC5B4", TextUtil.urlDecodeUTF8(name) );
    }

    @Test
    public void testDecodeNameUTF8_3() {
        final String name = "%E6%97%A5%E6%9C%AC%E8%AA%9E";
        Assertions.assertEquals( "\u65E5\u672C\u8A9E", TextUtil.urlDecodeUTF8(name) );
    }

    @Test
    public void testReplaceString1() {
        final String text = "aabacaa";
        Assertions.assertEquals( "ddbacdd", TextUtil.replaceString( text, "aa", "dd" ) );
    }

    @Test
    public void testReplaceString4() {
        final String text = "aabacaafaa";
        Assertions.assertEquals( "ddbacddfdd", TextUtil.replaceString( text, "aa", "dd" ) );
    }

    @Test
    public void testReplaceString5() {
        final String text = "aaabacaaafaa";
        Assertions.assertEquals( "dbacdfaa", TextUtil.replaceString( text, "aaa", "d" ) );
    }

    @Test
    public void testReplaceString2() {
        final String text = "abcde";
        Assertions.assertEquals( "fbcde", TextUtil.replaceString( text, "a", "f" ) );
    }

    @Test
    public void testReplaceString3() {
        final String text = "ababab";
        Assertions.assertEquals( "afafaf", TextUtil.replaceString( text, "b", "f" ) );
    }

    @Test
    public void testReplaceStringCaseUnsensitive1() {
        final String text = "aABcAa";
        Assertions.assertEquals( "ddBcdd", TextUtil.replaceStringCaseUnsensitive( text, "aa", "dd" ) );
    }

    @Test
    public void testReplaceStringCaseUnsensitive2() {
        final String text = "Abcde";
        Assertions.assertEquals( "fbcde", TextUtil.replaceStringCaseUnsensitive( text, "a", "f" ) );
    }

    @Test
    public void testReplaceStringCaseUnsensitive3() {
        final String text = "aBAbab";
        Assertions.assertEquals( "afAfaf", TextUtil.replaceStringCaseUnsensitive( text, "b", "f" ) );
    }

    @Test
    public void testReplaceStringCaseUnsensitive4() {
        final String text = "AaBAcAAfaa";
        Assertions.assertEquals( "ddBAcddfdd", TextUtil.replaceStringCaseUnsensitive( text, "aa", "dd" ) );
    }

    @Test
    public void testReplaceStringCaseUnsensitive5() {
        final String text = "aAaBaCAAafaa";
        Assertions.assertEquals( "dBaCdfaa", TextUtil.replaceStringCaseUnsensitive( text, "aaa", "d" ) );
    }

    // Pure UNIX.
    @Test
    public void testNormalizePostdata1() {
        final String text = "ab\ncd";
        Assertions.assertEquals( "ab\r\ncd\r\n", TextUtil.normalizePostData( text ) );
    }

    // Pure MSDOS.
    @Test
    public void testNormalizePostdata2() {
        final String text = "ab\r\ncd";
        Assertions.assertEquals( "ab\r\ncd\r\n", TextUtil.normalizePostData( text ) );
    }

    // Pure Mac
    @Test
    public void testNormalizePostdata3() {
        final String text = "ab\rcd";
        Assertions.assertEquals( "ab\r\ncd\r\n", TextUtil.normalizePostData( text ) );
    }

    // Mixed, ending correct.
    @Test
    public void testNormalizePostdata4()
    {
        final String text = "ab\ncd\r\n\r\n\r";
        Assertions.assertEquals( "ab\r\ncd\r\n\r\n\r\n", TextUtil.normalizePostData( text ) );
    }

    // Multiple newlines
    @Test
    public void testNormalizePostdata5() {
        final String text = "ab\ncd\n\n\n\n";
        Assertions.assertEquals( "ab\r\ncd\r\n\r\n\r\n\r\n", TextUtil.normalizePostData( text ) );
    }

    // Empty.
    @Test
    public void testNormalizePostdata6() {
        final String text = "";
        Assertions.assertEquals( "\r\n", TextUtil.normalizePostData( text ) );
    }

    // Just a newline.
    @Test
    public void testNormalizePostdata7() {
        final String text = "\n";
        Assertions.assertEquals( "\r\n", TextUtil.normalizePostData( text ) );
    }

    @Test
    public void testGetBooleanProperty() {
        final Properties props = new Properties();
        props.setProperty("foobar.0", "YES");
        props.setProperty("foobar.1", "true");
        props.setProperty("foobar.2", "false");
        props.setProperty("foobar.3", "no");
        props.setProperty("foobar.4", "on");
        props.setProperty("foobar.5", "OFF");
        props.setProperty("foobar.6", "gewkjoigew");

        Assertions.assertTrue( TextUtil.getBooleanProperty( props, "foobar.0", false ), "foobar.0" );
        Assertions.assertTrue( TextUtil.getBooleanProperty( props, "foobar.1", false ), "foobar.1" );
        Assertions.assertFalse( TextUtil.getBooleanProperty( props, "foobar.2", true ), "foobar.2" );
        Assertions.assertFalse( TextUtil.getBooleanProperty( props, "foobar.3", true ), "foobar.3" );
        Assertions.assertTrue( TextUtil.getBooleanProperty( props, "foobar.4", false ), "foobar.4" );
        Assertions.assertFalse( TextUtil.getBooleanProperty( props, "foobar.5", true ), "foobar.5" );
        Assertions.assertFalse( TextUtil.getBooleanProperty( props, "foobar.6", true ), "foobar.6" );
    }

    @Test
    public void testGetSection1() {
        final String src = "Single page.";

        Assertions.assertEquals( src, TextUtil.getSection(src,1), "section 1" );
        Assertions.assertThrows( IllegalArgumentException.class, () -> TextUtil.getSection( src, 5 ) );
        Assertions.assertThrows( IllegalArgumentException.class, () -> TextUtil.getSection( src, -1 ) );
    }

    @Test
    public void testGetSection2() {
        final String src = "First section\n----\nSecond section\n\n----\n\nThird section";

        Assertions.assertEquals( "First section\n", TextUtil.getSection(src,1), "section 1" );
        Assertions.assertEquals( "\nSecond section\n\n", TextUtil.getSection(src,2), "section 2" );
        Assertions.assertEquals( "\n\nThird section", TextUtil.getSection(src,3), "section 3" );
        Assertions.assertThrows( IllegalArgumentException.class, () -> TextUtil.getSection( src, 4 ) );
    }

    @Test
    public void testGetSection3() {
        final String src = "----\nSecond section\n----";

        Assertions.assertEquals( "", TextUtil.getSection(src,1), "section 1" );
        Assertions.assertEquals( "\nSecond section\n", TextUtil.getSection(src,2), "section 2" );
        Assertions.assertEquals( "", TextUtil.getSection(src,3), "section 3" );
        Assertions.assertThrows( IllegalArgumentException.class, () -> TextUtil.getSection( src, 4 ) );
    }

    @Test
    public void testGetSectionWithMoreThanFourDashes() {
        final String src = "----------------\nSecond section\n----";
        Assertions.assertEquals( "\nSecond section\n", TextUtil.getSection(src, 2), "section 2" );
    }

    @Test
    public void testBooleanParameter() {
        Assertions.assertTrue( TextUtil.isPositive(" true "), "1" );
        Assertions.assertFalse( TextUtil.isPositive(" fewqkfow kfpokwe "), "2" );
        Assertions.assertTrue( TextUtil.isPositive("on"), "3" );
        Assertions.assertTrue( TextUtil.isPositive("\t\ton"), "4" );
    }

    @Test
    public void testTrimmedProperty() {
        final String[] vals = { "foo", " this is a property ", "bar", "60" };
        final Properties props = TextUtil.createProperties(vals);

        Assertions.assertEquals( "this is a property", TextUtil.getStringProperty(props,"foo",""), "foo" );
        Assertions.assertEquals( 60, TextUtil.getIntegerProperty(props,"bar",0), "bar" );
    }

    @Test
    public void testGetStringProperty() {
        final String[] vals = { "foo", " this is a property " };
        final Properties props = TextUtil.createProperties(vals);
        Assertions.assertEquals( "this is a property", TextUtil.getStringProperty( props, "foo", "err" ) );
    }

    @Test
    public void testGetStringPropertyDeprecated() {
        final String[] vals = { "foo", " this is a property ", "foo-dep", "deprecated" };
        final Properties props = TextUtil.createProperties(vals);
        Assertions.assertEquals( "deprecated", TextUtil.getStringProperty( props, "foo", "foo-dep", "err" ) );
        Assertions.assertEquals( "this is a property", TextUtil.getStringProperty( props, "foo", "bar-dep", "err" ) );
        Assertions.assertEquals( "err", TextUtil.getStringProperty( props, "fooo", "bar-dep", "err" ) );
    }

    @Test
    public void testGetStringPropertyDefaultValue() {
        final String defaultValue = System.getProperty( "user.home" ) + File.separator + "wikantik-files";
        final String[] vals = { "foo", " this is a property " };
        final Properties props = TextUtil.createProperties(vals);
        Assertions.assertEquals( defaultValue, TextUtil.getStringProperty( props, "bar", defaultValue ) );
    }

    @Test
    public void testGetCanonicalFilePathProperty() {
        final String[] values = { "wikantik.fileSystemProvider.pageDir", " ." + File.separator + "data" + File.separator + "private " };
        final Properties props = TextUtil.createProperties(values);
        final String path = TextUtil.getCanonicalFilePathProperty(props, "wikantik.fileSystemProvider.pageDir", "NA");
        Assertions.assertTrue( path.endsWith( File.separator + "data" + File.separator + "private" ) );
        Assertions.assertFalse( path.endsWith( "." + File.separator + "data" + File.separator + "private" ) );
    }

    @Test
    public void testGetCanonicalFilePathPropertyDefaultValue() {
        final String defaultValue = System.getProperty( "user.home" ) + File.separator + "wikantik-files";
        final String[] values = {};
        final Properties props = TextUtil.createProperties(values);
        final String path = TextUtil.getCanonicalFilePathProperty(props, "wikantik.fileSystemProvider.pageDir", defaultValue);
        Assertions.assertTrue(path.endsWith("wikantik-files"));
    }

    @Test
    public void testGetRequiredProperty() {
        final String[] vals = { "foo", " this is a property ", "bar", "60" };
        final Properties props = TextUtil.createProperties( vals );
        Assertions.assertEquals( "60", TextUtil.getRequiredProperty( props, "bar" ) );
    }

    @Test
    public void testGetRequiredPropertyNSEE() {
        final String[] vals = { "foo", " this is a property ", "bar", "60" };
        final Properties props = TextUtil.createProperties(vals);
        Assertions.assertThrows( NoSuchElementException.class, () -> TextUtil.getRequiredProperty( props, "ber" ) );
    }

    @Test
    public void testGetRequiredPropertyDeprecated() {
        final String[] vals = { "foo", " this is a property ", "foo-dep", "deprecated" };
        final Properties props = TextUtil.createProperties( vals );
        Assertions.assertEquals( "deprecated", TextUtil.getRequiredProperty( props, "foo", "foo-dep" ) );
        Assertions.assertEquals( "this is a property", TextUtil.getRequiredProperty( props, "foo", "bar-dep" ) );
        Assertions.assertThrows( NoSuchElementException.class, () -> TextUtil.getRequiredProperty( props, "fooo", "bar-dep" ) );
    }

    @Test
    public void testCleanString() {
        Assertions.assertNull( TextUtil.cleanString( null, TextUtil.PUNCTUATION_CHARS_ALLOWED ) );
        Assertions.assertEquals( " This is a link ", TextUtil.cleanString( " [ This is a link ] ", TextUtil.PUNCTUATION_CHARS_ALLOWED ) );
        Assertions.assertEquals( "ThisIsALink", TextUtil.cleanString( " [ This is a link ] ", TextUtil.LEGACY_CHARS_ALLOWED ) );
    }

    // --- beautifyString tests ---

    @Test
    public void testBeautifyStringCamelCase() {
        assertEquals( "Camel Case", TextUtil.beautifyString( "CamelCase" ) );
    }

    @Test
    public void testBeautifyStringWithNumbers() {
        assertEquals( "Page 123 Test", TextUtil.beautifyString( "Page123Test" ) );
    }

    @Test
    public void testBeautifyStringAllCaps() {
        assertEquals( "HTML Parser", TextUtil.beautifyString( "HTMLParser" ) );
    }

    @Test
    public void testBeautifyStringWithCustomSpace() {
        assertEquals( "Camel&nbsp;Case", TextUtil.beautifyString( "CamelCase", "&nbsp;" ) );
    }

    @Test
    public void testBeautifyStringNullReturnsEmpty() {
        assertEquals( "", TextUtil.beautifyString( null ) );
    }

    @Test
    public void testBeautifyStringEmptyReturnsEmpty() {
        assertEquals( "", TextUtil.beautifyString( "" ) );
    }

    @Test
    public void testBeautifyStringSingleChar() {
        assertEquals( "X", TextUtil.beautifyString( "X" ) );
    }

    @Test
    public void testBeautifyStringAllLowerCase() {
        assertEquals( "hello", TextUtil.beautifyString( "hello" ) );
    }

    @Test
    public void testBeautifyStringDigitToLetter() {
        assertEquals( "Test 3 rd", TextUtil.beautifyString( "Test3rd" ) );
    }

    // --- cleanString additional tests ---

    @Test
    public void testCleanStringCollapseWhitespace() {
        // Multiple whitespace should collapse to a single space, and words get capitalized
        assertEquals( "Hello World", TextUtil.cleanString( "Hello   World", TextUtil.PUNCTUATION_CHARS_ALLOWED ) );
    }

    @Test
    public void testCleanStringSpecialChars() {
        // Characters not in allowed set get stripped, next letter capitalized
        assertEquals( "HelloWorld", TextUtil.cleanString( "hello#world", TextUtil.LEGACY_CHARS_ALLOWED ) );
    }

    @Test
    public void testCleanStringUnicode() {
        // Unicode letters should be preserved
        assertEquals( "\u00c4\u00f6\u00fc", TextUtil.cleanString( "\u00e4\u00f6\u00fc", TextUtil.LEGACY_CHARS_ALLOWED ) );
    }

    // --- escapeHTMLEntities tests ---

    @Test
    public void testEscapeHTMLEntitiesBasic() {
        assertEquals( "&lt;div&gt;", TextUtil.escapeHTMLEntities( "<div>" ) );
    }

    @Test
    public void testEscapeHTMLEntitiesQuote() {
        assertEquals( "&quot;hello&quot;", TextUtil.escapeHTMLEntities( "\"hello\"" ) );
    }

    @Test
    public void testEscapeHTMLEntitiesPreservesExistingEntity() {
        // An already-escaped entity like &amp; should be left alone
        assertEquals( "&amp;", TextUtil.escapeHTMLEntities( "&amp;" ) );
    }

    @Test
    public void testEscapeHTMLEntitiesNumericEntity() {
        // Numeric entities like &#39; should be preserved
        assertEquals( "&#39;", TextUtil.escapeHTMLEntities( "&#39;" ) );
    }

    @Test
    public void testEscapeHTMLEntitiesBareAmpersand() {
        // A bare & that is NOT part of an entity should be escaped
        assertEquals( "&amp; foo", TextUtil.escapeHTMLEntities( "& foo" ) );
    }

    @Test
    public void testEscapeHTMLEntitiesAmpersandAtEnd() {
        assertEquals( "&amp;", TextUtil.escapeHTMLEntities( "&" ) );
    }

    // --- urlEncode / urlDecode tests ---

    @Test
    public void testUrlEncodeSpecialChars() {
        byte[] bytes = new byte[] { '_', '.', '*', '-', '/' };
        assertEquals( "_.*-/", TextUtil.urlEncode( bytes ) );
    }

    @Test
    public void testUrlEncodeSpace() {
        byte[] bytes = new byte[] { ' ' };
        assertEquals( "+", TextUtil.urlEncode( bytes ) );
    }

    @Test
    public void testUrlEncodeHexEncoding() {
        byte[] bytes = new byte[] { '~' };
        assertEquals( "%7E", TextUtil.urlEncode( bytes ) );
    }

    @Test
    public void testUrlDecodeRoundTrip() {
        String original = "Hello World ~special/chars";
        String encoded = TextUtil.urlEncodeUTF8( original );
        String decoded = TextUtil.urlDecodeUTF8( encoded );
        assertEquals( original, decoded );
    }

    @Test
    public void testUrlEncodeUTF8Null() {
        assertEquals( "", TextUtil.urlEncodeUTF8( null ) );
    }

    @Test
    public void testUrlDecodeUTF8Null() {
        assertNull( TextUtil.urlDecodeUTF8( null ) );
    }

    @Test
    public void testUrlDecodeInvalidThrows() {
        // A trailing % with no hex digits should throw
        assertThrows( IllegalArgumentException.class, () ->
            TextUtil.urlDecode( "%A".getBytes( StandardCharsets.ISO_8859_1 ), StandardCharsets.UTF_8.name() ) );
    }

    @Test
    public void testUrlDecodeNull() {
        assertNull( TextUtil.urlDecode( (byte[]) null, StandardCharsets.UTF_8.name() ) );
    }

    @Test
    public void testUrlEncodeWithISO8859() {
        String data = "hello world";
        String encoded = TextUtil.urlEncode( data, StandardCharsets.ISO_8859_1.name() );
        assertEquals( "hello+world", encoded );
    }

    @Test
    public void testUrlDecodeWithISO8859() {
        String data = "hello+world";
        String decoded = TextUtil.urlDecode( data, StandardCharsets.ISO_8859_1.name() );
        assertEquals( "hello world", decoded );
    }

    @Test
    public void testUrlEncodeDecodeUTF8ViaStringMethod() {
        String original = "\u00e4\u00f6\u00fc";
        String encoded = TextUtil.urlEncode( original, StandardCharsets.UTF_8.name() );
        String decoded = TextUtil.urlDecode( encoded, StandardCharsets.UTF_8.name() );
        assertEquals( original, decoded );
    }

    // --- toHexString tests ---

    @Test
    public void testToHexStringKnownValues() {
        byte[] bytes = new byte[] { 0x01, 0x02, 0x3E };
        assertEquals( "01023E", TextUtil.toHexString( bytes ) );
    }

    @Test
    public void testToHexStringEmpty() {
        assertEquals( "", TextUtil.toHexString( new byte[0] ) );
    }

    @Test
    public void testToHexStringSingleByte() {
        assertEquals( "FF", TextUtil.toHexString( new byte[] { (byte) 0xFF } ) );
    }

    @Test
    public void testToHexStringZeroByte() {
        assertEquals( "00", TextUtil.toHexString( new byte[] { 0x00 } ) );
    }

    // --- generateRandomPassword tests ---

    @Test
    public void testGenerateRandomPasswordLength() {
        String pwd = TextUtil.generateRandomPassword();
        assertEquals( TextUtil.PASSWORD_LENGTH, pwd.length() );
    }

    @Test
    public void testGenerateRandomPasswordOnlyAllowedChars() {
        for( int i = 0; i < 100; i++ ) {
            String pwd = TextUtil.generateRandomPassword();
            for( char c : pwd.toCharArray() ) {
                assertTrue( TextUtil.PWD_BASE.indexOf( c ) >= 0,
                    "Character '" + c + "' not in allowed set" );
            }
        }
    }

    @Test
    public void testGenerateRandomPasswordNotAllSame() {
        // Two randomly generated passwords should almost certainly differ
        String pwd1 = TextUtil.generateRandomPassword();
        String pwd2 = TextUtil.generateRandomPassword();
        // With 56^8 possibilities, collision is astronomically unlikely
        // If this fails, it indicates a broken RNG
        assertNotEquals( pwd1, pwd2, "Two random passwords should not be identical" );
    }

    // --- parseIntParameter tests ---

    @Test
    public void testParseIntParameterValid() {
        assertEquals( 42, TextUtil.parseIntParameter( "42", 0 ) );
    }

    @Test
    public void testParseIntParameterNegative() {
        assertEquals( -7, TextUtil.parseIntParameter( "-7", 0 ) );
    }

    @Test
    public void testParseIntParameterWithWhitespace() {
        assertEquals( 10, TextUtil.parseIntParameter( "  10  ", 0 ) );
    }

    @Test
    public void testParseIntParameterInvalidReturnsDefault() {
        assertEquals( 99, TextUtil.parseIntParameter( "not_a_number", 99 ) );
    }

    @Test
    public void testParseIntParameterNullReturnsDefault() {
        assertEquals( 5, TextUtil.parseIntParameter( null, 5 ) );
    }

    @Test
    public void testParseIntParameterEmptyReturnsDefault() {
        assertEquals( 5, TextUtil.parseIntParameter( "", 5 ) );
    }

    // --- isNumber tests ---

    @Test
    public void testIsNumberPositive() {
        assertTrue( TextUtil.isNumber( "12345" ) );
    }

    @Test
    public void testIsNumberNegative() {
        assertTrue( TextUtil.isNumber( "-42" ) );
    }

    @Test
    public void testIsNumberNull() {
        assertFalse( TextUtil.isNumber( null ) );
    }

    @Test
    public void testIsNumberNotANumber() {
        assertFalse( TextUtil.isNumber( "abc" ) );
    }

    @Test
    public void testIsNumberMixed() {
        assertFalse( TextUtil.isNumber( "12a3" ) );
    }

    @Test
    public void testIsNumberEmpty() {
        // An empty string - after trimming, there's nothing, so should return true (all chars are digits, vacuously)
        assertTrue( TextUtil.isNumber( "" ) );
    }

    @Test
    public void testIsNumberJustMinus() {
        // Just "-" with nothing after: after stripping the minus, the empty string passes the vacuous check.
        // But the implementation returns false because the loop doesn't execute and isNumber returns true only if all chars are digits.
        // Actually: s becomes "", loop doesn't run, returns true. Let's check what the actual behavior is.
        // The substring is "", length is 0, loop doesn't execute, returns true. But test shows false...
        // This means "-" actually returns false. Let's verify the logic: s.length()>1 && s.charAt(0)=='-' => "1>1" is false, so the minus is NOT stripped.
        // Then the loop checks if '-' is a digit, which it isn't, so returns false.
        assertFalse( TextUtil.isNumber( "-" ) );
    }

    // --- native2Ascii tests ---

    @Test
    public void testNative2AsciiBasic() {
        assertEquals( "hello", TextUtil.native2Ascii( "hello" ) );
    }

    @Test
    public void testNative2AsciiNonAscii() {
        assertEquals( "\\u00E4\\u00F6\\u00FC", TextUtil.native2Ascii( "\u00e4\u00f6\u00fc" ) );
    }

    @Test
    public void testNative2AsciiControlChar() {
        // Tab (0x09) is below 0x0020 and should be escaped
        assertEquals( "\\u0009", TextUtil.native2Ascii( "\t" ) );
    }

    // --- replaceEntities tests ---

    @Test
    public void testReplaceEntities() {
        assertEquals( "&amp;&lt;&gt;&quot;", TextUtil.replaceEntities( "&<>\"" ) );
    }

    @Test
    public void testReplaceEntitiesNull() {
        assertNull( TextUtil.replaceEntities( null ) );
    }

    // --- replaceString edge cases ---

    @Test
    public void testReplaceStringNull() {
        assertNull( TextUtil.replaceString( null, "a", "b" ) );
    }

    @Test
    public void testReplaceStringEmptySrc() {
        assertEquals( "hello", TextUtil.replaceString( "hello", "", "x" ) );
    }

    @Test
    public void testReplaceStringNullSrcThrows() {
        assertThrows( NullPointerException.class, () -> TextUtil.replaceString( "hello", null, "x" ) );
    }

    @Test
    public void testReplaceStringNullDestThrows() {
        assertThrows( NullPointerException.class, () -> TextUtil.replaceString( "hello", "h", null ) );
    }

    @Test
    public void testReplaceStringByPosition() {
        assertEquals( "hXXXo", TextUtil.replaceString( "hello", 1, 4, "XXX" ) );
    }

    @Test
    public void testReplaceStringByPositionNull() {
        assertNull( TextUtil.replaceString( null, 0, 1, "x" ) );
    }

    // --- replaceStringCaseUnsensitive edge cases ---

    @Test
    public void testReplaceStringCaseUnsensitiveNull() {
        assertNull( TextUtil.replaceStringCaseUnsensitive( null, "a", "b" ) );
    }

    // --- countSections tests ---

    @Test
    public void testCountSectionsEmpty() {
        assertEquals( 0, TextUtil.countSections( "" ) );
    }

    @Test
    public void testCountSectionsNoSeparators() {
        assertEquals( 1, TextUtil.countSections( "Just text" ) );
    }

    @Test
    public void testCountSectionsMultiple() {
        assertEquals( 3, TextUtil.countSections( "A----B----C" ) );
    }

    // --- repeatString tests ---

    @Test
    public void testRepeatString() {
        assertEquals( "abcabcabc", TextUtil.repeatString( "abc", 3 ) );
    }

    @Test
    public void testRepeatStringZeroTimes() {
        assertEquals( "", TextUtil.repeatString( "abc", 0 ) );
    }

    // --- normalizePostData additional test ---

    @Test
    public void testNormalizePostDataAlreadyNormalized() {
        // If text already ends with \r\n, no extra \r\n should be appended
        assertEquals( "hello\r\n", TextUtil.normalizePostData( "hello\r\n" ) );
    }

    // --- createProperties tests ---

    @Test
    public void testCreateProperties() {
        String[] vals = { "a", "1", "b", "2" };
        Properties p = TextUtil.createProperties( vals );
        assertEquals( "1", p.getProperty( "a" ) );
        assertEquals( "2", p.getProperty( "b" ) );
    }

    @Test
    public void testCreatePropertiesOddArrayThrows() {
        assertThrows( IllegalArgumentException.class, () -> TextUtil.createProperties( new String[] { "a" } ) );
    }

    @Test
    public void testCreatePropertiesEmpty() {
        Properties p = TextUtil.createProperties( new String[0] );
        assertTrue( p.isEmpty() );
    }

    // --- getIntegerProperty tests ---

    @Test
    public void testGetIntegerProperty() {
        Properties props = new Properties();
        props.setProperty( "num", "42" );
        assertEquals( 42, TextUtil.getIntegerProperty( props, "num", 0 ) );
    }

    @Test
    public void testGetIntegerPropertyMissing() {
        Properties props = new Properties();
        assertEquals( 99, TextUtil.getIntegerProperty( props, "missing", 99 ) );
    }

    // --- isPositive additional tests ---

    @Test
    public void testIsPositiveNull() {
        assertFalse( TextUtil.isPositive( null ) );
    }

    @Test
    public void testIsPositiveFalse() {
        assertFalse( TextUtil.isPositive( "false" ) );
    }

    @Test
    public void testIsPositiveYes() {
        assertTrue( TextUtil.isPositive( "YES" ) );
    }

}
