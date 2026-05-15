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
package com.wikantik.llm.activity;

import java.time.Instant;

/**
 * One recorded LLM call. Created IN_FLIGHT by {@link LlmActivityLog#begin}; mutated
 * exactly once to OK/ERROR by {@code succeed}/{@code fail}. Mutable finalization
 * fields are volatile so a snapshot taken on another thread sees a consistent state.
 */
public final class LlmCall {

    private final long seq;
    private final Instant startedAt;
    private final long startedNanos;
    private final Subsystem subsystem;
    private final String backend;
    private final String model;
    private final String operation;
    private final String promptPreview;

    private volatile CallStatus status = CallStatus.IN_FLIGHT;
    private volatile long durationMs = -1L;
    private volatile String responsePreview;
    private volatile Integer inputTokens;
    private volatile Integer outputTokens;
    private volatile String errorMessage;

    public LlmCall( final long seq, final Instant startedAt, final long startedNanos,
                    final Subsystem subsystem, final String backend, final String model,
                    final String operation, final String promptPreview ) {
        this.seq = seq;
        this.startedAt = startedAt;
        this.startedNanos = startedNanos;
        this.subsystem = subsystem;
        this.backend = backend;
        this.model = model;
        this.operation = operation;
        this.promptPreview = promptPreview;
    }

    public void finishOk( final long durationMs, final String responsePreview ) {
        this.durationMs = durationMs;
        this.responsePreview = responsePreview;
        this.status = CallStatus.OK;
    }

    public void finishError( final long durationMs, final String errorMessage ) {
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
        this.status = CallStatus.ERROR;
    }

    public long seq()              { return seq; }
    public Instant startedAt()     { return startedAt; }
    public long startedNanos()     { return startedNanos; }
    public Subsystem subsystem()   { return subsystem; }
    public String backend()        { return backend; }
    public String model()          { return model; }
    public String operation()      { return operation; }
    public String promptPreview()  { return promptPreview; }
    public CallStatus status()     { return status; }
    public long durationMs()       { return durationMs; }
    public String responsePreview(){ return responsePreview; }
    public Integer inputTokens()   { return inputTokens; }
    public Integer outputTokens()  { return outputTokens; }
    public String errorMessage()   { return errorMessage; }

    public LlmCallView toView() {
        return new LlmCallView(
            seq,
            startedAt == null ? null : startedAt.toString(),
            subsystem.name(),
            backend,
            model,
            operation,
            status.name(),
            durationMs,
            promptPreview,
            responsePreview,
            inputTokens,
            outputTokens,
            errorMessage );
    }
}
