package dev.uhihi.tailtag.world;

import dev.uhihi.tailtag.TailTagPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class WorldBorderController {

    private final TailTagPlugin plugin;

    // 월드별 축소 스케줄 태스크
    private final Map<String, BukkitTask> shrinkTasks = new HashMap<>();

    public WorldBorderController(TailTagPlugin plugin) {
        this.plugin = plugin;
    }

    // 게임 시작 시 모든 대상 월드에 보더 적용
    public void applyOnGameStart(Collection<? extends org.bukkit.entity.Player> participants) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("worldborder.enabled", true)) return;

        double initialSize = cfg.getDouble("worldborder.initial_size", 800.0);
        double finalSize = cfg.getDouble("worldborder.final_size", 50.0);
        long afterSec = cfg.getLong("worldborder.shrink_after_seconds", 600);
        long durationSec = cfg.getLong("worldborder.shrink_duration_seconds", 600);
        double damage = cfg.getDouble("worldborder.damage_amount", 1.0);
        double buffer = cfg.getDouble("worldborder.damage_buffer", 2.0);
        int warnDist = cfg.getInt("worldborder.warning_distance", 5);

        double centerX = cfg.getDouble("worldborder.center.x", 0.0);
        double centerZ = cfg.getDouble("worldborder.center.z", 0.0);

        List<World> targets = resolveTargetWorlds(cfg, participants);
        if (targets.isEmpty()) return;

        // 기존 축소 태스크 정리
        cancelAllShrinkTasks();

        for (World w : targets) {
            WorldBorder wb = w.getWorldBorder();
            wb.setCenter(centerX, centerZ);
            wb.setDamageAmount(damage);
            wb.setDamageBuffer(buffer);
            wb.setWarningDistance(warnDist);

            // 초기 크기 설정
            wb.setSize(initialSize);

            // 축소 예약
            if (durationSec > 0) {
                if (afterSec <= 0) {
                    // 즉시 축소 시작
                    wb.setSize(finalSize, durationSec);
                } else {
                    // afterSec 뒤에 축소 시작
                    BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        World world = w; // capture
                        try {
                            WorldBorder border = world.getWorldBorder();
                            border.setSize(finalSize, durationSec);
                        } catch (Throwable ignored) { }
                    }, afterSec * 20L);
                    shrinkTasks.put(w.getName(), task);
                }
            }
        }
    }

    // 게임 종료 시 보더 원복
    public void resetOnGameStop(Collection<? extends org.bukkit.entity.Player> participants) {
        cancelAllShrinkTasks();

        FileConfiguration cfg = plugin.getConfig();
        List<World> targets = resolveTargetWorlds(cfg, participants);
        if (targets.isEmpty()) return;

        boolean resetToDefault = cfg.getBoolean("worldborder.reset_to_default_on_stop", true);
        if (resetToDefault) {
            for (World w : targets) {
                try {
                    WorldBorder wb = w.getWorldBorder();
                    // 마인크래프트 기본값(사실상 무제한)
                    wb.setSize(6.0E7);
                    wb.setWarningDistance(5);
                    wb.setDamageAmount(0.2);
                    wb.setDamageBuffer(5.0);
                } catch (Throwable ignored) { }
            }
        } else {
            // 초기값으로만 되돌리고 싶은 경우 (원하면 옵션화)
            double initialSize = cfg.getDouble("worldborder.initial_size", 800.0);
            double centerX = cfg.getDouble("worldborder.center.x", 0.0);
            double centerZ = cfg.getDouble("worldborder.center.z", 0.0);
            for (World w : targets) {
                try {
                    WorldBorder wb = w.getWorldBorder();
                    wb.setCenter(centerX, centerZ);
                    wb.setSize(initialSize);
                } catch (Throwable ignored) { }
            }
        }
    }

    private void cancelAllShrinkTasks() {
        for (BukkitTask t : shrinkTasks.values()) {
            try { t.cancel(); } catch (Throwable ignored) { }
        }
        shrinkTasks.clear();
    }

    // 적용할 월드 해석: config의 worldborder.apply_worlds가 있으면 그대로 사용,
    // 없으면 로드된 월드 중 환경 필터(worldborder.apply_environments)를 사용
    private List<World> resolveTargetWorlds(FileConfiguration cfg, Collection<? extends org.bukkit.entity.Player> participants) {
        List<String> names = cfg.getStringList("worldborder.apply_worlds");
        Set<World> worlds = new LinkedHashSet<>();

        if (names != null && !names.isEmpty()) {
            for (String name : names) {
                World w = Bukkit.getWorld(name);
                if (w != null) worlds.add(w);
            }
        } else {
            // 환경 필터 사용
            List<String> envs = cfg.getStringList("worldborder.apply_environments");
            Set<World.Environment> allowed;
            if (envs == null || envs.isEmpty()) {
                allowed = EnumSet.of(World.Environment.NORMAL, World.Environment.NETHER, World.Environment.THE_END);
            } else {
                allowed = envs.stream()
                        .map(s -> {
                            try { return World.Environment.valueOf(s.toUpperCase(Locale.ROOT)); }
                            catch (IllegalArgumentException e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(World.Environment.class)));
            }

            // 참가자들이 있는 월드 + 로드된 월드 중 허용 환경
            for (World w : Bukkit.getWorlds()) {
                if (allowed.contains(w.getEnvironment())) worlds.add(w);
            }
        }
        return new ArrayList<>(worlds);
    }

    // 좌표를 해당 월드 보더 안으로 클램프(리스너에서 활용 가능)
    public static Location clampInsideBorder(Location loc) {
        if (loc == null || loc.getWorld() == null) return loc;
        WorldBorder wb = loc.getWorld().getWorldBorder();
        Location c = wb.getCenter();
        double half = wb.getSize() / 2.0;
        // 여유 마진 1블록
        double margin = 1.0;

        double minX = c.getX() - half + margin;
        double maxX = c.getX() + half - margin;
        double minZ = c.getZ() - half + margin;
        double maxZ = c.getZ() + half - margin;

        double x = Math.max(minX, Math.min(maxX, loc.getX()));
        double z = Math.max(minZ, Math.min(maxZ, loc.getZ()));

        if (x == loc.getX() && z == loc.getZ()) return loc;
        Location clamped = loc.clone();
        clamped.setX(x);
        clamped.setZ(z);
        return clamped;
    }
}