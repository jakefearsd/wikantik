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
import com.wikantik.work.DeploymentDraft;
import com.wikantik.work.WorkDeploymentsService.DeploymentView;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.work.mcp.WorkMcpArgs.*;

/** Asks for a product version to be deployed, carrying the items it ships. */
public final class RequestDeploymentTool extends WorkMcpTool {

    public static final String TOOL_NAME = "request_deployment";
    private static final Set< String > ALLOWED = Set.of( "product", "version", "items", "environment" );

    private final WorkService svc;
    private final WorkDirectory directory;

    public RequestDeploymentTool( final WorkService svc, final WorkDirectory directory ) {
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
        props.put( "product", prop( "string", "The product key.", "wikantik" ) );
        props.put( "version", prop( "string", "The version to deploy.", "2.5.26" ) );
        final Map< String, Object > items = new LinkedHashMap<>();
        items.put( "type", "array" );
        items.put( "items", Map.of( "type", "string" ) );
        items.put( "description", "The keys of the items this version ships. Each must target the product, and you "
            + "must be a member of each one's project." );
        items.put( "examples", List.of( List.of( "PLAT-12", "PLAT-15" ) ) );
        props.put( "items", items );
        props.put( "environment", prop( "string", "Where it goes; production when omitted.", "production" ) );
        return McpSchema.Tool.builder()
            .name( TOOL_NAME )
            .description( "Request a deployment of a product version carrying the items it ships. Returns the "
                + "deployment with its id; may_approve says whether you could approve it (the product decides: "
                + "where a person approves deployments, an agent can only request). Refusals: items: (none, one "
                + "that targets another product, or one not yet ready), denied:, archived: (an item's project is "
                + "read-only), Not found. (the product or an item you cannot see)." )
            .inputSchema( schema( props, List.of( "product", "version", "items" ) ) )
            .annotations( WRITES )
            .build();
    }

    @Override
    protected McpSchema.CallToolResult run( final Actor actor, final Map< String, Object > args ) throws SQLException {
        rejectOthers( args, ALLOWED, Map.of() );
        final DeploymentView v = svc.deployments().request( actor, new DeploymentDraft(
            requiredString( args, "product" ), stringArg( args, "environment" ), requiredString( args, "version" ),
            WorkMcpDeployments.itemKeys( args ) ) );
        WorkMcpLog.written( TOOL_NAME, "deployment-requested", "deployment:" + v.deployment().id(), actor );
        return ok( WorkMcpDeployments.deployment( svc, directory, actor, v ) );
    }
}
