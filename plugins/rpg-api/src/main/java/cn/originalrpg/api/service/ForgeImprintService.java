package cn.originalrpg.api.service;

import cn.originalrpg.api.model.ForgeImprint;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

/** Verification boundary between the 26.2 sulfur loop and equipment forging. */
public interface ForgeImprintService {
    boolean isImprint(ItemStack item);

    Optional<ForgeImprint> inspect(ItemStack item, UUID expectedOwner);
}
