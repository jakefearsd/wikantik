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
package com.wikantik.work.mcp;

import com.wikantik.work.Actor;
import com.wikantik.work.WorkDeploymentsService.DeploymentView;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/** Moves a deployment along: approved, running, verified (with evidence), failed, rolled back. */
public final class UpdateDeploymentTool extends WorkMcpTool {

    public static final String TOOL_NAME = "update_deployment";
    private static final Set< String > ALLOWED = Set.of( "deployment", "state", "evidence", "note" );
    private static final String VERIFIED = "verified";

    private final WorkService svc;
    private final WorkDirectory directory;

    public UpdateDeploymentTool( final WorkService svc, final WorkDirectory directory ) {
        this.svc = svc;
        this.directory = directory;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    protected boolean writes() {
        return true;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "deployment", prop( "integer", "The deployment's id.", 4 ) );
        props.put( "state", prop( "string", "approved, rejected, running, verified, failed or rolled_back.", "running" ) );
        props.put( "evidence", prop( "string", "With verified: the health and smoke-test output. Required.",
            "GET /api/health 200; smoke-wiki.sh: SMOKE OK" ) );
        props.put( "note", prop( "string", "Why, for the deployment's history.", "Smoke test failed on search." ) );
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Move a deployment to its next state. It only moves forward: requested, approved, running, "
                + "then verified or failed; a verified or failed one can be rolled_back. One that is not running yet "
                + "can be rejected, by whoever requested it or could approve it. Approving is refused where "
                + "a person approves deployments of the product. Returns the deployment. Refusals: denied:, "
                + "bad_transition: (not the next state), evidence: (verified needs it)." )
            .inputSchema( schema( props, List.of( "deployment", "state" ) ) )
            .annotations( WRITES )
            .build();
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, ALLOWED, Map.of() );
        final long id = idArg( args, "deployment" );
        final String state = requiredString( args, "state" );
        final String evidence = stringArg( args, "evidence" );
        final String note = stringArg( args, "note" );
        requireFitting( state, evidence, note );
        final DeploymentView v = move( actor, id, state, evidence, note );
        WorkMcpLog.written( TOOL_NAME, "deployment-" + state.replace( '_', '-' ), "deployment:" + id, actor );
        return ok( WorkMcpDeployments.deployment( svc, directory, actor, v ) );
    }

    /**
     * Evidence goes only with verified, and the two moves that record no note take none: said by name, or the
     * agent would believe it attached something it did not.
     */
    private static void requireFitting( final String state, final String evidence, final String note ) {
        if ( evidence != null && !VERIFIED.equals( state ) ) {
            throw new WorkValidationException( "evidence", "Evidence goes with verified." );
        }
        if ( note != null && ( "running".equals( state ) || VERIFIED.equals( state ) ) ) {
            throw new WorkValidationException( "note", "A note goes with approved, rejected, failed or rolled_back." );
        }
    }

    private DeploymentView move( final Actor actor, final long id, final String state, final String evidence,
                                 final String note ) throws SQLException {
        return switch ( state ) {
            case "approved" -> svc.deployments().approve( actor, id, note );
            case "running" -> svc.deployments().start( actor, id );
            case VERIFIED -> svc.deployments().verify( actor, id, evidence );
            case "failed" -> svc.deployments().fail( actor, id, note );
            case "rolled_back" -> svc.deployments().rollBack( actor, id, note );
            case "rejected" -> svc.deployments().reject( actor, id, note );
            default -> throw new WorkValidationException( "state",
                "Use approved, rejected, running, verified, failed or rolled_back." );
        };
    }
}
