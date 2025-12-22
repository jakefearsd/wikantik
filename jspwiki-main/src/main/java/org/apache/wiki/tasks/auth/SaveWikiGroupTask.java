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
package org.apache.wiki.tasks.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.exceptions.WikiException;
import org.apache.wiki.auth.authorize.Group;
import org.apache.wiki.auth.authorize.GroupManager;
import org.apache.wiki.tasks.TasksManager;
import org.apache.wiki.workflow.Outcome;
import org.apache.wiki.workflow.Task;
import org.apache.wiki.workflow.WorkflowManager;


/**
 * Handles the actual group save action after workflow approval.
 */
public class SaveWikiGroupTask extends Task {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( SaveWikiGroupTask.class );

    /**
     * Constructs a new Task for saving a wiki group.
     */
    public SaveWikiGroupTask() {
        super( TasksManager.GROUP_SAVE_TASK_MESSAGE_KEY );
    }

    /**
     * Saves the wiki group to the group database.
     *
     * @return {@link org.apache.wiki.workflow.Outcome#STEP_COMPLETE} if the task completed successfully
     * @throws WikiException if the save did not complete for some reason
     */
    @Override
    public Outcome execute( final Context context ) throws WikiException {
        // Retrieve the group name and members from workflow attributes
        // (Group is not Serializable, so we store its components separately)
        final String groupName = ( String ) getWorkflowContext().get( WorkflowManager.WF_GRP_SAVE_ATTR_SAVED_GROUP );
        final String memberLine = ( String ) getWorkflowContext().get( WorkflowManager.WF_GRP_SAVE_ATTR_SAVED_GROUP + ".members" );

        if ( groupName == null ) {
            throw new WikiException( "No group name found in workflow context" );
        }

        LOG.info( "Completing group save workflow for group: {}", groupName );

        // Reconstruct the group using GroupManager
        final GroupManager groupManager = context.getEngine().getManager( GroupManager.class );
        final Group group = groupManager.parseGroup( groupName, memberLine, true );

        // Delegate to GroupManager to perform the actual save
        // The GroupManager will handle cache updates and events
        groupManager.setGroupInternal( context.getWikiSession(), group );

        return Outcome.STEP_COMPLETE;
    }

}
