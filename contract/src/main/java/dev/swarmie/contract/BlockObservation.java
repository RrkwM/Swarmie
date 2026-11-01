package dev.swarmie.contract;

/**
 * One explicit block observation. A non-OK status has no block and MUST NOT be
 * converted to air by clients.
 */
public record BlockObservation(
        RobotId robotId,
        BlockPosDto position,
        ObservationStatus status,
        BlockSnapshotDto block,
        String reason,
        long serverEpoch,
        long serverTick) {

    public BlockObservation {
        if (robotId == null || position == null || status == null) {
            throw new IllegalArgumentException("robotId, position and status are required");
        }
        if (serverEpoch < 0 || serverTick < 0) {
            throw new IllegalArgumentException("serverEpoch and serverTick must be non-negative");
        }
        if (status == ObservationStatus.OK && block == null) {
            throw new IllegalArgumentException("an OK observation needs a block snapshot");
        }
        if (status != ObservationStatus.OK && block != null) {
            throw new IllegalArgumentException("a non-OK observation must not carry a block snapshot");
        }
        reason = reason == null ? "" : reason;
    }
}
