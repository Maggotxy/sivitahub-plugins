import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Dependency-free PNG gate used by build.sh. */
public final class ValidateTextures {
    private ValidateTextures() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected texture directory and pixel size");
        }
        Path directory = Path.of(args[0]).toRealPath();
        int expectedSize = Integer.parseInt(args[1]);
        List<Path> textures = new ArrayList<>();
        try (var files = Files.walk(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(textures::add);
        }
        if (textures.isEmpty()) throw new IllegalStateException("No PNG textures in " + directory);

        for (Path texture : textures) validate(texture, expectedSize);
        System.out.printf("Validated %d RGBA pixel-art textures at %dx%d%n",
                textures.size(), expectedSize, expectedSize);
    }

    private static void validate(Path texture, int expectedSize) throws IOException {
        BufferedImage image = ImageIO.read(texture.toFile());
        if (image == null) throw new IllegalStateException("Unreadable PNG: " + texture);
        if (image.getWidth() != expectedSize || image.getHeight() != expectedSize) {
            throw new IllegalStateException("Texture must be " + expectedSize + "x" + expectedSize + ": " + texture
                    + " is " + image.getWidth() + "x" + image.getHeight());
        }
        if (!image.getColorModel().hasAlpha()) {
            throw new IllegalStateException("Texture has no alpha channel: " + texture);
        }
        int transparent = 0;
        int opaque = 0;
        int partialAlpha = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha == 0) transparent++;
                else if (alpha == 255) opaque++;
                else partialAlpha++;
            }
        }
        if (transparent == 0 || opaque == 0) {
            throw new IllegalStateException("Texture must contain both transparent and opaque pixels: " + texture);
        }
        if (partialAlpha != 0) {
            throw new IllegalStateException("Texture uses anti-aliased partial alpha; keep the shared crisp pixel style: "
                    + texture + " (" + partialAlpha + " pixels)");
        }
    }
}
