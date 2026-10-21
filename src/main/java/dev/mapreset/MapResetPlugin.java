package dev.mapreset;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Random;

public final class MapResetPlugin extends JavaPlugin {

    private World target;
    private World template;
    private final Deque<int[]> queue = new ArrayDeque<>();
    private final Random random = new Random();
    private final List<int[]> protectedAreas = new ArrayList<>();
    private boolean busy = false;

    // chunk key -> last time (ms) a player was near that chunk
    private final Map<Long, Long> lastSeen = new HashMap<>();
    private long startMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        target = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (target == null) {
            getLogger().severe("World '" + getConfig().getString("world") + "' not found. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String templateName = getConfig().getString("template-world", "reset_template");
        template = new WorldCreator(templateName)
                .seed(target.getSeed())
                .environment(target.getEnvironment())
                .type(WorldType.NORMAL)
                .generateStructures(true)
                .createWorld();
        if (template == null) {
            getLogger().severe("Could not create template world. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        template.setAutoSave(false);
        template.setSpawnFlags(false, false);

        for (String s : getConfig().getStringList("protected-areas")) {
            try {
                String[] p = s.split(",");
                int x1 = Integer.parseInt(p[0].trim()), z1 = Integer.parseInt(p[1].trim());
                int x2 = Integer.parseInt(p[2].trim()), z2 = Integer.parseInt(p[3].trim());
                protectedAreas.add(new int[]{Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)});
            } catch (Exception e) {
                getLogger().warning("Bad protected area '" + s + "' (expected x1,z1,x2,z2)");
            }
        }

        startMillis = System.currentTimeMillis();
        // Record which chunks players are near, once per second
        Bukkit.getScheduler().runTaskTimer(this, this::trackPlayers, 20L, 20L);
        // Forget old entries once per minute so memory stays tiny
        Bukkit.getScheduler().runTaskTimer(this, this::pruneSeen, 1200L, 1200L);

        long period = Math.max(1, getConfig().getLong("interval-minutes", 15)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> queueRandomChunks(getConfig().getInt("chunks-per-reset", 1)), period, period);
        getLogger().info("MapReset active on '" + target.getName() + "', template world '" + templateName + "'.");
    }

    @Override
    public void onDisable() {
        if (template != null) {
            Bukkit.unloadWorld(template, false);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("now")) {
            long wait = graceRemainingSeconds();
            if (wait > 0) {
                sender.sendMessage("Too soon after server start. Resets begin in " + wait + "s (player-cooldown-minutes).");
                return true;
            }
            int n = queueRandomChunks(getConfig().getInt("chunks-per-reset", 1));
            sender.sendMessage("Queued " + n + " chunk(s) for reset.");
            return true;
        }
        sender.sendMessage("Usage: /mapreset now");
        return true;
    }

    // ---------- chunk selection ----------

    private int queueRandomChunks(int count) {
        if (graceRemainingSeconds() > 0) return 0;
        int queued = 0;
        for (int i = 0; i < count; i++) {
            int[] c = pickChunk();
            if (c != null) {
                queue.add(c);
                queued++;
            }
        }
        processQueue();
        return queued;
    }

    private int[] pickChunk() {
        int radius = getConfig().getInt("radius-chunks", 64);
        int spawnProt = getConfig().getInt("spawn-protection-chunks", 10);
        int sx = target.getSpawnLocation().getBlockX() >> 4;
        int sz = target.getSpawnLocation().getBlockZ() >> 4;

        for (int attempt = 0; attempt < 50; attempt++) {
            int cx = sx + random.nextInt(radius * 2 + 1) - radius;
            int cz = sz + random.nextInt(radius * 2 + 1) - radius;
            if (Math.max(Math.abs(cx - sx), Math.abs(cz - sz)) < spawnProt) continue;
            if (isProtected(cx, cz)) continue;
            if (areaBusy(cx, cz)) continue;
            return new int[]{cx, cz};
        }
        return null;
    }

    private boolean isProtected(int cx, int cz) {
        int minX = cx << 4, maxX = minX + 15, minZ = cz << 4, maxZ = minZ + 15;
        for (int[] a : protectedAreas) {
            if (maxX >= a[0] && minX <= a[2] && maxZ >= a[1] && minZ <= a[3]) return true;
        }
        return false;
    }

    private long cooldownMs() {
        return Math.max(0, getConfig().getLong("player-cooldown-minutes", 10)) * 60_000L;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Seconds left before resets are allowed after server start (0 = allowed). */
    private long graceRemainingSeconds() {
        long left = cooldownMs() - (System.currentTimeMillis() - startMillis);
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    private void trackPlayers() {
        long now = System.currentTimeMillis();
        int r = getConfig().getInt("skip-if-player-within-chunks", 6);
        for (Player p : target.getPlayers()) {
            int px = p.getLocation().getBlockX() >> 4;
            int pz = p.getLocation().getBlockZ() >> 4;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    lastSeen.put(key(px + dx, pz + dz), now);
                }
            }
        }
    }

    private void pruneSeen() {
        long now = System.currentTimeMillis();
        long cd = cooldownMs();
        lastSeen.entrySet().removeIf(e -> now - e.getValue() > cd);
    }

    private boolean recentlyVisited(int cx, int cz) {
        Long t = lastSeen.get(key(cx, cz));
        return t != null && System.currentTimeMillis() - t < cooldownMs();
    }

    private boolean areaBusy(int cx, int cz) {
        return playerNear(cx, cz) || recentlyVisited(cx, cz);
    }

    private boolean playerNear(int cx, int cz) {
        int dist = getConfig().getInt("skip-if-player-within-chunks", 6);
        for (Player p : target.getPlayers()) {
            int px = p.getLocation().getBlockX() >> 4;
            int pz = p.getLocation().getBlockZ() >> 4;
            if (Math.max(Math.abs(px - cx), Math.abs(pz - cz)) <= dist) return true;
        }
        return false;
    }

    // ---------- job processing ----------

    private void processQueue() {
        if (busy) return;
        int[] c = queue.poll();
        if (c == null) return;
        if (areaBusy(c[0], c[1])) { // a player wandered over since it was queued
            processQueue();
            return;
        }
        startJob(c[0], c[1]);
    }

    private void startJob(int cx, int cz) {
        busy = true;
        target.addPluginChunkTicket(cx, cz, this);
        template.addPluginChunkTicket(cx, cz, this);

        // Async chunk loading avoids a lag spike from generating the template chunk
        template.getChunkAtAsync(cx, cz).thenCombine(target.getChunkAtAsync(cx, cz), (s, d) -> new Chunk[]{s, d})
                .whenComplete((chunks, err) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (err != null || chunks == null) {
                        getLogger().warning("Failed to load chunk " + cx + "," + cz);
                        finish(cx, cz);
                        return;
                    }
                    new ResetTask(cx, cz, chunks[0], chunks[1]).runTaskTimer(this, 1L, 1L);
                }));
    }

    private void finish(int cx, int cz) {
        target.removePluginChunkTicket(cx, cz, this);
        template.removePluginChunkTicket(cx, cz, this);
        busy = false;
        // Small pause between chunks to keep the server relaxed
        Bukkit.getScheduler().runTaskLater(this, this::processQueue, 20L);
    }

    private final class ResetTask extends BukkitRunnable {
        private final int cx, cz, minY, maxY;
        private final Chunk dstChunk;
        private final ChunkSnapshot src, dst;
        private int y;
        private int changed = 0;

        ResetTask(int cx, int cz, Chunk srcChunk, Chunk dstChunk) {
            this.cx = cx;
            this.cz = cz;
            this.dstChunk = dstChunk;
            this.src = srcChunk.getChunkSnapshot(false, true, false);
            this.dst = dstChunk.getChunkSnapshot(false, false, false);
            this.minY = target.getMinHeight();
            this.maxY = target.getMaxHeight();
            this.y = minY;

            // Remove mobs/items/etc. so nothing is left floating or buried
            for (Entity e : dstChunk.getEntities()) {
                if (!(e instanceof Player)) e.remove();
            }
        }

        @Override
        public void run() {
            int layers = Math.max(1, getConfig().getInt("layers-per-tick", 8));
            int end = Math.min(y + layers, maxY);

            for (; y < end; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockData want = src.getBlockData(x, y, z);
                        if (!want.equals(dst.getBlockData(x, y, z))) {
                            dstChunk.getBlock(x, y, z).setBlockData(want, false);
                            changed++;
                        }
                    }
                }
                if ((y & 3) == 0) {
                    for (int x = 0; x < 16; x += 4) {
                        for (int z = 0; z < 16; z += 4) {
                            dstChunk.getBlock(x, y, z).setBiome(src.getBiome(x, y, z));
                        }
                    }
                }
            }

            if (y >= maxY) {
                cancel();
                getLogger().info("Reset chunk " + cx + "," + cz + " (" + changed + " blocks changed)");
                finish(cx, cz);
            }
        }
    }
}
