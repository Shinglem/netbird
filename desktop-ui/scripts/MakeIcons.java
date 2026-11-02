import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Rasterizes the SVG icon into PNGs and a multi-size ICO.
 *
 * <p>Written as a single-file JDK program because this machine has no ImageMagick / Inkscape /
 * librsvg, and the icon must not pull a dependency into the app. Only the path commands used by
 * the NetBird mark are supported (M/L/H/V/C/Z) - anything else fails loudly instead of rendering
 * something subtly wrong.
 *
 * <p>Run through {@code desktop-ui/scripts/make-icons.ps1}, which picks the JDK.
 */
public final class MakeIcons {

    private static final double VIEW_WIDTH = 31.0;
    private static final double VIEW_HEIGHT = 23.0;
    private static final double PADDING_RATIO = 0.06;
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private static final Pattern PATH_TAG = Pattern.compile("<path[^>]*>");
    private static final Pattern DATA_ATTR = Pattern.compile("d=\"([^\"]+)\"");
    private static final Pattern FILL_ATTR = Pattern.compile("fill=\"([^\"]+)\"");
    private static final Pattern TOKEN = Pattern.compile("[MmLlHhVvCcZz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?");

    private record Shape(String data, String fill) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: java MakeIcons.java <source.svg> <png-dir> <icon.ico>");
            System.exit(2);
        }

        String svg = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        List<Shape> shapes = parseShapes(svg);
        if (shapes.isEmpty()) {
            throw new IllegalStateException("no <path> found in " + args[0]);
        }

        Path pngDir = Path.of(args[1]);
        Files.createDirectories(pngDir);

        List<byte[]> blobs = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage image = render(shapes, size);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(image, "png", buffer);
            byte[] bytes = buffer.toByteArray();
            blobs.add(bytes);
            Files.write(pngDir.resolve("netbird-" + size + ".png"), bytes);
        }

        writeIco(Path.of(args[2]), blobs);
        System.out.println("png -> " + pngDir.toAbsolutePath());
        System.out.println("ico -> " + Path.of(args[2]).toAbsolutePath());
    }

    private static List<Shape> parseShapes(String svg) {
        List<Shape> shapes = new ArrayList<>();
        Matcher tag = PATH_TAG.matcher(svg);
        while (tag.find()) {
            Matcher data = DATA_ATTR.matcher(tag.group());
            if (!data.find()) continue;
            Matcher fill = FILL_ATTR.matcher(tag.group());
            shapes.add(new Shape(data.group(1), fill.find() ? fill.group(1) : "#000000"));
        }
        return shapes;
    }

    private static BufferedImage render(List<Shape> shapes, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(new Color(0, 0, 0, 0));
            graphics.fillRect(0, 0, size, size);
            graphics.setComposite(AlphaComposite.SrcOver);

            double pad = size * PADDING_RATIO;
            double scale = Math.min((size - 2 * pad) / VIEW_WIDTH, (size - 2 * pad) / VIEW_HEIGHT);
            double offsetX = (size - VIEW_WIDTH * scale) / 2.0;
            double offsetY = (size - VIEW_HEIGHT * scale) / 2.0;

            for (Shape shape : shapes) {
                graphics.setColor(Color.decode(shape.fill()));
                graphics.fill(buildPath(shape.data(), scale, offsetX, offsetY));
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static Path2D.Double buildPath(String data, double scale, double offsetX, double offsetY) {
        Path2D.Double path = new Path2D.Double();
        Matcher tokens = TOKEN.matcher(data);
        List<String> all = new ArrayList<>();
        while (tokens.find()) all.add(tokens.group());

        int index = 0;
        char command = 'M';
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;

        while (index < all.size()) {
            String token = all.get(index);
            if (token.length() == 1 && Character.isLetter(token.charAt(0))) {
                command = token.charAt(0);
                index++;
                if (command == 'Z' || command == 'z') {
                    path.closePath();
                    x = startX;
                    y = startY;
                }
                continue;
            }

            switch (command) {
                case 'M' -> {
                    x = number(all.get(index++));
                    y = number(all.get(index++));
                    path.moveTo(mx(x, scale, offsetX), my(y, scale, offsetY));
                    startX = x;
                    startY = y;
                    command = 'L';
                }
                case 'm' -> {
                    x += number(all.get(index++));
                    y += number(all.get(index++));
                    path.moveTo(mx(x, scale, offsetX), my(y, scale, offsetY));
                    startX = x;
                    startY = y;
                    command = 'l';
                }
                case 'L' -> {
                    x = number(all.get(index++));
                    y = number(all.get(index++));
                    path.lineTo(mx(x, scale, offsetX), my(y, scale, offsetY));
                }
                case 'l' -> {
                    x += number(all.get(index++));
                    y += number(all.get(index++));
                    path.lineTo(mx(x, scale, offsetX), my(y, scale, offsetY));
                }
                case 'H' -> {
                    x = number(all.get(index++));
                    path.lineTo(mx(x, scale, offsetX), my(y, scale, offsetY));
                }
                case 'h' -> {
                    x += number(all.get(index++));
                    path.lineTo(mx(x, scale, offsetX), my(y, scale, offsetY));
                }
                case 'V' -> {
                    y = number(all.get(index++));
                    path.lineTo(mx(x, scale, offsetX), my(y, scale, offsetY));
                }
                case 'v' -> {
                    y += number(all.get(index++));
                    path.lineTo(mx(x, scale, offsetX), my(y, scale, offsetY));
                }
                case 'C' -> {
                    double x1 = number(all.get(index++));
                    double y1 = number(all.get(index++));
                    double x2 = number(all.get(index++));
                    double y2 = number(all.get(index++));
                    x = number(all.get(index++));
                    y = number(all.get(index++));
                    path.curveTo(
                        mx(x1, scale, offsetX), my(y1, scale, offsetY),
                        mx(x2, scale, offsetX), my(y2, scale, offsetY),
                        mx(x, scale, offsetX), my(y, scale, offsetY));
                }
                case 'c' -> {
                    double x1 = x + number(all.get(index++));
                    double y1 = y + number(all.get(index++));
                    double x2 = x + number(all.get(index++));
                    double y2 = y + number(all.get(index++));
                    double x3 = x + number(all.get(index++));
                    double y3 = y + number(all.get(index++));
                    path.curveTo(
                        mx(x1, scale, offsetX), my(y1, scale, offsetY),
                        mx(x2, scale, offsetX), my(y2, scale, offsetY),
                        mx(x3, scale, offsetX), my(y3, scale, offsetY));
                    x = x3;
                    y = y3;
                }
                default -> throw new IllegalStateException(
                    "path command '" + command + "' is not supported (only M/L/H/V/C/Z)");
            }
        }
        return path;
    }

    private static double mx(double x, double scale, double offset) {
        return offset + x * scale;
    }

    private static double my(double y, double scale, double offset) {
        return offset + y * scale;
    }

    private static double number(String token) {
        return Double.parseDouble(token);
    }

    private static void writeIco(Path ico, List<byte[]> blobs) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * SIZES.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0);
        header.putShort((short) 1);
        header.putShort((short) SIZES.length);

        int offset = header.capacity();
        for (int i = 0; i < SIZES.length; i++) {
            int size = SIZES[i];
            byte dimension = (byte) (size >= 256 ? 0 : size);
            header.put(dimension);
            header.put(dimension);
            header.put((byte) 0);
            header.put((byte) 0);
            header.putShort((short) 1);
            header.putShort((short) 32);
            header.putInt(blobs.get(i).length);
            header.putInt(offset);
            offset += blobs.get(i).length;
        }

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(header.array());
        for (byte[] blob : blobs) all.write(blob);

        Path parent = ico.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(ico, all.toByteArray());
    }
}
