package ir.layercloud.cortexdrone.service.navigation.impl;

/**
 * One node in the flight A* frontier.
 *
 * <p>Immutable except for the parent link, which is fixed at construction. The
 * {@code f} score is precomputed so the priority queue compares a field instead
 * of recomputing it on every poll.</p>
 */
@SuppressWarnings("unused")
final class AStarNode implements Comparable<AStarNode> {
    final int x;
    final int y;
    final int z;
    final double g;
    final double h;
    final double f;
    final AStarNode parent;

    AStarNode(int x, int y, int z, double g, double h, double f, AStarNode parent) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.g = g;
        this.h = h;
        this.f = f;
        this.parent = parent;
    }

    @Override
    public int compareTo(AStarNode other) {
        return Double.compare(this.f, other.f);
    }
}
