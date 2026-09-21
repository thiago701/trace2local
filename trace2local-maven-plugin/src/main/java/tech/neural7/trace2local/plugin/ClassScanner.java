package tech.neural7.trace2local.plugin;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Engenharia reversa por bytecode (ASM): identifica os recursos que serão
 * mapeados no canvas do Trace2Local — endpoints Spring, métodos de negócio
 * {@code @Trace2Local}, serviços AWS SDK v2, JDBC — e audita a higiene de logs
 * (SLF4J × {@code System.out}, {@code printStackTrace}).
 */
public final class ClassScanner {

    private static final String SPRING_REST = "Lorg/springframework/web/bind/annotation/RestController;";
    private static final String SPRING_CONTROLLER = "Lorg/springframework/web/bind/annotation/Controller;";
    private static final String REQUEST_MAPPING = "Lorg/springframework/web/bind/annotation/RequestMapping;";
    private static final String TRACE_VANTA = "Ltech/neural7/trace2local/spring/Trace2Local;";

    public Findings scan(Path classesDir) throws IOException {
        Findings findings = new Findings();
        if (!Files.isDirectory(classesDir)) {
            return findings;
        }
        try (Stream<Path> walk = Files.walk(classesDir)) {
            for (Path classFile : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                scanClass(classFile, findings);
            }
        }
        return findings;
    }

    private void scanClass(Path classFile, Findings findings) {
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {

                private String className = "?";
                private String classPathPrefix = "";
                private boolean isController;
                private String currentMethod = "?";

                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    className = name.replace('/', '.');
                    findings.scannedClasses++;
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (SPRING_REST.equals(descriptor) || SPRING_CONTROLLER.equals(descriptor)) {
                        isController = true;
                    }
                    if (TRACE_VANTA.equals(descriptor)) {
                        findings.businessMethods.add(className + " (classe)");
                    }
                    if (REQUEST_MAPPING.equals(descriptor)) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String name) {
                                if ("value".equals(name)) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String n, Object value) {
                                            classPathPrefix = String.valueOf(value);
                                        }
                                    };
                                }
                                return null;
                            }

                            @Override
                            public void visit(String n, Object value) {
                                if ("value".equals(n)) {
                                    classPathPrefix = String.valueOf(value);
                                }
                            }
                        };
                    }
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    currentMethod = name;
                    return new MethodVisitor(Opcodes.ASM9) {
                        private final StringBuilder path = new StringBuilder();
                        private String httpMethod;

                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            if (TRACE_VANTA.equals(desc)) {
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String n, Object value) {
                                        findings.businessMethods.add(className + "#" + currentMethod
                                                + " (@" + value + ")");
                                    }
                                };
                            }
                            if (REQUEST_MAPPING.equals(desc)) {
                                httpMethod = "ANY";
                                return pathVisitor();
                            }
                            String m = methodOf(desc);
                            if (m != null) {
                                httpMethod = m;
                                return pathVisitor();
                            }
                            return null;
                        }

                        private AnnotationVisitor pathVisitor() {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public AnnotationVisitor visitArray(String name) {
                                    if ("value".equals(name)) {
                                        return new AnnotationVisitor(Opcodes.ASM9) {
                                            @Override
                                            public void visit(String n, Object value) {
                                                appendPath(value);
                                            }
                                        };
                                    }
                                    return null;
                                }

                                @Override
                                public void visit(String n, Object value) {
                                    if ("value".equals(n)) {
                                        appendPath(value);
                                    }
                                }
                            };
                        }

                        private void appendPath(Object value) {
                            if (path.length() > 0) {
                                path.append(",");
                            }
                            path.append(String.valueOf(value));
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                            if ("java/lang/System".equals(owner) && ("out".equals(name) || "err".equals(name))) {
                                findings.systemOutSites.add(className + "#" + currentMethod);
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            if (owner.startsWith("software/amazon/awssdk/services/")) {
                                // software/amazon/awssdk/services/<service>/...
                                String[] parts = owner.split("/");
                                String service = parts.length > 4 ? parts[4] : "?";
                                findings.awsServices.add(service);
                            } else if (owner.startsWith("java/sql")) {
                                findings.usesJdbc = true;
                            } else if (owner.startsWith("org/slf4j/")) {
                                findings.slf4jUsages++;
                            } else if ("printStackTrace".equals(name)) {
                                findings.printStackTraceSites++;
                            }
                        }

                        @Override
                        public void visitEnd() {
                            if (isController && httpMethod != null && path.length() > 0) {
                                String full = join(classPathPrefix, path.toString());
                                findings.endpoints.add(new Findings.Endpoint(
                                        httpMethod, full, className + "#" + currentMethod));
                            }
                        }
                    };
                }
            }, 0);
        } catch (IOException | RuntimeException ignored) {
            // classe ilegível é pulada — engenharia reversa é best-effort
        }
    }

    private static String join(String prefix, String methodPath) {
        if (prefix == null || prefix.isBlank() || "/".equals(prefix)) {
            return methodPath.startsWith("/") ? methodPath : "/" + methodPath;
        }
        if (methodPath.startsWith("/")) {
            return prefix + methodPath;
        }
        return prefix.endsWith("/") ? prefix + methodPath : prefix + "/" + methodPath;
    }

    private static String methodOf(String descriptor) {
        return switch (descriptor) {
            case "Lorg/springframework/web/bind/annotation/GetMapping;" -> "GET";
            case "Lorg/springframework/web/bind/annotation/PostMapping;" -> "POST";
            case "Lorg/springframework/web/bind/annotation/PutMapping;" -> "PUT";
            case "Lorg/springframework/web/bind/annotation/DeleteMapping;" -> "DELETE";
            case "Lorg/springframework/web/bind/annotation/PatchMapping;" -> "PATCH";
            default -> null;
        };
    }
}
