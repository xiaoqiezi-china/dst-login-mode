package dev.dstmode.agent.tool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 构建期工具：把打进 jar 的 ASM 从 org/objectweb/asm 重定位到 dev/dstmode/agent/asm。
 *
 * <p>Fabric Loader 会检查 classpath 上是否存在重复类，未重定位的 ASM 会和
 * Minecraft 自带的 ASM 冲突并导致启动失败。</p>
 *
 * <p>保持 Java 8 语法：本工程与 agent 本体共用同一 source set。</p>
 */
public final class Relocate {
    private static final String FROM = "org/objectweb/asm";
    private static final String TO = "dev/dstmode/agent/asm";

    private Relocate() {
    }

    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(args[0]);
        Path tmp = Paths.get(args[0] + ".tmp");
        int classes = 0;
        int renamed = 0;
        ZipFile zin = new ZipFile(jar.toFile());
        ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(tmp));
        try {
            Enumeration<? extends ZipEntry> entries = zin.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] data = readAll(zin.getInputStream(entry));
                String outName = name;
                if (name.endsWith(".class")) {
                    data = rewrite(data);
                    classes++;
                    if (name.startsWith(FROM + "/")) {
                        outName = TO + name.substring(FROM.length());
                        renamed++;
                    }
                }
                ZipEntry copy = new ZipEntry(outName);
                copy.setTime(entry.getTime());
                zout.putNextEntry(copy);
                zout.write(data);
                zout.closeEntry();
            }
        } finally {
            zout.close();
            zin.close();
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[relocate] classes=" + classes + " asm_entries=" + renamed);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] rewrite(byte[] buf) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length);
        DataOutputStream dout = new DataOutputStream(out);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(buf));
        try {
            dout.writeInt(in.readInt());
            dout.writeShort(in.readUnsignedShort());
            dout.writeShort(in.readUnsignedShort());
            int cpCount = in.readUnsignedShort();
            dout.writeShort(cpCount);
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                dout.writeByte(tag);
                switch (tag) {
                    case 1: {
                        String text = in.readUTF();
                        if (text.contains(FROM)) {
                            text = text.replace(FROM, TO);
                        }
                        dout.writeUTF(text);
                        break;
                    }
                    case 3:
                    case 4:
                        dout.writeInt(in.readInt());
                        break;
                    case 5:
                    case 6:
                        dout.writeLong(in.readLong());
                        i++;
                        break;
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        dout.writeShort(in.readUnsignedShort());
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        dout.writeShort(in.readUnsignedShort());
                        dout.writeShort(in.readUnsignedShort());
                        break;
                    case 15:
                        dout.writeByte(in.readUnsignedByte());
                        dout.writeShort(in.readUnsignedShort());
                        break;
                    default:
                        throw new IOException("unknown constant pool tag " + tag);
                }
            }
            dout.write(readAll(in));
        } finally {
            in.close();
        }
        dout.flush();
        return out.toByteArray();
    }
}
