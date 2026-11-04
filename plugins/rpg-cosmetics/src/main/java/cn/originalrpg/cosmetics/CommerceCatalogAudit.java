package cn.originalrpg.cosmetics;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** Inspect the installed commerce plugin's live configuration, independent of directory naming. */
final class CommerceCatalogAudit {
    private CommerceCatalogAudit() { }

    static List<String> inspect(Plugin commerce) {
        var catalog = commerce.getConfig();
        List<String> errors = new ArrayList<>();
        for (CosmeticCatalog entry : CosmeticCatalog.values()) {
            String path = "products." + entry.productId();
            if (!catalog.isConfigurationSection(path)) {
                errors.add("Missing cosmetic product " + entry.productId()
                        + "; its voucher cannot unlock the cosmetic. Check RpgCommerce/config.yml.");
                continue;
            }
            String marker = catalog.getString(path + ".marker");
            long price = catalog.getLong(path + ".points", -1L);
            if (!entry.voucherMarker().equals(marker)) {
                errors.add("Cosmetic product " + entry.productId() + " requires marker " + entry.voucherMarker()
                        + ", but the installed commerce catalog uses " + marker + ".");
            }
            if (price != entry.starlightCost()) {
                errors.add("Cosmetic product " + entry.productId() + " requires a Starlight price of "
                        + entry.starlightCost() + ", but the installed commerce catalog uses " + price + ".");
            }
        }
        return List.copyOf(errors);
    }
}
