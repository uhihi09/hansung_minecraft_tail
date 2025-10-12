package dev.uhihi.tailtag.visual;

import dev.uhihi.tailtag.GameColor;
import dev.uhihi.tailtag.GameManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public class TeamVisuals {

    private static final String TEAM_PREFIX = "TT_"; // 스코어보드 팀 이름 접두사(플러그인 전용)

    private final GameManager gameManager;
    private final Map<GameColor, Team> colorTeams = new EnumMap<>(GameColor.class);

    public TeamVisuals(GameManager gameManager) {
        this.gameManager = gameManager;
        ensureTeams();
    }

    private Scoreboard getBoard() {
        ScoreboardManager sm = Bukkit.getScoreboardManager();
        return Objects.requireNonNull(sm, "ScoreboardManager is null").getMainScoreboard();
    }

    private String teamName(GameColor c) {
        return TEAM_PREFIX + c.name();
    }

    // 색상별 스코어보드 팀 생성/설정 보장
    private void ensureTeams() {
        Scoreboard board = getBoard();
        for (GameColor c : GameColor.values()) {
            String name = teamName(c);
            Team team = board.getTeam(name);
            if (team == null) {
                team = board.registerNewTeam(name);
            }
            // 색상, 아군 친선사격 비활성화, 투명 아군 보이기
            team.setColor(c.chat);
            team.setAllowFriendlyFire(false);
            team.setCanSeeFriendlyInvisibles(true);
            colorTeams.put(c, team);
        }
    }

    // 플레이어를 해당 색상 팀으로 배정(기존 TT_* 팀에서 제거 후 추가)
    public void assignPlayerToColor(Player p, GameColor color) {
        if (p == null || color == null) return;
        ensureTeams();

        removeFromAllOurTeams(p);

        Team t = colorTeams.get(color);
        if (t != null && !t.hasEntry(p.getName())) {
            t.addEntry(p.getName());
        }

        // 채팅/탭 목록 이름 컬러 적용
        p.setDisplayName(color.chat + p.getName() + ChatColor.RESET);
        p.setPlayerListName(color.chat + p.getName() + ChatColor.RESET);
        // 머리 위 네임태그는 스코어보드 팀 색상으로 표시됩니다.
    }

    // 현재 게임 참가자 전체를 색상대로 재적용
    public void refreshAll() {
        ensureTeams();
        for (Player p : gameManager.getParticipants()) {
            assignPlayerToColor(p, gameManager.getTeamColor(p));
        }
    }

    // 우리 플러그인이 만든 팀에서 제거하고 표시 이름 원복
    public void resetAll(Collection<? extends Player> players) {
        ensureTeams();
        for (Player p : players) {
            if (p == null) continue;
            removeFromAllOurTeams(p);
            try {
                p.setDisplayName(null);       // 기본값으로 복귀
                p.setPlayerListName(null);    // 탭 목록 이름 복귀
            } catch (Throwable ignored) { }
        }
    }

    private void removeFromAllOurTeams(Player p) {
        Scoreboard board = getBoard();
        for (GameColor c : GameColor.values()) {
            Team team = board.getTeam(teamName(c));
            if (team != null && team.hasEntry(p.getName())) {
                team.removeEntry(p.getName());
            }
        }
    }
}