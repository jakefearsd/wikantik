# Task 11 report: SimClient (F2)

Files: wikantik-main/src/test/java/com/wikantik/collab/testing/SimClient.java, SimClientTest.java.
Red: test compile failed (SimClient missing). Green: `SimClientTest` 10 tests, 0 failures.

## Mapping to @codemirror/collab 6.1.1
- collab() field update -> localEdit (empty edits dropped, appended to unconfirmed)
- receiveUpdates -> receive/apply (own-clientId confirmation from the front, changes.map(ours, true), compose of remote, unconfirmed rebased via u.map(changes) then changes.map(u, true))
- sendableUpdates -> sendable; getSyncedVersion -> version; getClientID -> clientId
- Effects not modelled.

## Decisions
- receive(updates, fromVersion): skip = version - fromVersion leading updates (E11, also partial overlap); fromVersion > version = gap, traced ("gap") and not applied.
- onPushResult only traces; confirmation comes solely via the pull carrying own updates (E9).
- resync: recoveryText = local doc (including unsynced edits) when unconfirmed non-empty; never re-applied; an empty later resync keeps earlier recovery text.
- trace(): List<String> of compact JSON; traceEntries() gives TraceEntry records (type, data) with toJson(). Types: local, receive, gap, push, resync, sendable (snapshot after each step). Update JSON uses keys clientID + changes (ChangeSetJson array).

## Concerns
- recoveryText holds the whole local doc rather than only the unsynced diff; Task 13/spec may want the diff.
- No SimServer/StubCollabAuthority changes needed.

## Fix round 1
- recoveryText now assigned on every resync (`unconfirmed.isEmpty() ? "" : doc`); test laterCleanResyncClearsStaleRecoveryText (red before: 14 run, failed on the new tests; the old keep-earlier test resyncWithNothingUnsyncedLeavesRecoveryEmpty still passes).
- Trace: `join` entry from the constructor; `resync` carries clientId, participantId, doc; TraceEntry Javadoc documents all types, push = informational, and the E11 skip/gap rule. Test traceHasJoinAndResyncCarriesNewIdentity; traceRecordsEventsAsJson index adjusted (join is entry 0).
- Added tiePositionRebaseOrdersForeignBeforeLocal (aBAb) and batchWithOwnUpdateAfterForeignOne.
- Command: `mvn -o test -pl wikantik-main -Dtest=SimClientTest -Dsurefire.failIfNoSpecifiedTests=false` -> 14 run, 0 failures (see commit).
