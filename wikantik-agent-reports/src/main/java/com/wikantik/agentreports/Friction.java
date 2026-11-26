package com.wikantik.agentreports;

/** One point of friction an agent hit: which product and tool, what went wrong, and whether it blocked the work. */
public record Friction( String product, String tool, String what, boolean blocking ) { }
