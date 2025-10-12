package dev.uhihi.tailtag.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class VoiceCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "플레이어만 사용할 수 있는 명령입니다.");
            return true;
        }
        Player p = (Player) sender;

        // OpenAudioMc 설치 여부 확인
        boolean hasOpenAudio = Bukkit.getPluginManager().getPlugin("OpenAudioMc") != null
                && Bukkit.getPluginManager().isPluginEnabled("OpenAudioMc");

        if (!hasOpenAudio) {
            p.sendMessage(ChatColor.YELLOW + "[Voice] 서버에 OpenAudioMc 플러그인이 설치되어 있지 않습니다.");
            p.sendMessage(ChatColor.GRAY + "관리자에게 문의하거나, 플러그인 설치 후 다시 시도하세요. (/audio)");
            return true;
        }

        // OpenAudioMc가 있으면 /audio 대리 실행
        Bukkit.dispatchCommand(p, "audio");
        return true;
    }
}