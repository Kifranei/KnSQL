import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Makes ojdbc initialize on Android, which has no java.lang.management. */
public final class PatchOracleJdbc {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        try (JarFile jar = new JarFile(input.toFile());
             OutputStream fileOut = Files.newOutputStream(output);
             JarOutputStream jarOut = new JarOutputStream(fileOut, jar.getManifest())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if ("META-INF/MANIFEST.MF".equals(entry.getName())) continue;
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (entry.getName().endsWith(".class")) bytes = transform(bytes);
                JarEntry next = new JarEntry(entry.getName());
                jarOut.putNextEntry(next);
                jarOut.write(bytes);
                jarOut.closeEntry();
            }
        }
    }

    private static byte[] transform(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new Visitor(writer), 0);
        return writer.toByteArray();
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
