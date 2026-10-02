import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Generates six class crests and forty-two distinct semantic skill glyphs. */
public final class GenerateSkillHudAtlas {
    private static final int SIZE = 9;
    private static final int CLEAR = 0x00000000;
    private static final int INK = 0xff181520;
    private static final int LIGHT = 0xffeef6ef;
    private static final int[] PRIMARY = {
            0xff61d6b2, 0xffe27645, 0xffa678de, 0xff5ebce6, 0xff78c96b, 0xffe8c85b
    };
    private static final int[] DARK = {
            0xff267b67, 0xff8d3b29, 0xff563b86, 0xff27678e, 0xff3f773a, 0xff8b6b24
    };
    private static final List<String> SKILLS = List.of(
            "heal", "war-cry", "shadow-step", "fireball", "power-shot", "improvisation",
            "renewal", "sanctuary", "fate-seal", "shield-wall", "fortress", "blood-rage",
            "venom-blade", "smoke-bomb", "toxic-burst", "frost-nova", "meteor", "mana-surge",
            "volley", "piercing-shot", "beast-call", "second-wind", "battle-trance", "arcane-edge",
            "life-thread", "omen-reversal", "soul-resonance", "rallying-standard", "ruin-charge", "unbroken-line",
            "void-cut", "plague-bloom", "vanished-horizon", "ash-domain", "time-lock", "axiomatic-break",
            "gale-step", "pack-hunt", "horizon-arrow", "adaptive-arsenal", "rune-reversal", "open-form");
    private final BufferedImage image = new BufferedImage(
            SIZE * (6 + SKILLS.size()), SIZE, BufferedImage.TYPE_INT_ARGB);

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Expected output PNG");
        GenerateSkillHudAtlas atlas = new GenerateSkillHudAtlas();
        for (int classIndex = 0; classIndex < 6; classIndex++) atlas.drawClass(classIndex);
        for (int index = 0; index < SKILLS.size(); index++) atlas.drawSkill(index, SKILLS.get(index));
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(atlas.image, "png", encoded)) throw new IllegalStateException("No PNG writer");
        byte[] bytes = encoded.toByteArray();
        if (!Files.exists(output) || !Arrays.equals(Files.readAllBytes(output), bytes)) Files.write(output, bytes);
        System.out.printf("Generated %d class/skill HUD glyphs: %s%n", 6 + SKILLS.size(), output);
    }

    private void drawClass(int classIndex) {
        int glyph = classIndex;
        frame(glyph, classIndex, glyph + 1);
        switch (classIndex) {
            case 0 -> cross(glyph);
            case 1 -> blade(glyph);
            case 2 -> dagger(glyph);
            case 3 -> crystal(glyph);
            case 4 -> bow(glyph);
            default -> star(glyph);
        }
    }

    private void drawSkill(int index, String id) {
        int glyph = 6 + index;
        int classIndex = index % 6;
        frame(glyph, classIndex, glyph + 1);
        if (contains(id, "heal", "renew", "life", "sanctuary")) cross(glyph);
        else if (contains(id, "fire", "meteor", "ash")) flame(glyph);
        else if (contains(id, "shot", "arrow", "volley", "horizon")) arrow(glyph);
        else if (contains(id, "shield", "fortress", "unbroken", "standard")) shield(glyph);
        else if (contains(id, "shadow", "smoke", "void", "vanished")) crescent(glyph);
        else if (contains(id, "venom", "toxic", "plague")) droplet(glyph);
        else if (contains(id, "mana", "arcane", "rune", "axiomatic", "fate", "omen", "soul")) crystal(glyph);
        else if (contains(id, "beast", "pack")) paw(glyph);
        else if (contains(id, "frost", "time")) hourglass(glyph);
        else if (contains(id, "gale", "step", "wind")) wing(glyph);
        else if (contains(id, "war", "battle", "blood", "charge", "blade")) blade(glyph);
        else star(glyph);
    }

    private void frame(int glyph, int classIndex, int code) {
        int primary = PRIMARY[classIndex];
        int dark = DARK[classIndex];
        pixel(glyph, 2, 0, INK); pixel(glyph, 3, 0, dark); pixel(glyph, 4, 0, primary);
        pixel(glyph, 5, 0, dark); pixel(glyph, 6, 0, INK);
        pixel(glyph, 1, 1, INK); pixel(glyph, 7, 1, INK);
        pixel(glyph, 0, 2, INK); pixel(glyph, 8, 2, INK);
        pixel(glyph, 0, 6, INK); pixel(glyph, 8, 6, INK);
        pixel(glyph, 1, 7, INK); pixel(glyph, 7, 7, INK);
        pixel(glyph, 2, 8, INK); pixel(glyph, 6, 8, INK);
        for (int bit = 0; bit < 5; bit++) {
            if ((code & (1 << bit)) != 0) pixel(glyph, 2 + bit, 8, primary);
        }
    }

    private void cross(int g) { line(g, 4, 2, 4, 6, LIGHT); line(g, 2, 4, 6, 4, LIGHT); pixel(g, 4, 4, PRIMARY[(g < 6 ? g : g - 6) % 6]); }
    private void blade(int g) { line(g, 6, 1, 2, 6, LIGHT); line(g, 7, 2, 3, 6, DARK[(g < 6 ? g : g - 6) % 6]); line(g, 2, 6, 5, 6, INK); pixel(g, 1, 7, LIGHT); }
    private void dagger(int g) { line(g, 5, 1, 3, 5, LIGHT); line(g, 6, 1, 4, 5, DARK[2]); line(g, 2, 5, 5, 5, INK); pixel(g, 2, 6, LIGHT); }
    private void crystal(int g) { diamond(g, 4, 4, 3, INK); diamond(g, 4, 4, 2, PRIMARY[(g < 6 ? g : g - 6) % 6]); pixel(g, 3, 3, LIGHT); }
    private void bow(int g) { pixel(g, 2, 2, LIGHT); pixel(g, 1, 3, LIGHT); pixel(g, 1, 4, LIGHT); pixel(g, 1, 5, LIGHT); pixel(g, 2, 6, LIGHT); line(g, 2, 2, 2, 6, DARK[4]); line(g, 2, 4, 7, 4, LIGHT); pixel(g, 6, 3, LIGHT); pixel(g, 6, 5, LIGHT); }
    private void star(int g) { line(g, 4, 1, 4, 7, LIGHT); line(g, 1, 4, 7, 4, LIGHT); pixel(g, 2, 2, LIGHT); pixel(g, 6, 2, LIGHT); pixel(g, 2, 6, LIGHT); pixel(g, 6, 6, LIGHT); pixel(g, 4, 4, PRIMARY[5]); }
    private void flame(int g) { pixel(g, 5, 1, LIGHT); pixel(g, 4, 2, LIGHT); pixel(g, 5, 3, PRIMARY[3]); pixel(g, 3, 3, LIGHT); pixel(g, 2, 4, DARK[3]); line(g, 2, 5, 6, 5, PRIMARY[3]); line(g, 3, 6, 5, 6, LIGHT); pixel(g, 4, 4, LIGHT); }
    private void arrow(int g) { line(g, 1, 6, 6, 1, LIGHT); pixel(g, 5, 1, LIGHT); pixel(g, 6, 2, LIGHT); line(g, 2, 6, 4, 6, DARK[4]); }
    private void shield(int g) { line(g, 2, 2, 6, 2, LIGHT); line(g, 2, 2, 2, 5, LIGHT); line(g, 6, 2, 6, 5, LIGHT); pixel(g, 3, 6, LIGHT); pixel(g, 4, 7, LIGHT); pixel(g, 5, 6, LIGHT); line(g, 4, 3, 4, 6, DARK[1]); }
    private void crescent(int g) { diamond(g, 4, 4, 3, LIGHT); pixel(g, 5, 2, CLEAR); pixel(g, 6, 3, CLEAR); pixel(g, 6, 4, CLEAR); pixel(g, 6, 5, CLEAR); pixel(g, 5, 6, CLEAR); pixel(g, 4, 4, DARK[2]); }
    private void droplet(int g) { pixel(g, 4, 1, LIGHT); line(g, 3, 2, 5, 2, LIGHT); line(g, 2, 3, 6, 5, PRIMARY[2]); line(g, 3, 6, 5, 6, LIGHT); pixel(g, 4, 7, LIGHT); }
    private void paw(int g) { pixel(g, 2, 2, LIGHT); pixel(g, 4, 1, LIGHT); pixel(g, 6, 2, LIGHT); pixel(g, 2, 4, LIGHT); pixel(g, 6, 4, LIGHT); line(g, 3, 5, 5, 5, LIGHT); line(g, 3, 6, 5, 6, PRIMARY[4]); }
    private void hourglass(int g) { line(g, 2, 1, 6, 1, LIGHT); line(g, 2, 7, 6, 7, LIGHT); pixel(g, 3, 2, LIGHT); pixel(g, 5, 2, LIGHT); pixel(g, 4, 3, LIGHT); pixel(g, 4, 4, PRIMARY[3]); pixel(g, 4, 5, LIGHT); pixel(g, 3, 6, LIGHT); pixel(g, 5, 6, LIGHT); }
    private void wing(int g) { line(g, 1, 5, 6, 2, LIGHT); line(g, 2, 6, 7, 4, PRIMARY[4]); line(g, 3, 7, 6, 6, LIGHT); }

    private static boolean contains(String id, String... needles) { for (String needle : needles) if (id.contains(needle)) return true; return false; }
    private void diamond(int g, int cx, int cy, int radius, int color) { for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) if (Math.abs(x) + Math.abs(y) <= radius) pixel(g, cx + x, cy + y, color); }
    private void line(int g, int x0, int y0, int x1, int y1, int color) { int dx=Math.abs(x1-x0), sx=x0<x1?1:-1, dy=-Math.abs(y1-y0), sy=y0<y1?1:-1, err=dx+dy; while(true){ pixel(g,x0,y0,color); if(x0==x1&&y0==y1) break; int e2=2*err; if(e2>=dy){err+=dy;x0+=sx;} if(e2<=dx){err+=dx;y0+=sy;} } }
    private void pixel(int g, int x, int y, int color) { if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) image.setRGB(g * SIZE + x, y, color); }
}
