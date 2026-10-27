package dev.swarmie.contract;

/** Contract version 0.x: intentionally unstable while the Adapter is refactored. */
public record ContractVersion(int major, int minor) implements Comparable<ContractVersion> {
    public static final ContractVersion CURRENT = new ContractVersion(0, 1);

    public ContractVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("version components must be non-negative");
        }
    }

    public boolean isCompatibleWith(ContractVersion peer) {
        return peer != null && peer.major == this.major && peer.minor <= this.minor;
    }

    @Override
    public int compareTo(ContractVersion other) {
        int byMajor = Integer.compare(this.major, other.major);
        return byMajor != 0 ? byMajor : Integer.compare(this.minor, other.minor);
    }

    @Override
    public String toString() {
        return this.major + "." + this.minor;
    }
}
