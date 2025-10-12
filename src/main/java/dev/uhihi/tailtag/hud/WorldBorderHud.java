package dev.uhihi.tailtag.hud;

import dev.uhihi.tailtag.GameManager;
import dev.uhihi.tailtag.TailTagPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class WorldBorderHud {

    private final TailTagPlugin plugin;
    private final GameManager gameManager;

    private BossBar bar;
    private BukkitTask task;

    private double initialSize;
    private double finalSize;
    private long shrinkAfterMs;
    private long shrinkDurationMs;

    private long startEpochMs;
    private boolean announcedLastMinute;

    public WorldBorderHud(TailTagPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("worldborder.hud.enabled", true)) return;
        stop(); // 중복 방지

        this.initialSize = plugin.getConfig().getDouble("worldborder.initial_size", 800.0);
        this.finalSize = plugin.getConfig().getDouble("worldborder.final_size", 50.0);
        this.shrinkAfterMs = plugin.getConfig().getLong("worldborder.shrink_after_seconds", 600) * 1000L;
        this.shrinkDurationMs = plugin.getConfig().getLong("worldborder.shrink_duration_seconds", 600) * 1000L;
        this.startEpochMs = System.currentTimeMillis();
        this.announcedLastMinute = false;

        this.bar = Bukkit.createBossBar("월드보더 HUD", BarColor.BLUE, BarStyle.SEGMENTED_10);
        this.bar.setVisible(true);  // 가시성 보장
        this.bar.setProgress(0.01); // 0%일 때 거의 안 보이는 문제 보정

        // 참가자 동기화
        syncParticipants();

        long period = plugin.getConfig().getLong("worldborder.hud.update_interval_ticks", 20L);
        if (period < 1) period = 20L;

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!gameManager.isRunning()) {
                stop();
                return;
            }

            // 참가자 기준 월드 선택
            List<Player> participants = new ArrayList<>(gameManager.getParticipants());
            if (participants.isEmpty()) {
                // 혹시 참가자 추적이 비어 있으면 온라인 중 게임 참여자 재추가 시도
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (gameManager.isInGame(p)) participants.add(p);
                }
                if (participants.isEmpty()) return;
            }
            World world = participants.get(0).getWorld();
            double currentSize = world.getWorldBorder().getSize();

            long now = System.currentTimeMillis();
            long shrinkStart = startEpochMs + shrinkAfterMs;
            long shrinkEnd = shrinkStart + shrinkDurationMs;

            double progress;
            if (now <= shrinkStart) progress = 0.0;
            else if (now >= shrinkEnd || shrinkDurationMs <= 0) progress = 1.0;
            else progress = (now - shrinkStart) / (double) shrinkDurationMs;

            // BossBar 진행도 최소 1% 보정(시인성)
            double barProgress = Math.max(0.01, Math.min(1.0, progress));

            long remainMs = Math.max(0L, shrinkEnd - now);
            String remainStr = formatTime(remainMs);

            String title = String.format(java.util.Locale.ROOT,
                    "월드보더 %.0f → %.0f | 남은 시간 %s | 현재 크기 %.0f",
                    initialSize, finalSize, remainStr, currentSize);
            bar.setTitle(title);

            double remainingRatio = 1.0 - progress;
            BarColor color = remainingRatio > 0.60 ? BarColor.BLUE :
                    remainingRatio > 0.20 ? BarColor.YELLOW : BarColor.RED;
            if (bar.getColor() != color) bar.setColor(color);

            bar.setProgress(barProgress);

            int lastSec = plugin.getConfig().getInt("worldborder.hud.last_minute_seconds", 60);
            if (!announcedLastMinute && remainMs <= lastSec * 1000L) {
                announcedLastMinute = true;
                announceLastMinute(participants);
            }

            syncParticipants();

        }, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bar != null) {
            for (Player p : new ArrayList<>(bar.getPlayers())) {
                bar.removePlayer(p);
            }
            bar.setVisible(false);
            bar = null;
        }
    }

    private void syncParticipants() {
        if (bar == null) return;
        Set<Player> shouldHave = new HashSet<>(gameManager.getParticipants());
        // 비상: getParticipants가 비어 있으면 온라인 중 참여자라도 추가
        if (shouldHave.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (gameManager.isInGame(p)) shouldHave.add(p);
            }
        }

        // 추가
        for (Player p : shouldHave) {
            if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
        }
        // 제거
        for (Player p : new ArrayList<>(bar.getPlayers())) {
            if (!shouldHave.contains(p)) bar.removePlayer(p);
        }
    }

    private void announceLastMinute(List<Player> players) {
        String main = colorize(plugin.getConfig().getString("worldborder.hud.title_main", "&c마지막 1분!"));
        String sub = colorize(plugin.getConfig().getString("worldborder.hud.title_sub", "&e보더가 곧 최종 크기에 도달합니다."));
        String soundName = plugin.getConfig().getString("worldborder.hud.sound", "BLOCK_NOTE_BLOCK_PLING");
        float volume = (float) plugin.getConfig().getDouble("worldborder.hud.volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("worldborder.hud.pitch", 1.2);

        org.bukkit.Sound sound;
        try {
            sound = org.bukkit.Sound.valueOf(soundName);
        } catch (IllegalArgumentException e) {
            sound = org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING;
        }

        for (Player p : players) {
            if (p == null) continue;
            p.sendTitle(main, sub, 10, 40, 10);
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    private String formatTime(long ms) {
        long totalSec = (ms + 999) / 1000;
        long mm = totalSec / 60;
        long ss = totalSec % 60;
        return String.format(java.util.Locale.ROOT, "%d:%02d", mm, ss);
    }

    private String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}