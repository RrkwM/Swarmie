package dev.swarmie.runtime;

import dev.swarmie.interaction.InteractionHandler;
import dev.swarmie.interaction.InteractionOutcome;
import net.minecraft.core.BlockPos;

/** Synchronous native block destruction performed on the server thread. */
final class MineBlockCommand extends SwarmieCommand {
    private final InteractionHandler handler;
    private final BlockPos pos;
    private InteractionOutcome outcome;

    MineBlockCommand(long id, long submittedTick, InteractionHandler handler, BlockPos pos) {
        super(id, Type.MINE_BLOCK, submittedTick);
        this.handler = handler;
        this.pos = pos;
    }

    @Override
    protected void onStart(FlightTarget target) {
        this.outcome = this.handler.mineBlock(this.pos);
        if (this.outcome == null || !this.outcome.success()) {
            throw new CommandFailureException(this.outcome == null ? "no interaction outcome" : this.outcome.message());
        }
    }

    @Override
    protected String onTick(FlightTarget target) {
        return "mined block " + this.pos.toShortString();
    }

    @Override
    protected void onCancel(FlightTarget target) {
        // Destruction is synchronous; nothing to interrupt.
    }
}
