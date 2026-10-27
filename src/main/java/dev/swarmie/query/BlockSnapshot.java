package dev.swarmie.query;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable block snapshot; no live Level/BlockState retained. */
public record BlockSnapshot(BlockPos pos, Identifier blockId, Map<String, String> properties, boolean air) {
    public BlockSnapshot {
        pos = pos.immutable();
        properties = Map.copyOf(properties);
    }

    /** Snapshot conversion shared by world queries and local sensing. */
    public static BlockSnapshot of(BlockPos pos, BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        state.getValues().forEach(value -> properties.put(value.property().getName(), value.valueName()));
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            id = Identifier.fromNamespaceAndPath("minecraft", "unknown");
        }
        return new BlockSnapshot(pos, id, properties, state.isAir());
    }
}
