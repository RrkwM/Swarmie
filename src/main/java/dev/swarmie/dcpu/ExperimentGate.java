package dev.swarmie.dcpu;

/** Pure gate used by the Fabric bridge; unit-testable without Minecraft. */
public final class ExperimentGate {
    private ExperimentGate() {
    }

    public static boolean enabled(boolean developmentEnvironment, boolean configured) {
        return developmentEnvironment && configured;
    }
}
