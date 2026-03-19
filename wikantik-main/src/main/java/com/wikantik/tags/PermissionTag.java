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

import org.apache.commons.lang3.StringUtils;
import com.wikantik.api.core.Command;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.GroupPrincipal;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.pages.PageManager;
import com.wikantik.ui.GroupCommand;

import java.security.Permission;

/**
 *  Tells whether the user in the current wiki context possesses a particular
 *  permission. The permission is typically a PagePermission (e.g., "edit", "view",
 *  "delete", "comment", "upload"). It may also be a wiki-wide WikiPermission
 *  ("createPages", "createGroups", "editProfile", "editPreferences", "login")
 *  or the administrator permission ("allPermission"). GroupPermissions 
 *  (e.g., "viewGroup", "editGroup", "deleteGroup").
 *  <p>
 *  Since 2.6, it is possible to list several permissions or use negative permissions,
 *  e.g.
 *  <pre>
 *     &lt;wiki:Permission permission="edit|rename|view"&gt;
 *        You have edit, rename, or  view permissions!
 *     &lt;/wiki:Permission&gt;
 *  </pre>
 *  
 *  or
 *
 *  <pre>
 *     &lt;wiki:Permission permission="!upload"&gt;
 *        You do not have permission to upload!
 *     &lt;/wiki:Permission&gt;
 *  </pre>
 *  
 *  @since 2.0
 */
public class PermissionTag extends WikiTagBase {

    private static final String ALL_PERMISSION   = "allPermission";
    private static final String CREATE_GROUPS    = "createGroups";
    private static final String CREATE_PAGES     = "createPages";
    private static final String DELETE_GROUP     = "deleteGroup";
    private static final String EDIT             = "edit";
    private static final String EDIT_GROUP       = "editGroup";
    private static final String EDIT_PREFERENCES = "editPreferences";
    private static final String EDIT_PROFILE     = "editProfile";
    private static final String LOGIN            = "login";
    private static final String VIEW_GROUP       = "viewGroup";
    
    private static final long serialVersionUID = 3761412993048982325L;
    
    private String[] permissionList;

    /**
     * Initializes the tag.
     */
    @Override
    public void initTag() {
        super.initTag();
        permissionList = null;
    }

    /**
     * Sets the permissions to look for (case sensitive).  See above for the format.
     * 
     * @param permission A list of permissions
     */
    public void setPermission( final String permission )
    {
        permissionList = StringUtils.split(permission,'|');
    }

    /**
     *  Checks a single permission.
     *  
     *  @param permission permission to check for
     *  @return true if granted, false if not
     */
    private boolean checkPermission( final String permission ) {
        final Session session          = wikiContext.getWikiSession();
        final Page page                = wikiContext.getPage();
        final AuthorizationManager mgr = wikiContext.getEngine().getManager( AuthorizationManager.class );
        boolean gotPermission          = false;
        
        if ( CREATE_GROUPS.equals( permission ) || CREATE_PAGES.equals( permission ) || EDIT_PREFERENCES.equals( permission ) || EDIT_PROFILE.equals( permission ) || LOGIN.equals( permission ) ) {
            gotPermission = mgr.checkPermission( session, new WikiPermission( page.getWiki(), permission ) );
        } else if ( VIEW_GROUP.equals( permission ) || EDIT_GROUP.equals( permission ) || DELETE_GROUP.equals( permission ) )  {
            final Command command = wikiContext.getCommand();
            gotPermission = false;
            if ( command instanceof GroupCommand groupCommand && groupCommand.getTarget() != null ) {
                final GroupPrincipal group = (GroupPrincipal)groupCommand.getTarget();
                final String groupName = group.getName();
                String action = "view";
                if( EDIT_GROUP.equals( permission ) ) {
                    action = "edit";
                } else if ( DELETE_GROUP.equals( permission ) ) {
                    action = "delete";
                }
                gotPermission = mgr.checkPermission( session, new GroupPermission( groupName, action ) );
            }
        } else if ( ALL_PERMISSION.equals( permission ) ) {
            gotPermission = mgr.checkPermission( session, new AllPermission( wikiContext.getEngine().getApplicationName() ) );
        } else if ( page != null ) {
            //
            //  Edit tag also checks that we're not trying to edit an old version: they cannot be edited.
            //
            if( EDIT.equals(permission) ) {
                final Page latest = wikiContext.getEngine().getManager( PageManager.class ).getPage( page.getName() );
                if( page.getVersion() != WikiProvider.LATEST_VERSION && latest.getVersion() != page.getVersion() ) {
                    return false;
                }
            }

            final Permission p = PermissionFactory.getPagePermission( page, permission );
            gotPermission = mgr.checkPermission( session, p );
        }
        
        return gotPermission;
    }
    
    /**
     * Initializes the tag.
     * @return the result of the tag: SKIP_BODY or EVAL_BODY_CONTINUE
     */
    @Override
    public final int doWikiStartTag() {
        for( final String perm : permissionList ) {
            final boolean hasPermission;
            if( perm.charAt( 0 ) == '!' ) {
                hasPermission = !checkPermission( perm.substring( 1 ) );
            } else {
                hasPermission = checkPermission( perm );
            }

            if( hasPermission ) {
                return EVAL_BODY_INCLUDE;
            }
        }

        return SKIP_BODY;
    }
}
