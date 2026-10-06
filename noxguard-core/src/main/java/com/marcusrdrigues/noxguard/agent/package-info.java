/**
 * Guards for agents with tools: {@link com.marcusrdrigues.noxguard.agent.ToolPolicy} decides whether each
 * tool call may run (deny by default), {@link com.marcusrdrigues.noxguard.agent.ProposalGate} holds proposed
 * actions, {@link com.marcusrdrigues.noxguard.agent.ToolBudget} caps the calls and
 * {@link com.marcusrdrigues.noxguard.agent.StrictSchema} prepares schemas for strict tool calling.
 */
package com.marcusrdrigues.noxguard.agent;
