package de.bossascrew.pathfinder.data;

import com.google.common.collect.Maps;
import de.bossascrew.core.bukkit.inventory.menu.HotbarMenu;
import de.bossascrew.core.bukkit.nbt.NBTEntity;
import de.bossascrew.core.bukkit.player.PlayerUtils;
import de.bossascrew.core.bukkit.util.BezierUtils;
import de.bossascrew.core.bukkit.util.HeadDBUtils;
import de.bossascrew.core.util.Pair;
import de.bossascrew.core.util.PluginUtils;
import de.bossascrew.pathfinder.PathPlugin;
import de.bossascrew.pathfinder.data.findable.*;
import de.bossascrew.pathfinder.data.visualisation.EditModeVisualizer;
import de.bossascrew.pathfinder.data.visualisation.PathVisualizer;
import de.bossascrew.pathfinder.handler.PathPlayerHandler;
import de.bossascrew.pathfinder.handler.RoadMapHandler;
import de.bossascrew.pathfinder.util.EditModeMenu;
import de.bossascrew.pathfinder.util.EntityHider;
import lombok.Getter;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import xyz.xenondevs.particle.ParticleBuilder;
import xyz.xenondevs.particle.ParticleEffect;
import xyz.xenondevs.particle.task.TaskManager;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Eine Straßenkarte, die verschiedene Wegpunkte enthält und
 * durch die mithifle des AStar Algorithmus ein Pfad gefunden werden kann.
 */
@Getter
public class RoadMap {

	private static final Vector ARMORSTAND_OFFSET = new Vector(0, -1.75, 0);
	private static final Vector ARMORSTAND_CHILD_OFFSET = new Vector(0, -1, 0);

	private final int databaseId;
	private String name;
	private World world;
	private boolean findableNodes;

	private final Map<Integer, Findable> findables = Maps.newHashMap();
	private final Collection<Pair<Findable, Findable>> edges;
	private final Map<Integer, FindableGroup> groups;
	private final Map<UUID, HotbarMenu> editingPlayers;
	private EntityHider entityHider;

	private PathVisualizer pathVisualizer;
	private EditModeVisualizer editModeVisualizer;
	private double nodeFindDistance;
	private double defaultBezierTangentLength;

	private final Map<Findable, ArmorStand> editModeNodeArmorStands;
	private final Map<Pair<Findable, Findable>, ArmorStand> editModeEdgeArmorStands;
	private int editModeTask = -1;

	private BukkitTask armorStandDistanceTask = null;

	public RoadMap(int databaseId, String name, World world, boolean findableNodes, PathVisualizer pathVisualizer,
				   EditModeVisualizer editModeVisualizer, double nodeFindDistance, double defaultBezierTangentLength) {

		this.editingPlayers = new HashMap<>();
		this.editModeNodeArmorStands = new ConcurrentHashMap<>();
		this.editModeEdgeArmorStands = new ConcurrentHashMap<>();

		this.databaseId = databaseId;
		this.name = name;
		this.world = world;
		this.findableNodes = findableNodes;
		this.nodeFindDistance = nodeFindDistance;
		this.defaultBezierTangentLength = defaultBezierTangentLength;

		this.groups = DatabaseModel.getInstance().loadFindableGroups(this);
		this.findables.putAll(DatabaseModel.getInstance().loadFindables(this));
		this.edges = loadEdgesFromIds(Objects.requireNonNull(DatabaseModel.getInstance().loadEdges(this)));

		setPathVisualizer(pathVisualizer);
		setEditModeVisualizer(editModeVisualizer);
	}

	public void setName(String name) {
		if (RoadMapHandler.getInstance().isNameUnique(name)) {
			this.name = name;
		}
		updateData();
	}

	public boolean isNodeNameUnique(String name) {
		return findables.values().stream().map(Findable::getName).noneMatch(context -> context.equalsIgnoreCase(name));
	}

	public boolean isNodeNPC(int id) {
		return findables.values().stream()
				.filter(findable -> findable instanceof NpcFindable)
				.map(findable -> (NpcFindable) findable)
				.anyMatch(npcFindable -> npcFindable.getNpcId() == id);
	}

	public void deleteFindable(int findableId) {
		deleteFindable(Objects.requireNonNull(getFindable(findableId)));
	}

	public void deleteFindable(Findable findable) {
		for (int edge : new ArrayList<>(findable.getEdges())) {
			Findable target = getFindable(edge);
			if (target == null) {
				continue;
			}
			disconnectNodes(findable, target);
		}
		DatabaseModel.getInstance().deleteFindable(findable.getDatabaseId());
		findables.remove(findable.getDatabaseId());

		if (isEdited()) {
			updateEditModeParticles();
			editModeNodeArmorStands.get(findable).remove();
			editModeNodeArmorStands.remove(findable);
		}
	}

	public QuestFindable createQuestFindable(int npcId, @Nullable String name, @Nullable Double bezierTangentLength, String permission) {
		QuestFindable findable =  DatabaseModel.getInstance().newQuestFindable(this, null, npcId, name, bezierTangentLength, permission);
		addFindable(findable);
		return findable;
	}

	public TraderFindable createTraderFindable(int npcId, @Nullable String name, @Nullable Double bezierTangentLength, String permission) {
		TraderFindable findable =  DatabaseModel.getInstance().newTraderFindable(this, null, npcId, name, bezierTangentLength, permission);
		addFindable(findable);
		return findable;
	}

	public Findable createNode(Vector vector, String name) {
		return createNode(vector, name, null, null);
	}

	public Findable createNode(Vector vector, String name, @Nullable Double bezierTangentLength, String permission) {
		Node node = (Node) DatabaseModel.getInstance().newFindable(this, Node.SCOPE, null,
				vector.getX(), vector.getY(), vector.getZ(), name, bezierTangentLength, permission);
		if (node != null) {
			addFindable(node);
		}
		if (isEdited()) {
			this.editModeNodeArmorStands.put(node, getNodeArmorStand(node));
		}
		return node;
	}

	public void addFindable(Findable findable) {
		findables.put(findable.getDatabaseId(), findable);
	}

	public void setFindables(Map<Integer, Findable> findables) {
		this.findables.clear();
		this.findables.putAll(findables);
	}

	public void addFindables(Map<Integer, Findable> findables) {
		this.findables.putAll(findables);
	}

	public @Nullable
	Findable getFindable(String name) {
		return findables.values().stream().filter(f -> f.getName().equalsIgnoreCase(name)).findAny().orElse(null);
	}

	public @Nullable
	Findable getFindable(int findableId) {
		return findables.values().stream().filter(f -> f.getDatabaseId() == findableId).findAny().orElse(null);
	}

	public @Nullable
	FindableGroup getFindableGroup(String name) {
		return groups.values().stream().filter(g -> g.getName().equalsIgnoreCase(name)).findAny().orElse(null);
	}

	public @Nullable
	FindableGroup getFindableGroup(Findable findable) {
		return getFindableGroup(findable.getNodeGroupId());
	}

	public @Nullable
	FindableGroup getFindableGroup(Integer groupId) {
		if (groupId == null) {
			return null;
		}
		return groups.values().stream().filter(group -> group.getDatabaseId() == groupId).findAny().orElse(null);
	}

	public void deleteFindableGroup(FindableGroup findableGroup) {
		findableGroup.delete();
		this.groups.remove(findableGroup.getDatabaseId());
		DatabaseModel.getInstance().deleteFindableGroup(findableGroup);
		for(Findable f : findableGroup.getFindables()) {
			updateArmorStandDisplay(f, false);
		}
	}

	public @Nullable
	FindableGroup addFindableGroup(String name) {
		return addFindableGroup(name, true);
	}

	public @Nullable
	FindableGroup addFindableGroup(String name, boolean findable) {
		if (!isGroupNameUnique(name)) {
			return null;
		}
		FindableGroup group = DatabaseModel.getInstance().newFindableGroup(this, name, findable);
		if (group == null) {
			return null;
		}
		groups.put(group.getDatabaseId(), group);
		return group;
	}

	public boolean isGroupNameUnique(String name) {
		return groups.values().stream().map(FindableGroup::getName).noneMatch(g -> g.equalsIgnoreCase(name));
	}

	/**
	 * erstellt neue Edge in der Datenbank
	 */
	public void connectNodes(Findable a, Findable b) {
		if (a.equals(b)) {
			return;
		}
		DatabaseModel.getInstance().newEdge(a, b);
		a.getEdges().add(b.getDatabaseId());
		b.getEdges().add(a.getDatabaseId());
		Pair<Findable, Findable> edge = new Pair<>(a, b);
		edges.add(edge);

		if (isEdited()) {
			updateEditModeParticles();
			this.editModeEdgeArmorStands.put(edge, getEdgeArmorStand(edge));
		}
	}

	public void disconnectNodes(Pair<Findable, Findable> edge) {
		if(edge.first == null) {
			return;
		}
		disconnectNodes(edge.first, edge.second);
	}

	public void disconnectNode(Findable f) {
		for(int edge : new HashSet<>(f.getEdges())) {
			disconnectNodes(f, getFindable(edge));
		}
	}

	public void disconnectNodes(Findable a, Findable b) {
		if (a.equals(b)) {
			return;
		}
		DatabaseModel.getInstance().deleteEdge(a, b);
		a.getEdges().remove((Integer) b.getDatabaseId());
		b.getEdges().remove((Integer) a.getDatabaseId());

		Pair<Findable, Findable> edge = edges.stream()
				.filter(pair -> (pair.first.equals(a) && pair.second.equals(b)) || pair.second.equals(a) && pair.first.equals(b))
				.findAny().orElse(null);

		if (edge == null) {
			return;
		}
		edges.remove(edge);
		if (isEdited()) {
			updateEditModeParticles();
			ArmorStand edgeArmorStand = editModeEdgeArmorStands.get(edge);
			if (edgeArmorStand != null) {
				edgeArmorStand.remove();
				editModeEdgeArmorStands.remove(edge);
			}
		}
	}

	private Collection<Pair<Findable, Findable>> loadEdgesFromIds(Collection<Pair<Integer, Integer>> edgesById) {
		Collection<Pair<Findable, Findable>> result = new ArrayList<>();
		for (Pair<Integer, Integer> pair : edgesById) {
			Findable a = getFindable(pair.first);
			Findable b = getFindable(pair.second);

			if (a == null || b == null) {
				continue;
			}
			a.getEdges().add(b.getDatabaseId());
			b.getEdges().add(a.getDatabaseId());

			result.add(new Pair<>(a, b));
		}
		return result;
	}

	public void delete() {
		cancelEditModes();
		for (UUID uuid : editingPlayers.keySet()) {
			Player player = Bukkit.getPlayer(uuid);
			if (player == null) {
				continue;
			}
			PlayerUtils.sendMessage(player, PathPlugin.PREFIX + ChatColor.RED + "Die Straßenkarte, die du gerade bearbeitet hast, wurde gelöscht.");
		}

		for (PathPlayer player : PathPlayerHandler.getInstance().getPlayers()) {
			player.deselectRoadMap(getDatabaseId());
			player.cancelPath(this);
		}
		DatabaseModel.getInstance().deleteRoadMap(this);
	}

	/**
	 * @return true sobald mindestens ein Spieler den Editmode aktiv hat
	 */
	public boolean isEdited() {
		return !editingPlayers.isEmpty();
	}

	public void toggleEditMode(UUID uuid) {
		setEditMode(uuid, !isEditing(uuid));
	}

	public void cancelEditModes() {
		for (UUID uuid : editingPlayers.keySet()) {
			setEditMode(uuid, false);
		}
	}

	/**
	 * Setzt den Bearbeitungsmodus für einen Spieler, wobei auch Hotbarmenü etc gesetzt werden => nicht threadsafe
	 *
	 * @param uuid    des Spielers, dessen Modus gesetzt wird
	 * @param editing ob der Modus aktiviert oder deaktiviert wird
	 */
	public void setEditMode(UUID uuid, boolean editing) {
		Player player = Bukkit.getPlayer(uuid);
		PathPlayer editor = PathPlayerHandler.getInstance().getPlayer(uuid);
		if (editor == null) {
			return;
		}

		if (editing) {
			if (player == null) {
				return;
			}
			if (!isEdited()) {
				startEditModeVisualizer();
			}
			editor.setEditMode(databaseId);
			HotbarMenu menu = new EditModeMenu(player, this).getHotbarMenu();
			editingPlayers.put(uuid, menu);
			menu.openInventory(player);
			player.setGameMode(GameMode.CREATIVE);
			toggleArmorStandsVisible(player, true);
		} else {
			if (player != null) {
				editingPlayers.get(uuid).closeInventory(player);
				toggleArmorStandsVisible(player, false);
			}

			editingPlayers.remove(uuid);
			editor.clearEditedRoadmap();

			if (!isEdited()) {
				stopEditModeVisualizer();
			}
		}
	}

	public void toggleArmorStandsVisible(Player player, boolean show) {
		if(show) {
			for(ArmorStand as : getEditModeEdgeArmorStands().values()) {
				entityHider.showEntity(player, as);
			}
			for(ArmorStand as : getEditModeNodeArmorStands().values()) {
				entityHider.showEntity(player, as);
			}
		} else {
			for(ArmorStand as : getEditModeEdgeArmorStands().values()) {
				entityHider.hideEntity(player, as);
			}
			for(ArmorStand as : getEditModeNodeArmorStands().values()) {
				entityHider.hideEntity(player, as);
			}
		}
	}

	public boolean isEditing(UUID uuid) {
		return editingPlayers.containsKey(uuid);
	}

	public boolean isEditing(Player player) {
		return isEditing(player.getUniqueId());
	}

	public void startEditModeVisualizer() {
		entityHider = new EntityHider(PathPlugin.getInstance(), EntityHider.Policy.BLACKLIST);

		for (Findable findable : findables.values()) {
			if(!findable.getLocation().isChunkLoaded()) {
				continue;
			}
			if(findable instanceof NpcFindable) {
				continue;
			}
			ArmorStand nodeArmorStand = getNodeArmorStand(findable);
			editModeNodeArmorStands.put(findable, nodeArmorStand);
		}
		List<Pair<Findable, Findable>> processedFindables = new ArrayList<>();
		for (Pair<Findable, Findable> edge : edges) {
			if (processedFindables.contains(edge)) {
				continue;
			}
			ArmorStand edgeArmorStand = getEdgeArmorStand(edge);
			editModeEdgeArmorStands.put(edge, edgeArmorStand);
			processedFindables.add(edge);
		}
		updateEditModeParticles();

		armorStandDistanceTask = Bukkit.getScheduler().runTaskTimer(PathPlugin.getInstance(), () -> {
			for (UUID uuid : editingPlayers.keySet()) {
				Player player = Bukkit.getPlayer(uuid);
				if (player == null || player.getWorld() != world) {
					continue;
				}
				List<ArmorStand> armorStands = new ArrayList<>(getEditModeNodeArmorStands().values());
				armorStands.addAll(getEditModeEdgeArmorStands().values());
				for (ArmorStand armorStand : armorStands) {
					if (player.getLocation().distance(armorStand.getLocation()) > 20) {
						entityHider.hideEntity(player, armorStand);
					} else {
						entityHider.showEntity(player, armorStand);
					}
				}
			}
		}, 10, 10);
	}

	public void stopEditModeVisualizer() {
		if (armorStandDistanceTask != null) {
			armorStandDistanceTask.cancel();
		}

		entityHider.destroy();
		entityHider = null;

		for (ArmorStand armorStand : editModeNodeArmorStands.values()) {
			armorStand.remove();
		}
		for (ArmorStand armorStand : editModeEdgeArmorStands.values()) {
			armorStand.remove();
		}
		editModeNodeArmorStands.clear();
		editModeEdgeArmorStands.clear();
		Bukkit.getScheduler().cancelTask(editModeTask);
	}

	public void updateArmorStandPosition(Findable findable) {
		ArmorStand as = editModeNodeArmorStands.get(findable);
		if (as == null) {
			return;
		}
		as.teleport(findable.getVector().toLocation(world).add(ARMORSTAND_OFFSET));

		for (Pair<Findable, Findable> edge : getEdges(findable)) {
			ArmorStand asEdge = editModeEdgeArmorStands.get(edge);
			if (asEdge == null) {
				return;
			}
			asEdge.teleport(getEdgeCenter(edge).add(ARMORSTAND_CHILD_OFFSET));
		}
	}

	public void updateArmorStandNodeHeads() {
		ItemStack head = HeadDBUtils.getHeadById(editModeVisualizer.getNodeHeadId());
		for (ArmorStand armorStand : editModeNodeArmorStands.values()) {
			armorStand.getEquipment().setHelmet(head);
		}
	}

	public void updateArmorStandEdgeHeads() {
		ItemStack head = HeadDBUtils.getHeadById(editModeVisualizer.getEdgeHeadId());
		for (ArmorStand armorStand : editModeEdgeArmorStands.values()) {
			armorStand.getEquipment().setHelmet(head);
		}
	}

	public void updateArmorStandDisplay(Findable findable) {
		updateArmorStandDisplay(findable, true);
	}

	public void updateArmorStandDisplay(Findable findable, boolean considerEdges) {
		ArmorStand as = editModeNodeArmorStands.get(findable);
		getNodeArmorStand(findable, as);

		if (!considerEdges) {
			return;
		}
		for (int edge : findable.getEdges()) {
			Pair<Findable, Findable> edgePair = getEdge(findable.getDatabaseId(), edge);
			if (edgePair == null) {
				continue;
			}
			getEdgeArmorStand(edgePair, editModeEdgeArmorStands.get(edgePair));
		}
	}

	/**
	 * Erstellt eine Liste aus Partikel Packets, die mithilfe eines Schedulers immerwieder an die Spieler im Editmode geschickt werden.
	 * Um gelöschte und neue Kanten darstellen zu können, muss diese Liste aus Packets aktualisiert werden.
	 * Wird asynchron ausgeführt
	 */
	public void updateEditModeParticles() {
		PluginUtils.getInstance().runSync(() -> {

			//Bestehenden Task cancellen
			Bukkit.getScheduler().cancelTask(editModeTask);

			//Packet List erstellen, die dem Spieler dann wieder und wieder geschickt wird. (Muss refreshed werden, wenn es Änderungen gibt.)
			List<Object> packets = new ArrayList<>();
			ParticleBuilder particle = new ParticleBuilder(ParticleEffect.valueOf(editModeVisualizer.getParticle().toString()))
					.setColor(java.awt.Color.RED);

			//Alle linearen Verbindungen der Waypoints errechnen und als Packet sammeln. Berücksichtigen, welche Node schon behandelt wurde, um doppelte Geraden zu vermeiden
			List<Pair<Findable, Findable>> processedFindables = new ArrayList<>();
			for (Pair<Findable, Findable> edge : edges) {
				if (processedFindables.contains(edge)) {
					continue;
				}
				List<Vector> points = BezierUtils.getBezierCurveDistanced(editModeVisualizer.getParticleDistance(), edge.first.getVector(), edge.second.getVector());
				packets.addAll(points.stream()
						.map(vector -> vector.toLocation(world))
						.map(location -> particle.setLocation(location).toPacket())
						.collect(Collectors.toSet()));
				processedFindables.add(edge);
			}
			if (packets.size() > editModeVisualizer.getParticleLimit()) {
				packets = packets.subList(0, editModeVisualizer.getParticleLimit());
			}
			final List<Object> fPackets = packets;
			editModeTask = TaskManager.startSuppliedTask(fPackets, editModeVisualizer.getSchedulerPeriod(), () -> editingPlayers.keySet().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).filter(Player::isOnline).collect(Collectors.toSet()));
		});
	}

	private ArmorStand getNodeArmorStand(Findable findable) {
		return getNodeArmorStand(findable, null);
	}

	private ArmorStand getNodeArmorStand(Findable findable, ArmorStand toEdit) {
		String name = findable.getName() + " #" + findable.getDatabaseId() +
				(findable.getGroup() == null ? "" : (findable.getGroup().isFindable() ? ChatColor.GRAY : ChatColor.DARK_GRAY) + " (" + findable.getGroup().getName() + ")");

		if (toEdit == null) {
			toEdit = getNewArmorStand(findable.getLocation().clone().add(ARMORSTAND_OFFSET), name, editModeVisualizer.getNodeHeadId());
		} else {
			toEdit.setCustomName(name);
			toEdit.getEquipment().setHelmet(HeadDBUtils.getHeadById(editModeVisualizer.getNodeHeadId()));
		}
		return toEdit;
	}

	private ArmorStand getEdgeArmorStand(Pair<Findable, Findable> edge) {
		return getEdgeArmorStand(edge, null);
	}

	private ArmorStand getEdgeArmorStand(Pair<Findable, Findable> edge, ArmorStand toEdit) {
		//String name = edge.first.getName() + " (#" + edge.first.getDatabaseId() + ") ↔ " + edge.second.getName() + " (#" + edge.second.getDatabaseId() + ")";

		if (toEdit == null) {
			toEdit = getNewArmorStand(getEdgeCenter(edge).add(ARMORSTAND_CHILD_OFFSET), null, editModeVisualizer.getEdgeHeadId(), true);
		} else {
			toEdit.setCustomName(null);
			toEdit.getEquipment().setHelmet(HeadDBUtils.getHeadById(editModeVisualizer.getEdgeHeadId()));
		}
		toEdit.setSmall(true);
		return toEdit;
	}

	public void setNodeFindDistance(double nodeFindDistance) {
		this.nodeFindDistance = nodeFindDistance;
		updateData();
	}

	public void setFindableNodes(boolean findableNodes) {
		this.findableNodes = findableNodes;
		updateData();
	}

	public void setEditModeVisualizer(EditModeVisualizer editModeVisualizer) {
		if (this.editModeVisualizer != null) {
			this.editModeVisualizer.getNodeHeadSubscribers().unsubscribe(this.getDatabaseId());
			this.editModeVisualizer.getEdgeHeadSubscribers().unsubscribe(this.getDatabaseId());
			this.editModeVisualizer.getUpdateParticle().unsubscribe(this.getDatabaseId());
		}
		this.editModeVisualizer = editModeVisualizer;
		updateData();

		this.editModeVisualizer.getNodeHeadSubscribers().subscribe(this.getDatabaseId(), integer -> PluginUtils.getInstance().runSync(() -> {
			if (isEdited()) {
				this.updateArmorStandNodeHeads();
			}
		}));
		this.editModeVisualizer.getEdgeHeadSubscribers().subscribe(this.getDatabaseId(), integer -> PluginUtils.getInstance().runSync(() -> {
			if (isEdited()) {
				this.updateArmorStandEdgeHeads();
			}
		}));
		this.editModeVisualizer.getUpdateParticle().subscribe(this.getDatabaseId(), obj -> {
			if (isEdited()) {
				updateEditModeParticles();
			}
		});

		if (isEdited()) {
			updateArmorStandEdgeHeads();
			updateArmorStandNodeHeads();
			updateEditModeParticles();
		}
	}

	public void updateChunkArmorStands(Chunk chunk, boolean unload) {
		if (!isEdited()) {
			return;
		}
		List<Findable> nodes = new ArrayList<>(getFindables());
		nodes = nodes.stream().filter(node -> node.getLocation().getChunk().equals(chunk)).collect(Collectors.toList());
		for (Findable findable : nodes) {
			if(findable instanceof NpcFindable) {
				continue;
			}
			//TODO edges
			if (unload) {
				getNodeArmorStand(findable).remove();
				editModeNodeArmorStands.remove(findable);
			} else {
				ArmorStand nodeArmorStand = getNodeArmorStand(findable);
				editModeNodeArmorStands.put(findable, nodeArmorStand);
			}
		}
	}

	public void setPathVisualizer(PathVisualizer pathVisualizer) {
		if (this.pathVisualizer != null) {
			this.pathVisualizer.getUpdateParticle().unsubscribe(this.getDatabaseId());
		}
		this.pathVisualizer = pathVisualizer;
		updateData();

		this.pathVisualizer.getUpdateParticle().subscribe(this.getDatabaseId(), integer -> this.updateActivePaths());
		updateActivePaths();
	}

	public void updateActivePaths() {
		if (PathPlayerHandler.getInstance() == null) {
			return;
		}
		//Jeder spieler kann pro Roadmap nur einen aktiven Pfad haben, weshalb man PathPlayer auf ParticlePath mappen kann
		PathPlayerHandler.getInstance().getPlayers().stream()
				.map(player -> player.getActivePaths().stream()
						.filter(particlePath -> particlePath.getRoadMap().getDatabaseId() == this.getDatabaseId())
						.findFirst().orElse(null))
				.filter(Objects::nonNull)
				.forEach(ParticlePath::run);
	}

	public void setDefaultBezierTangentLength(double length) {
		this.defaultBezierTangentLength = length;
		updateData();
	}

	public void setWorld(World world) {
		if (world == null) {
			return;
		}
		this.world = world;
		updateData();
	}

	private void updateData() {
		PluginUtils.getInstance().runAsync(() -> {
			DatabaseModel.getInstance().updateRoadMap(this);
		});
	}

	private Location getEdgeCenter(Pair<Findable, Findable> edge) {
		Findable a = edge.first;
		Findable b = edge.second;

		if (edge.first != null && edge.second != null) {
			Vector va = a.getVector().clone();
			Vector vb = b.getVector().clone();
			return va.add(vb.subtract(va).multiply(0.5)).toLocation(world);
		}
		return null;
	}

	private Collection<Pair<Findable, Findable>> getEdges(Findable findable) {
		Collection<Pair<Findable, Findable>> ret = new ArrayList<>();
		for (Pair<Findable, Findable> edge : edges) {
			if (edge.first != null && edge.second != null) {
				if (edge.first.equals(findable) || edge.second.equals(findable)) {
					ret.add(edge);
				}
			}
		}
		return ret;
	}

	public int getFindablesSize() {
		return getFindables().size();
	}

	public Collection<Findable> getFindables() {
		return findables.values();
	}

	public Collection<Findable> getFindables(PathPlayer player) {
		Player bukkitPlayer = Bukkit.getPlayer(player.getUuid());
		if (bukkitPlayer == null) {
			return new ArrayList<>();
		}
		if(!findableNodes) {
			return getFindables();
		}
		return getFindables().stream()
				.filter(node -> node.getGroup() != null && !node.getGroup().isFindable() || player.hasFound(node))
				.filter(node -> node.getPermission() == null || bukkitPlayer.hasPermission(node.getPermission()))
				.collect(Collectors.toSet());
	}

	public @Nullable
	Pair<Findable, Findable> getEdge(int aId, int bId) {
		return edges.stream()
				.filter(pair -> pair.first != null && pair.second != null)
				.filter(pair -> (pair.first.getDatabaseId() == aId && pair.second.getDatabaseId() == bId) ||
						(pair.second.getDatabaseId() == aId && pair.first.getDatabaseId() == bId))
				.findAny()
				.orElse(null);
	}

	public int getMaxFoundSize() {
		List<Integer> sizes = groups.values().stream().filter(FindableGroup::isFindable).map(g -> g.getFindables().size()).collect(Collectors.toList());
		sizes.add((int) findables.values().stream().filter(f -> f.getGroup() == null).count());

		int size = 0;
		for (int i : sizes) {
			size += i;
		}
		return size;
	}


	private ArmorStand getNewArmorStand(Location location, String name, int headDbId) {
		return getNewArmorStand(location, name, headDbId, false);
	}

	private ArmorStand getNewArmorStand(Location location, @Nullable String name, int headDbId, boolean small) {
		ArmorStand as = location.getWorld().spawn(location,
				ArmorStand.class,
				armorStand -> {
					entityHider.hideEntity(armorStand);
					for (UUID uuid : editingPlayers.keySet()) {
						entityHider.showEntity(Bukkit.getPlayer(uuid), armorStand);
					}
					armorStand.setVisible(false);
					if (name != null) {
						armorStand.setCustomNameVisible(true);
						armorStand.setCustomName(name);
					}
					armorStand.setGravity(false);
					armorStand.setInvulnerable(true);
					armorStand.setSmall(small);
					ItemStack helmet = HeadDBUtils.getHeadById(headDbId);
					if (armorStand.getEquipment() != null && helmet != null) {
						armorStand.getEquipment().setHelmet(helmet);
					}
				});

		NBTEntity e = new NBTEntity(as);
		e.getPersistentDataContainer().addCompound(PathPlugin.NBT_ARMORSTAND_KEY);
		return as;
	}
}
