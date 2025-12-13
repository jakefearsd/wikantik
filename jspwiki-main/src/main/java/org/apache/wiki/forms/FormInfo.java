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
package org.apache.wiki.forms;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Container for carrying HTTP FORM information between Plugin invocations in the Session.
 */
public class FormInfo implements Serializable {

    private static final long serialVersionUID = 0L;

    /**
     * State: Form is executed.
     */
    public static final int EXECUTED = 1;

    /**
     * State: Form is OK.
     */
    public static final int OK = 0;

    /**
     * State: There was an error.
     */
    public static final int ERROR = -1;

    private int status;
    private boolean hide;
    private String action;
    private String name;
    private String handler;
    private String result;
    private String error;
    private Map<String, String> submission;

    /**
     * Creates a new FormInfo with status == OK.
     */
    public FormInfo() {
        status = OK;
    }

    /**
     * Set the status of the Form processing.
     *
     * @param val EXECUTED, OK or ERROR.
     */
    public void setStatus( final int val) {
        status = val;
    }

    /**
     * Return the status.
     *
     * @return The status.
     */
    public int getStatus() {
        return status;
    }

    /**
     * Set the hide parameter.
     *
     * @param val True or false.
     */
    public void setHide( final boolean val) {
        hide = val;
    }

    /**
     * Returns true, if the form is supposed to be hidden.
     *
     * @return True or false.
     */
    public boolean hide() {
        return hide;
    }

    /**
     * Set the value of the action parameter.
     *
     * @param val A value parameter.
     */
    public void setAction( final String val) {
        action = val;
    }

    /**
     * Get the action set in {@link #setAction(String)}.
     *
     * @return An Action.
     */
    public String getAction() {
        return action;
    }

    /**
     * Sets the name of the form.
     *
     * @param val The name of the form.
     */
    public void setName( final String val) {
        name = val;
    }

    /**
     * Return the name of the form.
     *
     * @return The name of the form.
     */
    public String getName() {
        return name;
    }

    /**
     * Set the name of the handler class.
     *
     * @param val The name of the class.
     */
    public void setHandler( final String val) {
        handler = val;
    }

    /**
     * Return the name of the handler class.
     *
     * @return The name of the class.
     */
    public String getHandler() {
        return handler;
    }

    /**
     * Set the result.
     *
     * @param val The result.
     */
    public void setResult( final String val) {
        result = val;
    }

    /**
     * Return the result.
     *
     * @return The result.
     */
    public String getResult() {
        return result;
    }

    /**
     * Set an error string.
     *
     * @param val An error string.
     */
    public void setError( final String val) {
        error = val;
    }

    /**
     * Return the error.
     *
     * @return The error.
     */
    public String getError() {
        return error;
    }

    /**
     * Copies the given values into the handler parameter map using Map.putAll().
     *
     * @param val parameter name-value pairs for a Form handler WikiPlugin
     */
    public void setSubmission( final Map<String, String> val) {
        submission = new HashMap<>();
        submission.putAll(val);
    }

    /**
     * Adds the given values into the handler parameter map.
     *
     * @param val parameter name-value pairs for a Form handler WikiPlugin
     */
    public void addSubmission( final Map<String, String> val) {
        if (submission == null) {
            submission = new HashMap<>();
        }
        submission.putAll(val);
    }

    /**
     * Returns parameter name-value pairs for a Form handler WikiPlugin.
     * The names are those of Form input fields, and the values whatever
     * the user selected in the form. The FormSet plugin can also be used
     * to provide initial values.
     *
     * @return Handler parameter name-value pairs.
     */
    public Map<String, String> getSubmission() {
        return submission;
    }
}
