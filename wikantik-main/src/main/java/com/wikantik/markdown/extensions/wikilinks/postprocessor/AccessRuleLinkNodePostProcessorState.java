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
package com.wikantik.markdown.extensions.wikilinks.postprocessor;

import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.acl.DefaultAclManager;
import com.wikantik.markdown.nodes.WikantikLink;

import java.util.List;


/**
 * {@link NodePostProcessorState} which further post processes access rules links: removes each from the rendered page,
 * or shows an error in its place when the ACL parser would refuse it. It sets no ACL: the enforced ACL comes from
 * {@code AccessRules} alone, whatever was rendered (#104).
 */
public class AccessRuleLinkNodePostProcessorState implements NodePostProcessorState< WikantikLink > {

    private static final Logger LOG = LogManager.getLogger( AccessRuleLinkNodePostProcessorState.class );
    private final Context wikiContext;
    private final boolean wysiwygEditorMode;

    /**
     * Context variable the owning {@code MarkdownParser} sets (to {@link Boolean#FALSE}) for the duration of a
     * parse whose access rules were disabled via {@code MarkupParser.disableAccessRules()}. Absent or anything
     * else means rules are parsed.
     */
    public static final String VAR_PARSE_ACCESS_RULES = "_Markdown.parseAccessRules";

    /**
     * Context variable that, when it holds a {@code List<String>}, receives every rule line this post-processor reads,
     * in order — the render path's reading of the text, for a caller that compares it with another.
     */
    public static final String VAR_READ_ACCESS_RULES = "_Markdown.readAccessRules";

    public AccessRuleLinkNodePostProcessorState( final Context wikiContext ) {
        this.wikiContext = wikiContext;
        final Boolean wysiwygVariable = wikiContext.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE );
        wysiwygEditorMode = wysiwygVariable != null ? wysiwygVariable : false;
    }

    /**
     * The rule line this post-processor hands the ACL parser for an access-rule link: the link target less one leading
     * <code>{</code> and one trailing <code>}</code> ({@code {ALLOW view All}} gives {@code ALLOW view All}).
     *
     * @param link the access-rule link's target (see {@code WikantikLink.wikiLinkOf})
     * @return the rule line
     */
    public static String ruleLineOf( final String link ) {
        String ruleLine = link;
        if( ruleLine.startsWith( "{" ) ) {
            ruleLine = ruleLine.substring( 1 );
        }
        if( ruleLine.endsWith( "}" ) ) {
            ruleLine = ruleLine.substring( 0, ruleLine.length() - 1 );
        }
        return ruleLine;
    }

    /**
     * {@inheritDoc}
     *
     * @see NodePostProcessorState#process(NodeTracker, Node)
     */
    @Override
    public void process( final NodeTracker state, final WikantikLink link ) {
        final String target = NodePostProcessorStateCommonOperations.inlineLinkTextOnWysiwyg( state, link, wysiwygEditorMode );
        if( !Boolean.FALSE.equals( wikiContext.getVariable( VAR_PARSE_ACCESS_RULES ) ) ) {
            final Page page = wikiContext.getRealPage();
            final String ruleLine = ruleLineOf( target );
            LOG.debug( "page={}, ACL = {}", page.getName(), ruleLine );
            recordRead( ruleLine );
            try {
                DefaultAclManager.checkRuleLine( page, ruleLine );
                link.unlink();
                state.nodeRemoved( link );
            } catch( final WikiSecurityException wse ) {
                NodePostProcessorStateCommonOperations.makeError( state, link, wse.getMessage() );
            }
        }
    }

    @SuppressWarnings( "unchecked" )
    private void recordRead( final String ruleLine ) {
        final Object read = wikiContext.getVariable( VAR_READ_ACCESS_RULES );
        if( read instanceof List< ? > list ) {
            ( ( List< String > ) list ).add( ruleLine );
        }
    }

}
