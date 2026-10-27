package dev.swarmie.sensing;

import dev.swarmie.query.BlockSnapshot;
import dev.swarmie.query.DefaultWorldQuery;
import dev.swarmie.query.QueryStatus;
import dev.swarmie.query.RegionQueryResult;
import dev.swarmie.sensor.DefaultLocalSensor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SensingTest {
    @Test
    void regionVolumeIsBoundedAndOverflowSafe() {
        BlockPos origin = new BlockPos(0, 0, 0);
        assertEquals(1L, DefaultWorldQuery.regionVolume(origin, origin));
        assertEquals(4096L, DefaultWorldQuery.regionVolume(origin, new BlockPos(15, 15, 15)));
        assertEquals(4352L, DefaultWorldQuery.regionVolume(origin, new BlockPos(16, 15, 15)));
        assertEquals(0L, DefaultWorldQuery.regionVolume(
                new BlockPos(Integer.MIN_VALUE, 0, 0),
                new BlockPos(Integer.MAX_VALUE, 0, 0)));
    }

    @Test
    void fieldOfViewAndRangeBoundaries() {
        Vec3 view = new Vec3(0, 0, 1);
        Vec3 boundary = new Vec3(Math.sin(Math.toRadians(45)), 0, Math.cos(Math.toRadians(45)));
        Vec3 outside = new Vec3(Math.sin(Math.toRadians(45.1)), 0, Math.cos(Math.toRadians(45.1)));

        assertTrue(DefaultLocalSensor.withinCone(view, boundary, 90));
        assertFalse(DefaultLocalSensor.withinCone(view, outside, 90));
        assertFalse(DefaultLocalSensor.withinCone(view, new Vec3(0, 0, -1), 90));
        assertEquals(45.0, DefaultLocalSensor.angleDegrees(view, boundary), 1.0E-6);
        assertTrue(DefaultLocalSensor.withinRange(new Vec3(0, 0, 8), 8));
        assertFalse(DefaultLocalSensor.withinRange(new Vec3(0, 0, 8.001), 8));
        assertFalse(DefaultLocalSensor.withinRange(Vec3.ZERO, 8));
    }

    @Test
    void resultLimitsAndImmutability() {
        assertTrue(DefaultLocalSensor.exceedsLimit(5, 2));
        assertFalse(DefaultLocalSensor.exceedsLimit(2, 2));
        assertEquals(2, DefaultLocalSensor.limit(List.of(1, 2, 3, 4, 5), 2).size());

        Map<String, String> properties = new HashMap<>();
        properties.put("axis", "y");
        BlockSnapshot snapshot = new BlockSnapshot(new BlockPos(1, 2, 3),
                Identifier.fromNamespaceAndPath("minecraft", "stone"), properties, false);
        properties.put("mutated", "true");
        assertFalse(snapshot.properties().containsKey("mutated"));

        List<BlockSnapshot> source = new ArrayList<>();
        source.add(snapshot);
        RegionQueryResult result = new RegionQueryResult(
                QueryStatus.OK, snapshot.pos(), snapshot.pos(), source, List.of(), true, 1, "");
        source.clear();
        assertEquals(1, result.blocks().size());
    }
}
