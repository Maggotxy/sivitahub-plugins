import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independent release gate for the bitmap font atlas used by originalrpg:hud. */
public final class ValidateHudAtlas {
    private ValidateHudAtlas() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected atlas PNG, glyph count and glyph size");
        }
        Path atlas = Path.of(args[0]);
        int glyphCount = Integer.parseInt(args[1]);
        int glyphSize = Integer.parseInt(args[2]);
        if (glyphCount <= 0 || glyphSize <= 0) {
            throw new IllegalArgumentException("Glyph count and size must be positive");
        }

        BufferedImage image = ImageIO.read(atlas.toFile());
        if (image == null) throw new IllegalStateException("Unreadable HUD atlas: " + atlas);
        int expectedWidth = glyphCount * glyphSize;
        if (image.getWidth() != expectedWidth || image.getHeight() != glyphSize) {
            throw new IllegalStateException("HUD atlas must be " + expectedWidth + "x" + glyphSize
                    + ": " + atlas + " is " + image.getWidth() + "x" + image.getHeight());
        }
        if (!image.getColorModel().hasAlpha()) {
            throw new IllegalStateException("HUD atlas has no alpha channel: " + atlas);
        }

        Set<List<Integer>> signatures = new HashSet<>();
        for (int glyph = 0; glyph < glyphCount; glyph++) {
            int visible = 0;
            List<Integer> pixels = new ArrayList<>(glyphSize * glyphSize);
            for (int y = 0; y < glyphSize; y++) {
                for (int x = 0; x < glyphSize; x++) {
                    int argb = image.getRGB(glyph * glyphSize + x, y);
                    int alpha = argb >>> 24;
                    if (alpha != 0 && alpha != 255) {
                        throw new IllegalStateException("HUD atlas uses partial alpha at glyph " + glyph
                                + ", pixel " + x + "," + y);
                    }
                    if (alpha == 255) visible++;
                    pixels.add(argb);
                }
            }
            if (visible < 8 || visible > glyphSize * glyphSize - 9) {
                throw new IllegalStateException("HUD glyph " + glyph + " has invalid coverage: " + visible);
            }
            if (!signatures.add(List.copyOf(pixels))) {
                throw new IllegalStateException("HUD glyph " + glyph + " duplicates an earlier glyph");
            }
        }
        System.out.printf("Validated %d distinct binary-alpha HUD glyphs at %dx%d: %s%n",
                glyphCount, glyphSize, glyphSize, atlas);
    }
}
