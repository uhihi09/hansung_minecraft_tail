package dev.uhihi.tailtag.commands;

import dev.uhihi.tailtag.GameManager;
import dev.uhihi.tailtag.TailTagPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

public class ForceJoinAllCommand implements CommandExecutor {

    private final TailTagPlugin plugin;

    public ForceJoinAllCommand(TailTagPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        GameManager gm = plugin.getGameManager();
        List<String> allowed = plugin.getConfig().getStringList("force.allowed_names");

        boolean allowedSender =
                !(sender instanceof Player)
                        || (sender instanceof Player && ((Player) sender).isOp())
                        || sender.hasPermission("tailtag.force")
                        || allowed.stream().anyMatch(n -> n != null && n.equalsIgnoreCase(sender.getName()))
                        || "uhihi0729".equalsIgnoreCase(sender.getName());

        if (!allowedSender) {
            sender.sendMessage(ChatColor.RED + "이 명령을 사용할 권한이 없습니다.");
            return true;
        }

        int joined = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!gm.isInGame(p)) {
                gm.join(p);
                joined++;
            }
        }
        sender.sendMessage(ChatColor.GREEN + "모든 온라인 플레이어를 게임에 참가시켰습니다. (" + joined + "명)");
        return true;
    }
}