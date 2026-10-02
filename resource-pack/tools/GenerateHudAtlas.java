import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Builds the compact HUD glyphs used by originalrpg:hud. */
public final class GenerateHudAtlas {
    private static final int GLYPH_SIZE = 9;
    private static final int GLYPH_COUNT = 11;

    private static final int CLEAR = 0x00000000;
    private static final int INK = 0xff181520;
    private static final int STEEL_DARK = 0xff697580;
    private static final int STEEL = 0xffd7e5e8;
    private static final int CYAN_DARK = 0xff237f92;
    private static final int CYAN = 0xff59e1e3;
    private static final int PURPLE_DARK = 0xff61378e;
    private static final int PURPLE = 0xffb678ef;
    private static final int RED_DARK = 0xff7b2634;
    private static final int RED = 0xffe65454;
    private static final int GOLD_DARK = 0xffab6823;
    private static final int GOLD = 0xffffcf55;

    private final BufferedImage image = new BufferedImage(
            GLYPH_SIZE * GLYPH_COUNT, GLYPH_SIZE, BufferedImage.TYPE_INT_ARGB);

    private GenerateHudAtlas() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Expected output PNG");
        Path output = Path.of(args[0]);
        GenerateHudAtlas atlas = new GenerateHudAtlas();
        atlas.drawBloodMoon(0);
        atlas.drawMana(1);
        atlas.drawCritical(2);
        atlas.drawExecute(3);
        atlas.drawForge(4);
        atlas.drawProtection(5);
        atlas.drawRareDrop(6);
        atlas.drawSkill(7);
        atlas.drawHub(8);
        atlas.drawObjective(9);
        atlas.drawNpc(10);
        atlas.validate();
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(atlas.image, "png", encoded)) {
            throw new IllegalStateException("No PNG writer is available");
        }
        byte[] bytes = encoded.toByteArray();
        if (!Files.exists(output) || !Arrays.equals(Files.readAllBytes(output), bytes)) {
            Files.write(output, bytes);
        }
        System.out.printf("Generated %d crisp HUD glyphs at %dx%d: %s%n",
                GLYPH_COUNT, atlas.image.getWidth(), atlas.image.getHeight(), output);
    }

    private void drawBloodMoon(int glyph) {
        for (int y = 0; y < GLYPH_SIZE; y++) {
            for (int x = 0; x < GLYPH_SIZE; x++) {
                int distance = (x - 4) * (x - 4) + (y - 4) * (y - 4);
                if (distance <= 17) pixel(glyph, x, y, RED_DARK);
                if (distance <= 12) pixel(glyph, x, y, x < 4 ? RED : RED_DARK);
            }
        }
        for (int y = 0; y < GLYPH_SIZE; y++) {
            for (int x = 4; x <= 8; x++) {
                int cut = (x - 5) * (x - 5) + (y - 4) * (y - 4);
                if (cut <= 8) pixel(glyph, x, y, CLEAR);
            }
        }
        pixel(glyph, 7, 1, GOLD);
        pixel(glyph, 6, 1, GOLD_DARK);
        pixel(glyph, 7, 0, GOLD_DARK);
    }

    private void drawMana(int glyph) {
        int[][] ranges = {{4, 4}, {3, 5}, {3, 5}, {2, 6}, {1, 7}, {1, 7}, {2, 6}, {3, 5}, {4, 4}};
        for (int y = 0; y < ranges.length; y++) {
            for (int x = ranges[y][0]; x <= ranges[y][1]; x++) pixel(glyph, x, y, CYAN_DARK);
        }
        fillRange(glyph, 1, 4, 4, CYAN);
        fillRange(glyph, 2, 4, 4, CYAN);
        fillRange(glyph, 3, 3, 5, CYAN_DARK);
        fillRange(glyph, 4, 2, 6, CYAN_DARK);
        fillRange(glyph, 5, 2, 6, CYAN_DARK);
        fillRange(glyph, 6, 3, 5, CYAN_DARK);
        fillRange(glyph, 7, 4, 4, CYAN_DARK);
        pixel(glyph, 4, 0, CYAN);
        pixel(glyph, 4, 1, CYAN);
        pixel(glyph, 4, 2, CYAN);
        pixel(glyph, 3, 4, CYAN);
        pixel(glyph, 3, 5, STEEL);
    }

    private void drawCritical(int glyph) {
        line(glyph, 4, 0, 4, 8, INK);
        line(glyph, 0, 4, 8, 4, INK);
        pixel(glyph, 1, 1, RED);
        pixel(glyph, 7, 1, RED);
        pixel(glyph, 1, 7, RED);
        pixel(glyph, 7, 7, RED);
        diamond(glyph, 4, 4, 3, INK);
        diamond(glyph, 4, 4, 2, GOLD_DARK);
        diamond(glyph, 4, 4, 1, GOLD);
        pixel(glyph, 4, 4, STEEL);
    }

    private void drawExecute(int glyph) {
        line(glyph, 6, 0, 2, 6, INK);
        line(glyph, 7, 0, 3, 6, INK);
        line(glyph, 6, 1, 3, 5, STEEL);
        line(glyph, 1, 5, 5, 7, GOLD_DARK);
        pixel(glyph, 1, 6, GOLD);
        pixel(glyph, 5, 7, GOLD);
        line(glyph, 1, 8, 7, 8, RED_DARK);
        line(glyph, 3, 7, 7, 7, RED);
        pixel(glyph, 7, 6, RED);
    }

    private void drawForge(int glyph) {
        rect(glyph, 0, 1, 5, 3, INK);
        rect(glyph, 1, 1, 4, 2, STEEL);
        line(glyph, 4, 3, 7, 6, INK);
        line(glyph, 4, 4, 7, 7, GOLD_DARK);
        pixel(glyph, 6, 6, GOLD);
        line(glyph, 1, 6, 7, 6, INK);
        line(glyph, 2, 7, 6, 7, STEEL_DARK);
        line(glyph, 1, 8, 7, 8, INK);
        pixel(glyph, 1, 5, GOLD);
    }

    private void drawProtection(int glyph) {
        fillRange(glyph, 0, 1, 7, INK);
        fillRange(glyph, 1, 1, 7, INK);
        fillRange(glyph, 2, 1, 7, INK);
        fillRange(glyph, 3, 1, 7, INK);
        fillRange(glyph, 4, 2, 6, INK);
        fillRange(glyph, 5, 2, 6, INK);
        fillRange(glyph, 6, 3, 5, INK);
        fillRange(glyph, 7, 3, 5, INK);
        pixel(glyph, 4, 8, INK);
        rect(glyph, 2, 1, 6, 3, CYAN_DARK);
        fillRange(glyph, 4, 3, 5, CYAN_DARK);
        fillRange(glyph, 5, 3, 5, CYAN_DARK);
        fillRange(glyph, 6, 4, 4, CYAN_DARK);
        line(glyph, 4, 1, 4, 6, STEEL);
        line(glyph, 2, 3, 6, 3, STEEL);
        pixel(glyph, 3, 2, CYAN);
    }

    private void drawRareDrop(int glyph) {
        diamond(glyph, 4, 4, 4, INK);
        diamond(glyph, 4, 4, 3, PURPLE_DARK);
        line(glyph, 4, 1, 2, 4, PURPLE);
        line(glyph, 4, 1, 6, 4, GOLD_DARK);
        line(glyph, 2, 4, 4, 7, CYAN_DARK);
        line(glyph, 6, 4, 4, 7, PURPLE);
        pixel(glyph, 4, 3, STEEL);
        pixel(glyph, 7, 1, GOLD);
        pixel(glyph, 8, 1, GOLD_DARK);
        pixel(glyph, 7, 0, GOLD_DARK);
    }

    private void drawSkill(int glyph) {
        rect(glyph, 0, 2, 4, 7, INK);
        rect(glyph, 4, 2, 8, 7, INK);
        rect(glyph, 1, 3, 3, 6, CYAN_DARK);
        rect(glyph, 5, 3, 7, 6, PURPLE_DARK);
        line(glyph, 4, 2, 4, 8, GOLD_DARK);
        pixel(glyph, 2, 4, CYAN);
        pixel(glyph, 6, 4, PURPLE);
        pixel(glyph, 4, 0, GOLD);
        pixel(glyph, 3, 1, GOLD_DARK);
        pixel(glyph, 5, 1, GOLD_DARK);
        line(glyph, 1, 8, 7, 8, INK);
    }

    private void drawHub(int glyph) {
        rect(glyph, 1, 3, 7, 7, INK);
        line(glyph, 0, 4, 4, 0, INK);
        line(glyph, 4, 0, 8, 4, INK);
        line(glyph, 1, 4, 4, 1, GOLD_DARK);
        line(glyph, 4, 1, 7, 4, GOLD);
        rect(glyph, 2, 4, 6, 7, STEEL_DARK);
        rect(glyph, 3, 5, 5, 8, CYAN_DARK);
        pixel(glyph, 4, 5, CYAN);
        pixel(glyph, 4, 7, INK);
        pixel(glyph, 1, 8, GOLD_DARK);
        pixel(glyph, 7, 8, GOLD_DARK);
    }

    /** A compact compass needle with a highlighted target tip. */
    private void drawObjective(int glyph) {
        diamond(glyph, 4, 4, 4, INK);
        diamond(glyph, 4, 4, 3, STEEL_DARK);
        line(glyph, 4, 7, 4, 2, CYAN_DARK);
        line(glyph, 3, 6, 4, 2, CYAN);
        line(glyph, 5, 6, 4, 2, CYAN_DARK);
        pixel(glyph, 4, 1, GOLD);
        pixel(glyph, 3, 2, GOLD_DARK);
        pixel(glyph, 5, 2, GOLD_DARK);
        pixel(glyph, 4, 4, STEEL);
    }

    /** A player silhouette framed by a speech bubble for service NPCs. */
    private void drawNpc(int glyph) {
        rect(glyph, 0, 0, 8, 6, INK);
        rect(glyph, 1, 1, 7, 5, CYAN_DARK);
        pixel(glyph, 2, 6, INK);
        pixel(glyph, 2, 7, INK);
        pixel(glyph, 3, 6, CYAN_DARK);
        diamond(glyph, 4, 3, 2, STEEL_DARK);
        fillRange(glyph, 2, 3, 5, GOLD_DARK);
        fillRange(glyph, 3, 3, 5, GOLD);
        pixel(glyph, 3, 3, INK);
        pixel(glyph, 5, 3, INK);
        fillRange(glyph, 5, 2, 6, PURPLE_DARK);
        fillRange(glyph, 6, 1, 7, INK);
        fillRange(glyph, 7, 2, 6, PURPLE_DARK);
        pixel(glyph, 4, 5, PURPLE);
    }

    private void validate() {
        for (int glyph = 0; glyph < GLYPH_COUNT; glyph++) {
            int visible = 0;
            for (int y = 0; y < GLYPH_SIZE; y++) {
                for (int x = 0; x < GLYPH_SIZE; x++) {
                    int alpha = image.getRGB(glyph * GLYPH_SIZE + x, y) >>> 24;
                    if (alpha != 0 && alpha != 255) {
                        throw new IllegalStateException("HUD atlas contains partial alpha");
                    }
                    if (alpha == 255) visible++;
                }
            }
            if (visible < 8 || visible > 72) {
                throw new IllegalStateException("HUD glyph " + glyph + " has invalid coverage: " + visible);
            }
        }
    }

    private void fillRange(int glyph, int y, int left, int right, int color) {
        for (int x = left; x <= right; x++) pixel(glyph, x, y, color);
    }

    private void rect(int glyph, int left, int top, int right, int bottom, int color) {
        for (int y = top; y <= bottom; y++) fillRange(glyph, y, left, right, color);
    }

    private void diamond(int glyph, int centerX, int centerY, int radius, int color) {
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            int halfWidth = radius - Math.abs(y - centerY);
            fillRange(glyph, y, centerX - halfWidth, centerX + halfWidth, color);
        }
    }

    private void line(int glyph, int startX, int startY, int endX, int endY, int color) {
        int x = startX;
        int y = startY;
        int dx = Math.abs(endX - startX);
        int dy = Math.abs(endY - startY);
        int stepX = startX < endX ? 1 : -1;
        int stepY = startY < endY ? 1 : -1;
        int error = dx - dy;
        while (true) {
            pixel(glyph, x, y, color);
            if (x == endX && y == endY) return;
            int doubled = error * 2;
            if (doubled > -dy) {
                error -= dy;
                x += stepX;
            }
            if (doubled < dx) {
                error += dx;
                y += stepY;
            }
        }
    }

    private void pixel(int glyph, int x, int y, int color) {
        if (glyph < 0 || glyph >= GLYPH_COUNT || x < 0 || x >= GLYPH_SIZE || y < 0 || y >= GLYPH_SIZE) {
            throw new IllegalArgumentException("Pixel outside HUD atlas");
        }
        image.setRGB(glyph * GLYPH_SIZE + x, y, color);
    }
}
