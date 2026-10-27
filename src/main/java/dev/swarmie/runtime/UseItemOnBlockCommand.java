package dev.swarmie.runtime;

import dev.swarmie.interaction.InteractionHandler;
import dev.swarmie.interaction.InteractionOutcome;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Synchronous native use-item-on-block interaction. */
final class UseItemOnBlockCommand extends SwarmieCommand {
    private final InteractionHandler handler;
    private final BlockPos pos;
    private final Direction face;
    private final Vec3 hit;
    private InteractionOutcome outcome;

    UseItemOnBlockCommand(long id, long submittedTick, InteractionHandler handler,
                          BlockPos pos, Direction face, Vec3 hit) {
        super(id, Type.USE_ITEM_ON_BLOCK, submittedTick);
        this.handler = handler;
        this.pos = pos;
        this.face = face;
        this.hit = hit;
    }

    @Override
    protected void onStart(FlightTarget target) {
        this.outcome = this.handler.useItemOn(this.pos, this.face, this.hit);
        if (this.outcome == null || !this.outcome.success()) {
            throw new CommandFailureException(this.outcome == null ? "no interaction outcome" : this.outcome.message());
        }
    }

    @Override
    protected String onTick(FlightTarget target) {
        return "used item on " + this.pos.toShortString() + " face " + this.face;
    }

    @Override
    protected void onCancel(FlightTarget target) {
        // Interaction is synchronous; nothing to interrupt.
    }
}
