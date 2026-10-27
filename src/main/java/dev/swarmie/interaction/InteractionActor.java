package dev.swarmie.interaction;

import com.mojang.authlib.GameProfile;
import dev.swarmie.SwarmieEntity;
import dev.swarmie.inventory.SwarmieInventory;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Narrow adapter that executes native block destruction and item-on-block
 * interactions through a temporary fake player.
 *
 * <p>The robot inventory is authoritative. The fake player is an interaction
 * context only; it is never added to the world and never represents Swarmie's
 * body.
 */
public final class InteractionActor implements InteractionHandler, AutoCloseable {
    public static final double MAX_INTERACTION_RANGE = 6.0D;

    private final SwarmieEntity robot;
    private ServerLevel level;
    private FakePlayer player;

    public InteractionActor(SwarmieEntity robot) {
        this.robot = robot;
    }

    @Override
    public InteractionOutcome mineBlock(BlockPos pos) {
        try {
            InteractionOutcome validation = validate(pos, true);
            if (!validation.success()) {
                return validation;
            }
            return handoff(() -> {
                boolean destroyed = this.player.gameMode.destroyBlock(pos);
                if (!destroyed) {
                    return InteractionOutcome.failure("native destroyBlock returned false");
                }
                return InteractionOutcome.success("block destroyed");
            });
        }
        catch (RuntimeException exception) {
            return InteractionOutcome.failure("interaction failed: " + describe(exception));
        }
    }

    @Override
    public InteractionOutcome useItemOn(BlockPos pos, Direction face, Vec3 hit) {
        try {
            InteractionOutcome validation = validate(pos, false);
            if (!validation.success()) {
                return validation;
            }
            if (face == null) {
                return InteractionOutcome.failure("face is required");
            }
            if (hit == null || !Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)) {
                return InteractionOutcome.failure("hit position must be finite");
            }

            return handoff(() -> {
                ItemStack stack = this.player.getInventory().getItem(0);
                InteractionResult result = this.player.gameMode.useItemOn(
                        this.player,
                        this.level,
                        stack,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit, face, pos, false));

                if (result.consumesAction()) {
                    return InteractionOutcome.success("item used");
                }
                return InteractionOutcome.failure("native useItemOn returned " + result);
            });
        }
        catch (RuntimeException exception) {
            return InteractionOutcome.failure("interaction failed: " + describe(exception));
        }
    }

    private InteractionOutcome validate(BlockPos pos, boolean requireBreakable) {
        if (this.robot.isRemoved()) {
            return InteractionOutcome.failure("robot is removed");
        }
        if (pos == null) {
            return InteractionOutcome.failure("block position is required");
        }
        if (!(this.robot.level() instanceof ServerLevel serverLevel)) {
            return InteractionOutcome.failure("robot is not on a server level");
        }
        if (!serverLevel.isLoaded(pos)) {
            return InteractionOutcome.failure("target chunk is not loaded");
        }
        if (!serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return InteractionOutcome.failure("target is outside the world border");
        }
        if (this.robot.position().distanceToSqr(Vec3.atCenterOf(pos))
                > MAX_INTERACTION_RANGE * MAX_INTERACTION_RANGE) {
            return InteractionOutcome.failure("target is outside interaction range");
        }

        FakePlayer fakePlayer = ensurePlayer(serverLevel);
        if (!serverLevel.mayInteract(fakePlayer, pos)) {
            return InteractionOutcome.failure("target is protected");
        }

        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir()) {
            return InteractionOutcome.failure("target is air");
        }
        if (requireBreakable && state.getDestroySpeed(serverLevel, pos) < 0.0F) {
            return InteractionOutcome.failure("block is unbreakable");
        }
        return InteractionOutcome.success("validated");
    }

    private FakePlayer ensurePlayer(ServerLevel serverLevel) {
        if (this.player == null
                || this.level != serverLevel
                || this.player.isRemoved()
                || this.player.level() != serverLevel) {
            close();
            GameProfile profile = new GameProfile(UUID.randomUUID(), "Swarmie");
            this.player = new SwarmieFakePlayer(serverLevel, profile);
            this.level = serverLevel;
        }

        this.player.setPos(this.robot.getX(), this.robot.getY(), this.robot.getZ());
        this.player.setYRot(this.robot.getYRot());
        this.player.setXRot(this.robot.getXRot());
        this.player.setDeltaMovement(Vec3.ZERO);
        return this.player;
    }

    private InteractionOutcome handoff(java.util.function.Supplier<InteractionOutcome> action) {
        SwarmieInventory inventory = this.robot.inventory();
        int slot = inventory.getSelectedSlot();
        ItemStack before = inventory.getItem(slot).copy();

        FakePlayer fakePlayer = this.player;
        fakePlayer.getInventory().clearContent();
        fakePlayer.getInventory().setSelectedSlot(0);
        fakePlayer.getInventory().setItem(0, before.copy());
        fakePlayer.getInventory().setChanged();

        try {
            return action.get();
        }
        catch (RuntimeException exception) {
            return InteractionOutcome.failure("interaction exception: " + describe(exception));
        }
        finally {
            ItemStack after = fakePlayer.getInventory().getItem(0);
            ItemStack current = inventory.getItem(slot);
            // The robot inventory stays authoritative. Copy back only if the
            // slot still matches the stack we handed off; otherwise a newer
            // robot-side change wins.
            if (ItemStack.matches(current, before)) {
                inventory.setItem(slot, after);
            }
            fakePlayer.getInventory().clearContent();
            fakePlayer.getInventory().setChanged();
        }
    }

    private static String describe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }

    @Override
    public void close() {
        if (this.player != null) {
            this.player.getInventory().clearContent();
            this.player.discard();
            this.player = null;
        }
        this.level = null;
    }

    private static final class SwarmieFakePlayer extends FakePlayer {
        private SwarmieFakePlayer(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }
    }
}
