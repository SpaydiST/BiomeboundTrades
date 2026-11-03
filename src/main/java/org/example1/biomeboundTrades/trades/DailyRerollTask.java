package org.example1.biomeboundTrades.trades;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.example1.biomeboundTrades.BiomeboundTrades;
import org.example1.biomeboundTrades.config.PluginConfig;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Schedules maintenance for loaded villagers by their next restock or book deadline.
 * Villager PDC stays authoritative; chunk load and trade-open paths catch up missed work.
 */
public final class DailyRerollTask extends BukkitRunnable implements Listener {

    private final BiomeboundTrades plugin;
    private final Map<UUID, Long> lastWorldDay = new HashMap<>();
    private final Map<UUID, Villager> loaded = new HashMap<>();
    private final Map<UUID, Map<UUID, Villager>> loadedByWorld = new HashMap<>();
    private final Map<UUID, ScheduledVillager> activeSchedules = new HashMap<>();
    private final Map<UUID, PriorityQueue<ScheduledVillager>> worldQueues = new HashMap<>();
    private final PriorityQueue<ScheduledVillager> realTimeQueue = new PriorityQueue<>(
            Comparator.comparingLong(entry -> entry.realTimeMillis));
    public DailyRerollTask(BiomeboundTrades plugin) {
        this.plugin = plugin;
    }

    public void bootstrap() {
        for (World world : plugin.getServer().getWorlds()) {
            lastWorldDay.put(world.getUID(), world.getFullTime() / 24000L);
            for (Chunk chunk : world.getLoadedChunks()) {
                indexChunk(chunk);
            }
            plugin.getInflationListener().ensureDailySale(world);
            plugin.getInflationListener().ensureSeasonalStock(world);
        }
    }

    @Override
    public void run() {
        for (World world : plugin.getServer().getWorlds()) {
            long day = world.getFullTime() / 24000L;
            Long previous = lastWorldDay.put(world.getUID(), day);
            if (previous == null) {
                plugin.getInflationListener().ensureDailySale(world);
                plugin.getInflationListener().ensureSeasonalStock(world);
            } else if (day > previous) {
                plugin.getInflationListener().onNewDay(world);
            }
        }

        int budget = Math.max(1, plugin.getConfigData().dailyReroll().batchSize);
        long now = System.currentTimeMillis();
        while (budget > 0 && !realTimeQueue.isEmpty()
                && realTimeQueue.peek().realTimeMillis <= now) {
            ScheduledVillager due = realTimeQueue.poll();
            if (process(due)) {
                budget--;
            }
        }
        if (budget <= 0) {
            return;
        }
        for (World world : plugin.getServer().getWorlds()) {
            PriorityQueue<ScheduledVillager> queue = worldQueues.get(world.getUID());
            if (queue == null) {
                continue;
            }
            long worldTime = world.getFullTime();
            while (budget > 0 && !queue.isEmpty() && queue.peek().worldTick <= worldTime) {
                ScheduledVillager due = queue.poll();
                if (process(due)) {
                    budget--;
                }
            }
            if (budget <= 0) {
                break;
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        plugin.getServer().getScheduler().runTask(plugin, () -> indexChunk(chunk));
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager villager) {
                untrackVillager(villager.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof Villager villager) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> indexVillager(villager), 1L);
        }
    }

    public void trackVillager(Villager villager) {
        if (villager == null || !villager.isValid() || villager.isDead()) {
            return;
        }
        cacheVillager(villager);
        plugin.getTradeManager().prepareForScheduling(villager);
        schedule(villager);
    }

    public List<Villager> loadedVillagers(World world) {
        Map<UUID, Villager> villagers = loadedByWorld.get(world.getUID());
        if (villagers == null) {
            return List.of();
        }
        return villagers.values().stream().filter(villager -> villager.isValid() && !villager.isDead()).toList();
    }

    public void shutdown() {
        cancel();
        org.bukkit.event.HandlerList.unregisterAll(this);
        loaded.clear();
        loadedByWorld.clear();
        activeSchedules.clear();
        worldQueues.clear();
        realTimeQueue.clear();
    }

    private void indexChunk(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Villager villager) {
                indexVillager(villager);
            }
        }
    }

    private void indexVillager(Villager villager) {
        if (!villager.isValid() || villager.isDead()) {
            return;
        }
        cacheVillager(villager);
        if (villager.getProfession() == Villager.Profession.LIBRARIAN) {
            plugin.getTradeManager().handleTradeAcquired(villager);
        } else {
            plugin.getTradeManager().applyProfessionRebalance(villager);
        }
        plugin.getTradeManager().prepareForScheduling(villager);
        plugin.getTradeManager().processScheduledUpdates(villager);
        schedule(villager);
    }

    private void cacheVillager(Villager villager) {
        UUID villagerId = villager.getUniqueId();
        UUID worldId = villager.getWorld().getUID();
        loaded.put(villagerId, villager);
        loadedByWorld.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(worldId)) {
                return false;
            }
            entry.getValue().remove(villagerId);
            return entry.getValue().isEmpty();
        });
        loadedByWorld.computeIfAbsent(worldId, ignored -> new HashMap<>()).put(villagerId, villager);
    }

    private void untrackVillager(UUID villagerId) {
        loaded.remove(villagerId);
        loadedByWorld.entrySet().removeIf(entry -> {
            entry.getValue().remove(villagerId);
            return entry.getValue().isEmpty();
        });
        removeSchedule(villagerId);
    }

    private void schedule(Villager villager) {
        UUID id = villager.getUniqueId();
        removeSchedule(id);
        long worldTick = plugin.getTradeManager().nextScheduledWorldTick(villager);
        long realMillis = plugin.getTradeManager().nextScheduledRealMillis(villager);
        if (worldTick == Long.MAX_VALUE && realMillis == Long.MAX_VALUE) {
            return;
        }
        ScheduledVillager entry = new ScheduledVillager(id, villager.getWorld().getUID(),
                worldTick, realMillis);
        activeSchedules.put(id, entry);
        if (worldTick != Long.MAX_VALUE) {
            worldQueues.computeIfAbsent(entry.worldId, ignored -> new PriorityQueue<>(
                    Comparator.comparingLong(candidate -> candidate.worldTick))).add(entry);
        }
        if (realMillis != Long.MAX_VALUE) {
            realTimeQueue.add(entry);
        }
    }

    private void removeSchedule(UUID id) {
        ScheduledVillager previous = activeSchedules.remove(id);
        if (previous == null) {
            return;
        }
        PriorityQueue<ScheduledVillager> worldQueue = worldQueues.get(previous.worldId);
        if (worldQueue != null && previous.worldTick != Long.MAX_VALUE) {
            worldQueue.remove(previous);
        }
        if (previous.realTimeMillis != Long.MAX_VALUE) {
            realTimeQueue.remove(previous);
        }
    }

    private boolean process(ScheduledVillager due) {
        if (activeSchedules.get(due.villagerId) != due) {
            return false;
        }
        removeSchedule(due.villagerId);
        Villager villager = loaded.get(due.villagerId);
        if (villager == null || !villager.isValid() || villager.isDead()
                || !villager.getWorld().getUID().equals(due.worldId)) {
            untrackVillager(due.villagerId);
            return false;
        }
        plugin.getTradeManager().processScheduledUpdates(villager);
        schedule(villager);
        return true;
    }

    private static final class ScheduledVillager {
        final UUID villagerId;
        final UUID worldId;
        final long worldTick;
        final long realTimeMillis;
        ScheduledVillager(UUID villagerId, UUID worldId, long worldTick, long realTimeMillis) {
            this.villagerId = villagerId;
            this.worldId = worldId;
            this.worldTick = worldTick;
            this.realTimeMillis = realTimeMillis;
        }
    }
}
