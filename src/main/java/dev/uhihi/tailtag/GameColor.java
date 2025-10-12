package dev.uhihi.tailtag;

import org.bukkit.ChatColor;

public enum GameColor {
    RED("빨강", ChatColor.RED),
    ORANGE("주황", ChatColor.GOLD),
    YELLOW("노랑", ChatColor.YELLOW),
    GREEN("초록", ChatColor.GREEN),
    BLUE("파랑", ChatColor.BLUE),
    NAVY("남색", ChatColor.DARK_BLUE),
    PURPLE("보라", ChatColor.DARK_PURPLE),
    PINK("핑크", ChatColor.LIGHT_PURPLE); // 9번째 색 추가

    public final String displayKorean;
    public final ChatColor chat;

    GameColor(String displayKorean, ChatColor chat) {
        this.displayKorean = displayKorean;
        this.chat = chat;
    }
}