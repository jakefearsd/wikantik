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
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.acl.AclManager;
import com.wikantik.markdown.nodes.WikantikLink;
import com.wikantik.render.RenderingManager;


/**
 * {@link NodePostProcessorState} which further post processes access rules links.
 */
public class AccessRuleLinkNodePostProcessorState implements NodePostProcessorState< WikantikLink > {

    private static final Logger LOG = LogManager.getLogger( AccessRuleLinkNodePostProcessorState.class );
    private final Context wikiContext;
    private final boolean wysiwygEditorMode;

    public AccessRuleLinkNodePostProcessorState( final Context wikiContext ) {
        this.wikiContext = wikiContext;
        final Boolean wysiwygVariable = wikiContext.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE );
        wysiwygEditorMode = wysiwygVariable != null ? wysiwygVariable : false;
    }

    /**
     * {@inheritDoc}
     *
     * @see NodePostProcessorState#process(NodeTracker, Node)
     */
    @Override
    public void process( final NodeTracker state, final WikantikLink link ) {
        String ruleLine = NodePostProcessorStateCommonOperations.inlineLinkTextOnWysiwyg( state, link, wysiwygEditorMode );
        if( wikiContext.getEngine().getManager( RenderingManager.class ).getParser( wikiContext, link.getUrl().toString() ).isParseAccessRules() ) {
            final Page page = wikiContext.getRealPage();
            if( ruleLine.startsWith( "{" ) ) {
                ruleLine = ruleLine.substring( 1 );
            }
            if( ruleLine.endsWith( "}" ) ) {
                ruleLine = ruleLine.substring( 0, ruleLine.length() - 1 );
            }
            LOG.debug( "page={}, ACL = {}", page.getName(), ruleLine );

            try {
                final Acl acl = wikiContext.getEngine().getManager( AclManager.class ).parseAcl( page, ruleLine );
                page.setAcl( acl );
                link.unlink();
                state.nodeRemoved( link );
                LOG.debug( acl.toString() );
            } catch( final WikiSecurityException wse ) {
                NodePostProcessorStateCommonOperations.makeError( state, link, wse.getMessage() );
            }
        }
    }

}
