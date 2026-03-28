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

import org.jdom2.Element;
import org.jdom2.output.Format;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class XhtmlUtilTest
{
    // ---- serialize tests ------------------------------------------------

    @Test
    public void testSerializeCompact()
    {
        final Element p = XhtmlUtil.element( XHTML.p, "hello" );
        final String result = XhtmlUtil.serialize( p );
        Assertions.assertEquals( "<p>hello</p>", result );
    }

    @Test
    public void testSerializePretty()
    {
        final Element div = XhtmlUtil.element( XHTML.div );
        final Element span = XhtmlUtil.element( XHTML.span, "text" );
        div.addContent( span );
        final String pretty = XhtmlUtil.serialize( div, true );
        Assertions.assertTrue( pretty.contains( "<div>" ) );
        Assertions.assertTrue( pretty.contains( "<span>text</span>" ) );
    }

    @Test
    public void testSerializeCustomFormat()
    {
        final Element em = XhtmlUtil.element( XHTML.em, "word" );
        final String result = XhtmlUtil.serialize( em, Format.getRawFormat() );
        Assertions.assertEquals( "<em>word</em>", result );
    }

    @Test
    public void testExpandEmptyNodes()
    {
        final Element td = XhtmlUtil.element( XHTML.td );
        final String result = XhtmlUtil.serialize( td, XhtmlUtil.EXPAND_EMPTY_NODES );
        Assertions.assertEquals( "<td></td>", result );
    }

    // ---- element tests --------------------------------------------------

    @Test
    public void testElementNoContent()
    {
        final Element el = XhtmlUtil.element( XHTML.br );
        Assertions.assertEquals( "br", el.getName() );
        Assertions.assertEquals( 0, el.getContentSize() );
    }

    @Test
    public void testElementWithContent()
    {
        final Element el = XhtmlUtil.element( XHTML.p, "paragraph" );
        Assertions.assertEquals( "p", el.getName() );
        Assertions.assertEquals( "paragraph", el.getText() );
    }

    @Test
    public void testElementWithNullContent()
    {
        final Element el = XhtmlUtil.element( XHTML.div, null );
        Assertions.assertEquals( "div", el.getName() );
        Assertions.assertTrue( el.getText().isEmpty() );
    }

    // ---- link tests -----------------------------------------------------

    @Test
    public void testLinkWithContent()
    {
        final Element a = XhtmlUtil.link( "http://example.com", "Click" );
        Assertions.assertEquals( "a", a.getName() );
        Assertions.assertEquals( "http://example.com", a.getAttributeValue( "href" ) );
        Assertions.assertEquals( "Click", a.getText() );
    }

    @Test
    public void testLinkNullContent()
    {
        final Element a = XhtmlUtil.link( "http://example.com", null );
        Assertions.assertEquals( "http://example.com", a.getAttributeValue( "href" ) );
        Assertions.assertTrue( a.getText().isEmpty() );
    }

    @Test
    public void testLinkNullHrefThrows()
    {
        Assertions.assertThrows( IllegalArgumentException.class, () -> XhtmlUtil.link( null, "text" ) );
    }

    // ---- target tests ---------------------------------------------------

    @Test
    public void testTargetWithContent()
    {
        final Element a = XhtmlUtil.target( "section1", "Title" );
        Assertions.assertEquals( "a", a.getName() );
        Assertions.assertEquals( "section1", a.getAttributeValue( "id" ) );
        Assertions.assertEquals( "Title", a.getText() );
        Assertions.assertNull( a.getAttributeValue( "href" ) );
    }

    @Test
    public void testTargetNullIdThrows()
    {
        Assertions.assertThrows( IllegalArgumentException.class, () -> XhtmlUtil.target( null, "text" ) );
    }

    // ---- img tests ------------------------------------------------------

    @Test
    public void testImgWithAlt()
    {
        final Element img = XhtmlUtil.img( "photo.jpg", "A photo" );
        Assertions.assertEquals( "img", img.getName() );
        Assertions.assertEquals( "photo.jpg", img.getAttributeValue( "src" ) );
        Assertions.assertEquals( "A photo", img.getAttributeValue( "alt" ) );
    }

    @Test
    public void testImgWithoutAlt()
    {
        final Element img = XhtmlUtil.img( "photo.jpg", null );
        Assertions.assertEquals( "photo.jpg", img.getAttributeValue( "src" ) );
        Assertions.assertNull( img.getAttributeValue( "alt" ) );
    }

    @Test
    public void testImgNullSrcThrows()
    {
        Assertions.assertThrows( IllegalArgumentException.class, () -> XhtmlUtil.img( null, "alt" ) );
    }

    // ---- input tests ----------------------------------------------------

    @Test
    public void testInputAllAttributes()
    {
        final Element input = XhtmlUtil.input( "text", "username", "admin" );
        Assertions.assertEquals( "input", input.getName() );
        Assertions.assertEquals( "text", input.getAttributeValue( "type" ) );
        Assertions.assertEquals( "username", input.getAttributeValue( "name" ) );
        Assertions.assertEquals( "admin", input.getAttributeValue( "value" ) );
    }

    @Test
    public void testInputNullAttributes()
    {
        final Element input = XhtmlUtil.input( null, null, null );
        Assertions.assertEquals( "input", input.getName() );
        Assertions.assertNull( input.getAttributeValue( "type" ) );
        Assertions.assertNull( input.getAttributeValue( "name" ) );
        Assertions.assertNull( input.getAttributeValue( "value" ) );
    }

    // ---- setClass tests -------------------------------------------------

    @Test
    public void testSetClass()
    {
        final Element div = XhtmlUtil.element( XHTML.div );
        XhtmlUtil.setClass( div, "highlight" );
        Assertions.assertEquals( "highlight", div.getAttributeValue( "class" ) );
    }

    @Test
    public void testSetClassNullThrows()
    {
        final Element div = XhtmlUtil.element( XHTML.div );
        Assertions.assertThrows( IllegalArgumentException.class, () -> XhtmlUtil.setClass( div, null ) );
    }
}
