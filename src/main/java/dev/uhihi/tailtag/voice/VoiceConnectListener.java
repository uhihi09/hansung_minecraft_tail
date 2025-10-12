package dev.uhihi.tailtag.voice;

import dev.uhihi.tailtag.GameManager;
import dev.uhihi.tailtag.TailTagPlugin;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class VoiceConnectListener implements Listener {

    private final TailTagPlugin plugin;
    private final GameManager gameManager;

    public VoiceConnectListener(TailTagPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // 접속 2초 뒤 보이스 연결 안내
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendVoicePrompt(p), 40L);
    }

    public void sendVoicePrompt(Player p) {
        if (p == null || !p.isOnline()) return;

        TextComponent btn = new TextComponent(ChatColor.GREEN + "[보이스 연결]");
        // 기존 /audio 대신 우리 래퍼 커맨드 /voice 사용
        btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/voice"));
        btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(ChatColor.YELLOW + "클릭하여 보이스 채팅 링크를 받습니다").create()));

        TextComponent msg = new TextComponent(ChatColor.AQUA + "인게임 근접 보이스를 사용하려면 ");
        msg.addExtra(btn);
        msg.addExtra(new TextComponent(ChatColor.AQUA + " 을(를) 클릭하세요."));

        try {
            p.spigot().sendMessage(msg);
        } catch (Throwable ignored) {
            p.sendMessage(ChatColor.AQUA + "인게임 근접 보이스: /voice 를 입력하여 연결하세요.");
        }
    }
}