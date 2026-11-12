import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Makes ojdbc initialize on Android, which has no java.lang.management.
 *
 * With a third argument the driver is also moved under that package prefix (for example
 * {@code dev/knsql/ojdbc19/}), so an older ojdbc can sit next to the current one: the legacy
 * copy then answers to {@code dev.knsql.ojdbc19.oracle.jdbc.OracleDriver} and loads only its own
 * classes, message bundles and property files.
 */
public final class PatchOracleJdbc {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        String prefix = args.length > 2 ? args[2] : null;
        Files.createDirectories(output.getParent());
        try (JarFile jar = new JarFile(input.toFile());
             OutputStream fileOut = Files.newOutputStream(output);
             JarOutputStream jarOut = new JarOutputStream(fileOut, jar.getManifest())) {
            Relocation relocation = prefix == null ? null : new Relocation(prefix, jar);
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if ("META-INF/MANIFEST.MF".equals(name) || entry.isDirectory()) continue;
                // A relocated driver is created directly, never through ServiceLoader, so it can't
                // claim jdbc:oracle: URLs ahead of the current driver.
                if (relocation != null && name.startsWith("META-INF/services/")) continue;
                if (relocation != null && name.startsWith("META-INF/versions/")) continue;
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (name.endsWith(".class")) bytes = transform(bytes, relocation);
                if (relocation != null && name.startsWith("oracle/")) name = relocation.prefix + name;
                JarEntry next = new JarEntry(name);
                jarOut.putNextEntry(next);
                jarOut.write(bytes);
                jarOut.closeEntry();
            }
        }
    }

    private static byte[] transform(byte[] bytes, Relocation relocation) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        ClassVisitor sink = relocation == null ? writer : new ClassRemapper(writer, relocation);
        reader.accept(new Visitor(sink), 0);
        return writer.toByteArray();
    }

    /**
     * Moves every {@code oracle/...} type under {@link #prefix}. String constants are moved only
     * when they name something inside the jar (a class, a message bundle, a resource file or a
     * class-name prefix), so connection property names such as {@code oracle.jdbc.ReadTimeout}
     * keep working.
     */
    private static final class Relocation extends Remapper {
        final String prefix;
        private final Set<String> entries = new HashSet<>();
        private final Set<String> packages = new HashSet<>();

        Relocation(String prefix, JarFile jar) {
            this.prefix = prefix.endsWith("/") ? prefix : prefix + "/";
            jar.stream().forEach(entry -> {
                String name = entry.getName();
                entries.add(name);
                int slash = name.lastIndexOf('/');
                if (name.endsWith(".class") && slash > 0) packages.add(name.substring(0, slash + 1));
            });
        }

        @Override
        public String map(String internalName) {
            return internalName.startsWith("oracle/") ? prefix + internalName : internalName;
        }

        @Override
        public Object mapValue(Object value) {
            if (value instanceof String text) return mapString(text);
            return super.mapValue(value);
        }

        private String mapString(String text) {
            if (text.startsWith("/oracle/") && entries.contains(text.substring(1))) return "/" + prefix + text.substring(1);
            if (text.startsWith("oracle/") && (entries.contains(text) || entries.contains(text + ".class"))) return prefix + text;
            if (!text.startsWith("oracle.") || text.contains(" ") || text.contains("/")) return text;
            String path = text.replace('.', '/');
            boolean known = entries.contains(path + ".class") || entries.contains(path + ".properties");
            // "oracle.net.ano." + name builds class names; "oracle." and "oracle.jdbc." prefix property names.
            boolean classPrefix = text.endsWith(".") && !"oracle.".equals(text) && !"oracle.jdbc.".equals(text)
                    && packages.contains(path);
            if (!known && !classPrefix) return text;
            return prefix.replace('/', '.') + text;
        }
    }

    private static final class Visitor extends ClassVisitor {
        private String className = "";

        Visitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name;
            if ("oracle/jdbc/diagnostics/OracleDiagnosticsMXBean".equals(name)) {
                superName = "java/lang/Object";
            }
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = new AndroidRegexFix(super.visitMethod(access, name, descriptor, signature, exceptions));
            boolean driver = "oracle/jdbc/driver/OracleDriver".equals(className);
            boolean listener = className.endsWith("BlockReleaserListener") || className.endsWith("MemGuardListener");
            if ("oracle/jdbc/driver/PhysicalConnection".equals(className)
                    && "setLifecycle".equals(name)
                    && "(I)V".equals(descriptor)) {
                return replaceWith(delegate, () -> {
                    delegate.visitVarInsn(Opcodes.ALOAD, 0);
                    delegate.visitVarInsn(Opcodes.ILOAD, 1);
                    delegate.visitFieldInsn(
                            Opcodes.PUTFIELD,
                            "oracle/jdbc/driver/PhysicalConnection",
                            "lifecycle",
                            "I"
                    );
                    delegate.visitInsn(Opcodes.RETURN);
                    delegate.visitMaxs(2, 2);
                });
            }
            if (driver && ("registerMBeans".equals(name) || "unRegisterMBeans".equals(name))) {
                return replaceWith(delegate, () -> {
                    delegate.visitInsn(Opcodes.RETURN);
                    delegate.visitMaxs(0, 0);
                });
            }
            if (listener && "<init>".equals(name)) {
                return replaceWith(delegate, () -> {
                    delegate.visitVarInsn(Opcodes.ALOAD, 0);
                    delegate.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                    delegate.visitInsn(Opcodes.RETURN);
                    delegate.visitMaxs(1, 1);
                });
            }
            if (listener && "unregister".equals(name)) {
                return replaceWith(delegate, () -> {
                    delegate.visitInsn(Opcodes.RETURN);
                    delegate.visitMaxs(0, 1);
                });
            }
            if (driver && "<clinit>".equals(name)) return new OneArgRegister(delegate);
            if ("oracle/jdbc/diagnostics/OracleDiagnosticsMXBean".equals(className)) {
                return new DropStandardMBeanInit(delegate);
            }
            return delegate;
        }
    }

    /** Android's ICU regex rejects Oracle's `[A-z` ranges and possessive `++`. */
    private static String androidRegex(String value) {
        if (!value.contains("[A-z") && !value.contains(")++") && !value.contains("]++") && !value.contains("(?")) {
            return value;
        }
        return value
                .replace("[A-z0-9-._:]+", "[A-Za-z0-9._:-]+")
                .replace("[A-z0-9-._]+", "[A-Za-z0-9._-]+")
                .replace("[A-z0-9._-]", "[A-Za-z0-9._-]")
                .replace("[A-z0-9:]", "[A-Za-z0-9:]")
                .replace("[A-z0-9,-.]", "[A-Za-z0-9,.-]")
                .replace("[A-z]", "[A-Za-z]")
                .replace("[A-z", "[A-Za-z")
                .replace(")++", ")+")
                .replace("]++", "]+")
                .replace("(?iU)", "(?i)")
                .replace("(?Ui)", "(?i)")
                .replace("(?iu)", "(?i)")
                .replace("(?u)", "")
                .replace("(?U)", "");
    }

    private static final class AndroidRegexFix extends MethodVisitor {
        AndroidRegexFix(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String text) super.visitLdcInsn(androidRegex(text));
            else super.visitLdcInsn(value);
        }
    }

    private static MethodVisitor replaceWith(MethodVisitor delegate, Runnable body) {
        return new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitEnd() {
                delegate.visitCode();
                body.run();
                delegate.visitEnd();
            }
        };
    }

    private static final class DropStandardMBeanInit extends MethodVisitor {
        DropStandardMBeanInit(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESPECIAL
                    && "javax/management/StandardMBean".equals(owner)
                    && "<init>".equals(name)
                    && "(Ljava/lang/Class;Z)V".equals(descriptor)) {
                super.visitInsn(Opcodes.POP);
                super.visitInsn(Opcodes.POP);
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    private static final class OneArgRegister extends MethodVisitor {
        OneArgRegister(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            if ("deregister".equals(name) && descriptor.contains("DriverAction")) return;
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if ("java/sql/DriverManager".equals(owner)
                    && "registerDriver".equals(name)
                    && "(Ljava/sql/Driver;Ljava/sql/DriverAction;)V".equals(descriptor)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, "(Ljava/sql/Driver;)V", false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}
