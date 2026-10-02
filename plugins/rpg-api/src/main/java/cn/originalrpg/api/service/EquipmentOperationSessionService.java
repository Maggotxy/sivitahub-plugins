package cn.originalrpg.api.service;

import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.function.Consumer;

/** Main-thread player-data bridge for the durable JDBC equipment outbox. */
public interface EquipmentOperationSessionService {
    boolean hasPending(Player player);

    void stage(Player player, EquipmentOperationRequest request);

    void clearStaged(Player player, UUID operationId);

    void submit(Player player, EquipmentOperationRequest request, Consumer<EquipmentOperationResult> completion);

    void recover(Player player);

    /** Base64 Paper payload with transient operation markers removed. */
    String itemPayload(ItemStack item);
}
