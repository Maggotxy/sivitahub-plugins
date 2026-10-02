package cn.originalrpg.api.service;

import org.bukkit.entity.Player;

/** Opens trusted sections of the unified guide without routing through command text. */
public interface GuideMenuService {
    enum Section {
        MAIN,
        CLASS,
        ENHANCEMENT,
        DUNGEON,
        MARKET,
        ECONOMY,
        QUESTS,
        ONBOARDING,
        CURRENT,
        NPCS,
        HELP,
        VISUALS
    }

    void open(Player player, Section section);
}
