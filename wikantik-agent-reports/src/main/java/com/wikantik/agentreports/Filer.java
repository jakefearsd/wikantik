package com.wikantik.agentreports;

/** The server-filled identity of whoever files a report; never taken from the agent's arguments. */
public record Filer( String client, String sessionHash, String filedBy ) { }
