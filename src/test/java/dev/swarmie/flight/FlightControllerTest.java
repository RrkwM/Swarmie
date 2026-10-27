package dev.swarmie.flight;

import dev.swarmie.SwarmieFlightController;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightControllerTest {
    @Test
    void speedIsClampedToMaximum() {
        SwarmieFlightController controller = new SwarmieFlightController();
        controller.setDesiredVelocity(new Vec3(10, 0, 0));
        Vec3 velocity = Vec3.ZERO;
        for (int i = 0; i < 100; i++) {
            velocity = controller.update(velocity);
        }
        assertTrue(velocity.length() <= SwarmieFlightController.MAX_SPEED + 1.0E-9);
        assertTrue(velocity.x > 0.0);
    }

    @Test
    void stopDeceleratesThroughDamping() {
        SwarmieFlightController controller = new SwarmieFlightController();
        controller.setDesiredVelocity(new Vec3(0.2, 0, 0));
        Vec3 velocity = Vec3.ZERO;
        for (int i = 0; i < 40; i++) {
            velocity = controller.update(velocity);
        }
        assertTrue(velocity.length() > 0.0);

        controller.stop();
        Vec3 previous = velocity;
        for (int i = 0; i < 100; i++) {
            velocity = controller.update(velocity);
            assertTrue(velocity.length() <= previous.length() + 1.0E-9);
            previous = velocity;
        }
        assertTrue(velocity.length() <= 0.01);
    }
}
