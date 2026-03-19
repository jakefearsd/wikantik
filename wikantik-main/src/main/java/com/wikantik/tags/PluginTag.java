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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.plugin.PluginManager;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.BodyContent;
import java.io.IOException;
import java.util.Map;

/**
 *  Inserts any Wiki plugin.  The body of the tag becomes then
 *  the body for the plugin.
 *  <P><B>Attributes</B></P>
 *  <UL>
 *    <LI>plugin - name of the plugin you want to insert.
 *    <LI>args   - An argument string for the tag.
 *  </UL>
 *
 *  @since 2.0
 */
public class PluginTag
    extends WikiBodyTag
{
    private static final long serialVersionUID = 0L;
    private static final Logger LOG = LogManager.getLogger( PluginTag.class );
    
    private String plugin;
    private String args;

    private boolean evaluated;

    /**
     *  {@inheritDoc}
     */
    @Override
    public void release()
    {
        super.release();
        plugin = args = null;
        evaluated = false;
    }
    
    /**
     *  Set the name of the plugin to execute.
     *  
     *  @param p Name of the plugin.
     */
    public void setPlugin( final String p )
    {
        plugin = p;
    }

    /**
     *  Set the argument string to the plugin.
     *  
     *  @param a Arguments string.
     */
    public void setArgs( final String a )
    {
        args = a;
    }
    
    /**
     *  {@inheritDoc}
     */
    @Override
    public int doWikiStartTag() throws JspException, IOException
    {
        evaluated = false;
        return EVAL_BODY_BUFFERED;
    }

    private String executePlugin( final String plugin, final String args, final String body ) throws PluginException, IOException {
        final Engine engine = wikiContext.getEngine();
        final PluginManager pm  = engine.getManager( PluginManager.class );

        evaluated = true;

        final Map<String, String> argmap = pm.parseArgs( args );
        
        if( body != null ) 
        {
            argmap.put( "_body", body );
        }

        return pm.execute( wikiContext, plugin, argmap );
    }
    
    /**
     *  {@inheritDoc}
     */
    @Override
    public int doEndTag()
        throws JspException
    {
        if( !evaluated )
        {
            try
            {
                pageContext.getOut().write( executePlugin( plugin, args, null ) );
            }
            catch( final Exception e )
            {
                LOG.error( "Failed to insert plugin", e );
                throw new JspException( "Tag failed, check logs: "+e.getMessage() );
            }
        }
        return EVAL_PAGE;
    }
    
    /**
     *  {@inheritDoc}
     */
    @Override
    public int doAfterBody()
        throws JspException
    {
        try
        {
            final BodyContent bc = getBodyContent();
            
            getPreviousOut().write( executePlugin( plugin, args, (bc != null) ? bc.getString() : null) );
        }
        catch( final Exception e )
        {
            LOG.error( "Failed to insert plugin", e );
            throw new JspException( "Tag failed, check logs: "+e.getMessage() );
        }
        
        return SKIP_BODY;
    }
}
