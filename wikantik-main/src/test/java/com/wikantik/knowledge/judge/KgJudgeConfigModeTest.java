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
package com.wikantik.knowledge.judge;

import com.wikantik.api.config.GenAiMode;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code wikantik.genai.mode} acts as a ceiling on the KG judge service:
 * judge inference is chat inference, so it must be disabled whenever the mode
 * disallows chat inference, regardless of {@code wikantik.kg.judge.enabled}.
 */
class KgJudgeConfigModeTest {

    @Test
    void modeAbsent_behavesIdenticallyToToday() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.kg.judge.enabled", "true" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertTrue( cfg.enabled(), "no genai.mode set -> regression guard: unchanged behavior" );
    }

    @Test
    void embeddingsOnly_disablesJudgeEvenWhenFlagEnabled() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.kg.judge.enabled", "true" );
        p.setProperty( GenAiMode.PROP, "embeddings-only" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "embeddings-only must disable the judge even with judge.enabled=true" );
    }

    @Test
    void none_disablesJudge() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.kg.judge.enabled", "true" );
        p.setProperty( GenAiMode.PROP, "none" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "none must disable the judge" );
    }

    @Test
    void individualFlagOff_staysOffUnderFullMode() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.kg.judge.enabled", "false" );
        p.setProperty( GenAiMode.PROP, "full" );

        final KgJudgeConfig cfg = KgJudgeConfig.fromProperties( p );

        assertFalse( cfg.enabled(), "judge.enabled=false under mode=full must remain disabled" );
    }
}
