import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Derives 32 crisp, target-aware traveler-compass frames from the reviewed AI shell. */
public final class GenerateCompassFrames {
    private static final int SIZE = 32;
    private static final int FRAME_COUNT = 32;
    private static final int CENTER_X = 16;
    private static final int CENTER_Y = 16;

    private static final int INK = 0xff181520;
    private static final int FACE = 0xff2a2634;
    private static final int FACE_DARK = 0xff211d2b;
    private static final int STEEL_DARK = 0xff697580;
    private static final int STEEL = 0xffd7e5e8;
    private static final int CYAN_DARK = 0xff237f92;
    private static final int CYAN = 0xff59e1e3;
    private static final int PURPLE_DARK = 0xff61378e;
    private static final int PURPLE = 0xffb678ef;
    private static final int GOLD_DARK = 0xffab6823;
    private static final int GOLD = 0xffffcf55;

    private GenerateCompassFrames() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected source texture, frame texture directory and model directory");
        }
        Path source = Path.of(args[0]);
        Path textureDirectory = Path.of(args[1]);
        Path modelDirectory = Path.of(args[2]);
        BufferedImage reviewed = ImageIO.read(source.toFile());
        if (reviewed == null || reviewed.getWidth() != SIZE || reviewed.getHeight() != SIZE) {
            throw new IllegalStateException("Traveler compass source must be a readable 32x32 PNG: " + source);
        }
        Files.createDirectories(textureDirectory);
        Files.createDirectories(modelDirectory);
        BufferedImage base = cleanFace(reviewed);
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            BufferedImage image = copy(base);
            drawNeedle(image, frame);
            validateCardinal(frame, image);
            String id = String.format("traveler_compass_%02d", frame);
            Path texture = textureDirectory.resolve(String.format("frame_%02d.png", frame));
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", encoded)) {
                throw new IllegalStateException("No PNG writer is available");
            }
            writeIfChanged(texture, encoded.toByteArray());
            String json = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":"
                    + "\"originalrpg:item/traveler_compass/frame_" + String.format("%02d", frame) + "\"}}\n";
            writeIfChanged(modelDirectory.resolve(id + ".json"), json.getBytes(StandardCharsets.UTF_8));
        }
        System.out.printf("Generated %d traveler-compass angle frames from %s%n", FRAME_COUNT, source);
    }

    private static void writeIfChanged(Path output, byte[] bytes) throws IOException {
        if (!Files.exists(output) || !Arrays.equals(Files.readAllBytes(output), bytes)) {
            Files.write(output, bytes);
        }
    }

    private static BufferedImage cleanFace(BufferedImage source) {
        BufferedImage base = copy(source);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double dx = x - 15.5;
                double dy = y - 15.5;
                if (dx * dx + dy * dy <= 88.0) {
                    base.setRGB(x, y, ((x + y) % 7 == 0) ? FACE_DARK : FACE);
                }
            }
        }

        // Stable route marks remain still while only the resonance needle rotates.
        pixel(base, 15, 7, GOLD_DARK);
        pixel(base, 16, 7, GOLD);
        pixel(base, 16, 8, GOLD_DARK);
        pixel(base, 24, 15, STEEL_DARK);
        pixel(base, 24, 16, STEEL);
        pixel(base, 15, 24, PURPLE_DARK);
        pixel(base, 16, 24, PURPLE);
        pixel(base, 7, 15, STEEL_DARK);
        pixel(base, 7, 16, STEEL);
        pixel(base, 22, 9, STEEL_DARK);
        pixel(base, 22, 22, STEEL_DARK);
        pixel(base, 9, 22, STEEL_DARK);
        pixel(base, 9, 9, STEEL_DARK);
        return base;
    }

    private static void drawNeedle(BufferedImage image, int frame) {
        // Vanilla texture frame 16 points upward, 24 right, 00 down and 08 left.
        double angle = (frame - 24) * Math.PI * 2.0 / FRAME_COUNT;
        int tipX = CENTER_X + (int) Math.round(Math.cos(angle) * 8.0);
        int tipY = CENTER_Y + (int) Math.round(Math.sin(angle) * 8.0);
        int tailX = CENTER_X - (int) Math.round(Math.cos(angle) * 5.0);
        int tailY = CENTER_Y - (int) Math.round(Math.sin(angle) * 5.0);

        thickLine(image, tailX, tailY, tipX, tipY, INK);
        line(image, CENTER_X, CENTER_Y, tipX, tipY, CYAN_DARK);
        int highlightX = CENTER_X + (int) Math.round(Math.cos(angle) * 6.0);
        int highlightY = CENTER_Y + (int) Math.round(Math.sin(angle) * 6.0);
        line(image, CENTER_X, CENTER_Y, highlightX, highlightY, CYAN);
        line(image, CENTER_X, CENTER_Y, tailX, tailY, PURPLE_DARK);
        int tailGlowX = CENTER_X - (int) Math.round(Math.cos(angle) * 3.0);
        int tailGlowY = CENTER_Y - (int) Math.round(Math.sin(angle) * 3.0);
        line(image, CENTER_X, CENTER_Y, tailGlowX, tailGlowY, PURPLE);
        diamond(image, CENTER_X, CENTER_Y, 2, INK);
        diamond(image, CENTER_X, CENTER_Y, 1, GOLD_DARK);
        pixel(image, CENTER_X, CENTER_Y, GOLD);
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) result.setRGB(x, y, source.getRGB(x, y));
        }
        return result;
    }

    private static void validateCardinal(int frame, BufferedImage image) {
        int[][] checks = {
                {0, CENTER_X, CENTER_Y + 6},
                {8, CENTER_X - 6, CENTER_Y},
                {16, CENTER_X, CENTER_Y - 6},
                {24, CENTER_X + 6, CENTER_Y}
        };
        for (int[] check : checks) {
            if (frame == check[0] && image.getRGB(check[1], check[2]) != CYAN) {
                throw new IllegalStateException("Traveler compass cardinal frame is reversed: " + frame);
            }
        }
    }

    private static void thickLine(BufferedImage image, int startX, int startY, int endX, int endY, int color) {
        visitLine(startX, startY, endX, endY, (x, y) -> {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    if (Math.abs(offsetX) + Math.abs(offsetY) <= 1) {
                        pixel(image, x + offsetX, y + offsetY, color);
                    }
                }
            }
        });
    }

    private static void line(BufferedImage image, int startX, int startY, int endX, int endY, int color) {
        visitLine(startX, startY, endX, endY, (x, y) -> pixel(image, x, y, color));
    }

    private static void visitLine(int startX, int startY, int endX, int endY, PixelConsumer consumer) {
        int x = startX;
        int y = startY;
        int dx = Math.abs(endX - startX);
        int dy = Math.abs(endY - startY);
        int stepX = startX < endX ? 1 : -1;
        int stepY = startY < endY ? 1 : -1;
        int error = dx - dy;
        while (true) {
            consumer.accept(x, y);
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

    private static void diamond(BufferedImage image, int centerX, int centerY, int radius, int color) {
        for (int y = centerY - radius; y <= centerY + radius; y++) {
            int halfWidth = radius - Math.abs(y - centerY);
            for (int x = centerX - halfWidth; x <= centerX + halfWidth; x++) pixel(image, x, y, color);
        }
    }

    private static void pixel(BufferedImage image, int x, int y, int color) {
        if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) image.setRGB(x, y, color);
    }

    @FunctionalInterface
    private interface PixelConsumer {
        void accept(int x, int y);
    }
}
