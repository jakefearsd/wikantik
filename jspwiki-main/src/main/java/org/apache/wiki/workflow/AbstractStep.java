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

import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.exceptions.WikiException;

import java.io.Serializable;
import java.security.Principal;
import java.util.*;

/**
 * Abstract superclass that provides a complete implementation of most Step methods; subclasses need only implement {@link #execute(Context)} and
 * {@link #getActor()}.
 *
 * @since 2.5
 */
public abstract class AbstractStep implements Step {

    private static final long serialVersionUID = 8635678679349653768L;

    /** Timestamp of when the step started. */
    private Date start;

    /** Timestamp of when the step ended. */
    private Date end;

    private final String key;

    private boolean completed;

    private final Map< Outcome, Step > successors;

    private int workflowId;

    /** attribute map. */
    private Map< String, Serializable > workflowContext;

    private Outcome outcome;

    private final List<String> errors;

    private boolean started;

    /**
     * Protected constructor that creates a new Step with a specified message key. After construction, the method
     * {@link #setWorkflow(int, Map)} should be called.
     *
     * @param messageKey the Step's message key, such as {@code decision.editPageApproval}. By convention, the message prefix should
     *                   be a lower-case version of the Step's type, plus a period (<em>e.g.</em>, {@code task.} and {@code decision.}).
     */
    protected AbstractStep( final String messageKey ) {
        started = false;
        start = Step.TIME_NOT_SET;
        completed = false;
        end = Step.TIME_NOT_SET;
        errors = new ArrayList<>();
        outcome = Outcome.STEP_CONTINUE;
        key = messageKey;
        successors = new LinkedHashMap<>();
    }

    /**
     * Constructs a new Step belonging to a specified Workflow and having a specified message key.
     *
     * @param workflowId the parent workflow id to set
     * @param workflowContext the parent workflow context to set
     * @param messageKey the Step's message key, such as {@code decision.editPageApproval}. By convention, the message prefix should
     *                   be a lower-case version of the Step's type, plus a period (<em>e.g.</em>, {@code task.} and {@code decision.}).
     */
    public AbstractStep( final int workflowId, final Map< String, Serializable > workflowContext, final String messageKey ) {
        this( messageKey );
        setWorkflow( workflowId, workflowContext );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void addSuccessor(final Outcome outcome, final Step step ) {
        successors.put( outcome, step );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final Collection< Outcome > getAvailableOutcomes() {
        final Set< Outcome > outcomes = successors.keySet();
        return Collections.unmodifiableCollection( outcomes );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final List< String > getErrors() {
        return Collections.unmodifiableList( errors );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract Outcome execute(Context ctx ) throws WikiException;

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract Principal getActor();

    /**
     * {@inheritDoc}
     */
    @Override
    public final Date getEndTime() {
        return end;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final String getMessageKey() {
        return key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final synchronized Outcome getOutcome() {
        return outcome;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final Date getStartTime() {
        return start;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isCompleted() {
        return completed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isStarted() {
        return started;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final synchronized void setOutcome(final Outcome outcome ) {
        // Is this an allowed Outcome?
        if( !successors.containsKey( outcome ) ) {
            if( !Outcome.STEP_CONTINUE.equals( outcome ) && !Outcome.STEP_ABORT.equals( outcome ) ) {
                throw new IllegalArgumentException( "Outcome " + outcome.getMessageKey() + " is not supported for this Step." );
            }
        }

        // Is this a "completion" outcome?
        if( outcome.isCompletion() ) {
            if( completed ) {
                throw new IllegalStateException( "Step has already been marked complete; cannot set again." );
            }
            completed = true;
            end = new Date( System.currentTimeMillis() );
        }
        this.outcome = outcome;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final synchronized void start() throws WikiException {
        if( started ) {
            throw new IllegalStateException( "Step already started." );
        }
        started = true;
        start = new Date( System.currentTimeMillis() );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final Step getSuccessor(final Outcome outcome ) {
        return successors.get( outcome );
    }

    // --------------------------Helper methods--------------------------

    /**
     * method that sets the parent Workflow id and context post-construction.
     *
     * @param workflowId the parent workflow id to set
     * @param workflowContext the parent workflow context to set
     */
    @Override
    public final synchronized void setWorkflow(final int workflowId, final Map< String, Serializable > workflowContext ) {
        this.workflowId = workflowId;
        this.workflowContext = workflowContext;
    }

    public int getWorkflowId() {
        return workflowId;
    }

    public Map< String, Serializable > getWorkflowContext() {
        return workflowContext;
    }

    /**
     * Protected helper method that adds a String representing an error message to the Step's cached errors list.
     *
     * @param message the error message
     */
    protected final synchronized void addError( final String message ) {
        errors.add( message );
    }

}
