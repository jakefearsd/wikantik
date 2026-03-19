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

import com.wikantik.api.core.Session;
import com.wikantik.auth.AuthenticationManager;

/**
 *  Includes the content if an user check validates.  This has been considerably enhanced for 2.2.  The possibilities for the
 *  "status"-argument are:
 *
 * <ul>
 * <li>"anonymous"            - the body of the tag is included if the user is completely unknown (no cookie, no password)</li>
 * <li>"asserted"             - the body of the tag is included if the user has either been named by a cookie, but not been authenticated.</li>
 * <li>"authenticated"        - the body of the tag is included if the user is validated either through the container, or by our own authentication.</li>
 * <li>"assertionsAllowed"    - the body of the tag is included if wiki allows identities to be asserted using cookies.</li>
 * <li>"assertionsNotAllowed" - the body of the tag is included if wiki does <i>not</i> allow identities to be asserted using cookies.</li>
 * <li>"containerAuth"        - the body of the tag is included if the user is validated through the container.</li>
 * <li>"customAuth"           - the body of the tag is included if the user is validated through our own authentication.</li>
 * <li>"known"                - if the user is not anonymous</li>
 * <li>"notAuthenticated"     - the body of the tag is included if the user is not yet authenticated.</li>
 * </ul>
 *
 *  If the old "exists" -argument is used, it corresponds as follows:
 *  <p>
 *  <tt>exists="true" ==> status="known"<br>
 *  <tt>exists="false" ==> status="unknown"<br>
 *  </p>
 *
 *  It is NOT a good idea to use BOTH of the arguments.
 *
 *  @since 2.0
 */
public class UserCheckTag extends WikiTagBase {

    private static final long serialVersionUID = 3256438110127863858L;
    private static final String ASSERTED = "asserted";
    private static final String AUTHENTICATED = "authenticated";
    private static final String ANONYMOUS = "anonymous";
    private static final String ASSERTIONS_ALLOWED = "assertionsallowed";
    private static final String ASSERTIONS_NOT_ALLOWED = "assertionsnotallowed";
    private static final String CONTAINER_AUTH = "containerauth";
    private static final String CUSTOM_AUTH = "customauth";
    private static final String KNOWN = "known";
    private static final String NOT_AUTHENTICATED = "notauthenticated";

    private String status;

    /**
     *  {@inheritDoc}
     */
    @Override
    public void initTag() {
        super.initTag();
        status = null;
    }

    /**
     *  Get the status as defined above.
     *  
     *  @return The status to be checked.
     */
    public String getStatus()
    {
        return status;
    }

    /**
     *  Sets the status as defined above.
     *  
     *  @param status The status to be checked.
     */
    public void setStatus( final String status )
    {
        this.status = status.toLowerCase();
    }

    /**
     * {@inheritDoc}
     * @see com.wikantik.tags.WikiTagBase#doWikiStartTag()
     */
    @Override
    public final int doWikiStartTag() {
        final Session session = wikiContext.getWikiSession();
        final String sessionStatus = session.getStatus();
        final AuthenticationManager mgr = wikiContext.getEngine().getManager( AuthenticationManager.class );
        final boolean containerAuth = mgr.isContainerAuthenticated();
        final boolean cookieAssertions = mgr.allowsCookieAssertions();

        if( status != null ) {
            switch( status ) {
            case ANONYMOUS:
                if( sessionStatus.equals( Session.ANONYMOUS ) ) {
                    return EVAL_BODY_INCLUDE;
                }
                break;
            case AUTHENTICATED:
                if( sessionStatus.equals( Session.AUTHENTICATED ) ) {
                    return EVAL_BODY_INCLUDE;
                }
                break;
            case ASSERTED:
                if( sessionStatus.equals( Session.ASSERTED ) ) {
                    return EVAL_BODY_INCLUDE;
                }
                break;
            case ASSERTIONS_ALLOWED:
                if( cookieAssertions ) {
                    return EVAL_BODY_INCLUDE;
                }
                return SKIP_BODY;
            case ASSERTIONS_NOT_ALLOWED:
                if( !cookieAssertions ) {
                    return EVAL_BODY_INCLUDE;
                }
                return SKIP_BODY;
            case CONTAINER_AUTH:
                if( containerAuth ) {
                    return EVAL_BODY_INCLUDE;
                }
                return SKIP_BODY;
            case CUSTOM_AUTH:
                if( !containerAuth ) {
                    return EVAL_BODY_INCLUDE;
                }
                return SKIP_BODY;
            case KNOWN:
                if( !session.isAnonymous() ) {
                    return EVAL_BODY_INCLUDE;
                }
                return SKIP_BODY;
            case NOT_AUTHENTICATED:
                if( !sessionStatus.equals( Session.AUTHENTICATED ) ) {
                    return EVAL_BODY_INCLUDE;
                }
                break;
            }
        }

        return SKIP_BODY;
    }

}
