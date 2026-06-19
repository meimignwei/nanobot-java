package com.nanobot.agent.loop;

/**
 * States in the agent turn state machine.
 * Mirrors Python TurnState enum (loop.py lines 77-85).
 */
public enum TurnState {
    RESTORE,
    COMPACT,
    COMMAND,
    BUILD,
    RUN,
    SAVE,
    RESPOND,
    DONE
}
