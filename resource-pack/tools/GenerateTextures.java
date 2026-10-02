import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GenerateTextures {
    private static final int O = 0xff211d2b;
    private static final int STEEL = 0xffd8e4e8;
    private static final int STEEL_DARK = 0xff74868e;
    private static final int GOLD = 0xffffcf55;
    private static final int GOLD_DARK = 0xffb8752d;
    private static final int CYAN = 0xff59e1e3;
    private static final int CYAN_DARK = 0xff237f92;
    private static final int PURPLE = 0xffb678ef;
    private static final int PURPLE_DARK = 0xff61378e;
    private static final int RED = 0xffe65454;
    private static final int RED_DARK = 0xff7f2838;
    private static final int GREEN = 0xff76db83;
    private static final int LEATHER = 0xff8b5637;

    private GenerateTextures() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Expected output directory [--force]");
        Path output = Path.of(args[0]);
        boolean force = args.length == 2 && args[1].equals("--force");
        Files.createDirectories(output);
        save(output, "forged_assassin_weapon", assassin(), force);
        save(output, "forged_warrior_weapon", warrior(), force);
        save(output, "forged_mage_weapon", mage(), force);
        save(output, "forged_ranger_weapon", ranger(), force);
        save(output, "forged_healer_weapon", healer(), force);
        save(output, "forged_helmet", helmet(), force);
        save(output, "forged_chestplate", chestplate(), force);
        save(output, "forged_leggings", leggings(), force);
        save(output, "forged_boots", boots(), force);
        save(output, "forged_shield", forgedShield(), force);
        save(output, "mutation_shard", crystal(CYAN_DARK, CYAN, STEEL), force);
        save(output, "mutation_core", core(PURPLE_DARK, PURPLE, CYAN), force);
        save(output, "hunt_crystal", crystal(STEEL_DARK, CYAN, STEEL), force);
        save(output, "journey_hub", journeyHub(), force);
        save(output, "journey_class", journeyClass(), force);
        save(output, "journey_attribute", journeyAttribute(), force);
        save(output, "journey_explore", journeyExplore(), force);
        save(output, "journey_hunt", journeyHunt(), force);
        save(output, "journey_forge", journeyForge(), force);
        save(output, "journey_reveal", journeyReveal(), force);
        save(output, "journey_dungeon", journeyDungeon(), force);
        save(output, "blood_moon_essence", crescent(), force);
        save(output, "dungeon_core", core(STEEL_DARK, CYAN_DARK, GOLD), force);
        save(output, "hidden_ticket", ticket(), force);
        save(output, "ancient_echo_skill_book", book(), force);
        save(output, "common_insight_skill_book", insightBook(), force);
        save(output, "protection_stone", shield(), force);
        save(output, "lucky_charm", charm(), force);
        save(output, "mob_boomerang", mobBoomerang(), force);
        save(output, "mob_corrosive_orb", mobCorrosiveOrb(), force);
        save(output, "mob_seeker_orb", mobSeekerOrb(), force);
    }

    private static BufferedImage assassin() {
        BufferedImage image = image();
        line(image, 3, 13, 11, 5, O, 3);
        line(image, 4, 12, 12, 4, STEEL, 1);
        line(image, 6, 13, 3, 10, GOLD_DARK, 1);
        line(image, 5, 14, 2, 11, GOLD, 1);
        pixel(image, 12, 3, PURPLE);
        pixel(image, 13, 2, PURPLE_DARK);
        return image;
    }

    private static BufferedImage warrior() {
        BufferedImage image = image();
        line(image, 8, 4, 7, 14, O, 3);
        line(image, 8, 5, 7, 14, LEATHER, 1);
        rect(image, 3, 2, 10, 6, O);
        rect(image, 4, 3, 10, 5, STEEL_DARK);
        rect(image, 3, 3, 7, 4, STEEL);
        pixel(image, 11, 4, RED);
        return image;
    }

    private static BufferedImage mage() {
        BufferedImage image = image();
        line(image, 4, 14, 10, 5, O, 3);
        line(image, 5, 13, 10, 6, GOLD_DARK, 1);
        diamond(image, 11, 3, 3, O);
        diamond(image, 11, 3, 2, PURPLE_DARK);
        diamond(image, 11, 3, 1, PURPLE);
        pixel(image, 10, 2, STEEL);
        return image;
    }

    private static BufferedImage ranger() {
        BufferedImage image = image();
        line(image, 4, 2, 2, 7, O, 2);
        line(image, 2, 7, 4, 13, O, 2);
        line(image, 5, 2, 3, 7, GOLD_DARK, 1);
        line(image, 3, 7, 5, 13, GOLD, 1);
        line(image, 5, 2, 5, 13, STEEL, 1);
        line(image, 5, 8, 13, 8, O, 2);
        line(image, 6, 8, 13, 8, GREEN, 1);
        pixel(image, 13, 7, STEEL);
        return image;
    }

    private static BufferedImage healer() {
        BufferedImage image = image();
        line(image, 4, 14, 10, 5, O, 3);
        line(image, 5, 13, 10, 6, CYAN_DARK, 1);
        rect(image, 8, 1, 12, 5, O);
        rect(image, 9, 2, 11, 4, STEEL);
        pixel(image, 10, 1, GREEN);
        pixel(image, 10, 5, GREEN);
        pixel(image, 8, 3, GREEN);
        pixel(image, 12, 3, GREEN);
        pixel(image, 10, 3, CYAN);
        return image;
    }

    private static BufferedImage helmet() {
        BufferedImage image = image();
        rect(image, 3, 3, 12, 11, O);
        rect(image, 4, 3, 11, 8, STEEL_DARK);
        rect(image, 5, 4, 10, 7, CYAN_DARK);
        rect(image, 3, 8, 6, 12, STEEL);
        rect(image, 9, 8, 12, 12, STEEL);
        rect(image, 6, 8, 9, 9, GOLD);
        return image;
    }

    private static BufferedImage chestplate() {
        BufferedImage image = image();
        rect(image, 3, 2, 12, 13, O);
        rect(image, 4, 3, 6, 7, STEEL_DARK);
        rect(image, 9, 3, 11, 7, STEEL_DARK);
        rect(image, 6, 4, 9, 13, CYAN_DARK);
        rect(image, 5, 6, 10, 12, STEEL);
        diamond(image, 8, 7, 2, GOLD_DARK);
        pixel(image, 8, 7, GOLD);
        return image;
    }

    private static BufferedImage leggings() {
        BufferedImage image = image();
        rect(image, 3, 2, 12, 7, O);
        rect(image, 4, 3, 11, 6, STEEL);
        rect(image, 4, 7, 7, 14, O);
        rect(image, 9, 7, 12, 14, O);
        rect(image, 5, 7, 7, 13, STEEL_DARK);
        rect(image, 9, 7, 11, 13, STEEL_DARK);
        rect(image, 6, 3, 9, 5, CYAN_DARK);
        return image;
    }

    private static BufferedImage boots() {
        BufferedImage image = image();
        rect(image, 3, 3, 7, 12, O);
        rect(image, 9, 3, 13, 12, O);
        rect(image, 4, 4, 6, 10, STEEL_DARK);
        rect(image, 10, 4, 12, 10, STEEL_DARK);
        rect(image, 2, 10, 7, 13, STEEL);
        rect(image, 9, 10, 14, 13, STEEL);
        pixel(image, 5, 9, CYAN);
        pixel(image, 11, 9, CYAN);
        return image;
    }

    private static BufferedImage forgedShield() {
        BufferedImage image = image();
        rect(image, 2, 2, 13, 8, O);
        diamond(image, 8, 8, 7, O);
        rect(image, 3, 3, 12, 7, STEEL_DARK);
        diamond(image, 8, 8, 5, CYAN_DARK);
        diamond(image, 8, 8, 2, GOLD_DARK);
        pixel(image, 8, 8, GOLD);
        return image;
    }

    private static BufferedImage crystal(int dark, int light, int glint) {
        BufferedImage image = image();
        diamond(image, 8, 7, 6, O);
        diamond(image, 8, 7, 5, dark);
        diamond(image, 8, 7, 3, light);
        line(image, 6, 4, 9, 8, glint, 1);
        pixel(image, 7, 3, glint);
        return image;
    }

    private static BufferedImage core(int dark, int light, int center) {
        BufferedImage image = image();
        diamond(image, 8, 8, 7, O);
        diamond(image, 8, 8, 5, dark);
        line(image, 4, 8, 12, 8, light, 1);
        line(image, 8, 4, 8, 12, light, 1);
        diamond(image, 8, 8, 2, center);
        return image;
    }

    private static BufferedImage crescent() {
        BufferedImage image = image();
        diamond(image, 7, 8, 7, O);
        diamond(image, 7, 8, 6, RED_DARK);
        diamond(image, 9, 6, 5, RED);
        diamond(image, 11, 5, 5, 0x00000000);
        diamond(image, 7, 9, 1, GOLD);
        return image;
    }

    private static BufferedImage ticket() {
        BufferedImage image = image();
        rect(image, 2, 4, 13, 12, O);
        rect(image, 3, 5, 12, 11, 0xff4a3d51);
        pixel(image, 2, 7, 0x00000000);
        pixel(image, 13, 9, 0x00000000);
        line(image, 8, 5, 8, 10, PURPLE, 1);
        line(image, 8, 8, 11, 8, PURPLE, 1);
        pixel(image, 7, 5, STEEL);
        return image;
    }

    private static BufferedImage book() {
        BufferedImage image = image();
        rect(image, 2, 3, 13, 13, O);
        rect(image, 3, 4, 12, 12, PURPLE_DARK);
        rect(image, 4, 4, 5, 12, LEATHER);
        line(image, 7, 7, 11, 7, CYAN, 1);
        line(image, 8, 9, 10, 9, CYAN, 1);
        rect(image, 11, 5, 12, 7, GOLD);
        return image;
    }

    private static BufferedImage insightBook() {
        BufferedImage image = image();
        rect(image, 2, 3, 13, 13, O);
        rect(image, 3, 4, 12, 12, CYAN_DARK);
        rect(image, 4, 4, 5, 12, LEATHER);
        diamond(image, 9, 8, 3, STEEL);
        pixel(image, 9, 8, GOLD);
        rect(image, 11, 5, 12, 7, GOLD_DARK);
        return image;
    }

    private static BufferedImage shield() {
        BufferedImage image = image();
        diamond(image, 8, 7, 7, O);
        rect(image, 3, 3, 13, 7, STEEL_DARK);
        diamond(image, 8, 8, 4, CYAN_DARK);
        diamond(image, 8, 8, 2, CYAN);
        return image;
    }

    private static BufferedImage charm() {
        BufferedImage image = image();
        diamond(image, 8, 8, 7, O);
        line(image, 8, 1, 8, 14, GOLD_DARK, 3);
        line(image, 1, 8, 14, 8, GOLD, 3);
        diamond(image, 8, 8, 3, PURPLE_DARK);
        diamond(image, 8, 8, 1, PURPLE);
        return image;
    }

    /** Split crescent with a weighted return core; readable while rotating in flight. */
    private static BufferedImage mobBoomerang() {
        BufferedImage image = image();
        line(image, 2, 4, 7, 8, O, 4);
        line(image, 7, 8, 13, 3, O, 4);
        line(image, 2, 4, 7, 8, PURPLE_DARK, 2);
        line(image, 7, 8, 13, 3, PURPLE, 2);
        diamond(image, 7, 8, 2, GOLD_DARK);
        pixel(image, 7, 8, GOLD);
        pixel(image, 1, 3, STEEL);
        pixel(image, 14, 2, STEEL);
        return image;
    }

    /** A pressurised alchemical glob, distinct from a vanilla slimeball fallback. */
    private static BufferedImage mobCorrosiveOrb() {
        BufferedImage image = image();
        rect(image, 4, 3, 11, 12, O);
        rect(image, 3, 5, 12, 10, O);
        rect(image, 5, 4, 10, 11, 0xff2d6b3c);
        rect(image, 4, 6, 11, 9, 0xff2d6b3c);
        rect(image, 5, 5, 8, 8, GREEN);
        pixel(image, 5, 4, STEEL);
        pixel(image, 4, 6, CYAN);
        pixel(image, 9, 10, 0xffb6f067);
        pixel(image, 11, 9, GREEN);
        return image;
    }

    /** Three offset fins around an aether core remain legible during orbit and homing. */
    private static BufferedImage mobSeekerOrb() {
        BufferedImage image = image();
        diamond(image, 8, 8, 7, O);
        line(image, 8, 1, 8, 14, PURPLE_DARK, 2);
        line(image, 2, 11, 13, 5, CYAN_DARK, 2);
        line(image, 3, 4, 12, 12, STEEL_DARK, 1);
        diamond(image, 8, 8, 3, PURPLE);
        diamond(image, 8, 8, 1, CYAN);
        pixel(image, 8, 8, STEEL);
        return image;
    }

    /** A lit gate and inward path: the first action is entering the hub flow. */
    private static BufferedImage journeyHub() {
        BufferedImage image = image();
        rect(image, 2, 3, 13, 5, O);
        rect(image, 3, 2, 12, 4, STEEL_DARK);
        rect(image, 4, 3, 11, 4, GOLD);
        rect(image, 3, 5, 5, 13, O);
        rect(image, 10, 5, 12, 13, O);
        rect(image, 4, 5, 5, 12, CYAN_DARK);
        rect(image, 10, 5, 11, 12, CYAN_DARK);
        line(image, 8, 6, 8, 14, GOLD_DARK, 3);
        line(image, 8, 7, 8, 14, GOLD, 1);
        pixel(image, 8, 5, STEEL);
        return image;
    }

    /** Six-point crest for the six base classes, kept readable at inventory scale. */
    private static BufferedImage journeyClass() {
        BufferedImage image = image();
        diamond(image, 8, 8, 7, O);
        diamond(image, 8, 8, 5, PURPLE_DARK);
        line(image, 8, 2, 8, 14, STEEL_DARK, 1);
        line(image, 2, 8, 14, 8, STEEL_DARK, 1);
        line(image, 4, 4, 12, 12, STEEL_DARK, 1);
        line(image, 12, 4, 4, 12, STEEL_DARK, 1);
        pixel(image, 8, 2, RED);
        pixel(image, 13, 6, GOLD);
        pixel(image, 11, 12, GREEN);
        pixel(image, 5, 12, CYAN);
        pixel(image, 3, 6, PURPLE);
        diamond(image, 8, 8, 2, STEEL);
        pixel(image, 8, 8, GOLD);
        return image;
    }

    /** Five connected nodes represent Defense, Strength, Agility, Intelligence and Stamina. */
    private static BufferedImage journeyAttribute() {
        BufferedImage image = image();
        line(image, 8, 8, 8, 2, O, 3);
        line(image, 8, 8, 3, 6, O, 3);
        line(image, 8, 8, 5, 13, O, 3);
        line(image, 8, 8, 11, 13, O, 3);
        line(image, 8, 8, 13, 6, O, 3);
        line(image, 8, 8, 8, 2, CYAN_DARK, 1);
        line(image, 8, 8, 3, 6, CYAN_DARK, 1);
        line(image, 8, 8, 5, 13, CYAN_DARK, 1);
        line(image, 8, 8, 11, 13, CYAN_DARK, 1);
        line(image, 8, 8, 13, 6, CYAN_DARK, 1);
        diamond(image, 8, 8, 2, STEEL);
        pixel(image, 8, 2, STEEL);
        pixel(image, 3, 6, RED);
        pixel(image, 5, 13, GREEN);
        pixel(image, 11, 13, GOLD);
        pixel(image, 13, 6, PURPLE);
        return image;
    }

    /** Folded vanilla-world map with a marked route, not a generic compass reuse. */
    private static BufferedImage journeyExplore() {
        BufferedImage image = image();
        rect(image, 2, 3, 13, 13, O);
        line(image, 5, 3, 5, 13, STEEL_DARK, 1);
        line(image, 10, 3, 10, 13, STEEL_DARK, 1);
        rect(image, 3, 4, 4, 12, GREEN);
        rect(image, 6, 4, 9, 12, STEEL);
        rect(image, 11, 4, 12, 12, GREEN);
        line(image, 4, 11, 7, 8, CYAN_DARK, 3);
        line(image, 7, 8, 11, 6, CYAN, 1);
        diamond(image, 11, 5, 2, RED_DARK);
        pixel(image, 11, 5, GOLD);
        return image;
    }

    /** Target eye crossed by a blade: find an eligible adaptive monster, then defeat it. */
    private static BufferedImage journeyHunt() {
        BufferedImage image = image();
        diamond(image, 7, 8, 7, O);
        diamond(image, 7, 8, 5, RED_DARK);
        diamond(image, 7, 8, 3, STEEL_DARK);
        diamond(image, 7, 8, 1, RED);
        line(image, 3, 13, 13, 3, O, 3);
        line(image, 4, 12, 13, 3, STEEL, 1);
        line(image, 3, 13, 5, 11, GOLD_DARK, 2);
        pixel(image, 14, 2, CYAN);
        return image;
    }

    /** Hammer striking an anvil, with one bounded spark instead of ambient particles. */
    private static BufferedImage journeyForge() {
        BufferedImage image = image();
        rect(image, 2, 10, 13, 13, O);
        rect(image, 3, 10, 12, 11, STEEL_DARK);
        rect(image, 5, 12, 10, 14, STEEL);
        line(image, 5, 3, 11, 9, O, 3);
        line(image, 5, 3, 11, 9, LEATHER, 1);
        rect(image, 2, 1, 8, 5, O);
        rect(image, 3, 2, 7, 4, STEEL);
        pixel(image, 12, 6, GOLD);
        pixel(image, 14, 8, RED);
        pixel(image, 12, 9, CYAN);
        return image;
    }

    /** An opening eye over a single slot gem makes progressive reveal explicit. */
    private static BufferedImage journeyReveal() {
        BufferedImage image = image();
        line(image, 2, 6, 8, 2, O, 3);
        line(image, 8, 2, 14, 6, O, 3);
        line(image, 2, 6, 8, 10, O, 3);
        line(image, 8, 10, 14, 6, O, 3);
        line(image, 3, 6, 8, 3, PURPLE, 1);
        line(image, 8, 3, 13, 6, PURPLE, 1);
        line(image, 3, 6, 8, 9, PURPLE_DARK, 1);
        line(image, 8, 9, 13, 6, PURPLE_DARK, 1);
        diamond(image, 8, 6, 3, STEEL_DARK);
        diamond(image, 8, 6, 1, CYAN);
        diamond(image, 8, 13, 2, GOLD_DARK);
        pixel(image, 8, 13, GOLD);
        return image;
    }

    /** Red-eyed stone gate for an instanced dungeon and its boss settlement. */
    private static BufferedImage journeyDungeon() {
        BufferedImage image = image();
        rect(image, 2, 2, 13, 5, O);
        rect(image, 3, 3, 12, 5, STEEL_DARK);
        rect(image, 2, 5, 5, 14, O);
        rect(image, 10, 5, 13, 14, O);
        rect(image, 3, 6, 5, 13, STEEL_DARK);
        rect(image, 10, 6, 12, 13, STEEL_DARK);
        diamond(image, 8, 7, 3, RED_DARK);
        pixel(image, 8, 7, RED);
        pixel(image, 7, 6, GOLD);
        line(image, 6, 14, 8, 10, CYAN_DARK, 1);
        line(image, 10, 14, 8, 10, CYAN, 1);
        return image;
    }

    private static BufferedImage image() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    private static void save(Path output, String name, BufferedImage image, boolean force) throws IOException {
        Path target = output.resolve(name + ".png");
        if (force || Files.notExists(target)) {
            BufferedImage scaled = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(image, 0, 0, 32, 32, null);
            graphics.dispose();
            ImageIO.write(scaled, "PNG", target.toFile());
        }
    }

    private static void pixel(BufferedImage image, int x, int y, int color) {
        if (x >= 0 && x < 16 && y >= 0 && y < 16) image.setRGB(x, y, color);
    }

    private static void rect(BufferedImage image, int x1, int y1, int x2, int y2, int color) {
        for (int y = y1; y <= y2; y++) for (int x = x1; x <= x2; x++) pixel(image, x, y, color);
    }

    private static void diamond(BufferedImage image, int cx, int cy, int radius, int color) {
        for (int y = -radius; y <= radius; y++) {
            int width = radius - Math.abs(y);
            for (int x = -width; x <= width; x++) pixel(image, cx + x, cy + y, color);
        }
    }

    private static void line(BufferedImage image, int x1, int y1, int x2, int y2, int color, int width) {
        int dx = Math.abs(x2 - x1);
        int sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1);
        int sy = y1 < y2 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            int radius = width / 2;
            rect(image, x1 - radius, y1 - radius, x1 + radius, y1 + radius, color);
            if (x1 == x2 && y1 == y2) break;
            int twice = 2 * error;
            if (twice >= dy) {
                error += dy;
                x1 += sx;
            }
            if (twice <= dx) {
                error += dx;
                y1 += sy;
            }
        }
    }
}
