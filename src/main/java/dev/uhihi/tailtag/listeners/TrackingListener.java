package dev.uhihi.tailtag.listeners;

import dev.uhihi.tailtag.GameManager;
import dev.uhihi.tailtag.TailTagPlugin;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class TrackingListener implements Listener {

    private final TailTagPlugin plugin;
    private final GameManager gameManager;
    private final Set<UUID> trackingNow = Collections.synchronizedSet(new HashSet<>());

    public TrackingListener(TailTagPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    @EventHandler
    public void onDiamondRightClick(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null) return;

        String matName = plugin.getConfig().getString("tracking.cost_item", "DIAMOND");
        Material costMat = Material.matchMaterial(matName);
        if (costMat == null) costMat = Material.DIAMOND;

        if (item.getType() != costMat) return;

        if (!gameManager.isRunning() || !gameManager.isInGame(player)) {
            player.sendMessage(ChatColor.RED + "게임 중에만 사용할 수 있습니다.");
            return;
        }
        Player target = gameManager.getTarget(player);
        if (target == null || !target.isOnline()) {
            player.sendMessage(ChatColor.RED + "타겟이 없습니다.");
            return;
        }

        if (trackingNow.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "이미 추적 중입니다.");
            return;
        }

        // 비용 1개 소모
        if (!removeOne(player, costMat)) {
            player.sendMessage(ChatColor.RED + costMat.name() + "가 부족합니다.");
            return;
        }

        startTrackingEffect(player, target);
    }

    private boolean removeOne(Player p, Material mat) {
        Map<Integer, ? extends ItemStack> all = p.getInventory().all(mat);
        for (Map.Entry<Integer, ? extends ItemStack> e : all.entrySet()) {
            ItemStack stack = e.getValue();
            if (stack.getAmount() > 1) {
                stack.setAmount(stack.getAmount() - 1);
            } else {
                p.getInventory().setItem(e.getKey(), null);
            }
            p.updateInventory();
            return true;
        }
        return false;
    }

    private void startTrackingEffect(Player hunter, Player target) {
        int seconds = plugin.getConfig().getInt("tracking.duration_seconds", 10);
        int interval = plugin.getConfig().getInt("tracking.interval_ticks", 5);
        String hex = plugin.getConfig().getString("tracking.particle_color", "#00FFAA");
        Color color = parseHexColor(hex, Color.AQUA);
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);

        UUID uid = hunter.getUniqueId();
        trackingNow.add(uid);

        hunter.sendMessage(ChatColor.AQUA + "추적 시작! " + seconds + "초 동안 방향 이펙트가 표시됩니다.");

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!hunter.isOnline() || !target.isOnline() || !gameManager.isInGame(hunter) || !gameManager.isRunning()) {
                return;
            }
            Player currentTarget = gameManager.getTarget(hunter);
            if (currentTarget == null || !currentTarget.getUniqueId().equals(target.getUniqueId())) {
                return; // 타겟 변경 시 중단
            }

            Location eye = hunter.getEyeLocation();
            Location teye = target.getEyeLocation();
            Vector dir = teye.toVector().subtract(eye.toVector());
            double dist = Math.max(1.0, dir.length());
            dir.normalize();

            int steps = 12;
            double stepLen = Math.min(20.0, dist) / steps;

            for (int i = 1; i <= steps; i++) {
                Location pLoc = eye.clone().add(dir.clone().multiply(stepLen * i));
                // 수정: 1.20.4에서는 REDSTONE 파티클로 색상 DustOptions 사용
                hunter.spawnParticle(Particle.REDSTONE, pLoc, 2, 0.03, 0.03, 0.03, 0, dust);
            }
            hunter.playSound(hunter.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
        }, 0L, interval);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            task.cancel();
            trackingNow.remove(uid);
            hunter.sendMessage(ChatColor.GRAY + "추적 종료.");
        }, seconds * 20L);
    }

    private Color parseHexColor(String hex, Color def) {
        try {
            String s = hex.startsWith("#") ? hex.substring(1) : hex;
            int r = Integer.parseInt(s.substring(0, 2), 16);
            int g = Integer.parseInt(s.substring(2, 4), 16);
            int b = Integer.parseInt(s.substring(4, 6), 16);
            return Color.fromRGB(r, g, b);
        } catch (Exception e) {
            return def;
        }
    }
}