package dev.uhihi.tailtag;

import dev.uhihi.tailtag.commands.TailCommand;
import dev.uhihi.tailtag.commands.VoiceCommand;
import dev.uhihi.tailtag.listeners.GameListener;
import dev.uhihi.tailtag.listeners.PortalClampListener;
import dev.uhihi.tailtag.voice.VoiceConnectListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class TailTagPlugin extends JavaPlugin {

    private GameManager gameManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.gameManager = new GameManager(this);

        // /tail
        TailCommand tailCmd = new TailCommand(this, gameManager);
        PluginCommand tail = Objects.requireNonNull(getCommand("tail"),
                "plugin.yml에 'tail' 커맨드가 없습니다 (src/main/resources/plugin.yml 확인)");
        tail.setExecutor(tailCmd);
        tail.setTabCompleter(tailCmd); // TailCommand가 TabExecutor이므로 OK

        // /voice (OpenAudioMc 래퍼)
        PluginCommand voice = Objects.requireNonNull(getCommand("voice"),
                "plugin.yml에 'voice' 커맨드가 없습니다");
        voice.setExecutor(new dev.uhihi.tailtag.commands.VoiceCommand());

        // 리스너
        Bukkit.getPluginManager().registerEvents(new GameListener(this, gameManager), this);
        Bukkit.getPluginManager().registerEvents(new PortalClampListener(), this);
        Bukkit.getPluginManager().registerEvents(new VoiceConnectListener(this, gameManager), this);

        getLogger().info("TailTagPlugin enabled.");
    }

    @Override
    public void onDisable() {
        if (gameManager != null && gameManager.isRunning()) {
            gameManager.stopGame(true);
        }
    }

    public GameManager getGameManager() {
        return gameManager;
    }
}