import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Stable decoded-pixel fingerprint; PNG encoders may use different metadata/compression. */
public final class PngFingerprint {
    private PngFingerprint() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one PNG path");
        BufferedImage image = ImageIO.read(new File(args[0]));
        if (image == null) throw new IllegalArgumentException("Not a readable PNG: " + args[0]);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateInt(digest, image.getWidth());
        updateInt(digest, image.getHeight());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) updateInt(digest, image.getRGB(x, y));
        }
        System.out.println(image.getWidth() + "x" + image.getHeight() + ":"
                + HexFormat.of().formatHex(digest.digest()));
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
