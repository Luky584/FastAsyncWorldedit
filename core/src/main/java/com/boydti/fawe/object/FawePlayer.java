package com.boydti.fawe.object;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.FaweAPI;
import com.boydti.fawe.config.BBC;
import com.boydti.fawe.config.Settings;
import com.boydti.fawe.object.clipboard.DiskOptimizedClipboard;
import com.boydti.fawe.object.exception.FaweException;
import com.boydti.fawe.regions.FaweMaskManager;
import com.boydti.fawe.util.MainUtil;
import com.boydti.fawe.util.SetQueue;
import com.boydti.fawe.util.TaskManager;
import com.boydti.fawe.util.WEManager;
import com.boydti.fawe.wrappers.FakePlayer;
import com.boydti.fawe.wrappers.LocationMaskedPlayerWrapper;
import com.boydti.fawe.wrappers.PlayerWrapper;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.command.tool.BrushTool;
import com.sk89q.worldedit.command.tool.Tool;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.event.platform.CommandEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.CommandManager;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionOperationException;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.registry.WorldData;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class FawePlayer<T> extends Metadatable {

    public final T parent;
    private LocalSession session;

    public static final class METADATA_KEYS {
        public static final String ANVIL_CLIPBOARD = "anvil-clipboard";
        public static final String ROLLBACK = "rollback";
    }

    /**
     * Wrap some object into a FawePlayer<br>
     * - org.bukkit.entity.Player
     * - org.spongepowered.api.entity.living.player
     * - com.sk89q.worldedit.entity.Player
     * - String (name)
     * - UUID (player UUID)
     *
     * @param obj
     * @param <V>
     * @return
     */
    public static <V> FawePlayer<V> wrap(Object obj) {
        if (obj == null || (obj instanceof String && obj.equals("*"))) {
            return FakePlayer.getConsole().toFawePlayer();
        }
        if (obj instanceof FawePlayer) {
            return (FawePlayer<V>) obj;
        }
        if (obj instanceof FakePlayer) {
            return ((FakePlayer) obj).toFawePlayer();
        }
        if (obj instanceof Player) {
            Player actor = LocationMaskedPlayerWrapper.unwrap((Player) obj);
            if (obj.getClass().getSimpleName().equals("PlayerProxy")) {
                try {
                    Field fieldBasePlayer = actor.getClass().getDeclaredField("basePlayer");
                    fieldBasePlayer.setAccessible(true);
                    Player player = (Player) fieldBasePlayer.get(actor);
                    FawePlayer<Object> result = wrap(player);
                    return (FawePlayer<V>) (result == null ? wrap(player.getName()) : result);
                } catch (Throwable e) {
                    MainUtil.handleError(e);
                    return Fawe.imp().wrap(actor.getName());
                }
            } else if (obj instanceof PlayerWrapper) {
                return wrap(((PlayerWrapper) obj).getParent());
            } else {
                try {
                    Field fieldPlayer = actor.getClass().getDeclaredField("player");
                    fieldPlayer.setAccessible(true);
                    return wrap(fieldPlayer.get(actor));
                } catch (Throwable ignore) {
                }
            }
        }
        if (obj instanceof Actor) {
            Actor actor = (Actor) obj;
            FawePlayer existing = Fawe.get().getCachedPlayer(actor.getName());
            if (existing != null) {
                return existing;
            }
            FakePlayer fake = new FakePlayer(actor.getName(), actor.getUniqueId(), actor);
            return fake.toFawePlayer();
        }
        if (obj != null && obj.getClass().getName().contains("CraftPlayer") && !Fawe.imp().getPlatform().equals("bukkit")) {
            try {
                Method methodGetHandle = obj.getClass().getDeclaredMethod("getHandle");
                obj = methodGetHandle.invoke(obj);
            } catch (Throwable e) {
                e.printStackTrace();
                return null;
            }
        }
        return Fawe.imp().wrap(obj);
    }

    @Deprecated
    public FawePlayer(final T parent) {
        this.parent = parent;
        Fawe.get().register(this);
        if (Settings.IMP.CLIPBOARD.USE_DISK) {
            loadClipboardFromDisk();
        }
    }

    public void checkConfirmationRadius(String command, int radius) throws RegionOperationException {
        if (getMeta("cmdConfirmRunning", false)) {
            return;
        }
        if (radius > 0) {
            if (radius > 448) {
                setMeta("cmdConfirm", command);
                throw new RegionOperationException(BBC.WORLDEDIT_CANCEL_REASON_CONFIRM.f(0, radius, command));
            }
        }
    }

    public void checkConfirmationStack(String command, Region region, int times) throws RegionOperationException {
        if (getMeta("cmdConfirmRunning", false)) {
            return;
        }
        if (region != null) {
            Vector min = region.getMinimumPoint();
            Vector max = region.getMaximumPoint();
            long area = (long) ((max.getX() - min.getX()) * (max.getZ() - min.getZ() + 1)) * times;
            if (area > 2 << 18) {
                setMeta("cmdConfirm", command);
                throw new RegionOperationException(BBC.WORLDEDIT_CANCEL_REASON_CONFIRM.f(min, max, command));
            }
        }
    }

    public void checkConfirmationRegion(String command, Region region) throws RegionOperationException {
        if (getMeta("cmdConfirmRunning", false)) {
            return;
        }
        if (region != null) {
            Vector min = region.getMinimumPoint();
            Vector max = region.getMaximumPoint();
            long area = (long) ((max.getX() - min.getX()) * (max.getZ() - min.getZ() + 1));
            if (area > 2 << 18) {
                setMeta("cmdConfirm", command);
                throw new RegionOperationException(BBC.WORLDEDIT_CANCEL_REASON_CONFIRM.f(min, max, command));
            }
        }
    }

    public void checkAllowedRegion(Region selection) {
        checkAllowedRegion(new RegionWrapper(selection.getMinimumPoint(), selection.getMaximumPoint()));
    }

    public void checkAllowedRegion(RegionWrapper wrappedSelection) {
        RegionWrapper[] allowed = WEManager.IMP.getMask(this, FaweMaskManager.MaskType.OWNER);
        HashSet<RegionWrapper> allowedSet = new HashSet<>(Arrays.asList(allowed));
        if (allowed.length == 0) {
            throw new FaweException(BBC.WORLDEDIT_CANCEL_REASON_NO_REGION);
        } else if (!WEManager.IMP.regionContains(wrappedSelection, allowedSet)) {
            throw new FaweException(BBC.WORLDEDIT_CANCEL_REASON_MAX_FAILS);
        }
    }

    public boolean confirm() {
        String confirm = deleteMeta("cmdConfirm");
        if (confirm == null) {
            return false;
        }
        queueAction(new Runnable() {
            @Override
            public void run() {
                setMeta("cmdConfirmRunning", true);
                CommandEvent event = new CommandEvent(getPlayer(), confirm);
                CommandManager.getInstance().handleCommandOnCurrentThread(event);
                setMeta("cmdConfirmRunning", false);
            }
        });
        return true;
    }

    public boolean toggle(String perm) {
        if (this.hasPermission(perm)) {
            this.setPermission(perm, false);
            return false;
        } else {
            this.setPermission(perm, true);
            return true;
        }
    }

    private AtomicInteger runningCount = new AtomicInteger();

    public void queueAction(final Runnable run) {
        Runnable wrappedTask = new Runnable() {
            @Override
            public void run() {
                try {
                    run.run();
                } catch (Throwable e) {
                    while (e.getCause() != null) {
                        e = e.getCause();
                    }
                    if (e instanceof WorldEditException) {
                        sendMessage(BBC.getPrefix() + e.getLocalizedMessage());
                    } else {
                        FaweException fe = FaweException.get(e);
                        if (fe != null) {
                            sendMessage(fe.getMessage());
                        } else {
                            e.printStackTrace();
                        }
                    }
                }
                runningCount.decrementAndGet();
                Runnable next = getActions().poll();
                if (next != null) {
                    next.run();
                }
            }
        };
        getActions().add(wrappedTask);
        FaweLimit limit = getLimit();
        if (runningCount.getAndIncrement() < limit.MAX_ACTIONS) {
            Runnable task = getActions().poll();
            if (task != null) {
                task.run();
            }
        }
    }

    public void clearActions() {
        while (getActions().poll() != null) {
            runningCount.decrementAndGet();
        }
    }

    private ConcurrentLinkedDeque<Runnable> getActions() {
        ConcurrentLinkedDeque<Runnable> adder = getMeta("fawe_action_v2");
        if (adder == null) {
            adder = new ConcurrentLinkedDeque();
            ConcurrentLinkedDeque<Runnable> previous = (ConcurrentLinkedDeque<Runnable>) getAndSetMeta("fawe_action_v2", adder);
            if (previous != null) {
                setMeta("fawe_action_v2", adder = previous);
            }
        }
        return adder;
    }

    public boolean runAsyncIfFree(Runnable r) {
        return runAction(r, true, true);
    }

    public boolean runIfFree(Runnable r) {
        return runAction(r, true, false);
    }

    public boolean runAction(final Runnable ifFree, boolean checkFree, boolean async) {
        long[] actionTime = getMeta("lastActionTime");
        if (actionTime == null) {
            setMeta("lastActionTime", actionTime = new long[2]);
        }
        actionTime[1] = actionTime[0];
        actionTime[0] = Fawe.get().getTimer().getTick();
        if (checkFree) {
            if (async) {
                TaskManager.IMP.taskNow(new Runnable() {
                    @Override
                    public void run() {
                        queueAction(ifFree);
                    }
                }, async);
            } else {
                queueAction(ifFree);
            }
            return true;
        } else {
            TaskManager.IMP.taskNow(ifFree, async);
        }
        return false;
    }

    /**
     * Loads any history items from disk:
     * - Should already be called if history on disk is enabled
     */
    public void loadClipboardFromDisk() {
        File file = MainUtil.getFile(Fawe.imp().getDirectory(), Settings.IMP.PATHS.CLIPBOARD + File.separator + getUUID() + ".bd");
        try {
            if (file.exists() && file.length() > 5) {
                DiskOptimizedClipboard doc = new DiskOptimizedClipboard(file);
                Player player = toWorldEditPlayer();
                LocalSession session = getSession();
                try {
                    if (session.getClipboard() != null) {
                        return;
                    }
                } catch (EmptyClipboardException e) {
                }
                if (player != null && session != null) {
                    WorldData worldData = player.getWorld().getWorldData();
                    Clipboard clip = doc.toClipboard();
                    ClipboardHolder holder = new ClipboardHolder(clip, worldData);
                    getSession().setClipboard(holder);
                }
            }
        } catch (Exception ignore) {
            Fawe.debug("====== INVALID CLIPBOARD ======");
            MainUtil.handleError(ignore, false);
            Fawe.debug("===============---=============");
            Fawe.debug("This shouldn't result in any failure");
            Fawe.debug("File: " + file.getName() + " (len:" + file.length() + ")");
            Fawe.debug("===============---=============");
        }
    }

    /**
     * Get the current World
     *
     * @return
     */
    public World getWorld() {
        return FaweAPI.getWorld(getLocation().world);
    }

    public FaweQueue getMaskedFaweQueue(boolean autoQueue) {
        FaweQueue queue = SetQueue.IMP.getNewQueue(getWorld(), true, autoQueue);
        RegionWrapper[] allowedRegions = getCurrentRegions();
        if (allowedRegions.length == 1 && allowedRegions[0].isGlobal()) {
            return queue;
        }
        return new MaskedFaweQueue(queue, allowedRegions);
    }

    /**
     * Load all the undo EditSession's from disk for a world <br>
     * - Usually already called when necessary
     *
     * @param world
     */
    public void loadSessionsFromDisk(final World world) {
        if (world == null) {
            return;
        }
        getSession().loadSessionHistoryFromDisk(getUUID(), world);
    }

    /**
     * Send a title
     *
     * @param head
     * @param sub
     */
    public abstract void sendTitle(String head, String sub);

    /**
     * Remove the title
     */
    public abstract void resetTitle();

    /**
     * Get the player's limit
     *
     * @return
     */
    public FaweLimit getLimit() {
        return Settings.IMP.getLimit(this);
    }

    /**
     * Get the player's name
     *
     * @return
     */
    public abstract String getName();

    /**
     * Get the player's UUID
     *
     * @return
     */
    public abstract UUID getUUID();

    /**
     * Check the player's permission
     *
     * @param perm
     * @return
     */
    public abstract boolean hasPermission(final String perm);

    /**
     * Set a permission (requires Vault)
     *
     * @param perm
     * @param flag
     */
    public abstract void setPermission(final String perm, final boolean flag);

    /**
     * Send a message to the player
     *
     * @param message
     */
    public abstract void sendMessage(final String message);

    /**
     * Have the player execute a command
     *
     * @param substring
     */
    public abstract void executeCommand(final String substring);

    /**
     * Get the player's location
     *
     * @return
     */
    public abstract FaweLocation getLocation();

    /**
     * Get the WorldEdit player object
     *
     * @return
     */
    public abstract Player toWorldEditPlayer();

    private Player cachedWorldEditPlayer;

    public Player getPlayer() {
        if (cachedWorldEditPlayer == null) {
            cachedWorldEditPlayer = toWorldEditPlayer();
        }
        return cachedWorldEditPlayer;
    }

    /**
     * Get the player's current selection (or null)
     *
     * @return
     */
    public Region getSelection() {
        try {
            return this.getSession().getSelection(this.getPlayer().getWorld());
        } catch (final IncompleteRegionException e) {
            return null;
        }
    }

    /**
     * Get the player's current LocalSession
     *
     * @return
     */
    public LocalSession getSession() {
        return (this.session != null || this.getPlayer() == null || Fawe.get() == null) ? this.session : (session = Fawe.get().getWorldEdit().getSession(this.getPlayer()));
    }

    /**
     * Get the player's current allowed WorldEdit regions
     *
     * @return
     */
    public RegionWrapper[] getCurrentRegions() {
        return WEManager.IMP.getMask(this);
    }

    public RegionWrapper[] getCurrentRegions(FaweMaskManager.MaskType type) {
        return WEManager.IMP.getMask(this, type);
    }

    /**
     * Set the player's WorldEdit selection to the following CuboidRegion
     *
     * @param region
     */
    public void setSelection(final RegionWrapper region) {
        final Player player = this.getPlayer();
        Vector top = region.getTopVector();
        top.mutY(getWorld().getMaxY());
        final RegionSelector selector = new CuboidRegionSelector(player.getWorld(), region.getBottomVector(), top);
        this.getSession().setRegionSelector(player.getWorld(), selector);
    }

    /**
     * Set the player's WorldEdit selection
     *
     * @param selector
     */
    public void setSelection(final RegionSelector selector) {
        this.getSession().setRegionSelector(toWorldEditPlayer().getWorld(), selector);
    }

    /**
     * Get the largest region in the player's allowed WorldEdit region
     *
     * @return
     */
    public RegionWrapper getLargestRegion() {
        int area = 0;
        RegionWrapper max = null;
        for (final RegionWrapper region : this.getCurrentRegions()) {
            final int tmp = (region.maxX - region.minX) * (region.maxZ - region.minZ);
            if (tmp > area) {
                area = tmp;
                max = region;
            }
        }
        return max;
    }

    @Override
    public String toString() {
        return this.getName();
    }

    /**
     * Check if the player has WorldEdit bypass enabled
     *
     * @return
     */
    public boolean hasWorldEditBypass() {
        return this.hasPermission("fawe.bypass");
    }

    /**
     * Unregister this player (delets all metadata etc)
     * - Usually called on logout
     */
    public void unregister() {
        if (Settings.IMP.HISTORY.DELETE_ON_LOGOUT) {
            session = getSession();
            WorldEdit.getInstance().removeSession(toWorldEditPlayer());
            session.setClipboard(null);
            session.clearHistory();
            for (Map.Entry<Integer, Tool> entry : session.getTools().entrySet()) {
                Tool tool = entry.getValue();
                if (tool instanceof BrushTool) {
                    ((BrushTool) tool).clear(getPlayer());
                }
            }
        }
        Fawe.get().unregister(getName());
    }

    /**
     * Get a new EditSession from this player
     */
    public EditSession getNewEditSession() {
        return WorldEdit.getInstance().getEditSessionFactory().getEditSession(getWorld(), -1, toWorldEditPlayer());
    }


    /**
     * Get the tracked EditSession(s) for this player<br>
     * - Queued or autoqueued EditSessions are considered tracked
     *
     * @param requiredStage
     * @return
     */
    public Map<EditSession, SetQueue.QueueStage> getTrackedSessions(SetQueue.QueueStage requiredStage) {
        Map<EditSession, SetQueue.QueueStage> map = new ConcurrentHashMap<>(8, 0.9f, 1);
        if (requiredStage == null || requiredStage == SetQueue.QueueStage.ACTIVE) {
            for (FaweQueue queue : SetQueue.IMP.getActiveQueues()) {
                Set<EditSession> sessions = queue.getEditSessions();
                for (EditSession session : sessions) {
                    FawePlayer currentPlayer = session.getPlayer();
                    if (currentPlayer == this) {
                        map.put(session, SetQueue.QueueStage.ACTIVE);
                    }
                }
            }
        }
        if (requiredStage == null || requiredStage == SetQueue.QueueStage.INACTIVE) {
            for (FaweQueue queue : SetQueue.IMP.getInactiveQueues()) {
                Set<EditSession> sessions = queue.getEditSessions();
                for (EditSession session : sessions) {
                    FawePlayer currentPlayer = session.getPlayer();
                    if (currentPlayer == this) {
                        map.put(session, SetQueue.QueueStage.INACTIVE);
                    }
                }
            }
        }
        return map;
    }
}