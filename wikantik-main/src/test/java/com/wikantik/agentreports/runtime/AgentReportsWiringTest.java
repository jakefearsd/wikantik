package com.wikantik.agentreports.runtime;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class AgentReportsWiringTest {

    @Test
    void noDataSourceMeansNoService() {
        assertNull( AgentReportsWiring.build( null, new Properties() ) );
    }

    @Test
    void aDataSourceBuildsTheService() {
        assertNotNull( AgentReportsWiring.build( mock( javax.sql.DataSource.class ), new Properties() ) );
    }

    @Test
    void limitDefaultsToSixtyAndIsConfigurable() {
        assertEquals( 60, AgentReportsWiring.maxPerHour( new Properties() ) );
        final Properties p = new Properties();
        p.setProperty( AgentReportsWiring.KEY_MAX_PER_HOUR, "5" );
        assertEquals( 5, AgentReportsWiring.maxPerHour( p ) );
    }
}
