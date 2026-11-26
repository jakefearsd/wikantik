package com.wikantik.agentreports;

/** One piece of context an agent consulted: where it came from, what it was, and whether it helped. */
public record ContextUse( String source, String ref, String helped ) { }
