package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CosmeticMoment;
import org.bukkit.entity.Player;

/**
 * Queues optional cosmetic feedback under the cosmetics plugin's shared load and packet budget.
 * Calls may originate off-thread; Bukkit access remains inside the service implementation.
 */
public interface CosmeticEffectService {
    void playMoment(Player player, CosmeticMoment moment);
}
