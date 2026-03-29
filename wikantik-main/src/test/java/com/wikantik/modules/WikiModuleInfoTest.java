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
package com.wikantik.modules;

import org.jdom2.Element;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class WikiModuleInfoTest {

    // -----------------------------------------------------------------------
    // Construction and name
    // -----------------------------------------------------------------------

    @Test
    void testConstructorSetsName() {
        final WikiModuleInfo info = new WikiModuleInfo( "TestPlugin" );
        assertEquals( "TestPlugin", info.getName() );
    }

    // -----------------------------------------------------------------------
    // Getters — all return null before XML initialization
    // -----------------------------------------------------------------------

    @Test
    void testGettersReturnNullWhenNotInitialized() {
        final WikiModuleInfo info = new WikiModuleInfo( "SomeModule" );
        assertNull( info.getDescription() );
        assertNull( info.getModuleUrl() );
        assertNull( info.getModuleVersion() );
        assertNull( info.getHtmlTemplate() );
        assertNull( info.getStylesheetLocation() );
        assertNull( info.getScriptLocation() );
        assertNull( info.getAuthor() );
        assertNull( info.getAuthorUrl() );
        assertNull( info.getMinVersion() );
        assertNull( info.getMaxVersion() );
        assertNull( info.getAdminBeanClass() );
    }

    // -----------------------------------------------------------------------
    // initializeFromXML populates all fields
    // -----------------------------------------------------------------------

    @Test
    void testInitializeFromXMLPopulatesAllFields() {
        final WikiModuleInfo info = new WikiModuleInfo( "XmlPlugin" );

        final Element el = new Element( "plugin" );
        el.addContent( new Element( "adminBean" ).setText( "com.example.AdminBean" ) );
        el.addContent( new Element( "author" ).setText( "Alice" ) );
        el.addContent( new Element( "authorUrl" ).setText( "https://example.com/alice" ) );
        el.addContent( new Element( "description" ).setText( "A test plugin" ) );
        el.addContent( new Element( "maxVersion" ).setText( "9.9.9" ) );
        el.addContent( new Element( "minVersion" ).setText( "1.0.0" ) );
        el.addContent( new Element( "script" ).setText( "scripts/plugin.js" ) );
        el.addContent( new Element( "stylesheet" ).setText( "styles/plugin.css" ) );
        el.addContent( new Element( "template" ).setText( "templates/plugin.html" ) );
        el.addContent( new Element( "url" ).setText( "https://example.com/plugin" ) );
        el.addContent( new Element( "version" ).setText( "2.1.0" ) );

        info.initializeFromXML( el );

        assertEquals( "com.example.AdminBean", info.getAdminBeanClass() );
        assertEquals( "Alice", info.getAuthor() );
        assertEquals( "https://example.com/alice", info.getAuthorUrl() );
        assertEquals( "A test plugin", info.getDescription() );
        assertEquals( "9.9.9", info.getMaxVersion() );
        assertEquals( "1.0.0", info.getMinVersion() );
        assertEquals( "scripts/plugin.js", info.getScriptLocation() );
        assertEquals( "styles/plugin.css", info.getStylesheetLocation() );
        assertEquals( "templates/plugin.html", info.getHtmlTemplate() );
        assertEquals( "https://example.com/plugin", info.getModuleUrl() );
        assertEquals( "2.1.0", info.getModuleVersion() );
    }

    @Test
    void testInitializeFromXMLWithMissingElementsLeavesFieldsNull() {
        final WikiModuleInfo info = new WikiModuleInfo( "EmptyXmlPlugin" );
        final Element el = new Element( "plugin" );

        info.initializeFromXML( el );

        assertNull( info.getAdminBeanClass() );
        assertNull( info.getAuthor() );
        assertNull( info.getDescription() );
        assertNull( info.getMinVersion() );
        assertNull( info.getMaxVersion() );
    }

    // -----------------------------------------------------------------------
    // equals
    // -----------------------------------------------------------------------

    @Test
    void testEqualsReturnsTrueForSameName() {
        final WikiModuleInfo a = new WikiModuleInfo( "PluginA" );
        final WikiModuleInfo b = new WikiModuleInfo( "PluginA" );
        assertEquals( a, b );
    }

    @Test
    void testEqualsReturnsFalseForDifferentName() {
        final WikiModuleInfo a = new WikiModuleInfo( "PluginA" );
        final WikiModuleInfo b = new WikiModuleInfo( "PluginB" );
        assertNotEquals( a, b );
    }

    @Test
    void testEqualsReturnsFalseForNonWikiModuleInfoObject() {
        final WikiModuleInfo info = new WikiModuleInfo( "Plugin" );
        assertNotEquals( info, "Plugin" );
        assertNotEquals( info, null );
    }

    @Test
    void testEqualsSelf() {
        final WikiModuleInfo info = new WikiModuleInfo( "Plugin" );
        assertEquals( info, info );
    }

    // -----------------------------------------------------------------------
    // hashCode
    // -----------------------------------------------------------------------

    @Test
    void testHashCodeConsistentWithEquals() {
        final WikiModuleInfo a = new WikiModuleInfo( "HashMe" );
        final WikiModuleInfo b = new WikiModuleInfo( "HashMe" );
        assertEquals( a, b );
        assertEquals( a.hashCode(), b.hashCode() );
    }

    @Test
    void testHashCodeDiffersForDifferentNames() {
        final WikiModuleInfo a = new WikiModuleInfo( "Alpha" );
        final WikiModuleInfo b = new WikiModuleInfo( "Beta" );
        // Not strictly guaranteed, but these two strings have distinct hashes
        assertNotEquals( a.hashCode(), b.hashCode() );
    }

    // -----------------------------------------------------------------------
    // compareTo
    // -----------------------------------------------------------------------

    @Test
    void testCompareToSameName() {
        final WikiModuleInfo a = new WikiModuleInfo( "Plugin" );
        final WikiModuleInfo b = new WikiModuleInfo( "Plugin" );
        assertEquals( 0, a.compareTo( b ) );
    }

    @Test
    void testCompareToOrdering() {
        final WikiModuleInfo a = new WikiModuleInfo( "Alpha" );
        final WikiModuleInfo b = new WikiModuleInfo( "Beta" );
        assertTrue( a.compareTo( b ) < 0 );
        assertTrue( b.compareTo( a ) > 0 );
    }

    // -----------------------------------------------------------------------
    // getTextResource — null resource path
    // -----------------------------------------------------------------------

    @Test
    void testGetTextResourceReturnsEmptyStringWhenResourceIsNull() throws IOException {
        final WikiModuleInfo info = new WikiModuleInfo( "NoResource" );
        // resource field is null by default; getTextResource should return ""
        assertEquals( "", info.getTextResource( "ini/wikantik_module.xml" ) );
    }
}
