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
package org.apache.wiki.auth.authorize;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.core.Session;
import org.apache.wiki.api.exceptions.NoRequiredPropertyException;
import org.apache.wiki.api.exceptions.WikiException;
import org.apache.wiki.auth.AuthenticationManager;
import org.apache.wiki.auth.Authorizer;
import org.apache.wiki.auth.GroupPrincipal;
import org.apache.wiki.auth.NoSuchPrincipalException;
import org.apache.wiki.auth.UserManager;
import org.apache.wiki.auth.WikiPrincipal;
import org.apache.wiki.auth.WikiSecurityException;
import org.apache.wiki.auth.user.UserProfile;
import org.apache.wiki.event.WikiEvent;
import org.apache.wiki.event.WikiEventListener;
import org.apache.wiki.event.WikiEventManager;
import org.apache.wiki.event.WikiSecurityEvent;
import org.apache.wiki.tasks.TasksManager;
import org.apache.wiki.ui.InputValidator;
import org.apache.wiki.util.ClassUtil;
import org.apache.wiki.workflow.Decision;
import org.apache.wiki.workflow.DecisionRequiredException;
import org.apache.wiki.workflow.Fact;
import org.apache.wiki.workflow.Step;
import org.apache.wiki.workflow.Workflow;
import org.apache.wiki.workflow.WorkflowBuilder;
import org.apache.wiki.workflow.WorkflowManager;

import java.security.Principal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;


/**
 * <p>
 * Facade class for storing, retrieving and managing wiki groups on behalf of AuthorizationManager, JSPs and other presentation-layer
 * classes. GroupManager works in collaboration with a back-end {@link GroupDatabase}, which persists groups to permanent storage.
 * </p>
 * <p>
 * <em>Note: prior to JSPWiki 2.4.19, GroupManager was an interface; it is now a concrete, final class. The aspects of GroupManager
 * which previously extracted group information from storage (e.g., wiki pages) have been refactored into the GroupDatabase interface.</em>
 * </p>
 * @since 2.4.19
 */
public class DefaultGroupManager implements GroupManager, Authorizer, WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( DefaultGroupManager.class );

    protected Engine engine;

    protected WikiEventListener groupListener;

    private GroupDatabase groupDatabase;

    /** Map with GroupPrincipals as keys, and Groups as values */
    private final Map< Principal, Group > groups = new HashMap<>();

    /** {@inheritDoc} */
    @Override
    public Principal findRole( final String name ) {
        try {
            final Group group = getGroup( name );
            return group.getPrincipal();
        } catch( final NoSuchPrincipalException e ) {
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Group getGroup( final String name ) throws NoSuchPrincipalException {
        final Group group = groups.get( new GroupPrincipal( name ) );
        if( group != null ) {
            return group;
        }
        throw new NoSuchPrincipalException( "Group " + name + " not found." );
    }

    /** {@inheritDoc} */
    @Override
    public GroupDatabase getGroupDatabase() throws WikiSecurityException {
        if( groupDatabase != null ) {
            return groupDatabase;
        }

        String dbClassName = "<unknown>";
        String dbInstantiationError = null;
        Throwable cause = null;
        try {
            final Properties props = engine.getWikiProperties();
            dbClassName = props.getProperty( PROP_GROUPDATABASE );
            if( dbClassName == null ) {
                dbClassName = XMLGroupDatabase.class.getName();
            }
            LOG.info( "Attempting to load group database class {}", dbClassName );
            groupDatabase = ClassUtil.buildInstance( "org.apache.wiki.auth.authorize", dbClassName );
            groupDatabase.initialize( engine, engine.getWikiProperties() );
            LOG.info( "Group database initialized." );
        } catch( final ReflectiveOperationException e ) {
            LOG.error( "UserDatabase {} cannot be instantiated", dbClassName, e );
            dbInstantiationError = "Access GroupDatabase class " + dbClassName + " denied";
            cause = e;
        } catch( final NoRequiredPropertyException e ) {
            LOG.error( "Missing property: " + e.getMessage() + "." );
            dbInstantiationError = "Missing property: " + e.getMessage();
            cause = e;
        }

        if( dbInstantiationError != null ) {
            throw new WikiSecurityException( dbInstantiationError + " Cause: " + cause.getMessage(), cause );
        }

        return groupDatabase;
    }

    /** {@inheritDoc} */
    @Override
    public Principal[] getRoles() {
        return groups.keySet().toArray( new Principal[0] );
    }

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine engine, final Properties props ) throws WikiSecurityException {
        this.engine = engine;

        try {
            groupDatabase = getGroupDatabase();
        } catch( final WikiException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }

        // Load all groups from the database into the cache
        final Group[] groups = groupDatabase.groups();
        synchronized( groups ) {
            for( final Group group : groups ) {
                // Add new group to cache; fire GROUP_ADD event
                this.groups.put( group.getPrincipal(), group );
                fireEvent( WikiSecurityEvent.GROUP_ADD, group );
            }
        }

        // Make the GroupManager listen for WikiEvents (WikiSecurityEvents for changed user profiles)
        engine.getManager( UserManager.class ).addWikiEventListener( this );

        // Success!
        LOG.info( "Authorizer GroupManager initialized successfully; loaded " + groups.length + " group(s)." );
    }

    /** {@inheritDoc} */
    @Override
    public boolean isUserInRole( final Session session, final Principal role ) {
        // Always return false if session/role is null, or if role isn't a GroupPrincipal
        if ( session == null || !( role instanceof GroupPrincipal groupPrincipal ) || !session.isAuthenticated() ) {
            return false;
        }

        // Get the group we're examining
        final Group group = groups.get( groupPrincipal );
        if( group == null ) {
            return false;
        }

        // Check each user principal to see if it belongs to the group
        return Arrays.stream(session.getPrincipals()).anyMatch(principal -> AuthenticationManager.isUserPrincipal(principal) && group.isMember(principal));
    }

    /** {@inheritDoc} */
    @Override
    public Group parseGroup( String name, String memberLine, final boolean create ) throws WikiSecurityException {
        // If null name parameter, it's because someone's creating a new group
        if( name == null ) {
            if( create ) {
                name = "MyGroup";
            } else {
                throw new WikiSecurityException( "Group name cannot be blank." );
            }
        } else if( ArrayUtils.contains( Group.RESTRICTED_GROUPNAMES, name ) ) {
            // Certain names are forbidden
            throw new WikiSecurityException( "Illegal group name: " + name );
        }
        name = name.trim();

        // Normalize the member line
        if( InputValidator.isBlank( memberLine ) ) {
            memberLine = "";
        }
        memberLine = memberLine.trim();

        // Create or retrieve the group (may have been previously cached)
        final Group group = new Group( name, engine.getApplicationName() );
        try {
            final Group existingGroup = getGroup( name );

            // If existing, clone it
            group.setCreator( existingGroup.getCreator() );
            group.setCreated( existingGroup.getCreated() );
            group.setModifier( existingGroup.getModifier() );
            group.setLastModified( existingGroup.getLastModified() );
            for( final Principal existingMember : existingGroup.members() ) {
                group.add( existingMember );
            }
        } catch( final NoSuchPrincipalException e ) {
            // It's a new group.... throw error if we don't create new ones
            if( !create ) {
                throw new NoSuchPrincipalException( "Group '" + name + "' does not exist." );
            }
        }

        // If passed members not empty, overwrite
        final String[] members = extractMembers( memberLine );
        if( members.length > 0 ) {
            group.clear();
            for( final String member : members ) {
                group.add( new WikiPrincipal( member ) );
            }
        }

        return group;
    }

    /** {@inheritDoc} */
    @Override
    public void removeGroup( final String index ) throws WikiSecurityException {
        if( index == null ) {
            throw new IllegalArgumentException( "Group cannot be null." );
        }

        final Group group = groups.get( new GroupPrincipal( index ) );
        if( group == null ) {
            throw new NoSuchPrincipalException( "Group " + index + " not found" );
        }

        // Delete the group
        // TODO: need rollback procedure
        synchronized( groups ) {
            groups.remove( group.getPrincipal() );
        }
        groupDatabase.delete( group );
        fireEvent( WikiSecurityEvent.GROUP_REMOVE, group );
    }

    /** {@inheritDoc} */
    @Override
    public void setGroup( final Session session, final Group group ) throws WikiException {
        // TODO: check for appropriate permissions

        // Check if workflow approval is required
        final WorkflowManager workflowManager = engine.getManager( WorkflowManager.class );
        if ( workflowManager.requiresApproval( WorkflowManager.WF_GRP_SAVE_APPROVER ) ) {
            // Start workflow for group save
            startGroupSaveWorkflow( session, group );
        } else {
            // No approval required - save directly
            setGroupInternal( session, group );
        }
    }

    /**
     * Starts a workflow for saving a group when approval is required.
     *
     * @param session the wiki session
     * @param group the group to save
     * @throws WikiException if the workflow cannot be started or approval is required
     */
    private void startGroupSaveWorkflow( final Session session, final Group group ) throws WikiException {
        final Principal submitter = session.getUserPrincipal();
        boolean isNewGroup = true;
        String currentMembers = "";

        try {
            final Group existingGroup = getGroup( group.getName() );
            isNewGroup = false;
            // Get current members as a newline-separated string
            currentMembers = Arrays.stream( existingGroup.members() )
                                   .map( Principal::getName )
                                   .reduce( ( a, b ) -> a + "\n" + b )
                                   .orElse( "" );
        } catch ( final NoSuchPrincipalException e ) {
            // Group doesn't exist - isNewGroup stays true, currentMembers stays empty
        }

        // Get proposed members as a newline-separated string
        final String proposedMembers = Arrays.stream( group.members() )
                                             .map( Principal::getName )
                                             .reduce( ( a, b ) -> a + "\n" + b )
                                             .orElse( "" );

        // Build facts for the approval decision
        final Fact[] facts = new Fact[] {
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_GROUP_NAME, group.getName() ),
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_PROPOSED_MEMBERS, proposedMembers ),
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_CURRENT_MEMBERS, currentMembers ),
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_SUBMITTER, submitter.getName() ),
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_IS_NEW, isNewGroup )
        };

        // Build the workflow
        final WorkflowBuilder builder = WorkflowBuilder.getBuilder( engine );
        final Step completionTask = engine.getManager( TasksManager.class ).buildSaveWikiGroupTask();

        try {
            final Workflow workflow = builder.buildApprovalWorkflow(
                submitter,
                WorkflowManager.WF_GRP_SAVE_APPROVER,
                null,  // no prep task
                WorkflowManager.WF_GRP_SAVE_DECISION_MESSAGE_KEY,
                facts,
                completionTask,
                WorkflowManager.WF_GRP_SAVE_REJECT_MESSAGE_KEY
            );

            // Store the group information as serializable strings for later retrieval
            // We store the name and members separately since Group is not Serializable
            workflow.setAttribute( WorkflowManager.WF_GRP_SAVE_ATTR_SAVED_GROUP, group.getName() );
            workflow.setAttribute( WorkflowManager.WF_GRP_SAVE_ATTR_SAVED_GROUP + ".members", proposedMembers );

            // Start the workflow
            workflow.start( null );

            // Check if we're waiting on a decision - throw as WikiSecurityException subclass
            if ( workflow.getCurrentStep() instanceof Decision ) {
                throw new DecisionRequiredException( "This group change must be approved before it takes effect." );
            }
        } catch ( final DecisionRequiredException e ) {
            // Re-throw decision required exceptions (it extends WikiSecurityException)
            throw e;
        } catch ( final Exception e ) {
            throw new WikiSecurityException( "Could not start group save workflow: " + e.getMessage(), e );
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setGroupInternal( final Session session, final Group group ) throws WikiSecurityException {
        // If group already exists, delete it; fire GROUP_REMOVE event
        final Group oldGroup = groups.get( group.getPrincipal() );
        if( oldGroup != null ) {
            fireEvent( WikiSecurityEvent.GROUP_REMOVE, oldGroup );
            synchronized( groups ) {
                groups.remove( oldGroup.getPrincipal() );
            }
        }

        // Copy existing modifier info & timestamps
        if( oldGroup != null ) {
            group.setCreator( oldGroup.getCreator() );
            group.setCreated( oldGroup.getCreated() );
            group.setModifier( oldGroup.getModifier() );
            group.setLastModified( oldGroup.getLastModified() );
        }

        // Add new group to cache; announce GROUP_ADD event
        synchronized( groups ) {
            groups.put( group.getPrincipal(), group );
        }
        fireEvent( WikiSecurityEvent.GROUP_ADD, group );

        // Save the group to back-end database; if it fails, roll back to previous state. Note that the back-end
        // MUST timestammp the create/modify fields in the Group.
        try {
            groupDatabase.save( group, session.getUserPrincipal() );
        }

        // We got an exception! Roll back...
        catch( final WikiSecurityException e ) {
            if( oldGroup != null ) {
                // Restore previous version, re-throw...
                fireEvent( WikiSecurityEvent.GROUP_REMOVE, group );
                fireEvent( WikiSecurityEvent.GROUP_ADD, oldGroup );
                synchronized( groups ) {
                    groups.put( oldGroup.getPrincipal(), oldGroup );
                }
                throw new WikiSecurityException( e.getMessage() + " (rolled back to previous version).", e );
            }
            // Re-throw security exception
            throw new WikiSecurityException( e.getMessage(), e );
        }
    }

    /** {@inheritDoc} */
    @Override
    public void validateGroup( final Context context, final Group group ) {
        final InputValidator validator = new InputValidator( MESSAGES_KEY, context );

        // Name cannot be null or one of the restricted names
        try {
            checkGroupName( context, group.getName() );
        } catch( final WikiSecurityException e ) {
        }

        // Member names must be "safe" strings
        final Principal[] members = group.members();
        for( final Principal member : members ) {
            validator.validateNotNull( member.getName(), "Full name", InputValidator.ID );
        }
    }

    /** {@inheritDoc} */
    @Override
    public void checkGroupName( final Context context, final String name ) throws WikiSecurityException {
        // TODO: groups cannot have the same name as a user

        // Name cannot be null
        final InputValidator validator = new InputValidator( MESSAGES_KEY, context );
        validator.validateNotNull( name, "Group name" );

        // Name cannot be one of the restricted names either
        if( ArrayUtils.contains( Group.RESTRICTED_GROUPNAMES, name ) ) {
            throw new WikiSecurityException( "The group name '" + name + "' is illegal. Choose another." );
        }
    }

    /**
     * Extracts carriage-return separated members into a Set of String objects.
     *
     * @param memberLine the list of members
     * @return the list of members
     */
    protected String[] extractMembers( final String memberLine ) {
        final Set< String > members = new HashSet<>();
        if( memberLine != null ) {
            final StringTokenizer tok = new StringTokenizer( memberLine, "\n" );
            while( tok.hasMoreTokens() ) {
                final String uid = tok.nextToken().trim();
                if( !uid.isEmpty() ) {
                    members.add( uid );
                }
            }
        }
        return members.toArray( new String[0] );
    }

    // events processing .......................................................

    /** {@inheritDoc} */
    @Override
    public synchronized void addWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.addWikiEventListener( this, listener );
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void removeWikiEventListener( final WikiEventListener listener ) {
        WikiEventManager.removeWikiEventListener( this, listener );
    }

    /** {@inheritDoc} */
    @Override
    public void actionPerformed( final WikiEvent event ) {
        if( !( event instanceof WikiSecurityEvent se ) ) {
            return;
        }

        if( se.getType() == WikiSecurityEvent.PROFILE_NAME_CHANGED ) {
            final Session session = se.getSrc();
            final UserProfile[] profiles = ( UserProfile[] )se.getTarget();
            final Principal[] oldPrincipals = new Principal[] { new WikiPrincipal( profiles[ 0 ].getLoginName() ),
                    new WikiPrincipal( profiles[ 0 ].getFullname() ), new WikiPrincipal( profiles[ 0 ].getWikiName() ) };
            final Principal newPrincipal = new WikiPrincipal( profiles[ 1 ].getFullname() );

            // Examine each group
            int groupsChanged = 0;
            try {
                for( final Group group : groupDatabase.groups() ) {
                    boolean groupChanged = false;
                    for( final Principal oldPrincipal : oldPrincipals ) {
                        if( group.isMember( oldPrincipal ) ) {
                            group.remove( oldPrincipal );
                            group.add( newPrincipal );
                            groupChanged = true;
                        }
                    }
                    if( groupChanged ) {
                        setGroup( session, group );
                        groupsChanged++;
                    }
                }
            } catch( final WikiException e ) {
                // Oooo! This is really bad...
                LOG.error( "Could not change user name in Group lists because of GroupDatabase error:" + e.getMessage() );
            }
            LOG.info( "Profile name change for '" + newPrincipal + "' caused " + groupsChanged + " groups to change also." );
        }
    }

}
