package dev.uhihi.tailtag.commands;

import dev.uhihi.tailtag.GameManager;
import dev.uhihi.tailtag.TailTagPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TailCommand implements TabExecutor {

    private final TailTagPlugin plugin;
    private final GameManager game;

    public TailCommand(TailTagPlugin plugin, GameManager game) {
        this.plugin = plugin;
        this.game = game;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return help(sender, label);

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help": return help(sender, label);
            case "join":
                if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "플레이어만 사용 가능."); return true; }
                game.join((Player) sender); return true;
            case "leave":
                if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "플레이어만 사용 가능."); return true; }
                game.leave((Player) sender); return true;
            case "start":
                if (!sender.hasPermission("tailtag.admin")) { sender.sendMessage(ChatColor.RED + "권한 없음(tailtag.admin)"); return true; }
                game.startGame(); return true;
            case "stop":
                if (!sender.hasPermission("tailtag.admin")) { sender.sendMessage(ChatColor.RED + "권한 없음(tailtag.admin)"); return true; }
                game.stopGame(true); return true;
            case "status":
                sender.sendMessage(ChatColor.AQUA + "[TailTag] 진행 중: " + game.isRunning());
                sender.sendMessage(ChatColor.AQUA + "[TailTag] 참가자 수: " + game.getParticipants().size());
                return true;
            default:
                return help(sender, label);
        }
    }

    private boolean help(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "==== TailTag ====");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " join" + ChatColor.GRAY + " - 게임 대기열 참가");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " leave" + ChatColor.GRAY + " - 대기열 나가기");
        if (sender.hasPermission("tailtag.admin")) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.GRAY + " - 게임 시작");
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " stop" + ChatColor.GRAY + " - 게임 종료");
        }
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " status" + ChatColor.GRAY + " - 진행 상태");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(Arrays.asList("help", "join", "leave", "status"));
            if (sender.hasPermission("tailtag.admin")) {
                base.add("start");
                base.add("stop");
            }
            String p = args[0].toLowerCase();
            base.removeIf(s -> !s.startsWith(p));
            return base;
        }
        return java.util.Collections.emptyList();
    }
}