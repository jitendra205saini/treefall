package dev.jiten.treefall;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Remembers logs that players placed by hand.
 *
 * <p>Without this, anyone can stack logs into a fake tree, break the bottom
 * one and have the whole stack pop into their inventory for free. TreeAssist
 * solves this by querying CoreProtect or Prism; keeping our own record means
 * no dependency and no lookup cost at break time.
 *
 * <p>Positions are packed into a single {@code long} per block, kept per
 * world, and bounded - once {@link #capacity} is reached the oldest entry is
 * dropped. An insertion-ordered set gives that eviction for free.
 */
final class PlacedLogs {

    private final Map<UUID, LinkedHashSet<Long>> byWorld = new HashMap<>();
    private final int capacity;

    PlacedLogs(int capacity) {
        this.capacity = Math.max(0, capacity);
    }

    /**
     * Packs a block position into one long.
     *
     * <p>26 bits each for x and z, 12 for y. That covers the whole world
     * height (offset so negative y stays positive) and x/z out to +-33 million,
     * which is past the world border anyway.
     */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | ((y + 2048) & 0xFFF);
    }

    boolean enabled() {
        return capacity > 0;
    }

    void add(Block block) {
        if (!enabled()) {
            return;
        }
        LinkedHashSet<Long> set = byWorld.computeIfAbsent(
                block.getWorld().getUID(), key -> new LinkedHashSet<>());

        long key = pack(block.getX(), block.getY(), block.getZ());
        // re-insert so a replaced block counts as fresh
        set.remove(key);
        set.add(key);

        while (set.size() > capacity) {
            Iterator<Long> oldest = set.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    void remove(Block block) {
        if (!enabled()) {
            return;
        }
        LinkedHashSet<Long> set = byWorld.get(block.getWorld().getUID());
        if (set != null) {
            set.remove(pack(block.getX(), block.getY(), block.getZ()));
        }
    }

    boolean contains(Block block) {
        if (!enabled()) {
            return false;
        }
        LinkedHashSet<Long> set = byWorld.get(block.getWorld().getUID());
        return set != null && set.contains(pack(block.getX(), block.getY(), block.getZ()));
    }

    int size() {
        return byWorld.values().stream().mapToInt(LinkedHashSet::size).sum();
    }

    void load(File file, Logger log) {
        byWorld.clear();
        if (!enabled() || !file.exists()) {
            return;
        }
        YamlConfiguration store = YamlConfiguration.loadConfiguration(file);
        for (String worldId : store.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(worldId);
            } catch (IllegalArgumentException e) {
                log.warning("Skipping malformed world id in placed-logs.yml: " + worldId);
                continue;
            }
            if (Bukkit.getWorld(id) == null) {
                continue; // world is gone, its records are dead weight
            }
            LinkedHashSet<Long> set = new LinkedHashSet<>();
            for (Number packed : (List<Number>) store.getList(worldId, List.of())) {
                set.add(packed.longValue());
            }
            byWorld.put(id, set);
        }
    }

    void save(File file, Logger log) {
        if (!enabled()) {
            return;
        }
        YamlConfiguration store = new YamlConfiguration();
        byWorld.forEach((id, set) -> {
            if (!set.isEmpty()) {
                store.set(id.toString(), new ArrayList<>(set));
            }
        });
        try {
            file.getParentFile().mkdirs();
            store.save(file);
        } catch (IOException e) {
            log.warning("Could not save placed-logs.yml: " + e.getMessage());
        }
    }

    /** Drops every record for a world, used when a world unloads. */
    void forget(World world) {
        byWorld.remove(world.getUID());
    }
}
