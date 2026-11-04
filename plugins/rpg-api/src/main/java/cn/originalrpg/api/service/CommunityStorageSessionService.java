package cn.originalrpg.api.service;

import org.bukkit.entity.Player;

/**
 * Main-thread bridge supplied by RpgMarket. Deposits use its existing inventory marker,
 * saved removal receipt, crash recovery and mailbox protocol. The adapter validates the
 * exact authenticated Player handle and pristine main-hand stack before preparing custody.
 */
public interface CommunityStorageSessionService {
    void deposit(Player player);
}
