package dev.swarmie.adapter.registry;

import dev.swarmie.contract.CommandType;
import dev.swarmie.contract.RobotDescriptor;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.RobotLifecycleState;
import dev.swarmie.contract.Vec3Dto;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RobotRegistryTest {
    private static final Set<CommandType> CAPS = Set.copyOf(EnumSet.allOf(CommandType.class));

    @Test
    void unloadKeepsIdentityAndReloadDoesNotDuplicate() {
        RobotRegistry registry = new RobotRegistry();
        RobotId id = new RobotId(UUID.randomUUID());

        registry.load(id, "minecraft:overworld", new Vec3Dto(1, 2, 3), CAPS, 1);
        assertEquals(1, registry.snapshot().size());
        assertEquals(RobotLifecycleState.LOADED, registry.snapshot().get(0).lifecycle());

        registry.unload(id, "minecraft:overworld", new Vec3Dto(4, 5, 6), CAPS, 2);
        assertEquals(1, registry.snapshot().size());
        assertEquals(RobotLifecycleState.UNLOADED, registry.snapshot().get(0).lifecycle());
        assertEquals(new Vec3Dto(4, 5, 6), registry.snapshot().get(0).position());

        registry.load(id, "minecraft:overworld", new Vec3Dto(7, 8, 9), CAPS, 3);
        assertEquals(1, registry.snapshot().size());
        assertEquals(RobotLifecycleState.LOADED, registry.snapshot().get(0).lifecycle());
    }

    @Test
    void dimensionChangeIsNotRemovalAndIsConsumedOnLoad() {
        RobotRegistry registry = new RobotRegistry();
        RobotId id = new RobotId(UUID.randomUUID());
        registry.load(id, "minecraft:overworld", new Vec3Dto(0, 64, 0), CAPS, 1);

        registry.dimensionChanged(id, "minecraft:overworld", new Vec3Dto(0, 64, 0), CAPS, 2);
        assertEquals(RobotLifecycleState.UNLOADED, registry.find(id).orElseThrow().lifecycle());
        assertTrue(registry.consumeDimensionChange(id));
        assertFalse(registry.consumeDimensionChange(id));

        registry.load(id, "minecraft:the_nether", new Vec3Dto(0, 32, 0), CAPS, 3);
        assertEquals(RobotLifecycleState.LOADED, registry.find(id).orElseThrow().lifecycle());
        assertEquals("minecraft:the_nether", registry.find(id).orElseThrow().dimensionId());
    }

    @Test
    void removedRecordsAreBoundedAndLoadedRecordsAreNotEvicted() {
        RobotRegistry registry = new RobotRegistry();
        RobotId loaded = new RobotId(UUID.randomUUID());
        registry.load(loaded, "minecraft:overworld", Vec3Dto.ZERO, CAPS, 1);
        for (int i = 0; i < RobotRegistry.MAX_REMOVED_RECORDS + 20; i++) {
            RobotId id = new RobotId(UUID.randomUUID());
            registry.remove(id, "minecraft:overworld", Vec3Dto.ZERO, CAPS, i + 2);
        }
        assertNotNull(registry.find(loaded).orElse(null));
        long removed = registry.snapshot().stream()
                .filter(r -> r.lifecycle() == RobotLifecycleState.REMOVED)
                .count();
        assertTrue(removed <= RobotRegistry.MAX_REMOVED_RECORDS);
    }

    @Test
    void snapshotIsImmutable() {
        RobotRegistry registry = new RobotRegistry();
        RobotId id = new RobotId(UUID.randomUUID());
        registry.load(id, "minecraft:overworld", Vec3Dto.ZERO, CAPS, 1);
        assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().clear());
    }
}
