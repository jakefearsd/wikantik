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
package com.wikantik.parser;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.ParserStagePlugin;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.plugin.PluginManager;
import com.wikantik.variables.VariableManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PluginContent} targeting the uncovered branches:
 * getParameter / getParameters, getValue, getText when doc is null or context is null,
 * invoke in wysiwyg mode and normal mode, invoke with plugin execution disabled,
 * invoke exception path, executeParse, and parsePluginLine.
 */
class PluginContentTest {

    private static final String PLUGIN_NAME = "SamplePlugin";

    private Map<String, String> params;

    @BeforeEach
    void setUp() {
        params = new HashMap<>();
        params.put("_cmdline", "SamplePlugin text=hello");
        params.put("text", "hello");
    }

    // -------------------------------------------------------------------------
    //  Constructor / accessors
    // -------------------------------------------------------------------------

    @Test
    void getPluginNameReturnsSuppliedName() {
        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        assertEquals( PLUGIN_NAME, pc.getPluginName() );
    }

    @Test
    void getParameterReturnsCorrectValue() {
        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        assertEquals( "hello", pc.getParameter( "text" ) );
    }

    @Test
    void getParameterReturnsNullForMissingKey() {
        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        assertNull( pc.getParameter( "no_such_key" ) );
    }

    @Test
    void getParametersReturnsSameMap() {
        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        assertSame( params, pc.getParameters() );
    }

    // -------------------------------------------------------------------------
    //  getValue / getText when not yet attached to a document
    // -------------------------------------------------------------------------

    @Test
    void getValueDelegatesToGetText() {
        // When no document is attached getText() should return pluginName
        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        assertEquals( PLUGIN_NAME, pc.getValue() );
    }

    @Test
    void getTextReturnsPluginNameWhenDocumentIsNull() {
        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        // No document attached — getDocument() returns null
        assertEquals( PLUGIN_NAME, pc.getText() );
    }

    @Test
    void getTextReturnsPluginNameWhenContextIsNull() {
        // Build a WikiDocument whose context is explicitly set to null (simulates GC)
        final Page page = mock( Page.class );
        final WikiDocument doc = new WikiDocument( page );
        doc.setContext( null );  // WeakReference<>(null).get() returns null
        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        // Give the document a root element so addContent() works
        doc.setRootElement( new org.jdom2.Element( "root" ) );
        doc.getRootElement().addContent( pc );   // attach to doc so getDocument() != null
        // context returns null → getText() should return pluginName
        assertEquals( PLUGIN_NAME, pc.getText() );
    }

    // -------------------------------------------------------------------------
    //  invoke — WYSIWYG editor mode (plugin is NOT emittable)
    // -------------------------------------------------------------------------

    @Test
    void invokeReturnsMarkupInWysiwygModeForNonEmittablePlugin() {
        final Context context = mock( Context.class );
        when( context.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE ) ).thenReturn( Boolean.TRUE );

        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        final String result = pc.invoke( context );
        // Should contain the plugin name wrapped in [{ … }]
        assertTrue( result.startsWith( "[{" + PLUGIN_NAME ) );
        assertTrue( result.endsWith( "}]" ) );
    }

    @Test
    void invokeReturnsEmptyStringOnExceptionInWysiwygMode() {
        final Context context = mock( Context.class );
        when( context.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE ) ).thenReturn( Boolean.TRUE );

        // Pass null params so params.get("_cmdline").replaceAll(...) throws NPE
        final PluginContent pc = new PluginContent( PLUGIN_NAME, new HashMap<>() );
        final String result = pc.invoke( context );
        assertEquals( "", result );
    }

    // -------------------------------------------------------------------------
    //  invoke — VAR_EXECUTE_PLUGINS == false
    // -------------------------------------------------------------------------

    @Test
    void invokeReturnsBlankWhenExecutePluginsDisabled() {
        final Context context = mock( Context.class );
        when( context.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE ) ).thenReturn( null );
        when( context.getVariable( Context.VAR_EXECUTE_PLUGINS ) ).thenReturn( Boolean.FALSE );

        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        final String result = pc.invoke( context );
        assertEquals( "", result );
    }

    // -------------------------------------------------------------------------
    //  invoke — normal execution path
    // -------------------------------------------------------------------------

    @Test
    void invokeExecutesPluginAndReturnsResult() throws PluginException {
        final VariableManager vm = mock( VariableManager.class );
        when( vm.expandVariables( any( Context.class ), anyString() ) )
                .thenAnswer( inv -> inv.getArgument( 1 ) ); // pass through

        final PluginManager pm = mock( PluginManager.class );
        when( pm.execute( any( Context.class ), eq( PLUGIN_NAME ), anyMap() ) )
                .thenReturn( "plugin-output" );

        final Engine engine = MockEngineBuilder.engine()
                .with( VariableManager.class, vm )
                .with( PluginManager.class, pm )
                .build();

        final Context context = mock( Context.class );
        when( context.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE ) ).thenReturn( null );
        when( context.getVariable( Context.VAR_EXECUTE_PLUGINS ) ).thenReturn( null );
        when( context.getEngine() ).thenReturn( engine );

        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        final String result = pc.invoke( context );
        assertEquals( "plugin-output", result );
    }

    // -------------------------------------------------------------------------
    //  invoke — exception in normal execution (non-wysiwyg)
    // -------------------------------------------------------------------------

    @Test
    void invokeReturnsErrorMarkupOnPluginException() throws PluginException {
        final VariableManager vm = mock( VariableManager.class );
        when( vm.expandVariables( any( Context.class ), anyString() ) )
                .thenAnswer( inv -> inv.getArgument( 1 ) );

        final PluginManager pm = mock( PluginManager.class );
        when( pm.execute( any( Context.class ), anyString(), anyMap() ) )
                .thenThrow( new PluginException( "boom" ) );

        final Engine engine = MockEngineBuilder.engine()
                .with( VariableManager.class, vm )
                .with( PluginManager.class, pm )
                .build();

        final Page page = mock( Page.class );
        when( page.getWiki() ).thenReturn( "test" );
        when( page.getName() ).thenReturn( "TestPage" );

        final Context context = mock( Context.class );
        when( context.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE ) ).thenReturn( null );
        when( context.getVariable( Context.VAR_EXECUTE_PLUGINS ) ).thenReturn( null );
        when( context.getEngine() ).thenReturn( engine );
        when( context.getRealPage() ).thenReturn( page );

        // Supply the plugin resource bundle so the error formatter can find its key
        final ResourceBundle rb = ResourceBundle.getBundle( Plugin.CORE_PLUGINS_RESOURCEBUNDLE );
        try ( var ignored = mockStatic( com.wikantik.preferences.Preferences.class ) ) {
            when( com.wikantik.preferences.Preferences.getBundle( any(), anyString() ) ).thenReturn( rb );

            final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
            final String result = pc.invoke( context );
            // The error markup is a non-empty string produced by MarkupParser.makeError
            assertNotNull( result );
            assertFalse( result.isEmpty() );
        }
    }

    // -------------------------------------------------------------------------
    //  executeParse — plugins disabled
    // -------------------------------------------------------------------------

    @Test
    void executeParseDoesNothingWhenPluginsDisabled() throws PluginException {
        final PluginManager pm = mock( PluginManager.class );
        when( pm.pluginsEnabled() ).thenReturn( false );

        final Engine engine = MockEngineBuilder.engine()
                .with( PluginManager.class, pm )
                .build();

        final Context context = mock( Context.class );
        when( context.getEngine() ).thenReturn( engine );

        final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
        // Should not throw
        pc.executeParse( context );
        verify( pm, never() ).newWikiPlugin( anyString(), any( ResourceBundle.class ) );
    }

    // -------------------------------------------------------------------------
    //  executeParse — plugin enabled, non-ParserStagePlugin
    // -------------------------------------------------------------------------

    @Test
    void executeParseDoesNothingForNonParserStagePlugin() throws PluginException {
        final Plugin regularPlugin = mock( Plugin.class );
        final PluginManager pm = mock( PluginManager.class );
        when( pm.pluginsEnabled() ).thenReturn( true );
        when( pm.newWikiPlugin( eq( PLUGIN_NAME ), any( ResourceBundle.class ) ) )
                .thenReturn( regularPlugin );

        final Engine engine = MockEngineBuilder.engine()
                .with( PluginManager.class, pm )
                .build();

        final Context context = mock( Context.class );
        when( context.getEngine() ).thenReturn( engine );

        final ResourceBundle rb = ResourceBundle.getBundle( Plugin.CORE_PLUGINS_RESOURCEBUNDLE );
        try ( var ignored = mockStatic( com.wikantik.preferences.Preferences.class ) ) {
            when( com.wikantik.preferences.Preferences.getBundle( any(), anyString() ) ).thenReturn( rb );

            final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
            // Should not throw and should not call executeParser
            pc.executeParse( context );
        }
    }

    // -------------------------------------------------------------------------
    //  executeParse — plugin is a ParserStagePlugin
    // -------------------------------------------------------------------------

    @Test
    void executeParseCallsExecuteParserForParserStagePlugin() throws PluginException {
        // Create a mock that is both Plugin and ParserStagePlugin
        interface TestParserPlugin extends Plugin, ParserStagePlugin {}
        final TestParserPlugin psp = mock( TestParserPlugin.class );

        final PluginManager pm = mock( PluginManager.class );
        when( pm.pluginsEnabled() ).thenReturn( true );
        when( pm.newWikiPlugin( eq( PLUGIN_NAME ), any( ResourceBundle.class ) ) )
                .thenReturn( psp );

        final Engine engine = MockEngineBuilder.engine()
                .with( PluginManager.class, pm )
                .build();

        final Context context = mock( Context.class );
        when( context.getEngine() ).thenReturn( engine );

        final ResourceBundle rb = ResourceBundle.getBundle( Plugin.CORE_PLUGINS_RESOURCEBUNDLE );
        try ( var ignored = mockStatic( com.wikantik.preferences.Preferences.class ) ) {
            when( com.wikantik.preferences.Preferences.getBundle( any(), anyString() ) ).thenReturn( rb );

            final PluginContent pc = new PluginContent( PLUGIN_NAME, params );
            pc.executeParse( context );

            verify( psp ).executeParser( eq( pc ), eq( context ), anyMap() );
        }
    }

    // -------------------------------------------------------------------------
    //  parsePluginLine
    // -------------------------------------------------------------------------

    @Test
    void parsePluginLineReturnsNullForNonMatchingLine() throws PluginException {
        final PluginManager pm = mock( PluginManager.class );
        when( pm.getPluginPattern() ).thenReturn(
                java.util.regex.Pattern.compile( "\\{?(\\s*)(INSERT)?\\s*([\\w.]+)[\\s]+WHERE" ) );

        final Engine engine = MockEngineBuilder.engine()
                .with( PluginManager.class, pm )
                .build();
        final Context context = mock( Context.class );
        when( context.getEngine() ).thenReturn( engine );

        final PluginContent result = PluginContent.parsePluginLine( context, "no match here", -1 );
        assertNull( result );
    }

}
