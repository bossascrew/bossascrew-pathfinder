package de.bossascrew.pathfinder;

import de.bossascrew.acf.BukkitCommandExecutionContext;
import de.bossascrew.acf.CommandContexts;
import de.bossascrew.acf.InvalidCommandArgument;
import de.bossascrew.acf.MessageKeys;
import de.bossascrew.core.BukkitMain;
import de.bossascrew.pathfinder.commands.*;
import de.bossascrew.pathfinder.data.DatabaseModel;
import de.bossascrew.pathfinder.data.FindableGroup;
import de.bossascrew.pathfinder.data.PathPlayer;
import de.bossascrew.pathfinder.data.RoadMap;
import de.bossascrew.pathfinder.data.findable.Findable;
import de.bossascrew.pathfinder.data.findable.Node;
import de.bossascrew.pathfinder.data.visualisation.EditModeVisualizer;
import de.bossascrew.pathfinder.data.visualisation.PathVisualizer;
import de.bossascrew.pathfinder.handler.PathPlayerHandler;
import de.bossascrew.pathfinder.handler.RoadMapHandler;
import de.bossascrew.pathfinder.handler.VisualizerHandler;
import lombok.Getter;
import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public class PathPlugin extends JavaPlugin {

    public static final String PERM_FIND_NODE = "bcrew.pathfinder.find";

    public static final String COMPLETE_ROADMAPS = "@roadmaps";
    public static final String COMPLETE_ACTIVE_ROADMAPS = "@activeroadmaps";
    public static final String COMPLETE_PATH_VISUALIZER = "@path_visualizer";
    public static final String COMPLETE_EDITMODE_VISUALIZER = "@editmode_visualizer";
    public static final String COMPLETE_PARTICLES = "@particles";
    public static final String COMPLETE_NODES = "@nodes";
    public static final String COMPLETE_NODE_GROUPS = "@nodegroups";

    public static final String PREFIX = ChatColor.BLUE + "Pathfinder" + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY;


    @Getter
    private static PathPlugin instance;
    @Getter
    private RoadMapHandler roadMapHandler;
    @Getter
    private PathPlayerHandler playerHandler;
    @Getter
    private VisualizerHandler visualizerHandler;

    @Override
    public void onEnable() {
        instance = this;

        new DatabaseModel(this);
        this.visualizerHandler = new VisualizerHandler();
        this.roadMapHandler = new RoadMapHandler();
        this.playerHandler = new PathPlayerHandler();

        registerContexts();

        BukkitMain.getInstance().getCommandManager().registerCommand(new CancelPath());
        BukkitMain.getInstance().getCommandManager().registerCommand(new EditModeVisualizerCommand());
        BukkitMain.getInstance().getCommandManager().registerCommand(new FindeCommand());
        BukkitMain.getInstance().getCommandManager().registerCommand(new NodeGroupCommand());
        BukkitMain.getInstance().getCommandManager().registerCommand(new PathSystemCommand());
        BukkitMain.getInstance().getCommandManager().registerCommand(new PathVisualizerCommand());
        BukkitMain.getInstance().getCommandManager().registerCommand(new RoadMapCommand());
        BukkitMain.getInstance().getCommandManager().registerCommand(new WaypointCommand());
        registerCompletions();
    }

    @Override
    public void onDisable() {

    }

    private void registerCompletions() {
        BukkitMain.getInstance().registerAsyncCompletion(COMPLETE_ROADMAPS, context -> RoadMapHandler.getInstance().getRoadMapsStream()
                .map(RoadMap::getName)
                .collect(Collectors.toSet()));
        BukkitMain.getInstance().registerAsyncCompletion(COMPLETE_ACTIVE_ROADMAPS, context -> PathPlayerHandler.getInstance().getPlayer(context.getPlayer().getUniqueId()).getActivePaths().stream()
                .map(path -> RoadMapHandler.getInstance().getRoadMap(path.getRoadMap().getDatabaseId()))
                .filter(Objects::nonNull)
                .map(RoadMap::getName)
                .collect(Collectors.toSet()));
        BukkitMain.getInstance().registerAsyncCompletion(COMPLETE_PATH_VISUALIZER, context -> VisualizerHandler
                .getInstance().getPathVisualizerStream()
                .map(PathVisualizer::getName)
                .collect(Collectors.toSet()));
        BukkitMain.getInstance().registerAsyncCompletion(COMPLETE_EDITMODE_VISUALIZER, context -> VisualizerHandler
                .getInstance().getEditModeVisualizerStream()
                .map(EditModeVisualizer::getName)
                .collect(Collectors.toSet()));
        BukkitMain.getInstance().registerAsyncCompletion(COMPLETE_PARTICLES, context -> Arrays.stream(Particle.values())
                .map(Particle::name)
                .collect(Collectors.toSet()));
        BukkitMain.getInstance().registerAsyncCompletion(COMPLETE_NODE_GROUPS, context -> {
            Player player = context.getPlayer();
            PathPlayer pPlayer = PathPlayerHandler.getInstance().getPlayer(player.getUniqueId());
            if (pPlayer == null) {
                return null;
            }
            RoadMap rm = RoadMapHandler.getInstance().getRoadMap(pPlayer.getSelectedRoadMapId());
            if (rm == null) {
                return null;
            }
            return rm.getGroups().stream()
                    .map(FindableGroup::getName)
                    .collect(Collectors.toSet());
        });
        BukkitMain.getInstance().registerAsyncCompletion(COMPLETE_NODES, context -> { //TODO wirft fehler
            Player player = context.getPlayer();
            PathPlayer pPlayer = PathPlayerHandler.getInstance().getPlayer(player.getUniqueId());
            if (pPlayer == null) {
                return null;
            }
            RoadMap rm = RoadMapHandler.getInstance().getRoadMap(pPlayer.getSelectedRoadMapId());
            if (rm == null) {
                return null;
            }
            return rm.getFindables().stream()
                    .map(Findable::getName)
                    .collect(Collectors.toSet());
        });
    }

    private void registerContexts() {
        CommandContexts<BukkitCommandExecutionContext> cm = BukkitMain.getInstance().getCommandManager().getCommandContexts();
        cm.registerContext(RoadMap.class, context -> {
            String search = context.popFirstArg();

            RoadMap roadMap = roadMapHandler.getRoadMap(search);
            if (roadMap == null) {
                if (context.isOptional()) {
                    return null;
                }
                throw new InvalidCommandArgument("Ungültige Roadmap: " + search);
            }
            return roadMap;
        });
        cm.registerContext(PathVisualizer.class, context -> {
            String search = context.popFirstArg();

            PathVisualizer visualizer = visualizerHandler.getPathVisualizer(search);
            if (visualizer == null) {
                if(context.isOptional()) {
                    return null;
                }
                throw new InvalidCommandArgument("Ungültiger Pfad-Visualisierer.");
            }
            return visualizer;
        });
        cm.registerContext(EditModeVisualizer.class, context -> {
            String search = context.popFirstArg();

            EditModeVisualizer visualizer = visualizerHandler.getEditModeVisualizer(search);
            if (visualizer == null) {
                if(context.isOptional()) {
                    return null;
                }
                throw new InvalidCommandArgument("Ungültiger EditMode-Visualisierer.");
            }
            return visualizer;
        });
        cm.registerContext(Particle.class, context -> {
            String search = context.popFirstArg();
            Particle particle = null;
            try {
                particle = Particle.valueOf(search);
            } catch (IllegalArgumentException e) {
                if(context.isOptional()) {
                    return null;
                }
                throw new InvalidCommandArgument("Ungültige Partikel.");
            }
            return particle;
        });
        cm.registerContext(Findable.class, this::resolveFindable);
        cm.registerContext(Node.class, this::resolveFindable);
        cm.registerContext(FindableGroup.class, context -> {
            String search = context.popFirstArg();
            Player player = context.getPlayer();
            PathPlayer pPlayer = PathPlayerHandler.getInstance().getPlayer(player.getUniqueId());
            if (pPlayer == null) {
                return null;
            }
            RoadMap roadMap = RoadMapHandler.getInstance().getRoadMap(pPlayer.getSelectedRoadMapId());
            if (roadMap == null) {
                throw new InvalidCommandArgument("Du musst eine RoadMap auswählen. (/roadmap select)");
            }
            FindableGroup group = roadMap.getFindableGroup(search);
            if (group == null) {
                if(context.isOptional()) {
                    return null;
                }
                throw new InvalidCommandArgument("Diese Gruppe existiert nicht.");
            }
            return group;
        });
        cm.registerContext(Double.class, context -> {
            String number = context.popFirstArg();
            if (number.equalsIgnoreCase("null")) {
                return null;
            }
            try {
                Double value = Double.parseDouble(number);
                if (value > Double.MAX_VALUE) {
                    return Double.MAX_VALUE;
                }
                if (value < -Double.MAX_VALUE) {
                    return -Double.MAX_VALUE;
                }
                return value;
            } catch (NumberFormatException e) {
                throw new InvalidCommandArgument(MessageKeys.MUST_BE_A_NUMBER, "{num}", number);
            }
        });
    }

    private Node resolveFindable(BukkitCommandExecutionContext context) {
        String search = context.popFirstArg();
        Player player = context.getPlayer();
        PathPlayer pPlayer = PathPlayerHandler.getInstance().getPlayer(player.getUniqueId());
        if (pPlayer == null) {
            return null;
        }
        RoadMap roadMap = RoadMapHandler.getInstance().getRoadMap(pPlayer.getSelectedRoadMapId());
        if (roadMap == null) {
            throw new InvalidCommandArgument("Du musst eine RoadMap auswählen. (/roadmap select)");
        }
        Node findable = (Node) roadMap.getFindable(search);
        if (findable == null) {
            if(context.isOptional()) {
                return null;
            }
            throw new InvalidCommandArgument("Diese Node existiert nicht.");
        }
        return findable;
    }
}
