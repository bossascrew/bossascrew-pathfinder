package de.bossascrew.pathfinder.astar;

public class AStarEdge {

    public final double cost;
    public final AStarNode target;

    public AStarEdge(AStarNode targetNode, double costVal) {
        target = targetNode;
        cost = costVal;
    }
}
