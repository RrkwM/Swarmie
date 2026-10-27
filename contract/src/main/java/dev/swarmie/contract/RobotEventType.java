package dev.swarmie.contract;

/** Minimal robot/command event kinds for the current phase. */
public enum RobotEventType {
    ROBOT_LOADED,
    ROBOT_UNLOADED,
    ROBOT_DIMENSION_CHANGED,
    ROBOT_REMOVED,
    COMMAND_ENQUEUED,
    COMMAND_ACCEPTED,
    COMMAND_STARTED,
    COMMAND_COMPLETED,
    COMMAND_FAILED,
    COMMAND_CANCELLED,
    COMMAND_INTERRUPTED,
    UNKNOWN
}
