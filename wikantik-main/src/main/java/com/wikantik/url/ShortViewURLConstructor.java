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
package com.wikantik.url;

import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Properties;

/**
 *  A specific URL constructor that returns easy-to-grok URLs for VIEW and ATTACH contexts, but delegates to the default constructor otherwise.
 *
 *  @since 2.2
 */
public class ShortViewURLConstructor extends ShortURLConstructor {

    /**
     *  {@inheritDoc}
     */
    @Override public void initialize( final Engine engine, final Properties properties ) {
        super.initialize( engine, properties );
    }
    
    private String makeURL( final String context, final String name ) {
        final String viewurl = "%p" + urlPrefix + "%n";
        if( context.equals( ContextEnum.PAGE_VIEW.getRequestContext() ) ) {
            if( name == null ) {
                return doReplacement("%u","" );
            }
            return doReplacement( viewurl, name );
        } else if( context.equals( ContextEnum.PAGE_EDIT.getRequestContext() ) ) {
            return doReplacement( "%pedit/%n", name );
        } else if( context.equals( ContextEnum.PAGE_DIFF.getRequestContext() ) ) {
            return doReplacement( "%pdiff/%n", name );
        } else if( context.equals( ContextEnum.WIKI_FIND.getRequestContext() ) ) {
            return doReplacement( "%psearch", name );
        } else if( context.equals( ContextEnum.WIKI_PREFS.getRequestContext() ) ) {
            return doReplacement( "%ppreferences", name );
        }

        return doReplacement( DefaultURLConstructor.getURLPattern( context, name ), name );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String makeURL( final String context, final String name, String parameters ) {
        if( parameters != null && !parameters.isEmpty() ) {
            if( context.equals( ContextEnum.PAGE_ATTACH.getRequestContext() ) || context.equals( ContextEnum.PAGE_VIEW.getRequestContext() ) || name == null ) {
                parameters = "?" + parameters;
            } else if( context.equals(ContextEnum.PAGE_NONE.getRequestContext()) ) {
                parameters = (name.indexOf('?') != -1 ) ? "&amp;" : "?" + parameters;
            } else {
                parameters = "&amp;" + parameters;
            }
        } else {
            parameters = "";
        }
        return makeURL( context, name ) + parameters;
    }

}
