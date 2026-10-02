import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Generates the compact, deterministic Minecraft-native Sivita HUD frame. */
public final class GenerateSivitaHudPanel {
    private static final int WIDTH = 132;
    private static final int HEIGHT = 44;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: GenerateSivitaHudPanel.java <output.png>");
        }
        File output = new File(args[0]);
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create output directory: " + parent);
        }

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        // A small deepslate status rail. Detailed skill prose belongs in /skills;
        // this frame is deliberately only tall enough for identity plus two hotkey rows.
        fill(graphics, 2, 2, WIDTH - 4, HEIGHT - 4, new Color(15, 18, 20, 214));
        frame(graphics, 1, 1, WIDTH - 2, HEIGHT - 2, new Color(34, 39, 42, 244));
        frame(graphics, 2, 2, WIDTH - 4, HEIGHT - 4, new Color(73, 82, 82, 224));

        // Oxidized copper pins and an amethyst/mana accent replace the generic gold trim.
        Color copperDark = new Color(42, 93, 82, 255);
        Color copperLight = new Color(91, 157, 132, 255);
        pin(graphics, 0, 0, copperDark, copperLight);
        pin(graphics, WIDTH - 5, 0, copperDark, copperLight);
        pin(graphics, 0, HEIGHT - 5, copperDark, copperLight);
        pin(graphics, WIDTH - 5, HEIGHT - 5, copperDark, copperLight);
        fill(graphics, 7, 4, 36, 2, new Color(115, 82, 176, 255));
        fill(graphics, 43, 4, 40, 2, new Color(49, 133, 156, 255));
        fill(graphics, 83, 4, 42, 2, new Color(73, 139, 112, 255));

        // Three compact text lanes. The inset starts at x=9, leaving a real
        // safe area when the client's GUI scale is large.
        int[] separators = {18, 30};
        for (int y : separators) {
            fill(graphics, 7, y, WIDTH - 14, 1, new Color(54, 62, 64, 185));
            for (int x = 9 + ((y / 13) & 1) * 3; x < WIDTH - 9; x += 12) {
                fill(graphics, x, y, 3, 1, new Color(76, 88, 89, 170));
            }
        }
        fill(graphics, 5, 19, 2, 11, new Color(49, 125, 137, 220));
        fill(graphics, WIDTH - 7, 31, 2, 8, new Color(76, 121, 103, 210));
        graphics.dispose();

        if (!ImageIO.write(image, "png", output)) {
            throw new IllegalStateException("PNG writer unavailable");
        }
    }

    private static void pin(Graphics2D graphics, int x, int y, Color dark, Color light) {
        fill(graphics, x, y, 5, 5, dark);
        fill(graphics, x + 1, y + 1, 3, 3, light);
        fill(graphics, x + 2, y + 2, 1, 1, new Color(187, 213, 178, 255));
    }

    private static void frame(Graphics2D graphics, int x, int y, int width, int height, Color color) {
        fill(graphics, x, y, width, 1, color);
        fill(graphics, x, y + height - 1, width, 1, color);
        fill(graphics, x, y, 1, height, color);
        fill(graphics, x + width - 1, y, 1, height, color);
    }

    private static void fill(Graphics2D graphics, int x, int y, int width, int height, Color color) {
        graphics.setColor(color);
        graphics.fillRect(x, y, width, height);
    }
}
