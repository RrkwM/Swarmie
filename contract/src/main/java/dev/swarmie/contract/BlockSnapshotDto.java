package dev.swarmie.contract;

import java.util.Map;

/** Bounded block-state summary; no live level, NBT or component map. */
public record BlockSnapshotDto(
        BlockPosDto position,
        String blockId,
        Map<String, String> properties,
        boolean air) {
    public BlockSnapshotDto {
        if (position == null) {
            throw new IllegalArgumentException("position is required");
        }
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("blockId is required");
        }
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
