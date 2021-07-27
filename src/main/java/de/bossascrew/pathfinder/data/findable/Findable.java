package de.bossascrew.pathfinder.data.findable;

import de.bossascrew.pathfinder.data.FindableGroup;
import de.bossascrew.pathfinder.data.RoadMap;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

@Getter
public abstract class Findable {

    protected final int databaseId;
    protected String name;
    protected final int roadMapId;
    protected final RoadMap roadMap;
    protected final List<Integer> edges;
    protected @Nullable Integer nodeGroupId = null;

    protected @Nullable Double bezierTangentLength = null;
    private @Nullable String permission = null;

    public Findable(int databaseId, RoadMap roadMap, @Nullable String name) {
        this.databaseId = databaseId;
        this.roadMap = roadMap;
        this.roadMapId = roadMap.getDatabaseId();
        this.name = name;

        edges = new ArrayList<>();
    }

    public void setGroup(Integer groupId) {
        setGroup(groupId, true);
    }

    public void setGroup(Integer groupId, boolean updateArmorStands) {
        this.nodeGroupId = groupId;
        if(groupId != null) {
            FindableGroup g = roadMap.getFindableGroup(groupId);
            if(g != null) {
                g.getFindables().add(this);
            }
        }
        if(updateArmorStands) {
            roadMap.updateArmorStandDisplay(this, false);
        }
        updateData();
    }

    public void setGroup(@Nullable FindableGroup nodeGroup) {
        setGroup(nodeGroup == null ? null : nodeGroup.getDatabaseId(), true);
    }

    public void removeFindableGroup() {
        setGroup(null, true);
    }

    public @Nullable
    FindableGroup getGroup() {
        return roadMap.getFindableGroup(nodeGroupId);
    }

    public @Nullable
    Double getBezierTangentLength() {
        return bezierTangentLength;
    }

    /**
     * @return Gibt die Bezierwichtung zurück, und falls diese nicht gesetzt ist den vorgegebenen Defaultwert der Roadmap.
     */
    public double getBezierTangentLengthOrDefault() {
        if (bezierTangentLength == null) {
            return roadMap.getDefaultBezierTangentLength();
        }
        return bezierTangentLength;
    }

    public void setBezierTangentLength(@Nullable Double bezierTangentLength) {
        this.bezierTangentLength = bezierTangentLength;
        updateData();
    }

    public void setPermission(@Nullable String permission) {
        this.permission = permission;
        updateData();
    }

    public void setName(String name) {
        this.name = name;
        roadMap.updateArmorStandDisplay(this);
        updateData();
    }

    /**
     * @return Gibt die Position des Objektes als Vektor an. Dieser lässt sich mit der Welt der Roadmap zu einer Location konvertieren.
     */
    public abstract Vector getVector();

    /**
     * @return Gibt die Location des Objektes mit Welt an.
     */
    public abstract Location getLocation();

    public abstract String getScope();

    abstract void updateData();
}
