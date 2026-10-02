package cn.originalrpg.pets;

import de.Keyle.MyPet.api.entity.Pet;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Particle;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;

/** Applies lightweight server-side variants; no client mod or per-tick renderer. */
final class PetSkinRenderer {
    private PetSkinRenderer() {
    }

    static void apply(Pet pet, String skinId, boolean celebrate) {
        Mob mob = pet.getBukkitEntity();
        if (mob == null || !mob.isValid()) return;
        pet.updateVisuals();
        int tier = "ascendant".equals(skinId) ? 2 : "resonant".equals(skinId) ? 1 : 0;
        if (tier > 0) applyVariant(mob, tier);
        if (celebrate) {
            Color color = tier == 2 ? Color.fromRGB(255, 202, 84) : Color.fromRGB(90, 213, 203);
            mob.getWorld().spawnParticle(Particle.DUST, mob.getLocation().add(0, mob.getHeight() * 0.55, 0),
                    24, 0.35, 0.45, 0.35, 0.0, new Particle.DustOptions(color, 1.0f));
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyVariant(Mob mob, int tier) {
        if (mob instanceof Wolf wolf) {
            wolf.setCollarColor(tier == 2 ? DyeColor.ORANGE : DyeColor.CYAN);
        } else if (mob instanceof Cat cat) {
            cat.setCatType(tier == 2 ? Cat.Type.ALL_BLACK : Cat.Type.JELLIE);
            cat.setCollarColor(tier == 2 ? DyeColor.ORANGE : DyeColor.CYAN);
        } else if (mob instanceof Sheep sheep) {
            sheep.setColor(tier == 2 ? DyeColor.ORANGE : DyeColor.CYAN);
        } else if (mob instanceof Horse horse) {
            horse.setColor(tier == 2 ? Horse.Color.BLACK : Horse.Color.GRAY);
            horse.setStyle(tier == 2 ? Horse.Style.WHITE_DOTS : Horse.Style.WHITEFIELD);
        } else if (mob instanceof Llama llama) {
            llama.setColor(tier == 2 ? Llama.Color.BROWN : Llama.Color.GRAY);
        } else if (mob instanceof Parrot parrot) {
            parrot.setVariant(tier == 2 ? Parrot.Variant.RED : Parrot.Variant.CYAN);
        } else if (mob instanceof Rabbit rabbit) {
            rabbit.setRabbitType(tier == 2 ? Rabbit.Type.BLACK : Rabbit.Type.SALT_AND_PEPPER);
        } else if (mob instanceof Fox fox) {
            fox.setFoxType(tier == 2 ? Fox.Type.RED : Fox.Type.SNOW);
        } else if (mob instanceof Axolotl axolotl) {
            axolotl.setVariant(tier == 2 ? Axolotl.Variant.GOLD : Axolotl.Variant.CYAN);
        }
    }
}
