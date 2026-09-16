package dev.jiten.treefall;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Break one log and the whole tree topples over, then replants itself.
 *
 * <p>Everything here keys off Minecraft's own block tags ({@link Tag#LOGS},
 * {@link Tag#LEAVES}) rather than a hand written list of wood types. That is
 * deliberate: when Mojang adds a new tree, it lands in those tags on day one,
 * so a new wood type needs no code change and no config file.
 */
public final class TreeFall extends JavaPlugin implements Listener, TabCompleter {

    /** Wood types whose "sapling" is not simply {@code <name>_sapling}. */
    private static final Map<String, Material> ODD_SAPLINGS = Map.of(
            "mangrove", Material.MANGROVE_PROPAGULE,
            "crimson", Material.CRIMSON_FUNGUS,
            "warped", Material.WARPED_FUNGUS
    );

    /**
     * Blocks that are part of a tree but are NOT in {@link Tag#LOGS}.
     * Mangroves stand on a tangle of roots, so without these the trunk is
     * cut off from the ground and breaking a root does nothing at all.
     */
    private static final Set<Material> EXTRA_TRUNK = Set.of(
            Material.MANGROVE_ROOTS,
            Material.MUDDY_MANGROVE_ROOTS
    );

    /**
     * Used when a config predates the {@code decorations} key. Without this
     * fallback an older config silently yields an empty list, and nothing
     * growing on a tree would come down at all.
     */
    private static final Set<Material> DEFAULT_DECORATIONS = Set.of(
            Material.VINE,
            Material.COCOA,
            Material.SHELF_MUSHROOM,
            Material.PALE_HANGING_MOSS,
            Material.GLOW_LICHEN,
            Material.NETHER_WART_BLOCK,
            Material.WARPED_WART_BLOCK,
            Material.SHROOMLIGHT,
            Material.WEEPING_VINES,
            Material.TWISTING_VINES,
            Material.BEE_NEST,
            Material.MOSS_CARPET,
            Material.PALE_MOSS_CARPET,
            Material.MANGROVE_PROPAGULE
    );

    private static final List<String> SUBCOMMANDS =
            List.of("reload", "toggle", "info", "forcebreak", "forcegrow");

    /** Falling blocks we spawned, so we can stop them turning back into blocks. */
    private final Set<UUID> ourDebris = new HashSet<>();

    /** Players who turned the plugin off for themselves. */
    private final Set<UUID> optedOut = new HashSet<>();

    private final Map<UUID, Long> lastFelled = new HashMap<>();

    /** Freshly replanted saplings, mapped to the time their shield expires. */
    private final Map<String, Long> guardedSaplings = new HashMap<>();

    private final Map<Material, Double> customDrops = new HashMap<>();
    private final Map<Material, Double> toolFactors = new HashMap<>();

    private PlacedLogs placedLogs = new PlacedLogs(0);

    private boolean requireAxe;
    private String requiredLore;
    private int maxBlocks;
    private int minLeaves;
    private boolean breakLeaves;
    private boolean replant;
    private boolean damageTool;
    private boolean protectTool;
    private boolean dropsToInventory;
    private boolean fallingBlocks;
    private double fallSpread;
    private boolean mangroveRoots;
    private int maxLeaves;
    private int maxDistance;
    private int maxFallingBlocks;
    private int delayTicks;
    private Set<Material> decorations = Set.of();

    private boolean respectClaims;
    private boolean whenSneaking;
    private boolean whenNotSneaking;
    private int cooldownSeconds;
    private boolean perTreePermissions;
    private boolean rememberToggle;
    private List<String> enabledWorlds = List.of();
    private List<String> disabledWorlds = List.of();
    private boolean customDropsEnabled;
    private boolean countStatistics;
    private int saplingGuardSeconds;
    private int forceDefaultRadius;
    private int forceMaxRadius;
    private int saveIntervalMinutes;

    private String msgToggleOn;
    private String msgToggleOff;
    private String msgCooldown;
    private String msgSaplingGuarded;

    /**
     * Depth counter, not a flag. Force-break fells many trees in one pass and
     * the delayed breaker runs across ticks, so a plain boolean would be
     * cleared by the first nested call and let our own probe events back in.
     */
    private int fellingDepth = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadToggles();
        placedLogs.load(new File(getDataFolder(), "placed-logs.yml"), getLogger());

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("treefall") != null) {
            getCommand("treefall").setTabCompleter(this);
        }

        // sweep expired sapling shields once a minute
        getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            guardedSaplings.values().removeIf(expiry -> expiry < now);
        }, 1200L, 1200L);

        // Periodic save. onDisable alone is not enough - a crash or a killed
        // process never runs it, and everything tracked since boot is lost.
        if (saveIntervalMinutes > 0) {
            long ticks = saveIntervalMinutes * 60L * 20L;
            getServer().getScheduler().runTaskTimer(this, this::saveData, ticks, ticks);
        }

        getLogger().info("TreeFall ready - using vanilla block tags, so new wood types work automatically.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void saveData() {
        saveToggles();
        placedLogs.save(new File(getDataFolder(), "placed-logs.yml"), getLogger());
    }

    // ------------------------------------------------------------------
    //  Config
    // ------------------------------------------------------------------

    private void loadSettings() {
        var c = getConfig();
        requireAxe = c.getBoolean("require-axe", true);
        requiredLore = c.getString("required-lore", "");
        maxBlocks = c.getInt("max-blocks", 512);
        minLeaves = c.getInt("min-leaves", 4);
        breakLeaves = c.getBoolean("break-leaves", true);
        maxLeaves = c.getInt("max-leaves", 2000);
        maxDistance = c.getInt("max-distance", 12);
        maxFallingBlocks = c.getInt("max-falling-blocks", 200);
        delayTicks = Math.max(0, c.getInt("delay-ticks", 0));
        replant = c.getBoolean("replant", true);
        damageTool = c.getBoolean("damage-tool", true);
        protectTool = c.getBoolean("protect-tool", true);
        dropsToInventory = c.getBoolean("drops-to-inventory", false);
        fallingBlocks = c.getBoolean("falling-blocks", true);
        fallSpread = c.getDouble("fall-spread", 1.0);
        mangroveRoots = c.getBoolean("mangrove-roots", true);

        respectClaims = c.getBoolean("respect-claims", true);
        whenSneaking = c.getBoolean("when-sneaking", true);
        whenNotSneaking = c.getBoolean("when-not-sneaking", true);
        cooldownSeconds = c.getInt("cooldown-seconds", 0);
        perTreePermissions = c.getBoolean("per-tree-permissions", false);
        rememberToggle = c.getBoolean("remember-toggle", true);
        enabledWorlds = c.getStringList("enabled-worlds");
        disabledWorlds = c.getStringList("disabled-worlds");
        countStatistics = c.getBoolean("count-statistics", false);
        saplingGuardSeconds = c.getInt("sapling-guard-seconds", 0);
        forceDefaultRadius = c.getInt("force-commands.default-radius", 10);
        forceMaxRadius = c.getInt("force-commands.max-radius", 30);
        saveIntervalMinutes = c.getInt("save-interval-minutes", 5);

        int placedCapacity = c.getBoolean("track-placed-logs", true)
                ? Math.max(0, c.getInt("track-placed-logs-limit", 100000))
                : 0;
        if (placedLogs.enabled() != (placedCapacity > 0) || placedLogs.size() == 0) {
            placedLogs = new PlacedLogs(placedCapacity);
            placedLogs.load(new File(getDataFolder(), "placed-logs.yml"), getLogger());
        }

        msgToggleOn = c.getString("messages.toggle-on", "TreeFall is now ON for you.");
        msgToggleOff = c.getString("messages.toggle-off", "TreeFall is now OFF for you.");
        msgCooldown = c.getString("messages.cooldown", "You must wait before felling another tree.");
        msgSaplingGuarded = c.getString("messages.sapling-guarded", "That sapling was just planted.");

        if (!c.isSet("decorations")) {
            decorations = DEFAULT_DECORATIONS;
            getLogger().info("No 'decorations' list in config.yml - using defaults. "
                    + "Delete the file and restart to see the full list.");
        } else {
            Set<Material> decor = new HashSet<>();
            for (String name : c.getStringList("decorations")) {
                Material material = Material.matchMaterial(name);
                if (material == null) {
                    getLogger().warning("Unknown block in 'decorations': " + name);
                    continue;
                }
                decor.add(material);
            }
            decorations = decor;
        }

        customDropsEnabled = c.getBoolean("custom-drops.enabled", true);
        readMaterialChances(c.getConfigurationSection("custom-drops.items"), customDrops);
        readMaterialChances(c.getConfigurationSection("custom-drops.tool-factors"), toolFactors);
    }

    private void readMaterialChances(ConfigurationSection section, Map<Material, Double> into) {
        into.clear();
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                getLogger().warning("Unknown material in custom-drops: " + key);
                continue;
            }
            into.put(material, section.getDouble(key));
        }
    }

    private File togglesFile() {
        return new File(getDataFolder(), "toggles.yml");
    }

    private void loadToggles() {
        optedOut.clear();
        if (!rememberToggle || !togglesFile().exists()) {
            return;
        }
        for (String id : YamlConfiguration.loadConfiguration(togglesFile()).getStringList("opted-out")) {
            try {
                optedOut.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Skipping malformed UUID in toggles.yml: " + id);
            }
        }
    }

    private void saveToggles() {
        if (!rememberToggle) {
            return;
        }
        YamlConfiguration store = new YamlConfiguration();
        store.set("opted-out", optedOut.stream().map(UUID::toString).toList());
        try {
            getDataFolder().mkdirs();
            store.save(togglesFile());
        } catch (IOException e) {
            getLogger().warning("Could not save toggles.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("treefall.admin")) {
                    sender.sendMessage("You do not have permission to do that.");
                    return true;
                }
                reloadConfig();
                loadSettings();
                sender.sendMessage("TreeFall config reloaded.");
            }
            case "toggle" -> doToggle(sender, args);
            case "info" -> {
                sender.sendMessage("TreeFall " + getPluginMeta().getVersion());
                sender.sendMessage("  falling blocks : " + fallingBlocks + " (max " + maxFallingBlocks + ")");
                sender.sendMessage("  break leaves   : " + breakLeaves + " (max " + maxLeaves + ")");
                sender.sendMessage("  replant        : " + replant);
                sender.sendMessage("  respect claims : " + respectClaims);
                sender.sendMessage("  decorations    : " + decorations.size() + " blocks");
                sender.sendMessage("  cooldown       : " + cooldownSeconds + "s");
                sender.sendMessage("  delay ticks    : " + delayTicks);
                sender.sendMessage("  placed logs    : "
                        + (placedLogs.enabled() ? placedLogs.size() + " tracked" : "not tracked"));
            }
            case "forcebreak" -> doForce(sender, args, true);
            case "forcegrow" -> doForce(sender, args, false);
            default -> sender.sendMessage(
                    "Usage: /" + label + " <reload|toggle|info|forcebreak|forcegrow>");
        }
        return true;
    }

    private void doToggle(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 1) {
            if (!sender.hasPermission("treefall.toggle.other")) {
                sender.sendMessage("You may only toggle TreeFall for yourself.");
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("No player online called " + args[1] + ".");
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("Usage from console: /treefall toggle <player>");
            return;
        }

        boolean nowOn = optedOut.remove(target.getUniqueId());
        if (!nowOn) {
            optedOut.add(target.getUniqueId());
        }
        target.sendMessage(nowOn ? msgToggleOn : msgToggleOff);
        if (!sender.equals(target)) {
            sender.sendMessage("TreeFall is now " + (nowOn ? "ON" : "OFF")
                    + " for " + target.getName() + ".");
        }
        saveToggles();
    }

    /** {@code forcebreak} fells every tree in range; {@code forcegrow} bone-meals every sapling. */
    private void doForce(CommandSender sender, String[] args, boolean breaking) {
        String node = breaking ? "treefall.forcebreak" : "treefall.forcegrow";
        if (!sender.hasPermission(node)) {
            sender.sendMessage("You do not have permission to do that.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("That command has to be run by a player.");
            return;
        }

        int radius = forceDefaultRadius;
        if (args.length > 1) {
            try {
                radius = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Radius must be a number.");
                return;
            }
        }
        radius = Math.max(1, Math.min(radius, forceMaxRadius));

        Block centre = player.getLocation().getBlock();
        ItemStack tool = player.getInventory().getItemInMainHand();
        int done = 0;

        fellingDepth++;
        try {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        Block block = centre.getRelative(dx, dy, dz);
                        if (breaking) {
                            if (!isTrunk(block.getType())) {
                                continue;
                            }
                            List<Block> logs = collectTree(block);
                            if (logs == null) {
                                continue;
                            }
                            fell(player, tool, block, logs);
                            done++;
                        } else if (Tag.SAPLINGS.isTagged(block.getType())) {
                            for (int attempt = 0; attempt < 8; attempt++) {
                                if (block.applyBoneMeal(BlockFace.UP)) {
                                    done++;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            fellingDepth--;
        }

        sender.sendMessage((breaking ? "Felled " : "Grew ") + done
                + (breaking ? " tree(s)" : " sapling(s)") + " within " + radius + " blocks.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("forcebreak") || sub.equals("forcegrow")) {
                return List.of(String.valueOf(forceDefaultRadius));
            }
            if (sub.equals("toggle") && sender.hasPermission("treefall.toggle.other")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    //  Listeners
    // ------------------------------------------------------------------

    /** Records logs players put down, so a stacked-up fake tree cannot be farmed. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isTrunk(event.getBlockPlaced().getType())) {
            placedLogs.add(event.getBlockPlaced());
        }
    }

    /** A freshly replanted sapling gets a few seconds of immunity. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onSaplingBreak(BlockBreakEvent event) {
        if (saplingGuardSeconds <= 0 || fellingDepth > 0) {
            return;
        }
        Long expiry = guardedSaplings.get(guardKey(event.getBlock()));
        if (expiry == null) {
            return;
        }
        if (expiry < System.currentTimeMillis()) {
            guardedSaplings.remove(guardKey(event.getBlock()));
            return;
        }
        if (event.getPlayer().hasPermission("treefall.admin")) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(msgSaplingGuarded);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (fellingDepth > 0) {
            return; // our own probe events, not a player swinging
        }

        Block origin = event.getBlock();
        if (!isTrunk(origin.getType())) {
            return;
        }
        // whatever happens next, this block is no longer standing
        placedLogs.remove(origin);

        Player player = event.getPlayer();
        if (!allowedHere(origin) || !allowedFor(player, origin.getType())) {
            return;
        }
        if (optedOut.contains(player.getUniqueId())) {
            return;
        }
        if (player.isSneaking() ? !whenSneaking : !whenNotSneaking) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (requireAxe && !Tag.ITEMS_AXES.isTagged(tool.getType())) {
            return;
        }
        if (!hasRequiredLore(tool)) {
            return;
        }
        if (onCooldown(player)) {
            return;
        }

        List<Block> logs = collectTree(origin);
        if (logs == null) {
            return; // too big, or not enough leaves to be a real tree
        }
        if (anyPlacedByHand(origin, logs)) {
            return; // someone stacked this up, it is not a tree
        }

        if (cooldownSeconds > 0) {
            lastFelled.put(player.getUniqueId(), System.currentTimeMillis());
        }

        fellingDepth++;
        try {
            fell(player, tool, origin, logs);
        } finally {
            fellingDepth--;
        }
    }

    /**
     * Stops the debris we spawned from settling back into real blocks.
     * Without this the tree would fall over and then rebuild itself as a
     * pile of logs on the ground.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDebrisLand(EntityChangeBlockEvent event) {
        if (ourDebris.remove(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        placedLogs.forget(event.getWorld());
    }

    // ------------------------------------------------------------------
    //  Gatekeeping
    // ------------------------------------------------------------------

    private boolean allowedHere(Block block) {
        String world = block.getWorld().getName();
        if (disabledWorlds.contains(world)) {
            return false;
        }
        return enabledWorlds.isEmpty() || enabledWorlds.contains(world);
    }

    private boolean allowedFor(Player player, Material logType) {
        if (!player.hasPermission("treefall.use")) {
            return false;
        }
        return !perTreePermissions || player.hasPermission("treefall.use." + treeName(logType));
    }

    private boolean hasRequiredLore(ItemStack tool) {
        if (requiredLore == null || requiredLore.isEmpty()) {
            return true;
        }
        ItemMeta meta = tool.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) {
            return false;
        }
        return meta.getLore().stream().anyMatch(line -> line.contains(requiredLore));
    }

    private boolean onCooldown(Player player) {
        if (cooldownSeconds <= 0 || player.hasPermission("treefall.bypass.cooldown")) {
            return false;
        }
        Long last = lastFelled.get(player.getUniqueId());
        if (last == null || System.currentTimeMillis() - last >= cooldownSeconds * 1000L) {
            return false;
        }
        player.sendMessage(msgCooldown);
        return true;
    }

    private boolean anyPlacedByHand(Block origin, List<Block> logs) {
        if (!placedLogs.enabled()) {
            return false;
        }
        if (placedLogs.contains(origin)) {
            return true;
        }
        for (Block log : logs) {
            if (placedLogs.contains(log)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asks the rest of the server whether this player may break this block,
     * by firing the same event a real swing would.
     *
     * <p>Only the first block of a tree passes through the normal event
     * chain - the rest are removed directly, which would sail straight past
     * GriefPrevention, WorldGuard and every other protection plugin. Probing
     * puts them back in charge of each one.
     */
    private boolean mayBreak(Player player, Block block) {
        if (!respectClaims) {
            return true;
        }
        BlockBreakEvent probe = new BlockBreakEvent(block, player);
        probe.setDropItems(false);
        Bukkit.getPluginManager().callEvent(probe);
        return !probe.isCancelled();
    }

    // ------------------------------------------------------------------
    //  Finding the tree
    // ------------------------------------------------------------------

    /**
     * Flood fills outward from the broken log.
     *
     * @return every connected log except the origin, or {@code null} if this
     *         does not look like a tree (too large, or too few leaves).
     */
    private List<Block> collectTree(Block origin) {
        Set<Block> seen = new HashSet<>();
        List<Block> logs = new ArrayList<>();
        Deque<Block> queue = new ArrayDeque<>();

        seen.add(origin);
        queue.add(origin);

        int leaves = 0;
        Set<Block> countedLeaves = new HashSet<>();

        while (!queue.isEmpty()) {
            Block current = queue.poll();

            // 3x3x3 so that diagonal branches stay connected
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Block next = current.getRelative(dx, dy, dz);
                        Material type = next.getType();

                        if (Tag.LEAVES.isTagged(type)) {
                            if (countedLeaves.add(next)) {
                                leaves++;
                            }
                            continue;
                        }
                        if (!isTrunk(type) || !seen.add(next)) {
                            continue;
                        }
                        logs.add(next);
                        if (logs.size() > maxBlocks) {
                            return null; // a build, not a tree
                        }
                        queue.add(next);
                    }
                }
            }
        }

        if (leaves < minLeaves) {
            return null; // bare log pillar
        }
        return logs;
    }

    /**
     * Walks outward through the crown from the trunk - leaf to leaf, vine to
     * vine - collecting everything that grew on this tree.
     *
     * <p>Only checking the blocks touching a log is nowhere near enough. A
     * canopy reaches two or three blocks past the outermost branch, and a
     * jungle vine can hang a dozen blocks below that, so most of the crown
     * would be left floating in mid air once the trunk went.
     */
    private List<Block> collectFoliage(List<Block> logs, Block origin) {
        List<Block> crown = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();

        seen.add(origin);
        queue.add(origin);
        for (Block log : logs) {
            if (seen.add(log)) {
                queue.add(log);
            }
        }

        while (!queue.isEmpty()) {
            Block current = queue.poll();

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Block next = current.getRelative(dx, dy, dz);
                        Material type = next.getType();

                        // Walk through the whole crown, including the parts we
                        // will not break. Otherwise a vine hanging off a leaf
                        // we are keeping could never be reached.
                        if (!isFoliage(type) || !seen.add(next)) {
                            continue;
                        }
                        // Leash it to the tree. Ground cover like moss carpet
                        // is all one connected sheet in a mangrove swamp, so
                        // without this the search would crawl off across the
                        // whole biome and strip it bare.
                        if (!withinReach(next, origin)) {
                            continue;
                        }
                        // leaves a player placed by hand are not ours to take
                        if (next.getBlockData() instanceof Leaves leaf && leaf.isPersistent()) {
                            continue;
                        }
                        queue.add(next);

                        if (shouldHarvest(type)) {
                            crown.add(next);
                            if (crown.size() >= maxLeaves) {
                                return crown;
                            }
                        }
                    }
                }
            }
        }
        return crown;
    }

    // ------------------------------------------------------------------
    //  Bringing it down
    // ------------------------------------------------------------------

    private void fell(Player player, ItemStack tool, Block origin, List<Block> logs) {
        int minY = origin.getY();
        for (Block log : logs) {
            minY = Math.min(minY, log.getY());
        }

        // Every trunk block that was standing on the ground, not just one.
        // Dark oak and pale oak grow from a 2x2 of saplings and will not
        // regrow from a single one, so we have to put back as many as we took.
        List<Block> trunkBase = new ArrayList<>();
        if (origin.getY() == minY) {
            trunkBase.add(origin);
        }
        for (Block log : logs) {
            if (log.getY() != minY) {
                continue;
            }
            // stay inside the trunk footprint, so a low branch does not seed a forest
            if (Math.abs(log.getX() - origin.getX()) > 1
                    || Math.abs(log.getZ() - origin.getZ()) > 1) {
                continue;
            }
            trunkBase.add(log);
        }

        Material saplingType = saplingFor(origin.getType());

        // Map the crown BEFORE the trunk goes, while the tree is still intact.
        List<Block> order = new ArrayList<>(logs);
        order.addAll(collectFoliage(logs, origin));

        // Fresh budget per tree. Forgetting this reset would leave every tree
        // after the first one falling without any animation at all.
        animationBudget = fallingBlocks ? maxFallingBlocks : 0;

        if (delayTicks > 0) {
            fellSlowly(player, tool, origin, order, trunkBase, saplingType);
        } else {
            for (Block block : order) {
                if (!chop(player, tool, block, origin)) {
                    break;
                }
            }
            scheduleReplant(trunkBase, saplingType);
        }
    }

    /**
     * The same felling, spread over time so the tree comes apart piece by
     * piece. Every block is re-checked before it is touched, because the
     * world can change between ticks - a chunk may unload, another player may
     * get there first, or the tree may already be gone.
     */
    private void fellSlowly(Player player, ItemStack tool, Block origin, List<Block> order,
                            List<Block> trunkBase, Material saplingType) {
        Deque<Block> pending = new ArrayDeque<>(order);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (pending.isEmpty() || !player.isOnline()) {
                    cancel();
                    scheduleReplant(trunkBase, saplingType);
                    return;
                }
                fellingDepth++;
                try {
                    Block next = pending.poll();
                    if (next != null && !chop(player, tool, next, origin)) {
                        pending.clear();
                    }
                } finally {
                    fellingDepth--;
                }
            }
        }.runTaskTimer(this, delayTicks, delayTicks);
    }

    /**
     * Breaks one block of the tree.
     *
     * @return false if felling should stop entirely (the tool is about to break)
     */
    private boolean chop(Player player, ItemStack tool, Block block, Block origin) {
        Material type = block.getType();
        boolean trunk = isTrunk(type);

        // re-validate: with delay-ticks on, this may be a different block now
        if (!trunk && !shouldHarvest(type)) {
            return true;
        }
        if (trunk && protectTool && damageTool && wouldBreak(tool)) {
            return false;
        }
        if (!mayBreak(player, block)) {
            return true;
        }

        boolean wasLeaf = Tag.LEAVES.isTagged(type);
        harvest(player, tool, block, origin, fallingBlocks && spendAnimation());
        placedLogs.remove(block);

        if (countStatistics) {
            try {
                player.incrementStatistic(Statistic.MINE_BLOCK, type);
            } catch (IllegalArgumentException ignored) {
                // not every material is a valid statistic key
            }
        }
        if (trunk) {
            damage(player, tool);
        } else if (wasLeaf) {
            rollCustomDrops(player, tool, block);
        }
        return true;
    }

    private int animationBudget = 0;

    private boolean spendAnimation() {
        return animationBudget-- > 0;
    }

    private void scheduleReplant(List<Block> trunkBase, Material saplingType) {
        if (!replant || saplingType == null) {
            return;
        }
        // one tick later, so the trunk blocks are definitely gone first
        getServer().getScheduler().runTask(this, () -> {
            for (Block spot : trunkBase) {
                plant(spot, saplingType);
            }
        });
    }

    /**
     * Gives the player the block's drops, then either topples the block as
     * debris or just removes it, depending on the animation budget.
     */
    private void harvest(Player player, ItemStack tool, Block block, Block origin, boolean animate) {
        Collection<ItemStack> drops = block.getDrops(tool, player);

        if (dropsToInventory) {
            var leftovers = player.getInventory().addItem(drops.toArray(new ItemStack[0]));
            leftovers.values().forEach(stack ->
                    block.getWorld().dropItemNaturally(block.getLocation(), stack));
        } else {
            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            drops.forEach(stack -> block.getWorld().dropItemNaturally(at, stack));
        }

        if (animate) {
            topple(block, origin);
        } else {
            block.setType(Material.AIR);
        }
    }

    /** Extra loot from leaves - apples, the odd golden apple, that sort of thing. */
    private void rollCustomDrops(Player player, ItemStack tool, Block block) {
        if (!customDropsEnabled || customDrops.isEmpty()) {
            return;
        }
        double factor = toolFactors.getOrDefault(tool.getType(), 1.0);
        if (factor <= 0.0) {
            return;
        }
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        for (Map.Entry<Material, Double> entry : customDrops.entrySet()) {
            if (ThreadLocalRandom.current().nextDouble() < entry.getValue() * factor) {
                block.getWorld().dropItemNaturally(at, new ItemStack(entry.getKey()));
            }
        }
    }

    /**
     * Replaces the block with a falling block entity that is pushed away from
     * the trunk, so the tree leans over as it comes down. Blocks higher up get
     * a harder shove, which is what sells the effect.
     */
    private void topple(Block block, Block origin) {
        BlockData data = block.getBlockData();
        Location spawnAt = block.getLocation().add(0.5, 0.0, 0.5);

        // remove without physics, or the leaves start decaying mid-animation
        block.setType(Material.AIR, false);

        FallingBlock debris = block.getWorld().spawnFallingBlock(spawnAt, data);
        debris.setDropItem(false);
        debris.setHurtEntities(false);
        debris.setCancelDrop(true);

        Vector push = new Vector(
                block.getX() - origin.getX(),
                0.0,
                block.getZ() - origin.getZ());

        if (push.lengthSquared() < 0.0001) {
            // straight trunk - pick a random lean so it does not drop flat
            var rng = ThreadLocalRandom.current();
            push = new Vector(rng.nextDouble(-1.0, 1.0), 0.0, rng.nextDouble(-1.0, 1.0));
        }

        double height = Math.max(0, block.getY() - origin.getY());
        push.normalize().multiply((0.04 + height * 0.012) * fallSpread);
        push.setY(0.05);
        debris.setVelocity(push);

        UUID id = debris.getUniqueId();
        ourDebris.add(id);
        // safety net: if it never lands, stop tracking it eventually
        getServer().getScheduler().runTaskLater(this, () -> ourDebris.remove(id), 200L);
    }

    private void plant(Block spot, Material sapling) {
        if (spot.getType() != Material.AIR) {
            return;
        }
        BlockData data = sapling.createBlockData();
        // Let the game decide what counts as valid ground. Tag.DIRT holds only
        // dirt, coarse_dirt and rooted_dirt - NOT grass block, mud, podzol,
        // mycelium or moss. Checking against it silently refused to replant
        // nearly everywhere, and on mangroves (which stand in mud) always.
        if (!spot.canPlace(data)) {
            return;
        }
        spot.setBlockData(data);

        if (saplingGuardSeconds > 0) {
            guardedSaplings.put(guardKey(spot),
                    System.currentTimeMillis() + saplingGuardSeconds * 1000L);
        }
    }

    private String guardKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    // ------------------------------------------------------------------
    //  Naming and small helpers
    // ------------------------------------------------------------------

    /** Logs, plus the odd non-log block that is still structurally a tree. */
    private boolean isTrunk(Material type) {
        return Tag.LOGS.isTagged(type) || (mangroveRoots && EXTRA_TRUNK.contains(type));
    }

    /** Leaves and anything growing on the tree - what the crown is made of. */
    private boolean isFoliage(Material type) {
        return Tag.LEAVES.isTagged(type) || decorations.contains(type);
    }

    /**
     * Decorations always come down - a vine left hanging in mid air after the
     * tree is gone looks broken. Leaves are optional.
     */
    private boolean shouldHarvest(Material type) {
        return decorations.contains(type) || (breakLeaves && Tag.LEAVES.isTagged(type));
    }

    /** Keeps the crown search from crawling away from the tree it started on. */
    private boolean withinReach(Block block, Block origin) {
        int dx = block.getX() - origin.getX();
        int dz = block.getZ() - origin.getZ();
        return dx * dx + dz * dz <= maxDistance * maxDistance;
    }

    /**
     * Strips a log name down to its wood type, so a brand new species is
     * handled without touching this class. {@code POPLAR_LOG} and
     * {@code STRIPPED_POPLAR_LOG} both come back as {@code poplar}.
     */
    private String treeName(Material log) {
        return log.name().toLowerCase(Locale.ROOT)
                .replace("stripped_", "")
                .replace("muddy_", "")
                .replace("_log", "")
                .replace("_wood", "")
                .replace("_hyphae", "")
                .replace("_stem", "")
                .replace("_roots", "");
    }

    private Material saplingFor(Material log) {
        String name = treeName(log);
        Material odd = ODD_SAPLINGS.get(name);
        if (odd != null) {
            return odd;
        }
        return Material.matchMaterial(name + "_sapling");
    }

    private boolean wouldBreak(ItemStack tool) {
        short max = tool.getType().getMaxDurability();
        if (max <= 0) {
            return false;
        }
        return tool.getItemMeta() instanceof Damageable d && d.getDamage() + 1 >= max;
    }

    private void damage(Player player, ItemStack tool) {
        if (!damageTool || player.getGameMode().name().equals("CREATIVE")) {
            return;
        }
        short max = tool.getType().getMaxDurability();
        if (max <= 0) {
            return;
        }
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return;
        }
        // Unbreaking: 1-in-(level+1) chance the hit actually costs durability
        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) {
            return;
        }
        damageable.setDamage(damageable.getDamage() + 1);
        tool.setItemMeta(meta);

        if (damageable.getDamage() >= max) {
            tool.setAmount(0);
        }
    }
}
