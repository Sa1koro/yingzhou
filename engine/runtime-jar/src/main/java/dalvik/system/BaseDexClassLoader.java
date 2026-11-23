package dalvik.system;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Desktop stand-in for Android's dex class loaders. Spiders (and shells) that download extra dex files and load
 * them with {@code new DexClassLoader(...)} get the files converted to class files by the engine's dex converter
 * ({@link #converter}). Classes are defined by this loader, so
 * {@code getClass().getClassLoader()} is the loader the spider created.
 */
public class BaseDexClassLoader extends ClassLoader {

    static { registerAsParallelCapable(); }

    /** Installed by the desktop jar runtime: turns a dex file or dex jar into a jar of class files. */
    public interface Converter {
        File convert(File dexOrJar) throws Exception;
    }

    public static volatile Converter converter;

    private final String dexPath;
    private final List<JarFile> jars = new ArrayList<>();
    private final List<File> jarFiles = new ArrayList<>();
    private final List<String> unsupported = new ArrayList<>();
    private final String librarySearchPath;

    public BaseDexClassLoader(String dexPath, File optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(parent);
        this.dexPath = dexPath == null ? "" : dexPath;
        this.librarySearchPath = librarySearchPath;
        for (String path : splitPath(this.dexPath)) {
            File file = new File(path);
            try {
                Converter c = converter;
                File converted = c != null ? c.convert(file) : file;
                jars.add(new JarFile(converted));
                jarFiles.add(converted);
            } catch (Throwable e) {
                unsupported.add(path);
                System.err.println("dex class loader: cannot load " + path + ": " + e);
            }
        }
    }

    public BaseDexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        this(dexPath, optimizedDirectory == null ? null : new File(optimizedDirectory), librarySearchPath, parent);
    }

    /** Android separates entries with ':', which collides with Windows drive letters ("C:\..."). */
    static List<String> splitPath(String dexPath) {
        List<String> out = new ArrayList<>();
        if (dexPath.isEmpty()) return out;
        if (new File(dexPath).exists()) {
            out.add(dexPath);
            return out;
        }
        for (String part : dexPath.split(File.pathSeparatorChar == ';' ? ";|:(?![\\\\/])" : ":")) {
            if (!part.isEmpty()) out.add(part);
        }
        // Re-join a drive letter split off by the pattern above ("C" + "\path").
        List<String> joined = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            String p = out.get(i);
            if (p.length() == 1 && Character.isLetter(p.charAt(0)) && i + 1 < out.size()) {
                joined.add(p + ":" + out.get(++i));
            } else {
                joined.add(p);
            }
        }
        return joined;
    }

    protected final Class<?> findDexClass(String name) throws ClassNotFoundException {
        String entry = name.replace('.', '/') + ".class";
        for (JarFile jar : jars) {
            ZipEntry e = jar.getEntry(entry);
            if (e == null) continue;
            try (InputStream in = jar.getInputStream(e)) {
                byte[] bytes = readAll(in);
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException ex) {
                throw new ClassNotFoundException(name, ex);
            }
        }
        throw new ClassNotFoundException(name + " (dex path: " + dexPath + ")");
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return findDexClass(name);
    }

    @Override
    protected URL findResource(String name) {
        for (int i = 0; i < jars.size(); i++) {
            if (jars.get(i).getEntry(name) != null) {
                try {
                    return new URL("jar:" + jarFiles.get(i).toURI().toURL() + "!/" + name);
                } catch (MalformedURLException ignored) {
                }
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        URL url = findResource(name);
        return url == null ? Collections.emptyEnumeration() : Collections.enumeration(Collections.singletonList(url));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    public String getDexPath() {
        return dexPath;
    }

    public List<String> getUnsupportedPaths() {
        return Collections.unmodifiableList(unsupported);
    }

    public boolean isFunctional() {
        return !jars.isEmpty();
    }

    public String findLibrary(String name) {
        return null;
    }

    @Override
    public String toString() {
        return getClass().getName() + "[" + dexPath + "]";
    }
}
