package dev.uhihi.tailtag.listeners;

import dev.uhihi.tailtag.world.WorldBorderController;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public class PortalClampListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getTo() == null) return;
        Location to = event.getTo();
        Location clamped = WorldBorderController.clampInsideBorder(to);
        if (!sameXZ(to, clamped)) {
            event.setTo(clamped);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalTeleport(PlayerTeleportEvent event) {
        // 혹시 다른 플러그인/상황에서 포탈 텔레포트가 발생한 경우 보조 보호
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL
                && event.getCause() != PlayerTeleportEvent.TeleportCause.END_GATEWAY) {
            return;
        }
        if (event.getTo() == null) return;
        Location to = event.getTo();
        Location clamped = WorldBorderController.clampInsideBorder(to);
        if (!sameXZ(to, clamped)) {
            event.setTo(clamped);
        }
    }

    private boolean sameXZ(Location a, Location b) {
        return a.getWorld() == b.getWorld()
                && Double.compare(a.getX(), b.getX()) == 0
                && Double.compare(a.getZ(), b.getZ()) == 0;
    }
}