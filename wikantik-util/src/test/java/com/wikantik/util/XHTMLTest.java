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


public class XHTMLTest
{
    @Test
    public void testElementNames()
    {
        Assertions.assertEquals( "a", XHTML.a.name() );
        Assertions.assertEquals( "div", XHTML.div.name() );
        Assertions.assertEquals( "table", XHTML.table.name() );
        Assertions.assertEquals( "img", XHTML.img.name() );
    }

    @Test
    public void testGetNameMatchesName()
    {
        Assertions.assertEquals( XHTML.p.name(), XHTML.p.getName() );
        Assertions.assertEquals( XHTML.span.name(), XHTML.span.getName() );
        Assertions.assertEquals( XHTML.h1.name(), XHTML.h1.getName() );
    }

    @Test
    public void testAttributeConstants()
    {
        Assertions.assertEquals( "id", XHTML.ATTR_id );
        Assertions.assertEquals( "class", XHTML.ATTR_class );
        Assertions.assertEquals( "href", XHTML.ATTR_href );
        Assertions.assertEquals( "src", XHTML.ATTR_src );
        Assertions.assertEquals( "alt", XHTML.ATTR_alt );
        Assertions.assertEquals( "name", XHTML.ATTR_name );
        Assertions.assertEquals( "type", XHTML.ATTR_type );
        Assertions.assertEquals( "value", XHTML.ATTR_value );
        Assertions.assertEquals( "style", XHTML.ATTR_style );
    }

    @Test
    public void testNamespaceAndDtdConstants()
    {
        Assertions.assertEquals( "http://www.w3.org/1999/xhtml", XHTML.XMLNS_xhtml );
        Assertions.assertTrue( XHTML.STRICT_DTD_PubId.contains( "Strict" ) );
        Assertions.assertTrue( XHTML.TRANSITIONAL_DTD_PubId.contains( "Transitional" ) );
        Assertions.assertTrue( XHTML.FRAMESET_DTD_PubId.contains( "Frameset" ) );
    }

    @Test
    public void testGetNamedCharacterEntity()
    {
        // 160 = nbsp, 169 = copy, 255 = yuml
        Assertions.assertEquals( "nbsp", XHTML.getNamedCharacterEntity( 160 ) );
        Assertions.assertEquals( "copy", XHTML.getNamedCharacterEntity( 169 ) );
        Assertions.assertEquals( "yuml", XHTML.getNamedCharacterEntity( 255 ) );
    }

    @Test
    public void testGetNamedCharacterEntityOutOfRangeThrows()
    {
        Assertions.assertThrows( ArrayIndexOutOfBoundsException.class, () -> XHTML.getNamedCharacterEntity( 159 ) );
        Assertions.assertThrows( ArrayIndexOutOfBoundsException.class, () -> XHTML.getNamedCharacterEntity( 256 ) );
    }
}
