package dev.uhihi.tailtag.listeners;

import dev.uhihi.tailtag.GameManager;
import dev.uhihi.tailtag.TailTagPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.projectiles.ProjectileSource;

public class GameListener implements Listener {

    private final TailTagPlugin plugin;
    private final GameManager gameManager;

    public GameListener(TailTagPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    // 월드보더 치명 피해는 사망 취소 + 중앙 TP + 2분 페널티(아이템 유지)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalWorldBorder(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        if (!gameManager.isRunning() || !gameManager.isInGame(p)) return;

        if (event.getCause() == EntityDamageEvent.DamageCause.WORLD_BORDER) {
            if (event.getFinalDamage() >= p.getHealth()) {
                event.setCancelled(true);
                gameManager.applyWorldBorderPenalty(p);
            }
        }
    }

    // 리더 처치 성공/자연사 처리
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!gameManager.isRunning() || !gameManager.isInGame(victim)) return;

        // 1) 플레이어에게 죽지 않은 "자연사"면 인벤/레벨 유지(용암 포함)
        Player killer = victim.getKiller();
        if (killer == null || !gameManager.isInGame(killer)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }

        // 2) WORLD_BORDER로 실제 사망까지 간 경우도 안전망
        if (victim.getLastDamageCause() != null
                && victim.getLastDamageCause().getCause() == EntityDamageEvent.DamageCause.WORLD_BORDER) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }

        // 3) 규칙 처리
        if (killer != null && gameManager.isInGame(killer)) {
            // 리더가 죽었고 가해자가 해당 리더 팀의 헌터 팀이면 팀 흡수
            if (victim.equals(gameManager.getLeader(gameManager.getTeamColor(victim)))) {
                gameManager.handleSuccessfulHunt(killer, victim);
            }
        } else {
            // 플레이어에게 죽지 않음 → 노예가 아니면 2분 얼림 페널티
            if (!gameManager.isSlave(victim)) {
                Location where = victim.getLocation();
                gameManager.freezeOnSelfDeath(victim, where);
                victim.sendMessage(ChatColor.YELLOW + "플레이어에게 죽지 않아 "
                        + ChatColor.AQUA + "2분 동안" + ChatColor.YELLOW + " 무적·발광 상태로 이동이 제한됩니다. (아이템 유지)");
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        if (!gameManager.isRunning() || !gameManager.isInGame(p)) return;

        // 노예는 리더 위치로 복귀
        if (gameManager.isSlave(p)) {
            Player leader = gameManager.getLeader(gameManager.getTeamColor(p));
            Location to = (leader != null && leader.isOnline())
                    ? leader.getLocation()
                    : p.getWorld().getSpawnLocation();
            plugin.getServer().getScheduler().runTask(plugin, () -> p.teleport(to));
        }

        // 얼림 상태면 위치/효과 재적용
        if (gameManager.isFrozen(p)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                gameManager.teleportToFreezeAnchor(p);
                gameManager.applyFreezeEffects(p);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (!gameManager.isFrozen(p)) return;

        if (event.getTo() != null &&
                (event.getTo().getX() != event.getFrom().getX()
                        || event.getTo().getZ() != event.getFrom().getZ())) {
            event.setTo(event.getFrom());
        } else {
            gameManager.tickFrozenActionBar(p);
        }
    }

    // 같은 팀 피해 무효 + "피해자 기준 헌터 팀"만 피해 허용
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageOnlyFromHunter(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player victim = (Player) event.getEntity();
        if (!gameManager.isRunning() || !gameManager.isInGame(victim)) return;

        // 가해자 플레이어 추출(직접/투사체)
        Player damager = null;
        if (event.getDamager() instanceof Player) {
            damager = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile) {
            Projectile proj = (Projectile) event.getDamager();
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player) {
                damager = (Player) src;
            }
        }
        if (damager == null) return;

        // 게임 외부 인원 → 차단
        if (!gameManager.isInGame(damager)) {
            event.setCancelled(true);
            return;
        }

        // 같은 팀끼리는 피해 무효
        if (gameManager.getTeamColor(damager) != null
                && gameManager.getTeamColor(damager) == gameManager.getTeamColor(victim)) {
            event.setCancelled(true);
            return;
        }

        // 자기 자신 공격은 허용
        if (damager.getUniqueId().equals(victim.getUniqueId())) return;

        // 피해자 기준 헌터팀 리더
        Player hunterLeader = gameManager.getHunter(victim);
        if (hunterLeader == null) return;

        // 가해자 팀과 헌터팀이 다르면 피해 취소
        if (gameManager.getTeamColor(damager) == null || gameManager.getTeamColor(hunterLeader) == null
                || gameManager.getTeamColor(damager) != gameManager.getTeamColor(hunterLeader)) {
            event.setCancelled(true);
            damager.sendMessage(ChatColor.RED + "당신은 이 플레이어의 헌터가 아니어서 피해를 줄 수 없습니다.");
        }
    }

    // 얼림(자연사 페널티) 중에는 모든 피해 무효
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageWhileFrozen(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        if (gameManager.isFrozen(p)) {
            event.setCancelled(true);
        }
    }

    // 노예의 '자연사'는 즉시 부활(죽음 취소) + 리더 위치로 이동
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalNaturalDamageForSlave(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        if (!gameManager.isRunning() || !gameManager.isInGame(p)) return;
        if (!gameManager.isSlave(p)) return;

        // WORLD_BORDER는 별도 처리
        if (event.getCause() == EntityDamageEvent.DamageCause.WORLD_BORDER) return;

        // PVP 여부 판정
        boolean killedByPlayer = false;
        if (event instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent ebe = (EntityDamageByEntityEvent) event;
            if (ebe.getDamager() instanceof Player) killedByPlayer = true;
            else if (ebe.getDamager() instanceof Projectile) {
                Projectile proj = (Projectile) ebe.getDamager();
                ProjectileSource src = proj.getShooter();
                if (src instanceof Player) killedByPlayer = true;
            }
        }

        if (!killedByPlayer && event.getFinalDamage() >= p.getHealth()) {
            // 치명적 자연 피해 → 사망 취소하고 즉시 리더에게 이동
            event.setCancelled(true);

            try {
                double targetMax = 10.0;
                if (p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null) {
                    p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).setBaseValue(targetMax);
                }
                if (p.getHealth() <= 1.0) {
                    p.setHealth(Math.min(targetMax, 4.0));
                }
            } catch (Throwable ignored) {}

            Player leader = gameManager.getLeader(gameManager.getTeamColor(p));
            Location to = (leader != null && leader.isOnline())
                    ? leader.getLocation()
                    : p.getWorld().getSpawnLocation();
            p.teleport(to);
            p.setFallDistance(0f);
            p.setFireTicks(0);
            p.setNoDamageTicks(Math.max(p.getNoDamageTicks(), 40));

            p.sendMessage(ChatColor.GRAY + "자연사로부터 즉시 부활하여 리더 위치로 이동했습니다.");
        }
    }

    // 재접속 시 닉네임 색/얼림 효과 재적용
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (gameManager.isRunning() && gameManager.isInGame(event.getPlayer())) {
            try {
                if (gameManager.getVisuals() != null) {
                    gameManager.getVisuals().assignPlayerToColor(event.getPlayer(), gameManager.getTeamColor(event.getPlayer()));
                }
            } catch (Throwable ignored) { }
        }

        if (gameManager.isFrozen(event.getPlayer())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                gameManager.applyFreezeEffects(event.getPlayer());
            });
        }
    }

    // 퀴트 시 얼림 상태 정리
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (gameManager.isFrozen(event.getPlayer())) {
            gameManager.unfreeze(event.getPlayer());
        }
    }
}