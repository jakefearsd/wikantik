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
package com.wikantik.parser.markdown;

import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.util.ast.Node;
import com.wikantik.markdown.extensions.wikilinks.postprocessor.AccessRuleLinkNodePostProcessorState;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The rule-shaped link labels of {@link RenderPathAccessRules.Structure#labels}: links with no target whose text is a
 * brace, any whitespace, then {@code allow} in any case — the shapes the render path does not read as rules
 * (<code>[{allow view Bob}]</code>, <code>[{ ALLOW view Bob}]</code>) but the access-rule reader may, read from the
 * label as the parse sees it.
 */
final class RuleLabels {

    /** A brace, any whitespace, then {@code allow} in any case; possessive, so a long whitespace run costs one pass. */
    private static final Pattern ALLOW_SHAPED = Pattern.compile( "\\{\\s*+(?i:allow)" );

    private RuleLabels() {
    }

    /**
     * Adds {@code node}'s label to {@code into} when it is a rule-shaped label that maps to source text.
     *
     * @return whether {@code node} is a rule-shaped label (added or not)
     */
    static boolean collect( final List< RenderPathAccessRules.Rule > into, final MarkdownBodySource.Prepared prepared, final Node node,
                            final int bodyStart, final int bodyLength ) {
        if ( !( node instanceof Link link ) || !link.getUrl().isEmpty() || !ALLOW_SHAPED.matcher( link.getText() ).lookingAt() ) {
            return false;
        }
        final int start = prepared.toBody( link.getStartOffset() );
        final int end = prepared.toBody( link.getEndOffset() );
        if ( start >= 0 && end > start && end <= bodyLength ) {
            into.add( new RenderPathAccessRules.Rule( bodyStart + start, bodyStart + end,
                    AccessRuleLinkNodePostProcessorState.ruleLineOf( link.getText().toString() ) ) );
        }
        return true;
    }
}
