package dev.swarmie;

import dev.swarmie.adapter.SwarmieAdapter;
import dev.swarmie.interaction.InteractionActor;
import dev.swarmie.inventory.SwarmieInventory;
import dev.swarmie.query.BlockQueryResult;
import dev.swarmie.query.DefaultWorldQuery;
import dev.swarmie.query.QueryStatus;
import dev.swarmie.query.RegionQueryResult;
import dev.swarmie.query.WorldQuery;
import dev.swarmie.runtime.FlightTarget;
import dev.swarmie.runtime.SwarmieRuntime;
import dev.swarmie.sensor.DefaultLocalSensor;
import dev.swarmie.sensor.LocalSensor;
import dev.swarmie.sensor.SensorConfig;
import dev.swarmie.sensor.SensorObservation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * The physical Swarmie robot body.
 *
 * <p>A plain {@link Entity}, deliberately not a {@code LivingEntity} and not a
 * {@code BlockEntity}. Vanilla handles UUID, position, rotation, tracking and
 * persistence.
 *
 * <p>The robot owns a nine-slot inventory and a per-entity Runtime. Native block
 * destruction and item-on-block interaction are performed by
 * {@link InteractionActor} through a temporary fake-player context; the robot
 * inventory remains authoritative.
 */
public class SwarmieEntity extends Entity implements FlightTarget {
    private static final double COLLISION_EPSILON = 1.0E-7D;

    private final SwarmieFlightController flightController = new SwarmieFlightController();
    private final SwarmieInventory inventory = new SwarmieInventory();
    private final InteractionActor interactionActor;
    private final SwarmieRuntime runtime;
    private WorldQuery worldQuery = new DefaultWorldQuery();
    private LocalSensor localSensor = new DefaultLocalSensor();
    private boolean worldQueryEnabled = true;
    private boolean localSensorEnabled = true;
    private boolean adapterSessionEnded;

    public SwarmieEntity(EntityType<? extends SwarmieEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.blocksBuilding = false;
        this.interactionActor = new InteractionActor(this);
        this.runtime = new SwarmieRuntime(this, this.interactionActor);
    }

    /** Per-robot Runtime. Never shared with another entity. */
    public SwarmieRuntime runtime() {
        return this.runtime;
    }

    /** Robot-owned inventory; never a view over a player inventory. */
    public SwarmieInventory inventory() {
        return this.inventory;
    }

    /** Independently replaceable world-query capability. */
    public WorldQuery worldQuery() {
        return this.worldQuery;
    }

    public void setWorldQuery(WorldQuery worldQuery) {
        if (worldQuery == null) {
            throw new NullPointerException("worldQuery");
        }
        this.worldQuery = worldQuery;
    }

    public boolean isWorldQueryEnabled() {
        return this.worldQueryEnabled;
    }

    public void setWorldQueryEnabled(boolean enabled) {
        this.worldQueryEnabled = enabled;
    }

    /** Entity-level query convenience that honours the enable switch. */
    public BlockQueryResult queryBlock(BlockPos pos) {
        if (!this.worldQueryEnabled) {
            return BlockQueryResult.failure(QueryStatus.UNSUPPORTED, pos, "world query is disabled");
        }
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return BlockQueryResult.failure(QueryStatus.INVALID, pos, "robot is not on a server level");
        }
        return this.worldQuery.queryBlock(serverLevel, pos);
    }

    public RegionQueryResult queryRegion(BlockPos from, BlockPos to) {
        if (!this.worldQueryEnabled) {
            return RegionQueryResult.failure(QueryStatus.UNSUPPORTED, from, to, "world query is disabled");
        }
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return RegionQueryResult.failure(QueryStatus.INVALID, from, to, "robot is not on a server level");
        }
        return this.worldQuery.queryRegion(serverLevel, from, to);
    }

    /** Independently replaceable local-sensor capability. */
    public LocalSensor localSensor() {
        return this.localSensor;
    }

    public void setLocalSensor(LocalSensor localSensor) {
        if (localSensor == null) {
            throw new NullPointerException("localSensor");
        }
        this.localSensor = localSensor;
    }

    public boolean isLocalSensorEnabled() {
        return this.localSensorEnabled;
    }

    public void setLocalSensorEnabled(boolean enabled) {
        this.localSensorEnabled = enabled;
    }

    /** Observe from the robot's authoritative server-side pose. */
    public SensorObservation sense(SensorConfig config) {
        if (!this.localSensorEnabled) {
            return SensorObservation.error(QueryStatus.UNSUPPORTED, this.position(), Vec3.ZERO, config,
                    "local sensor is disabled");
        }
        if (this.isRemoved()) {
            return SensorObservation.error(QueryStatus.ERROR, this.position(), Vec3.ZERO, config,
                    "robot is removed");
        }
        if (!(this.level() instanceof ServerLevel)) {
            return SensorObservation.error(QueryStatus.INVALID, this.position(), Vec3.ZERO, config,
                    "robot is not on a server level");
        }
        return this.localSensor.observe(this, config);
    }

    /** Small control API used by commands and future Runtime. */
    public void setDesiredVelocity(Vec3 desiredVelocity) {
        this.flightController.setDesiredVelocity(desiredVelocity);
    }

    public void stopFlight() {
        this.flightController.stop();
    }

    @Override
    public Vec3 getVelocity() {
        return this.getDeltaMovement();
    }

    public SwarmieFlightController getFlightController() {
        return this.flightController;
    }

    @Override
    public void tick() {
        super.tick();

        // Single authoritative simulation on the logical server. The client
        // only receives vanilla position updates and interpolates them.
        if (this.level().isClientSide()) {
            return;
        }

        // Runtime first: it may set a new flight target or run an interaction
        // command for this tick.
        this.runtime.tick();

        Vec3 current = this.getDeltaMovement();
        Vec3 requested = this.flightController.update(current);

        this.setDeltaMovement(requested);
        Vec3 before = this.position();
        this.move(MoverType.SELF, requested);
        Vec3 moved = this.position().subtract(before);

        // Native collision may clip one or more axes. Compare requested versus
        // actual displacement and remove persistent velocity only on blocked
        // axes, preserving sliding along unobstructed axes.
        double vx = Math.abs(moved.x - requested.x) > COLLISION_EPSILON ? 0.0D : requested.x;
        double vy = Math.abs(moved.y - requested.y) > COLLISION_EPSILON ? 0.0D : requested.y;
        double vz = Math.abs(moved.z - requested.z) > COLLISION_EPSILON ? 0.0D : requested.z;
        this.setDeltaMovement(new Vec3(vx, vy, vz));
    }

    @Override
    public void onRemoval(Entity.RemovalReason reason) {
        super.onRemoval(reason);

        // Chunk unload is not proof of permanent destruction. For other
        // removals (kill/discard/dimension change), unfinished work is
        // INTERRUPTED and the temporary interaction context is released.
        if (!this.level().isClientSide()
                && reason != Entity.RemovalReason.UNLOADED_TO_CHUNK
                && reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            if (SwarmieAdapter.isActive()) {
                SwarmieAdapter.onEntityRemoved(this, reason);
            }
            else {
                this.onAdapterSessionEnd(reason == Entity.RemovalReason.CHANGED_DIMENSION
                        ? "dimension change" : "owner removed");
            }
        }
    }

    /**
     * Ends Adapter-owned per-entity work exactly once. The registry and event
     * store are outside the entity and retain the result.
     */
    public void onAdapterSessionEnd(String reason) {
        if (this.adapterSessionEnded || this.level().isClientSide()) {
            return;
        }
        this.adapterSessionEnded = true;
        this.interactionActor.close();
        this.runtime.onOwnerUnloaded(reason);
    }

    public boolean isAdapterSessionEnded() {
        return this.adapterSessionEnded;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // No custom synced state at this gate.
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        // Not damageable at this gate; combat is out of scope.
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        this.inventory.clearContent();
        this.inventory.setSelectedSlot(0);
        input.child("SwarmieInventory").ifPresent(this.inventory::load);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        this.inventory.save(output.child("SwarmieInventory"));
    }
}
