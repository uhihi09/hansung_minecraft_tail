package dev.uhihi.tailtag;

import dev.uhihi.tailtag.visual.TeamVisuals;
import dev.uhihi.tailtag.world.Scatterer;
import dev.uhihi.tailtag.world.WorldBorderController;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import dev.uhihi.tailtag.hud.WorldBorderHud;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class GameManager {

    private final TailTagPlugin plugin;

    private final LinkedHashSet<UUID> waiting = new LinkedHashSet<>();
    private final EnumMap<GameColor, Team> teams = new EnumMap<>(GameColor.class);
    private final Map<UUID, GameColor> playerColor = new HashMap<>();

    private boolean running = false;

    private BukkitTask hunterAlertTask;
    private BukkitTask followTpTask;
    private final Map<UUID, Long> lastFollowTp = new HashMap<>();

    private final WorldBorderController borderController;
    private final TeamVisuals visuals;

    private final WorldBorderHud worldBorderHud;

    private static class FrozenState {
        final Location anchor;
        final long untilEpochMs;
        FrozenState(Location anchor, long untilEpochMs) {
            this.anchor = anchor.clone();
            this.untilEpochMs = untilEpochMs;
        }
    }
    private final Map<UUID, FrozenState> frozen = new HashMap<>();

    public GameManager(TailTagPlugin plugin) {
        this.plugin = plugin;
        this.borderController = new WorldBorderController(plugin);
        this.worldBorderHud = new WorldBorderHud(plugin, this); // HUD 초기화
        this.visuals = new TeamVisuals(this);
    }

    private static class Team {
        GameColor color;
        UUID leader;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        Team(GameColor color, UUID leader) {
            this.color = color;
            this.leader = leader;
            this.members.add(leader);
        }
        boolean isAlive() { return leader != null && !members.isEmpty(); }
    }

    public TeamVisuals getVisuals() { return visuals; }
    public int getMaxPlayers() { return GameColor.values().length; }
    public boolean isRunning() { return running; }

    public boolean isInGame(Player p) {
        if (p == null) return false;
        UUID id = p.getUniqueId();
        return running ? playerColor.containsKey(id) : waiting.contains(id) || playerColor.containsKey(id);
    }

    public List<Player> getParticipants() {
        if (!running) {
            return waiting.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).collect(Collectors.toList());
        }
        List<Player> list = new ArrayList<>();
        for (UUID id : playerColor.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) list.add(p);
        }
        return list;
    }

    public void broadcast(String msg) {
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
        plugin.getLogger().info(ChatColor.stripColor(msg));
    }

    public void join(Player p) {
        if (running) { p.sendMessage(ChatColor.RED + "[TailTag] 이미 게임이 진행 중입니다."); return; }
        if (waiting.size() >= getMaxPlayers()) { p.sendMessage(ChatColor.RED + "[TailTag] 최대 " + getMaxPlayers() + "명까지 참가할 수 있습니다."); return; }
        waiting.add(p.getUniqueId());
        p.sendMessage(ChatColor.GREEN + "[TailTag] 참가했습니다. 현재 대기 인원: " + waiting.size() + "/" + getMaxPlayers());
    }

    public void leave(Player p) {
        if (running) { p.sendMessage(ChatColor.RED + "[TailTag] 진행 중에는 나갈 수 없습니다."); return; }
        waiting.remove(p.getUniqueId());
        p.sendMessage(ChatColor.YELLOW + "[TailTag] 대기열에서 나갔습니다.");
    }

    public void startGame() {
        if (running) return;

        // 온라인 대기자만 후보로
        List<UUID> onlineWaiting = waiting.stream()
                .map(id -> {
                    Player p = Bukkit.getPlayer(id);
                    return (p != null && p.isOnline()) ? id : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        if (onlineWaiting.size() < 2) {
            broadcast(ChatColor.RED + "[TailTag] 최소 2명 이상 필요합니다.");
            return;
        }

        Collections.shuffle(onlineWaiting);
        int capacity = GameColor.values().length;
        int count = Math.min(onlineWaiting.size(), capacity);

        List<UUID> chosen = new ArrayList<>(onlineWaiting.subList(0, count));
        List<UUID> overflow = (onlineWaiting.size() > count)
                ? new ArrayList<>(onlineWaiting.subList(count, onlineWaiting.size()))
                : Collections.emptyList();

        // 팀/맵핑 초기화
        teams.clear();
        playerColor.clear();

        for (int i = 0; i < count; i++) {
            UUID id = chosen.get(i);
            GameColor color = GameColor.values()[i];
            Team t = new Team(color, id);
            teams.put(color, t);
            playerColor.put(id, color);
        }

        teams.forEach((c, t) -> {
            Player leader = Bukkit.getPlayer(t.leader);
            if (leader != null) leader.sendMessage(ChatColor.GRAY + "당신의 팀 색상: " + c.chat + c.displayKorean);
        });
        broadcast(ChatColor.AQUA + "[TailTag] 게임 시작! 팀이 배정되었습니다.");

        // 대기열 정리: 이번 판 선정자는 제거, 초과 인원은 유지(다음 판 자동 대기)
        waiting.removeAll(chosen);
        for (UUID id : overflow) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(ChatColor.YELLOW + "[TailTag] 이번 판은 인원 제한으로 참가하지 못했습니다. 다음 판 대기열에 남아 있습니다.");
        }

        running = true;

        // 이번 판 참가자 스냅샷
        List<Player> participants = chosen.stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 시작 전 상태 정리(최대체력/효과)
        for (Player p : participants) {
            try {
                AttributeInstance attr = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (attr != null) {
                    attr.setBaseValue(20.0);
                    if (p.getHealth() > 20.0) p.setHealth(20.0);
                }
                p.setAbsorptionAmount(0.0);
                p.removePotionEffect(PotionEffectType.WEAKNESS);
                p.setFireTicks(0);
            } catch (Throwable ignored) { }
        }

        // 보더/분산/시각화/HUD/알림
        borderController.applyOnGameStart(participants);
        new Scatterer(plugin).scatterPlayers(participants);
        visuals.refreshAll();
        worldBorderHud.start();
        startHunterAlertTask(); // [추가] 헌터 근접 알림
        startFollowTeleportTask();

        // 보이스 연결 안내(2초 뒤) - 게임 지속 여부 가드
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isRunning()) return;
            for (Player p : participants) {
                if (p == null || !p.isOnline()) continue;
                try {
                    net.md_5.bungee.api.chat.TextComponent btn =
                            new net.md_5.bungee.api.chat.TextComponent(net.md_5.bungee.api.ChatColor.GREEN + "[보이스 연결]");
                    btn.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                            net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, "/audio"));
                    btn.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                            net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                            new net.md_5.bungee.api.chat.ComponentBuilder(
                                    net.md_5.bungee.api.ChatColor.YELLOW + "클릭하여 보이스 채팅 링크를 받습니다").create()));
                    net.md_5.bungee.api.chat.TextComponent msg =
                            new net.md_5.bungee.api.chat.TextComponent(net.md_5.bungee.api.ChatColor.AQUA + "인게임 근접 보이스를 사용하려면 ");
                    msg.addExtra(btn);
                    msg.addExtra(new net.md_5.bungee.api.chat.TextComponent(
                            net.md_5.bungee.api.ChatColor.AQUA + " 을(를) 클릭하세요."));
                    p.spigot().sendMessage(msg);
                } catch (Throwable ignored) {
                    p.sendMessage(org.bukkit.ChatColor.AQUA + "인게임 근접 보이스: /audio 를 입력하여 연결하세요.");
                }
            }
        }, 40L);
    }

    public void stopGame(boolean resetBorder) {
        if (!running) return;
        List<Player> affected = getParticipants();
        running = false;

        // BossBar HUD 정지
        worldBorderHud.stop();

        for (UUID id : new ArrayList<>(frozen.keySet())) unfreeze(Bukkit.getPlayer(id));
        frozen.clear();

        visuals.resetAll(affected);
        resetMaxHealth(affected);

        if (hunterAlertTask != null) { hunterAlertTask.cancel(); hunterAlertTask = null; }
        if (followTpTask != null) { followTpTask.cancel(); followTpTask = null; }
        lastFollowTp.clear();

        teams.clear();
        playerColor.clear();

        if (resetBorder) borderController.resetOnGameStop(null);
        broadcast(ChatColor.RED + "[TailTag] 게임 종료!");
    }

    private void resetMaxHealth(Collection<? extends Player> players) {
        final double defaultMax = 20.0;
        for (Player p : players) {
            if (p == null || !p.isOnline()) continue;
            try {
                AttributeInstance attr = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (attr != null) {
                    attr.setBaseValue(defaultMax);
                    if (p.getHealth() > defaultMax) p.setHealth(defaultMax);
                }
                p.setAbsorptionAmount(0.0);
                p.removePotionEffect(PotionEffectType.WEAKNESS);
            } catch (Throwable ignored) {}
        }
    }

    public GameColor getTeamColor(Player p) { return p == null ? null : playerColor.get(p.getUniqueId()); }
    private Team getTeam(GameColor color) { return color == null ? null : teams.get(color); }
    public Player getLeader(GameColor color) {
        Team t = getTeam(color);
        if (t == null || t.leader == null) return null;
        return Bukkit.getPlayer(t.leader);
    }
    public boolean isSlave(Player p) {
        GameColor c = getTeamColor(p);
        Team t = getTeam(c);
        if (t == null) return false;
        UUID id = p.getUniqueId();
        return t.members.contains(id) && !id.equals(t.leader);
    }

    public List<GameColor> getAliveColorsInOrder() {
        List<GameColor> list = new ArrayList<>();
        for (GameColor c : GameColor.values()) {
            Team t = teams.get(c);
            if (t != null && t.isAlive()) list.add(c);
        }
        return list;
    }
    private GameColor leftAliveOf(GameColor my) {
        List<GameColor> alive = getAliveColorsInOrder();
        int idx = alive.indexOf(my);
        if (idx < 0 || alive.size() < 2) return null;
        return alive.get((idx - 1 + alive.size()) % alive.size());
    }
    private GameColor rightAliveOf(GameColor my) {
        List<GameColor> alive = getAliveColorsInOrder();
        int idx = alive.indexOf(my);
        if (idx < 0 || alive.size() < 2) return null;
        return alive.get((idx + 1) % alive.size());
    }
    public Player getTarget(Player p) { GameColor my = getTeamColor(p); return my == null ? null : getLeader(leftAliveOf(my)); }
    public Player getHunter(Player p) { GameColor my = getTeamColor(p); return my == null ? null : getLeader(rightAliveOf(my)); }

    public void startHunterAlertTask() {
        if (hunterAlertTask != null) hunterAlertTask.cancel();
        if (!plugin.getConfig().getBoolean("hunter_alert.enabled", true)) return;

        long period = plugin.getConfig().getLong("hunter_alert.interval_ticks", 40);
        double radius = plugin.getConfig().getDouble("hunter_alert.radius", 25.0);
        String soundName = plugin.getConfig().getString("hunter_alert.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
        float volume = (float) plugin.getConfig().getDouble("hunter_alert.volume", 0.6);
        float pitch = (float) plugin.getConfig().getDouble("hunter_alert.pitch", 1.3);
        org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName);

        hunterAlertTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running) return;
            for (Player target : getParticipants()) {
                Player hunter = getHunter(target);
                if (hunter == null) continue;
                if (!hunter.getWorld().equals(target.getWorld())) continue;
                double d = hunter.getLocation().distance(target.getLocation());
                if (d <= radius) {
                    String msg = ChatColor.RED + "헌터가 근처에 있습니다! (" + String.format(java.util.Locale.ROOT, "%.1f", d) + "m)";
                    target.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg));
                    target.playSound(target.getLocation(), sound, volume, pitch);
                }
            }
        }, period, period);
    }

    public void handleSuccessfulHunt(Player killer, Player victimLeader) {
        if (killer == null || victimLeader == null) return;
        GameColor killerColor = getTeamColor(killer);
        GameColor victimColor = getTeamColor(victimLeader);
        if (killerColor == null || victimColor == null) return;

        Team vTeam = getTeam(victimColor);
        if (vTeam == null || vTeam.leader == null || !vTeam.leader.equals(victimLeader.getUniqueId())) return;
        GameColor expectedHunterColor = rightAliveOf(victimColor);
        if (expectedHunterColor == null || expectedHunterColor != killerColor) return;

        Player hunterLeader = getLeader(killerColor);
        if (hunterLeader != null) {
            AttributeInstance maxHealth = hunterLeader.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (maxHealth != null) {
                double newMax = Math.max(2.0, maxHealth.getBaseValue() - 2.0);
                maxHealth.setBaseValue(newMax);
                if (hunterLeader.getHealth() > newMax) hunterLeader.setHealth(newMax);
            }
        }

        Team kTeam = getTeam(killerColor);
        if (kTeam != null) {
            LinkedHashSet<UUID> moving = new LinkedHashSet<>(vTeam.members);
            for (UUID id : moving) {
                vTeam.members.remove(id);
                kTeam.members.add(id);
                playerColor.put(id, killerColor);
                Player moved = Bukkit.getPlayer(id);
                if (moved != null) {
                    applySlaveAttributes(moved);
                    visuals.assignPlayerToColor(moved, killerColor);
                    moved.sendMessage(ChatColor.GRAY + "이제 " + killerColor.chat + killerColor.displayKorean + ChatColor.GRAY + " 팀의 추종자가 되었습니다. (최대체력 5칸, 나약함 II)");
                }
            }
        }

        vTeam.leader = null;
        vTeam.members.clear();
        teams.remove(victimColor);

        broadcast(ChatColor.GOLD + "[" + killerColor.chat + killerColor.displayKorean + ChatColor.GOLD + "] 팀이 "
                + victimColor.chat + victimColor.displayKorean + ChatColor.GOLD + " 팀의 리더를 처치하여 흡수했습니다!");

        List<GameColor> alive = getAliveColorsInOrder();
        if (alive.size() == 1) {
            GameColor winner = alive.get(0);
            Team wt = teams.get(winner);
            String names = wt.members.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).map(Player::getName).collect(Collectors.joining(", "));
            broadcast(ChatColor.AQUA + "우승: " + winner.chat + winner.displayKorean + ChatColor.AQUA + " 팀! (" + names + ")");
            stopGame(true);
        }
    }

    public void applySlaveAttributes(Player p) {
        if (p == null) return;
        try {
            AttributeInstance attr = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) {
                attr.setBaseValue(10.0);
                if (p.getHealth() > 10.0) p.setHealth(10.0);
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 9999, 1, true, false, false));
        } catch (Throwable ignored) {}
    }

    private void startFollowTeleportTask() {
        if (!plugin.getConfig().getBoolean("slave.follow_teleport.enabled", true)) return;

        long period = plugin.getConfig().getLong("slave.follow_teleport.check_interval_ticks", 20L);
        double maxDist = plugin.getConfig().getDouble("slave.follow_teleport.distance", 25.0);
        long cooldownMs = plugin.getConfig().getLong("slave.follow_teleport.cooldown_seconds", 5L) * 1000L;

        if (followTpTask != null) followTpTask.cancel();

        followTpTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running) return;
            long now = System.currentTimeMillis();

            for (Team t : teams.values()) {
                if (t == null || t.leader == null) continue;
                Player leader = Bukkit.getPlayer(t.leader);
                if (leader == null || !leader.isOnline()) continue;
                if (isFrozen(leader)) continue;

                for (UUID id : t.members) {
                    if (id.equals(t.leader)) continue;
                    Player m = Bukkit.getPlayer(id);
                    if (m == null || !m.isOnline()) continue;
                    if (isFrozen(m)) continue;

                    boolean sameWorld = leader.getWorld().equals(m.getWorld());
                    double dist;
                    try {
                        dist = sameWorld ? leader.getLocation().distance(m.getLocation()) : Double.MAX_VALUE;
                    } catch (IllegalArgumentException ex) {
                        dist = Double.MAX_VALUE;
                    }

                    if (!sameWorld || dist >= maxDist) {
                        long last = lastFollowTp.getOrDefault(id, 0L);
                        if (now - last < cooldownMs) continue;

                        Location to = leader.getLocation().clone();
                        m.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
                        m.setFallDistance(0f);
                        m.setNoDamageTicks(Math.max(m.getNoDamageTicks(), 20));
                        lastFollowTp.put(id, now);
                    }
                }
            }
        }, period, period);
    }

    public void freezeOnSelfDeath(Player p, Location deathLoc) {
        if (!plugin.getConfig().getBoolean("freeze_on_self_death.enabled", true)) return;
        int seconds = plugin.getConfig().getInt("freeze_on_self_death.duration_seconds", 120);
        long until = Instant.now().toEpochMilli() + seconds * 1000L;
        frozen.put(p.getUniqueId(), new FrozenState(deathLoc, until));

        applyFreezeEffects(p);

        UUID uid = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player pl = Bukkit.getPlayer(uid);
            if (pl != null) {
                FrozenState st = frozen.get(uid);
                if (st != null) {
                    unfreeze(pl);
                    pl.sendMessage(ChatColor.GREEN + "자연사 페널티가 해제되었습니다.");
                }
            }
        }, seconds * 20L);
    }

    public boolean isFrozen(Player p) {
        FrozenState st = frozen.get(p.getUniqueId());
        if (st == null) return false;
        if (Instant.now().toEpochMilli() >= st.untilEpochMs) {
            unfreeze(p);
            return false;
        }
        return true;
    }

    public void applyFreezeEffects(Player p) {
        if (p == null) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 20 * 9999, 10, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 20 * 9999, 200, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 9999, 2, true, false, false)); // 얼림용 나약함 III
        p.setGlowing(true);
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 9999, 0, true, false, false));
        p.setInvulnerable(true);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 5, 0, true, false, false));
    }

    public void tickFrozenActionBar(Player p) {
        FrozenState st = frozen.get(p.getUniqueId());
        if (st == null) return;
        long remainMs = Math.max(0, st.untilEpochMs - Instant.now().toEpochMilli());
        long sec = (remainMs + 999) / 1000;
        String msg = ChatColor.AQUA + "자연사 페널티(무적·발광): " + ChatColor.YELLOW + sec + "초" + ChatColor.GRAY + " 남음";
        p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg));
    }

    public void teleportToFreezeAnchor(Player p) {
        FrozenState st = frozen.get(p.getUniqueId());
        if (st == null) return;
        Location loc = st.anchor.clone();
        loc.setY(Math.max(1, loc.getBlockY() + 1));
        p.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0f);
        p.setNoDamageTicks(Math.max(p.getNoDamageTicks(), 40));
    }

    public void unfreeze(Player p) {
        if (p == null) return;
        frozen.remove(p.getUniqueId());

        // 모든 얼림 효과 제거(나약함 포함)
        p.removePotionEffect(PotionEffectType.SLOW);
        p.removePotionEffect(PotionEffectType.JUMP);
        p.removePotionEffect(PotionEffectType.WEAKNESS);       // 핵심: 얼림 종료 시 나약함 III 제거
        p.removePotionEffect(PotionEffectType.SLOW_FALLING);
        p.removePotionEffect(PotionEffectType.GLOWING);
        p.setGlowing(false);
        p.setInvulnerable(false);

        // 노예는 상시 나약함 II 재적용
        if (isSlave(p)) {
            try {
                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 9999, 1, true, false, false)); // II
            } catch (Throwable ignored) {}
        }
    }

    // 월드보더 밖 lethal 처리에서 사용하는 안전 중앙 계산/스냅핑
    public void applyWorldBorderPenalty(Player p) {
        if (p == null) return;
        if (!plugin.getConfig().getBoolean("worldborder.outside_penalty.enabled", true)) return;

        int seconds = plugin.getConfig().getInt("worldborder.outside_penalty.duration_seconds", 120);
        Location centerSafe = getWorldBorderSafeCenter(p.getWorld());

        long until = Instant.now().toEpochMilli() + seconds * 1000L;
        frozen.put(p.getUniqueId(), new FrozenState(centerSafe, until));

        p.teleport(centerSafe, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0f);
        applyFreezeEffects(p);

        p.sendMessage(ChatColor.YELLOW + "월드보더 밖으로 나가 위험해졌습니다. 중앙으로 이동되며 "
                + ChatColor.AQUA + seconds + "초" + ChatColor.YELLOW + " 동안 활동이 제한됩니다.");

        UUID uid = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player pl = Bukkit.getPlayer(uid);
            if (pl != null) {
                FrozenState st = frozen.get(uid);
                if (st != null) {
                    unfreeze(pl);
                    pl.sendMessage(ChatColor.GREEN + "월드보더 페널티가 해제되었습니다.");
                }
            }
        }, seconds * 20L);
    }

    private Location getWorldBorderSafeCenter(org.bukkit.World world) {
        org.bukkit.WorldBorder wb = world.getWorldBorder();
        Location c = wb.getCenter().clone();
        Location safe = snapToSafe(world, c);
        return safe != null ? safe : world.getSpawnLocation();
    }

    private Location snapToSafe(org.bukkit.World world, Location approx) {
        int bx = approx.getBlockX();
        int bz = approx.getBlockZ();

        org.bukkit.block.Block top = world.getHighestBlockAt(bx, bz);
        int y = top.getY();

        for (int dy = 0; dy < 6; dy++) {
            int yy = y + dy;
            org.bukkit.block.Block feet = world.getBlockAt(bx, yy, bz);
            org.bukkit.block.Block head = world.getBlockAt(bx, yy + 1, bz);
            org.bukkit.block.Block below = world.getBlockAt(bx, yy - 1, bz);
            if (isAirLike(feet.getType()) && isAirLike(head.getType()) && isSafeGround(below.getType())) {
                return new Location(world, bx + 0.5, yy, bz + 0.5, approx.getYaw(), approx.getPitch());
            }
        }
        for (int dy = 1; dy <= 8; dy++) {
            int yy = y - dy;
            if (yy <= world.getMinHeight() + 2) break;
            org.bukkit.block.Block feet = world.getBlockAt(bx, yy, bz);
            org.bukkit.block.Block head = world.getBlockAt(bx, yy + 1, bz);
            org.bukkit.block.Block below = world.getBlockAt(bx, yy - 1, bz);
            if (isAirLike(feet.getType()) && isAirLike(head.getType()) && isSafeGround(below.getType())) {
                return new Location(world, bx + 0.5, yy, bz + 0.5, approx.getYaw(), approx.getPitch());
            }
        }
        return null;
    }

    private boolean isAirLike(org.bukkit.Material m) {
        return m.isAir() || m == org.bukkit.Material.CAVE_AIR || m == org.bukkit.Material.VOID_AIR;
    }

    private boolean isSafeGround(org.bukkit.Material m) {
        switch (m) {
            case LAVA:
            case MAGMA_BLOCK:
            case CAMPFIRE:
            case FIRE:
            case SOUL_FIRE:
            case CACTUS:
            case SWEET_BERRY_BUSH:
            case POWDER_SNOW:
            case WATER:
                return false;
            default:
                return m.isSolid();
        }
    }
}