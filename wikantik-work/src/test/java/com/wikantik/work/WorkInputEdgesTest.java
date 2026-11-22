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
package com.wikantik.work;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edges of what a caller may send: missing values, blank values, values of the wrong type and values at the
 * limit. Each refusal names the field, so a client can show it next to the input that caused it.
 */
class WorkInputEdgesTest {

    private static String field( final Executable call ) {
        return assertThrows( WorkValidationException.class, call ).field();
    }

    // ---- names and keys

    @Test
    void aNameIsRequired() {
        assertEquals( "name", field( () -> WorkInputs.requireName( null ) ) );
        assertEquals( "name", field( () -> WorkInputs.requireName( "  " ) ) );
        WorkInputs.requireName( "Platform" );
    }

    @Test
    void keysAndAgentNamesHaveAShape() {
        assertEquals( "key", field( () -> WorkInputs.requireProjectKey( null ) ) );
        assertEquals( "key", field( () -> WorkInputs.requireProjectKey( "plat" ) ) );
        WorkInputs.requireProjectKey( "PLAT" );
        assertEquals( "key", field( () -> WorkInputs.requireQueueKey( null ) ) );
        assertEquals( "key", field( () -> WorkInputs.requireQueueKey( "Reviewer" ) ) );
        WorkInputs.requireQueueKey( "reviewer" );
        assertEquals( "name", field( () -> WorkInputs.requireAgentName( null ) ) );
        assertEquals( "name", field( () -> WorkInputs.requireAgentName( "Codex Bot" ) ) );
        WorkInputs.requireAgentName( "codex@docker2" );
    }

    @Test
    void aKeyThatIsAlsoARouteIsReserved() {
        for ( final String reserved : List.of( "ITEM", "products", "Agents", "LOG", "DEPLOYMENTS", "Deployments", "INBOX", "find" ) ) {
            assertEquals( "key", field( () -> WorkInputs.requireUnreservedKey( reserved ) ), reserved );
        }
        WorkInputs.requireUnreservedKey( null );
        WorkInputs.requireUnreservedKey( "PLAT" );
    }

    // ---- members

    @Test
    void aMemberNeedsANameAKindAndARole() {
        assertEquals( "role", field( () -> WorkInputs.requireMember( "lena", "user", null ) ) );
        assertEquals( "principal", field( () -> WorkInputs.requireMember( null, "user", WorkRole.MEMBER ) ) );
        assertEquals( "principal", field( () -> WorkInputs.requireMemberRef( " ", "user" ) ) );
        assertEquals( "principalKind", field( () -> WorkInputs.requireMemberRef( "lena", null ) ) );
        assertEquals( "principalKind", field( () -> WorkInputs.requireMemberRef( "lena", "robot" ) ) );
        for ( final String kind : List.of( "user", "group", "agent" ) ) {
            WorkInputs.requireMember( "x", kind, WorkRole.VIEWER );
        }
    }

    @Test
    void onlyMembersAreKeptAndAnEmptyLoginIsNeverAsked() throws SQLException {
        final List< String > asked = new ArrayList<>();
        assertEquals( List.of( "lena" ), WorkInputs.ofMembers( List.of( "", "lena", "bo" ), login -> {
            asked.add( login );
            return "lena".equals( login );
        } ) );
        assertEquals( List.of( "lena", "bo" ), asked );
    }

    // ---- products

    @Test
    void aNewProductGetsTheCautiousDefaults() {
        assertEquals( "key", field( () -> WorkInputs.newProduct( product( null, null, null ) ) ) );
        assertEquals( "key", field( () -> WorkInputs.newProduct( product( "Web App", null, null ) ) ) );
        final Product made = WorkInputs.newProduct( product( "web", null, null ) );
        assertEquals( Autonomy.DELEGATED, made.autonomyCeiling() );
        assertEquals( DeployGate.HUMAN, made.deployGate(), "a person approves deployments unless someone says otherwise" );
        final Product loose = WorkInputs.newProduct( product( "web", Autonomy.AUTONOMOUS, DeployGate.AGENT ) );
        assertEquals( Autonomy.AUTONOMOUS, loose.autonomyCeiling() );
        assertEquals( DeployGate.AGENT, loose.deployGate() );
    }

    @Test
    void anUpdateThatNamesNoPolicyKeepsTheOneItHas() {
        final Product existing = product( "web", Autonomy.SUPERVISED, DeployGate.AGENT );
        final Product kept = WorkInputs.updatedProduct( existing, product( "web", null, null ) );
        assertEquals( Autonomy.SUPERVISED, kept.autonomyCeiling() );
        assertEquals( DeployGate.AGENT, kept.deployGate() );
        final Product changed = WorkInputs.updatedProduct( existing, product( "web", Autonomy.DELEGATED, DeployGate.HUMAN ) );
        assertEquals( Autonomy.DELEGATED, changed.autonomyCeiling() );
        assertEquals( DeployGate.HUMAN, changed.deployGate() );
    }

    @Test
    void aRepositoryUrlCarriesNoUserNameOrPassword() {
        for ( final String url : List.of( "https://u:secret@host.example/x.git", "https://token@host.example/x.git",
                "https://TOKEN@github.com/x", "ssh://git:pw@host.example/x", "git+ssh://git:pw@host.example/x",
                " https://u@host.example/x " ) ) {
            final Product withUrl = new Product( 7, "web", "Web", url, "main", null, null, null, null );
            final WorkValidationException created = assertThrows( WorkValidationException.class, () -> WorkInputs.newProduct( withUrl ) );
            assertEquals( "repoUrl", created.field() );
            assertEquals( "Leave the user name and password out of the repository URL.", created.getMessage() );
            assertEquals( "repoUrl", field( () -> WorkInputs.updatedProduct( product( "web", null, null ), withUrl ) ) );
        }
        for ( final String ok : java.util.Arrays.asList( null, "", "https://host.example/a@b.git", "https://host.example:8443/x?u=a@b",
                "git@host.example:org/x.git", "ssh://git@host.example/org/x.git", "ssh://git@host.example:22/x.git",
                "git+ssh://git@host.example/x.git", "ssh+git://git@host.example/x.git", "SSH://git@host.example/x.git" ) ) {
            final Product withUrl = new Product( 7, "web", "Web", ok, "main", null, null, null, null );
            assertEquals( ok, WorkInputs.newProduct( withUrl ).repoUrl() );
        }
    }

    private static Product product( final String key, final Autonomy ceiling, final DeployGate gate ) {
        return new Product( 7, key, "Web", null, "main", null, ceiling, gate, null );
    }

    // ---- comments, links, filters, notes

    @Test
    void aCommentHasText() {
        assertEquals( "body", field( () -> WorkInputs.requireBody( null ) ) );
        assertEquals( "body", field( () -> WorkInputs.requireBody( " \n " ) ) );
        WorkInputs.requireBody( "Looks right." );
    }

    @Test
    void aLinkHasAKnownKindAndATarget() {
        assertEquals( "kind", field( () -> WorkInputs.linkTarget( null, "Page" ) ) );
        assertEquals( "kind", field( () -> WorkInputs.linkTarget( "mailto", "a@b" ) ) );
        assertEquals( "target", field( () -> WorkInputs.linkTarget( "page", null ) ) );
        assertEquals( "target", field( () -> WorkInputs.linkTarget( "page", "  " ) ) );
        assertEquals( "target", field( () -> WorkInputs.linkTarget( "page", "x".repeat( 2_001 ) ) ) );
        assertEquals( "target", field( () -> WorkInputs.linkTarget( "url", "ftp://example.com/x" ) ) );
        assertEquals( "target", field( () -> WorkInputs.linkTarget( "url", "javascript:alert(1)" ) ) );
        assertEquals( "https://example.com/x", WorkInputs.linkTarget( "url", "https://example.com/x" ) );
        assertEquals( "HTTP://example.com/x", WorkInputs.linkTarget( "url", "HTTP://example.com/x" ) );
        assertEquals( "MainPage", WorkInputs.linkTarget( "page", "MainPage" ) );
    }

    @Test
    void anEventTypeFilterTakesKnownTypesOnly() {
        WorkInputs.requireEventTypes( null, Set.of( "status" ) );
        WorkInputs.requireEventTypes( Set.of(), Set.of( "status" ) );
        WorkInputs.requireEventTypes( Set.of( "status" ), Set.of( "status", "created" ) );
        assertEquals( "types", field( () -> WorkInputs.requireEventTypes( Set.of( "bogus" ), Set.of( "status" ) ) ) );
    }

    @Test
    void aReleaseNoteCannotPassForAConsequence() {
        WorkInputs.requireReleaseNote( null );
        WorkInputs.requireReleaseNote( "blocked on the vendor" );
        for ( final String reserved : List.of( "moved", " Reassigned ", "ACCESS REMOVED", "agent retired",
            "project archived", "sponsor access removed" ) ) {
            assertEquals( "note", field( () -> WorkInputs.requireReleaseNote( reserved ) ), reserved );
        }
    }

    @Test
    void anActivityPageIsBounded() {
        assertTrue( WorkInputs.activityLimit( 0 ) > 0, "none asked: the default" );
        assertEquals( WorkInputs.activityLimit( 0 ), WorkInputs.activityLimit( -5 ) );
        assertEquals( 3, WorkInputs.activityLimit( 3 ) );
        assertTrue( WorkInputs.activityLimit( 1_000_000 ) < 1_000_000, "never more than the cap" );
    }

    // ---- item patches

    @Test
    void patchTextIsTextOrAbsent() {
        assertNull( PatchValues.blankToNull( null ) );
        assertNull( PatchValues.blankToNull( "  " ) );
        assertEquals( "x", PatchValues.blankToNull( " x " ) );
        assertNull( PatchValues.string( "title", null ) );
        assertEquals( "title", field( () -> PatchValues.string( "title", 7 ) ) );
        assertEquals( "title", field( () -> PatchValues.requiredString( "title", null ) ) );
        assertEquals( "T", PatchValues.requiredString( "title", "T" ) );
        assertEquals( "", PatchValues.bodyOf( null ), "a cleared body is empty, never null" );
        assertEquals( "b", PatchValues.bodyOf( "b" ) );
        assertNull( PatchValues.assignee( " " ) );
        assertEquals( "lena", PatchValues.assignee( " lena " ) );
    }

    @Test
    void patchNumbersAreFiniteAndPriorityIsWhole() {
        assertEquals( 1.5, PatchValues.number( "rank", 1.5 ) );
        assertEquals( "rank", field( () -> PatchValues.number( "rank", "1.5" ) ) );
        assertEquals( "rank", field( () -> PatchValues.number( "rank", null ) ) );
        assertEquals( "rank", field( () -> PatchValues.number( "rank", Double.NaN ) ) );
        assertEquals( "rank", field( () -> PatchValues.number( "rank", Double.POSITIVE_INFINITY ) ) );
        assertEquals( 2, PatchValues.priority( 2 ) );
        assertEquals( 2, PatchValues.priority( 2.0 ) );
        assertEquals( "priority", field( () -> PatchValues.priority( 2.5 ) ) );
        assertEquals( 0, PatchValues.priority( 1e12 ), "out of range falls back to none" );
        assertEquals( 0, PatchValues.priority( -1e12 ) );
    }

    @Test
    void patchEnumsAndFlagsAreChecked() {
        assertEquals( WorkStatus.READY, PatchValues.status( "ready" ) );
        assertEquals( WorkKind.DOCS, PatchValues.kind( "docs" ) );
        assertEquals( "kind", field( () -> PatchValues.kind( null ) ) );
        assertNull( PatchValues.autonomy( null ), "null clears the item's own level" );
        assertEquals( Autonomy.SUPERVISED, PatchValues.autonomy( "supervised" ) );
        assertNull( PatchValues.doneMeans( null ) );
        assertEquals( DoneMeans.IN_PRODUCTION, PatchValues.doneMeans( "in_production" ) );
        assertNull( PatchValues.allowDirect( null ) );
        assertEquals( Boolean.TRUE, PatchValues.allowDirect( true ) );
        assertEquals( "allowDirect", field( () -> PatchValues.allowDirect( "yes" ) ) );
    }

    @Test
    void aDueDateIsAnIsoDateOrCleared() {
        assertNull( PatchValues.dueOn( null ) );
        assertNull( PatchValues.dueOn( " " ) );
        assertEquals( LocalDate.of( 2026, 11, 15 ), PatchValues.dueOn( " 2026-11-15 " ) );
        assertEquals( "dueOn", field( () -> PatchValues.dueOn( "15/11/2026" ) ) );
        assertEquals( "dueOn", field( () -> PatchValues.dueOn( 20261115 ) ) );
    }

    // ---- changes

    @Test
    void aBranchACommitALinkAndANoteHaveLimits() {
        assertEquals( "branch", field( () -> ChangeInputs.branch( "branch", null ) ) );
        assertEquals( "branch", field( () -> ChangeInputs.branch( "branch", "has space" ) ) );
        assertEquals( "branch", field( () -> ChangeInputs.branch( "branch", "x".repeat( ChangeInputs.MAX_BRANCH + 1 ) ) ) );
        assertEquals( "work/PLAT-1-x", ChangeInputs.branch( "branch", "work/PLAT-1-x" ) );
        assertNull( ChangeInputs.sha( "headSha", null ) );
        assertNull( ChangeInputs.sha( "headSha", " " ) );
        assertEquals( "abc1234", ChangeInputs.sha( "headSha", " ABC1234 " ) );
        assertEquals( "headSha", field( () -> ChangeInputs.sha( "headSha", "xyz" ) ) );
        assertEquals( "mergedSha", field( () -> ChangeInputs.requiredSha( "mergedSha", null ) ) );
        assertEquals( "abc1234", ChangeInputs.requiredSha( "mergedSha", "abc1234" ) );
        assertNull( ChangeInputs.url( null ) );
        assertNull( ChangeInputs.url( "  " ) );
        assertEquals( "https://example.com/pull/7", ChangeInputs.url( " https://example.com/pull/7 " ) );
        assertEquals( "HTTP://example.com/pull/7", ChangeInputs.url( "HTTP://example.com/pull/7" ) );
        for ( final String bad : List.of( "example.com/pull/7", "ftp://example.com/x", "javascript:alert(1)",
            "https:///nohost", "http://exa mple.com", "https://example.com/" + "x".repeat( ChangeInputs.MAX_URL ) ) ) {
            assertEquals( "reviewUrl", field( () -> ChangeInputs.url( bad ) ), bad );
        }
        assertNull( ChangeInputs.note( null ) );
        assertNull( ChangeInputs.note( " " ) );
        assertEquals( "why", ChangeInputs.note( "why" ) );
        assertEquals( "note", field( () -> ChangeInputs.note( "x".repeat( ChangeInputs.MAX_NOTE + 1 ) ) ) );
    }

    // ---- the registry's small helpers

    @Test
    void anAgentPatchKeepsWhatItDoesNotName() {
        final Agent existing = new Agent( 4, "codex", "acme", "cli", "lena", List.of( "implementer" ), Instant.now(), null );
        final AgentDraft kept = RegistryChanges.merged( new AgentDraft( "codex", null, null, List.of( "reviewer" ) ), existing );
        assertEquals( "acme", kept.vendor() );
        assertEquals( "cli", kept.runtime() );
        final AgentDraft changed = RegistryChanges.merged( new AgentDraft( "codex", "other", "sdk", List.of() ), existing );
        assertEquals( "other", changed.vendor() );
        assertEquals( "sdk", changed.runtime() );
        assertEquals( "", RegistryChanges.orEmpty( null ) );
        assertEquals( "x", RegistryChanges.orEmpty( " x " ) );
    }

    @Test
    void onlyAUniqueKeyViolationIsADuplicate() {
        final SQLException other = new SQLException( "connection reset", "08006" );
        assertSame( other, RegistryChanges.duplicateOrRethrow( other, "exists" ) );
        assertEquals( WorkConflictException.EXISTS, assertThrows( WorkConflictException.class,
            () -> RegistryChanges.duplicateOrRethrow( new SQLException( "dup", "23505" ), "A queue with that key exists." ) )
            .reason() );
    }

    // ---- wire values

    @Test
    void anUnknownWireValueNamesItsField() {
        assertEquals( "deployGate", field( () -> DeployGate.fromWire( "robot" ) ) );
        assertEquals( "deployGate", field( () -> DeployGate.fromWire( null ) ) );
        assertEquals( DeployGate.AGENT, DeployGate.fromWire( "agent" ) );
        assertEquals( "doneMeans", field( () -> DoneMeans.fromWire( "shipped" ) ) );
        assertEquals( DoneMeans.MERGED, DoneMeans.fromWire( "merged" ) );
        assertEquals( "autonomy", field( () -> Autonomy.fromWire( "free" ) ) );
        assertThrows( RuntimeException.class, () -> ChangeState.fromWire( "nope" ) );
        assertThrows( RuntimeException.class, () -> MergeKind.fromWire( "nope" ) );
        assertEquals( ChangeState.IN_REVIEW, ChangeState.fromWire( "in_review" ) );
        assertEquals( MergeKind.DIRECT, MergeKind.fromWire( "direct" ) );
        for ( final Autonomy a : Autonomy.values() ) {
            assertEquals( a, Autonomy.fromWire( a.wire() ) );
        }
        for ( final WorkRole r : WorkRole.values() ) {
            assertEquals( r, WorkRole.fromWire( r.wire() ) );
        }
    }

    // ---- small records

    @Test
    void anAcknowledgementIsOwedOnlyUntilItIsGiven() {
        assertFalse( Ack.NONE.waiting() );
        assertTrue( new Ack( true, null, null ).waiting() );
        assertFalse( new Ack( true, "lena", Instant.now() ).waiting() );
        assertFalse( new Ack( false, "lena", Instant.now() ).waiting() );
    }

    @Test
    void aDeploymentsItemListIsNeverNullAndNeverShared() {
        final List< Long > ids = new ArrayList<>( Arrays.asList( 1L, 2L ) );
        final Deployment d = deployment( ids );
        ids.add( 3L );
        assertEquals( List.of( 1L, 2L ), d.itemIds(), "a later change to the caller's list does not reach the record" );
        assertEquals( List.of(), deployment( null ).itemIds() );
    }

    private static Deployment deployment( final List< Long > ids ) {
        return new Deployment( 1, 2, "production", "2.6.0", DeploymentState.REQUESTED, "mia", null, null, null,
            Instant.now(), null, null, null, null, null, ids );
    }

    // ---- the directory's defaults

    @Test
    void theDefaultDirectoryComposesAPersonFromWhatItKnows() {
        final WorkDirectory known = directory( true, Set.of( "Dev" ) );
        final WorkDirectory.Person lena = known.person( "lena" ).orElseThrow();
        assertEquals( Set.of( "Dev" ), lena.groups() );
        assertFalse( lena.admin(), "the default knows no admins" );
        assertEquals( Set.of(), directory( true, null ).person( "lena" ).orElseThrow().groups() );
        assertEquals( Optional.empty(), directory( false, Set.of() ).person( "ghost" ) );
        assertEquals( Optional.empty(), known.loginForDisplayName( "Lena" ) );
        assertEquals( Optional.empty(), known.loginForMentionId( "abc" ) );
    }

    private static WorkDirectory directory( final boolean exists, final Set< String > groups ) {
        return new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return groups; }
            @Override public boolean exists( final String login ) { return exists; }
            @Override public String displayName( final String login ) { return login; }
        };
    }

    // ---- a listener that fails must not change an outcome

    @Test
    void aFailingListenerIsLoggedAndTheCallGoesOn() {
        final GuardedListener guarded = new GuardedListener( new WorkListener() {
            @Override public void denied( final Action a ) { throw new IllegalStateException( "metrics down" ); }
            @Override public void claim( final ClaimOutcome o ) { throw new IllegalStateException( "metrics down" ); }
            @Override public void deployment( final String state ) { throw new IllegalStateException( "metrics down" ); }
            @Override public void gate( final String gate, final boolean allowed ) { throw new IllegalStateException( "metrics down" ); }
        } );
        guarded.denied( Action.VIEW );
        guarded.claim( ClaimOutcome.CLAIMED );
        guarded.deployment( "verified" );
        guarded.gate( "merge", true );
        guarded.released( 2 );
        guarded.released( 0 );
    }

    // ---- deployments

    @Test
    void aDeploymentsVersionEnvironmentNoteAndEvidenceAreChecked() {
        assertEquals( "2.6.0+build.7", DeploymentInputs.version( " 2.6.0+build.7 " ) );
        assertEquals( "version", field( () -> DeploymentInputs.version( null ) ) );
        assertEquals( "version", field( () -> DeploymentInputs.version( "two point six" ) ) );
        assertEquals( "version", field( () -> DeploymentInputs.version( "1".repeat( 61 ) ) ) );
        assertEquals( "production", DeploymentInputs.environment( null ), "where a deployment goes unless told otherwise" );
        assertEquals( "production", DeploymentInputs.environment( "  " ) );
        assertEquals( "staging", DeploymentInputs.environment( " staging " ) );
        assertEquals( "environment", field( () -> DeploymentInputs.environment( "Staging EU" ) ) );
        assertNull( DeploymentInputs.note( null ) );
        assertNull( DeploymentInputs.note( " " ) );
        assertEquals( "why", DeploymentInputs.note( "why" ) );
        assertEquals( "note", field( () -> DeploymentInputs.note( "x".repeat( DeploymentInputs.MAX_NOTE + 1 ) ) ) );
        assertEquals( "health 200", DeploymentInputs.evidence( "health 200" ) );
        assertEquals( "evidence", field( () -> DeploymentInputs.evidence( null ) ) );
        assertEquals( "evidence", field( () -> DeploymentInputs.evidence( " \n " ) ) );
        assertEquals( "evidence", field( () -> DeploymentInputs.evidence( "x".repeat( DeploymentInputs.MAX_EVIDENCE + 1 ) ) ) );
    }
}
