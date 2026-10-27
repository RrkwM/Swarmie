import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Project-local execution-context probe used by verify-runtime.ps1.
 *
 * Loom's Minecraft jar processing requires ZipFS to be writable. A normal
 * standard-user PowerShell session must make Files.isWritable() true for a
 * freshly created file and open a writable ZipFS.
 */
public final class ContextProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: ContextProbe <fresh-directory>");
            System.exit(2);
        }

        Path dir = Paths.get(args[0]).toAbsolutePath();
        Files.createDirectories(dir);
        boolean ok = true;

        System.out.println("DIR=" + dir);
        System.out.println("DIR_FS_READONLY=" + dir.getFileSystem().isReadOnly());

        Path plain = dir.resolve("plain.txt");
        Files.deleteIfExists(plain);
        Files.writeString(plain, "context-probe");
        boolean plainWritable = Files.isWritable(plain);
        boolean parentWritable = Files.isWritable(dir);
        System.out.println("PLAIN_WRITE=ok");
        System.out.println("PLAIN_IS_WRITABLE=" + plainWritable);
        System.out.println("PARENT_IS_WRITABLE=" + parentWritable);
        if (!plainWritable || !parentWritable) {
            ok = false;
        }

        Path zipOut = dir.resolve("zipout.zip");
        Files.deleteIfExists(zipOut);
        try (OutputStream output = Files.newOutputStream(zipOut);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("a.txt"));
            zip.write("x".getBytes());
            zip.closeEntry();
        }
        System.out.println("ZIPOUT_WRITE=ok");

        Path zipFs = dir.resolve("zipfs.zip");
        Files.deleteIfExists(zipFs);
        try (FileSystem fs = FileSystems.newFileSystem(
                URI.create("jar:" + zipFs.toUri()), Map.of("create", "true"))) {
            System.out.println("ZIPFS_CREATE_READONLY=" + fs.isReadOnly());
            Files.createDirectories(fs.getPath("d"));
            Files.writeString(fs.getPath("d/x.txt"), "x");
            System.out.println("ZIPFS_CREATE_WRITE=ok");
            if (fs.isReadOnly()) {
                ok = false;
            }
        }
        catch (Throwable t) {
            System.out.println("ZIPFS_CREATE_WRITE=FAIL " + t);
            ok = false;
        }

        if (ok) {
            System.out.println("CONTEXT_OK");
            System.exit(0);
        }
        System.out.println("CONTEXT_FAIL");
        System.exit(3);
    }
}
