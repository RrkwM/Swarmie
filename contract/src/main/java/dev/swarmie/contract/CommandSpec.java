package dev.swarmie.contract;

/** Minimal command data for the four supported commands. */
public sealed interface CommandSpec
        permits CommandSpec.TimedFlight,
                CommandSpec.Stop,
                CommandSpec.MineBlock,
                CommandSpec.UseItemOnBlock {

    CommandType type();

    record TimedFlight(Vec3Dto velocity, int durationTicks) implements CommandSpec {
        public TimedFlight {
            if (velocity == null) {
                throw new IllegalArgumentException("velocity is required");
            }
            if (durationTicks < 1 || durationTicks > ContractLimits.MAX_DURATION_TICKS) {
                throw new IllegalArgumentException("durationTicks must be in 1.."
                        + ContractLimits.MAX_DURATION_TICKS);
            }
        }

        @Override
        public CommandType type() {
            return CommandType.TIMED_FLIGHT;
        }
    }

    record Stop() implements CommandSpec {
        @Override
        public CommandType type() {
            return CommandType.STOP;
        }
    }

    record MineBlock(BlockPosDto position) implements CommandSpec {
        public MineBlock {
            if (position == null) {
                throw new IllegalArgumentException("position is required");
            }
        }

        @Override
        public CommandType type() {
            return CommandType.MINE_BLOCK;
        }
    }

    record UseItemOnBlock(BlockPosDto position, DirectionDto face, Vec3Dto hit) implements CommandSpec {
        public UseItemOnBlock {
            if (position == null || face == null || hit == null) {
                throw new IllegalArgumentException("position, face and hit are required");
            }
        }

        @Override
        public CommandType type() {
            return CommandType.USE_ITEM_ON_BLOCK;
        }
    }
}
