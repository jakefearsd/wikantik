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
package com.wikantik.auth;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.comments.PageOwnerService;
import com.wikantik.pagegraph.spine.TrustedAuthorsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.Principal;

/**
 * The single hard-delete path for a user account. Self-service delete and admin single/bulk delete all go
 * through {@link #delete}, so none of them can leave behind an identity-keyed row that a later account
 * re-created with the same login would inherit.
 *
 * <p>Several tables key on the login name with no foreign key to {@code users}: {@code api_keys.principal_login},
 * {@code group_members.member}, {@code trusted_authors.login_name}, and page ownership. Deleting only the
 * profile row leaves a live API key for a user that no longer exists and group memberships (including
 * {@code Admin}) that the next holder of that login silently inherits.</p>
 *
 * <p>Failure semantics, in order:</p>
 * <ol>
 *   <li>Orphaning owned pages is <b>best-effort</b> (logged, never aborts): the pages stay valid and an admin can
 *       reassign orphans later.</li>
 *   <li>Revoking API keys, removing group memberships and removing the trusted-author entry are
 *       <b>fail-closed</b>: a failure is logged and the delete is aborted with a {@link WikiSecurityException}
 *       <em>before</em> the profile row is touched, so the account remains and the operation can be retried. A
 *       live credential or privilege must never outlive its user. Group removal still attempts every group before
 *       reporting, so one bad group does not strand the others.</li>
 *   <li>The profile row is deleted last; its {@link NoSuchPrincipalException} propagates unchanged.</li>
 * </ol>
 *
 * <p>SCIM never hard-deletes (it routes decommission through {@link UserLifecycleService#deactivate}), so it does
 * not use this service.</p>
 */
public class UserDeletionService {

    private static final Logger LOG = LogManager.getLogger( UserDeletionService.class );

    private final Engine engine;
    private final UserDatabase users;
    private final GroupManager groups;
    private final ApiKeyService apiKeys;
    private final PageOwnerService pageOwners;
    private final TrustedAuthorsDao trustedAuthors;

    /**
     * @param apiKeys        {@code null} when no datasource is configured (nothing to revoke)
     * @param pageOwners     {@code null} skips page orphaning
     * @param trustedAuthors {@code null} skips the trusted-author cleanup
     */
    public UserDeletionService( final Engine engine, final UserDatabase users, final GroupManager groups,
                                final ApiKeyService apiKeys, final PageOwnerService pageOwners,
                                final TrustedAuthorsDao trustedAuthors ) {
        this.engine = engine;
        this.users = users;
        this.groups = groups;
        this.apiKeys = apiKeys;
        this.pageOwners = pageOwners;
        this.trustedAuthors = trustedAuthors;
    }

    /**
     * Hard-deletes {@code loginName} and everything keyed on it.
     *
     * @param actorSession the session performing the delete (recorded as the modifier of any group it edits)
     * @throws NoSuchPrincipalException if no such account exists
     * @throws WikiSecurityException    if a fail-closed cleanup step failed (the account is left in place) or the
     *                                  profile row could not be deleted
     */
    public void delete( final Session actorSession, final String loginName )
            throws NoSuchPrincipalException, WikiSecurityException {
        orphanOwnedPages( loginName );
        revokeApiKeys( loginName );
        removeFromAllGroups( actorSession, loginName );
        removeTrustedAuthor( loginName );
        users.deleteByLoginName( loginName );
        ProfileChangeNotifier.profileDeleted( engine, loginName );
    }

    private void orphanOwnedPages( final String loginName ) {
        if ( pageOwners == null ) {
            return;
        }
        try {
            pageOwners.orphanByOwner( loginName, "system:user-deleted:" + loginName );
        } catch ( final RuntimeException e ) {
            LOG.warn( "User delete: failed to orphan pages for user={}: {}", loginName, e.getMessage(), e );
        }
    }

    private void revokeApiKeys( final String loginName ) throws WikiSecurityException {
        if ( apiKeys == null ) {
            LOG.warn( "ApiKeyService not available — skipping API key revocation for '{}'", loginName );
            return;
        }
        try {
            apiKeys.revokeAllForPrincipalOrThrow( loginName );
        } catch ( final Exception e ) {
            LOG.warn( "User delete aborted: failed to revoke API keys for user={}: {}", loginName, e.getMessage(), e );
            throw new WikiSecurityException( "Could not revoke API keys for '" + loginName + "'; account not deleted", e );
        }
    }

    private void removeFromAllGroups( final Session actorSession, final String loginName ) throws WikiSecurityException {
        final Principal member = new WikiPrincipal( loginName, WikiPrincipal.LOGIN_NAME );
        final StringBuilder failed = new StringBuilder();
        for ( final Principal groupRole : groups.getRoles() ) {
            try {
                final Group group = groups.getGroup( groupRole.getName() );
                if ( group.isMember( member ) ) {
                    group.remove( member );
                    groups.setGroup( actorSession, group );
                }
            } catch ( final Exception e ) {
                LOG.warn( "User delete: failed to remove user={} from group={}: {}",
                        loginName, groupRole.getName(), e.getMessage(), e );
                failed.append( failed.length() == 0 ? "" : ", " ).append( groupRole.getName() );
            }
        }
        if ( failed.length() > 0 ) {
            throw new WikiSecurityException( "Could not remove '" + loginName + "' from group(s) " + failed
                    + "; account not deleted" );
        }
    }

    private void removeTrustedAuthor( final String loginName ) throws WikiSecurityException {
        if ( trustedAuthors == null ) {
            return;
        }
        try {
            trustedAuthors.remove( loginName );
        } catch ( final RuntimeException e ) {
            LOG.warn( "User delete aborted: failed to remove trusted-author entry for user={}: {}",
                    loginName, e.getMessage(), e );
            throw new WikiSecurityException( "Could not remove trusted-author entry for '" + loginName
                    + "'; account not deleted", e );
        }
    }
}
