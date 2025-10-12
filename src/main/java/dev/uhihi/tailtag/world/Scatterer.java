package dev.uhihi.tailtag.world;

import dev.uhihi.tailtag.TailTagPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class Scatterer {

    private final TailTagPlugin plugin;
    private final Random random = new Random();

    public Scatterer(TailTagPlugin plugin) {
        this.plugin = plugin;
    }

    public void scatterPlayers(Collection<? extends Player> players) {
        if (players == null || players.isEmpty()) return;
        if (!plugin.getConfig().getBoolean("scatter.enabled", true)) return;

        // 보더 설정이 먼저 적용되도록 1틱 뒤 실행
        Bukkit.getScheduler().runTask(plugin, () -> {
            World world = players.stream().findFirst().map(Player::getWorld).orElse(Bukkit.getWorlds().get(0));
            WorldBorder wb = world.getWorldBorder();
            Location center = wb.getCenter();
            double half = wb.getSize() / 2.0;

            double margin = plugin.getConfig().getDouble("scatter.border_margin", 10.0);
            double usableRadius = Math.max(half - margin, 5.0);

            double minDist = plugin.getConfig().getDouble("scatter.min_distance_between_players", 20.0);
            int attemptsPerPlayer = plugin.getConfig().getInt("scatter.attempts_per_player", 80);
            int slowFallingSec = plugin.getConfig().getInt("scatter.slow_falling_seconds", 5);

            List<Location> chosen = new ArrayList<>();

            for (Player p : players) {
                Location loc = findSafeLocation(world, center, usableRadius, minDist, chosen, attemptsPerPlayer);
                if (loc == null) {
                    // 실패 시 중심 근처 폴백
                    loc = center.clone().add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
                    loc = snapToSafe(world, loc);
                }

                world.getChunkAt(loc); // 동기 로드
                safeTeleport(p, loc, slowFallingSec);
                chosen.add(loc);
            }
        });
    }

    private Location findSafeLocation(World world, Location center, double radius, double minDist, List<Location> chosen, int attempts) {
        for (int i = 0; i < attempts; i++) {
            double theta = random.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(random.nextDouble()) * radius;

            double x = center.getX() + r * Math.cos(theta);
            double z = center.getZ() + r * Math.sin(theta);

            Location base = new Location(world, x, world.getSpawnLocation().getY(), z);
            Location safe = snapToSafe(world, base);
            if (safe == null) continue;

            boolean farEnough = true;
            for (Location prev : chosen) {
                if (prev.getWorld() == safe.getWorld() && prev.distanceSquared(safe) < (minDist * minDist)) {
                    farEnough = false;
                    break;
                }
            }
            if (!farEnough) continue;

            return safe;
        }
        return null;
    }

    // 해당 XZ의 가장 높은 안전한 Y를 찾아 플레이어가 설 수 있는 위치 반환
    private Location snapToSafe(World world, Location approx) {
        int bx = approx.getBlockX();
        int bz = approx.getBlockZ();

        Block top = world.getHighestBlockAt(bx, bz);
        int y = top.getY();

        // 위로 조금, 아래로 조금 탐색
        for (int dy = 0; dy < 6; dy++) {
            int yy = y + dy;
            Block feet = world.getBlockAt(bx, yy, bz);
            Block head = world.getBlockAt(bx, yy + 1, bz);
            Block below = world.getBlockAt(bx, yy - 1, bz);
            if (isAirLike(feet.getType()) && isAirLike(head.getType()) && isSafeGround(below.getType())) {
                return new Location(world, bx + 0.5, yy, bz + 0.5, approx.getYaw(), approx.getPitch());
            }
        }
        for (int dy = 1; dy <= 8; dy++) {
            int yy = y - dy;
            if (yy <= world.getMinHeight() + 2) break;
            Block feet = world.getBlockAt(bx, yy, bz);
            Block head = world.getBlockAt(bx, yy + 1, bz);
            Block below = world.getBlockAt(bx, yy - 1, bz);
            if (isAirLike(feet.getType()) && isAirLike(head.getType()) && isSafeGround(below.getType())) {
                return new Location(world, bx + 0.5, yy, bz + 0.5, approx.getYaw(), approx.getPitch());
            }
        }
        return null;
    }

    private boolean isAirLike(Material m) {
        return m.isAir() || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    private boolean isSafeGround(Material m) {
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

    private void safeTeleport(Player p, Location loc, int slowFallingSec) {
        p.teleport(loc);
        p.setFallDistance(0f);
        p.setNoDamageTicks(Math.max(p.getNoDamageTicks(), 20 * 2));
        if (slowFallingSec > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, slowFallingSec * 20, 0, true, false, false));
        }
    }
}