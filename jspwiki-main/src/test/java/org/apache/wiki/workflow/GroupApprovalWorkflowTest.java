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
package org.apache.wiki.workflow;

import org.apache.wiki.TestEngine;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Session;
import org.apache.wiki.api.exceptions.WikiException;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.auth.NoSuchPrincipalException;
import org.apache.wiki.auth.SecurityEventTrap;
import org.apache.wiki.auth.Users;
import org.apache.wiki.auth.WikiPrincipal;
import org.apache.wiki.auth.WikiSecurityException;
import org.apache.wiki.auth.authorize.Group;
import org.apache.wiki.auth.authorize.GroupManager;
import org.apache.wiki.event.WikiSecurityEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.apache.wiki.TestEngine.with;


/**
 * Tests for group management workflow functionality.
 *
 * Phase 1: Tests current GroupManager behavior (no workflow support currently exists)
 * Phase 2+: Tests workflow-enabled group management once implemented
 */
public class GroupApprovalWorkflowTest {

    private TestEngine engine;
    private GroupManager groupManager;
    private WorkflowManager workflowManager;
    private DecisionQueue decisionQueue;
    private Session adminSession;
    private final SecurityEventTrap eventTrap = new SecurityEventTrap();

    @BeforeEach
    void setUp() throws WikiException {
        // Build engine without group workflow approval configured (tests current behavior)
        engine = TestEngine.build();
        groupManager = engine.getManager( GroupManager.class );
        workflowManager = engine.getManager( WorkflowManager.class );
        decisionQueue = workflowManager.getDecisionQueue();
        adminSession = engine.adminSession();

        // Clean up any leftover test groups
        cleanupTestGroups();

        // Register event listener
        groupManager.addWikiEventListener( eventTrap );
        eventTrap.clearEvents();
    }

    @AfterEach
    void tearDown() {
        cleanupTestGroups();
    }

    private void cleanupTestGroups() {
        final String[] testGroups = {
            "TestWorkflowGroup", "TestEventGroup", "TestRemoveGroup",
            "TestModifyGroup", "TestApprovalGroup"
        };
        for ( final String groupName : testGroups ) {
            try {
                groupManager.removeGroup( groupName );
            } catch ( final WikiSecurityException e ) {
                // Ignore - group doesn't exist or other security issue during cleanup
            }
        }
    }

    // =========================================================================
    // Phase 1: Test current GroupManager behavior (no workflow support)
    // =========================================================================

    /**
     * Test 1.1: Verify that group creation works without workflow when no approval is configured.
     * This documents the current behavior where groups are saved immediately.
     */
    @Test
    public void testSetGroupWithoutApprovalConfig() throws WikiException {
        // Given: No workflow.saveWikiGroup approver configured (default engine)

        // When: Create and save a new group (members separated by newlines)
        final Group group = groupManager.parseGroup( "TestWorkflowGroup", "user1\nuser2", true );
        groupManager.setGroup( adminSession, group );

        // Then: Group is saved immediately (no workflow)
        final Group saved = groupManager.getGroup( "TestWorkflowGroup" );
        Assertions.assertNotNull( saved, "Group should be saved immediately without workflow" );
        Assertions.assertEquals( 2, saved.members().length, "Group should have 2 members" );
    }

    /**
     * Test 1.2: Verify that GROUP_ADD event fires when a group is created.
     */
    @Test
    public void testSetGroupFiresAddEvent() throws WikiException {
        // Given: Event listener is already registered in setUp()
        eventTrap.clearEvents();

        // When: Create and save a group
        final Group group = groupManager.parseGroup( "TestEventGroup", "user1", true );
        groupManager.setGroup( adminSession, group );

        // Then: GROUP_ADD event was fired
        final WikiSecurityEvent[] events = eventTrap.events();
        Assertions.assertEquals( 1, events.length, "Should have fired exactly 1 event" );
        Assertions.assertEquals( WikiSecurityEvent.GROUP_ADD, events[0].getType(),
            "Event should be GROUP_ADD" );
        Assertions.assertEquals( group.getName(), ((Group)events[0].getTarget()).getName(),
            "Event target should be the created group" );
    }

    /**
     * Test 1.3: Verify that GROUP_REMOVE event fires when a group is deleted.
     */
    @Test
    public void testRemoveGroupFiresRemoveEvent() throws WikiException {
        // Given: An existing group
        final Group group = groupManager.parseGroup( "TestRemoveGroup", "user1", true );
        groupManager.setGroup( adminSession, group );
        eventTrap.clearEvents();

        // When: Remove the group
        groupManager.removeGroup( "TestRemoveGroup" );

        // Then: GROUP_REMOVE event was fired
        final WikiSecurityEvent[] events = eventTrap.events();
        Assertions.assertEquals( 1, events.length, "Should have fired exactly 1 event" );
        Assertions.assertEquals( WikiSecurityEvent.GROUP_REMOVE, events[0].getType(),
            "Event should be GROUP_REMOVE" );
    }

    /**
     * Test 1.4: Verify that modifying an existing group works without workflow.
     */
    @Test
    public void testModifyGroupWithoutApprovalConfig() throws WikiException {
        // Given: An existing group with one member
        Group group = groupManager.parseGroup( "TestModifyGroup", "user1", true );
        groupManager.setGroup( adminSession, group );

        // When: Modify the group to add more members (members separated by newlines)
        group = groupManager.parseGroup( "TestModifyGroup", "user1\nuser2\nuser3", false );
        groupManager.setGroup( adminSession, group );

        // Then: Group is updated immediately
        final Group saved = groupManager.getGroup( "TestModifyGroup" );
        Assertions.assertEquals( 3, saved.members().length, "Group should now have 3 members" );
    }

    /**
     * Test 1.5: Verify that no workflows are created when group approval is not configured.
     */
    @Test
    public void testNoWorkflowCreatedWithoutConfig() throws WikiException {
        // Given: No workflow approval configured, empty workflow list
        final int initialWorkflowCount = workflowManager.getCompletedWorkflows().size();

        // When: Create a group
        final Group group = groupManager.parseGroup( "TestWorkflowGroup", "user1", true );
        groupManager.setGroup( adminSession, group );

        // Then: No new workflows were created
        Assertions.assertEquals( initialWorkflowCount, workflowManager.getCompletedWorkflows().size(),
            "No workflows should be created when approval is not configured" );

        // And: Decision queue should be empty for admin
        Assertions.assertEquals( 0, decisionQueue.getActorDecisions( adminSession ).size(),
            "No decisions should be pending" );
    }

    // =========================================================================
    // Phase 2: Verify workflow constants exist
    // =========================================================================

    /**
     * Test 2.1: Verify that group workflow constants are defined in WorkflowManager.
     */
    @Test
    public void testGroupWorkflowConstantsExist() {
        // Verify approver key
        Assertions.assertNotNull( WorkflowManager.WF_GRP_SAVE_APPROVER );
        Assertions.assertEquals( "workflow.saveWikiGroup", WorkflowManager.WF_GRP_SAVE_APPROVER );

        // Verify message keys
        Assertions.assertNotNull( WorkflowManager.WF_GRP_SAVE_DECISION_MESSAGE_KEY );
        Assertions.assertEquals( "decision.saveWikiGroup", WorkflowManager.WF_GRP_SAVE_DECISION_MESSAGE_KEY );

        Assertions.assertNotNull( WorkflowManager.WF_GRP_SAVE_REJECT_MESSAGE_KEY );
        Assertions.assertEquals( "notification.saveWikiGroup.reject", WorkflowManager.WF_GRP_SAVE_REJECT_MESSAGE_KEY );

        // Verify fact constants
        Assertions.assertNotNull( WorkflowManager.WF_GRP_SAVE_FACT_GROUP_NAME );
        Assertions.assertNotNull( WorkflowManager.WF_GRP_SAVE_FACT_PROPOSED_MEMBERS );
        Assertions.assertNotNull( WorkflowManager.WF_GRP_SAVE_FACT_CURRENT_MEMBERS );
        Assertions.assertNotNull( WorkflowManager.WF_GRP_SAVE_FACT_SUBMITTER );
        Assertions.assertNotNull( WorkflowManager.WF_GRP_SAVE_FACT_IS_NEW );

        // Verify attribute constant
        Assertions.assertNotNull( WorkflowManager.WF_GRP_SAVE_ATTR_SAVED_GROUP );
    }

    // =========================================================================
    // Phase 3: Test workflow requirement detection
    // =========================================================================

    /**
     * Test 3.1: No approval required when property is not set.
     */
    @Test
    public void testRequiresApprovalForGroup_NotConfigured() {
        // Given: No jspwiki.approver.workflow.saveWikiGroup property set (default engine)

        // When/Then: No approval required
        Assertions.assertFalse( workflowManager.requiresApproval( WorkflowManager.WF_GRP_SAVE_APPROVER ),
            "Should not require approval when property is not configured" );
    }

    /**
     * Test 3.2: Approval required when property is set.
     */
    @Test
    public void testRequiresApprovalForGroup_Configured() throws WikiException {
        // Given: Engine with jspwiki.approver.workflow.saveWikiGroup=Admin configured
        final TestEngine configuredEngine = TestEngine.build(
            with( "jspwiki.approver.workflow.saveWikiGroup", "Admin" )
        );
        final WorkflowManager configuredWm = configuredEngine.getManager( WorkflowManager.class );

        // When/Then: Approval is required
        Assertions.assertTrue( configuredWm.requiresApproval( WorkflowManager.WF_GRP_SAVE_APPROVER ),
            "Should require approval when property is configured" );
    }

    /**
     * Test 3.3: Correct approver principal is resolved.
     */
    @Test
    public void testGetApproverForGroup() throws WikiException {
        // Given: Engine with approver configured
        final TestEngine configuredEngine = TestEngine.build(
            with( "jspwiki.approver.workflow.saveWikiGroup", "Admin" )
        );
        final WorkflowManager configuredWm = configuredEngine.getManager( WorkflowManager.class );

        // When: Get the approver
        final java.security.Principal approver = configuredWm.getApprover( WorkflowManager.WF_GRP_SAVE_APPROVER );

        // Then: Correct principal returned
        Assertions.assertNotNull( approver, "Approver should not be null" );
        Assertions.assertEquals( "Admin", approver.getName(), "Approver should be Admin" );
    }

    // =========================================================================
    // Phase 4: Test group workflow building
    // =========================================================================

    /**
     * Test 4.1: Build a group approval workflow and verify its structure.
     */
    @Test
    public void testBuildGroupApprovalWorkflow() throws WikiException {
        // Given: Engine with group workflow approval configured
        final TestEngine configuredEngine = TestEngine.build(
            with( "jspwiki.approver.workflow.saveWikiGroup", Users.JANNE )
        );
        final WorkflowBuilder builder = WorkflowBuilder.getBuilder( configuredEngine );
        final Principal submitter = new WikiPrincipal( "GroupCreator" );

        // Create a test prep task
        final Task prepTask = new ApprovalWorkflowTest.TestPrepTask( "task.preSaveWikiGroup" );
        final Task completionTask = new ApprovalWorkflowTest.TestPrepTask( "task.saveWikiGroup" );

        // Build facts for a new group
        final Fact[] facts = new Fact[] {
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_GROUP_NAME, "NewTestGroup" ),
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_PROPOSED_MEMBERS, "user1\nuser2" ),
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_CURRENT_MEMBERS, "" ),
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_SUBMITTER, submitter.getName() ),
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_IS_NEW, true )
        };

        // When: Build the workflow
        final Workflow workflow = builder.buildApprovalWorkflow(
            submitter,
            WorkflowManager.WF_GRP_SAVE_APPROVER,
            prepTask,
            WorkflowManager.WF_GRP_SAVE_DECISION_MESSAGE_KEY,
            facts,
            completionTask,
            WorkflowManager.WF_GRP_SAVE_REJECT_MESSAGE_KEY
        );

        // Then: Workflow is properly constructed
        Assertions.assertNotNull( workflow, "Workflow should not be null" );
        Assertions.assertFalse( workflow.isStarted(), "Workflow should not be started yet" );
        Assertions.assertEquals( WorkflowManager.WF_GRP_SAVE_APPROVER, workflow.getMessageKey() );
        Assertions.assertEquals( submitter, workflow.getOwner() );

        // Start the workflow
        workflow.start( null );

        // Then: Prep task completed and we're at the decision step
        final Step currentStep = workflow.getCurrentStep();
        Assertions.assertInstanceOf( Decision.class, currentStep, "Current step should be a Decision" );
        Assertions.assertEquals( WorkflowManager.WF_GRP_SAVE_DECISION_MESSAGE_KEY, currentStep.getMessageKey() );

        // Verify the facts are attached to the decision
        final Decision decision = (Decision) currentStep;
        final List<Fact> decisionFacts = decision.getFacts();
        Assertions.assertEquals( 5, decisionFacts.size(), "Should have 5 facts" );

        // Verify approver
        Assertions.assertEquals( new WikiPrincipal( Users.JANNE ), decision.getActor() );
    }

    /**
     * Test 4.2: Approve a group workflow and verify completion.
     */
    @Test
    public void testGroupWorkflowApproval() throws WikiException {
        // Given: A started group workflow
        final TestEngine configuredEngine = TestEngine.build(
            with( "jspwiki.approver.workflow.saveWikiGroup", Users.JANNE )
        );
        final WorkflowBuilder builder = WorkflowBuilder.getBuilder( configuredEngine );
        final Principal submitter = new WikiPrincipal( "GroupCreator" );

        final Task completionTask = new ApprovalWorkflowTest.TestPrepTask( "task.saveWikiGroup" );
        final Fact[] facts = new Fact[] {
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_GROUP_NAME, "ApprovalTestGroup" )
        };

        final Workflow workflow = builder.buildApprovalWorkflow(
            submitter,
            WorkflowManager.WF_GRP_SAVE_APPROVER,
            null,  // no prep task
            WorkflowManager.WF_GRP_SAVE_DECISION_MESSAGE_KEY,
            facts,
            completionTask,
            WorkflowManager.WF_GRP_SAVE_REJECT_MESSAGE_KEY
        );

        workflow.start( null );
        final Decision decision = (Decision) workflow.getCurrentStep();

        // When: Approve the decision
        decision.decide( Outcome.DECISION_APPROVE, null );

        // Then: Workflow is completed
        Assertions.assertTrue( workflow.isCompleted(), "Workflow should be completed" );
        Assertions.assertNull( workflow.getCurrentStep(), "No current step after completion" );
        Assertions.assertEquals( 2, workflow.getHistory().size(), "History should have 2 steps" );
    }

    /**
     * Test 4.3: Deny a group workflow and verify rejection notification.
     */
    @Test
    public void testGroupWorkflowDenial() throws WikiException {
        // Given: A started group workflow
        final TestEngine configuredEngine = TestEngine.build(
            with( "jspwiki.approver.workflow.saveWikiGroup", Users.JANNE )
        );
        final WorkflowBuilder builder = WorkflowBuilder.getBuilder( configuredEngine );
        final Principal submitter = new WikiPrincipal( "GroupCreator" );

        final Task completionTask = new ApprovalWorkflowTest.TestPrepTask( "task.saveWikiGroup" );
        final Fact[] facts = new Fact[] {
            new Fact( WorkflowManager.WF_GRP_SAVE_FACT_GROUP_NAME, "DenialTestGroup" )
        };

        final Workflow workflow = builder.buildApprovalWorkflow(
            submitter,
            WorkflowManager.WF_GRP_SAVE_APPROVER,
            null,
            WorkflowManager.WF_GRP_SAVE_DECISION_MESSAGE_KEY,
            facts,
            completionTask,
            WorkflowManager.WF_GRP_SAVE_REJECT_MESSAGE_KEY
        );

        workflow.start( null );
        final Decision decision = (Decision) workflow.getCurrentStep();

        // When: Deny the decision
        decision.decide( Outcome.DECISION_DENY, null );

        // Then: Workflow is at the notification step
        Assertions.assertFalse( workflow.isCompleted(), "Workflow should not be completed yet" );
        final Step notification = workflow.getCurrentStep();
        Assertions.assertInstanceOf( SimpleNotification.class, notification );
        Assertions.assertEquals( WorkflowManager.WF_GRP_SAVE_REJECT_MESSAGE_KEY, notification.getMessageKey() );

        // Acknowledge the notification
        ((SimpleNotification) notification).acknowledge( null );

        // Then: Workflow is completed
        Assertions.assertTrue( workflow.isCompleted(), "Workflow should be completed after acknowledgment" );
    }

    // =========================================================================
    // Phase 5: End-to-end integration tests with GroupManager
    // =========================================================================

    /**
     * Test 5.1: Creating a group with approval configured throws DecisionRequiredException.
     */
    @Test
    public void testSetGroupWithApprovalConfigured_ThrowsDecisionRequired() throws WikiException {
        // Given: Engine with group workflow approval configured
        final TestEngine configuredEngine = TestEngine.build(
            with( "jspwiki.approver.workflow.saveWikiGroup", Users.JANNE )
        );
        final GroupManager configuredGm = configuredEngine.getManager( GroupManager.class );
        final Session session = configuredEngine.adminSession();

        // When: Try to create a group
        final Group group = configuredGm.parseGroup( "TestApprovalGroup", "user1\nuser2", true );

        // Then: DecisionRequiredException is thrown
        Assertions.assertThrows( DecisionRequiredException.class, () -> {
            configuredGm.setGroup( session, group );
        }, "Should throw DecisionRequiredException when approval is configured" );

        // And: Group should not exist yet (not saved)
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> {
            configuredGm.getGroup( "TestApprovalGroup" );
        }, "Group should not exist before approval" );

        // Cleanup
        configuredEngine.shutdown();
    }

    /**
     * Test 5.2: Approving a group creation workflow saves the group.
     */
    @Test
    public void testSetGroupWithApproval_ApproveCreatesGroup() throws WikiException {
        // Given: Engine with group workflow approval configured
        final TestEngine configuredEngine = TestEngine.build(
            with( "jspwiki.approver.workflow.saveWikiGroup", Users.JANNE )
        );
        final GroupManager configuredGm = configuredEngine.getManager( GroupManager.class );
        final WorkflowManager configuredWm = configuredEngine.getManager( WorkflowManager.class );
        final Session session = configuredEngine.adminSession();
        final Session janneSession = configuredEngine.janneSession();

        try {
            // When: Try to create a group
            final Group group = configuredGm.parseGroup( "TestApprovalGroup", "user1\nuser2", true );
            try {
                configuredGm.setGroup( session, group );
                Assertions.fail( "Should have thrown DecisionRequiredException" );
            } catch ( final DecisionRequiredException e ) {
                // Expected
            }

            // Then: A decision should be pending for Janne
            final DecisionQueue dq = configuredWm.getDecisionQueue();
            final Collection<Decision> decisionsCol = dq.getActorDecisions( janneSession );
            final List<Decision> decisions = new ArrayList<>( decisionsCol );
            Assertions.assertEquals( 1, decisions.size(), "Should have 1 pending decision" );

            final Decision decision = decisions.get( 0 );
            Assertions.assertEquals( WorkflowManager.WF_GRP_SAVE_DECISION_MESSAGE_KEY, decision.getMessageKey() );

            // Verify facts
            final List<Fact> facts = decision.getFacts();
            Assertions.assertTrue( facts.stream().anyMatch( f ->
                f.getMessageKey().equals( WorkflowManager.WF_GRP_SAVE_FACT_GROUP_NAME ) &&
                f.getValue().equals( "TestApprovalGroup" )
            ), "Facts should contain group name" );

            // When: Janne approves (need a context for the task to execute)
            final Context context = Wiki.context().create( configuredEngine, Wiki.contents().page( configuredEngine, "Main" ) );
            decision.decide( Outcome.DECISION_APPROVE, context );

            // Then: Group should now exist
            final Group savedGroup = configuredGm.getGroup( "TestApprovalGroup" );
            Assertions.assertNotNull( savedGroup, "Group should exist after approval" );
            Assertions.assertEquals( 2, savedGroup.members().length, "Group should have 2 members" );

        } finally {
            // Cleanup
            try {
                configuredGm.removeGroup( "TestApprovalGroup" );
            } catch ( final Exception e ) {
                // Ignore cleanup errors
            }
            configuredEngine.shutdown();
        }
    }

    /**
     * Test 5.3: Denying a group creation workflow does not save the group.
     */
    @Test
    public void testSetGroupWithApproval_DenyDoesNotCreateGroup() throws WikiException {
        // Given: Engine with group workflow approval configured
        final TestEngine configuredEngine = TestEngine.build(
            with( "jspwiki.approver.workflow.saveWikiGroup", Users.JANNE )
        );
        final GroupManager configuredGm = configuredEngine.getManager( GroupManager.class );
        final WorkflowManager configuredWm = configuredEngine.getManager( WorkflowManager.class );
        final Session session = configuredEngine.adminSession();
        final Session janneSession = configuredEngine.janneSession();

        try {
            // When: Try to create a group
            final Group group = configuredGm.parseGroup( "TestApprovalGroup", "user1\nuser2", true );
            try {
                configuredGm.setGroup( session, group );
                Assertions.fail( "Should have thrown DecisionRequiredException" );
            } catch ( final DecisionRequiredException e ) {
                // Expected
            }

            // Then: Get and deny the pending decision
            final DecisionQueue dq = configuredWm.getDecisionQueue();
            final Collection<Decision> decisionsCol = dq.getActorDecisions( janneSession );
            final List<Decision> decisions = new ArrayList<>( decisionsCol );
            final Decision decision = decisions.get( 0 );

            // When: Janne denies
            decision.decide( Outcome.DECISION_DENY, null );

            // Then: Group should still not exist
            Assertions.assertThrows( NoSuchPrincipalException.class, () -> {
                configuredGm.getGroup( "TestApprovalGroup" );
            }, "Group should not exist after denial" );

        } finally {
            configuredEngine.shutdown();
        }
    }

}
