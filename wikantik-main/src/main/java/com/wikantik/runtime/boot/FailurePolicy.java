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
package com.wikantik.runtime.boot;

/**
 * What a failed {@link BootStep} does to the steps declared after it in a {@link BootSequence}.
 */
public enum FailurePolicy {

    /**
     * The failure abandons the rest of the sequence: no later step starts, and the step's exception propagates
     * out of {@link BootSequence#start} for the caller to log once. Use for a subsystem the later steps build on.
     */
    ABORT_REST,

    /**
     * The failure is reported through {@link BootStep#reportContinuedFailure} and the sequence goes on with the
     * next step. Use for an optional subsystem the wiki can run without.
     */
    CONTINUE
}
