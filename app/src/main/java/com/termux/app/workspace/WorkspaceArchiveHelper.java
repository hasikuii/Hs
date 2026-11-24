package com.termux.app.workspace;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZArchiver/RAR-style file operations: recursive copy, duplicate, directory stats,
 * compression (.zip, .tar, .tar.gz), archive inspection, and extraction with Zip-Slip protection.
 */
public final class WorkspaceArchiveHelper {

    public enum ArchiveFormat {
        ZIP(".zip"),
        TAR_GZ(".tar.gz"),
        TAR(".tar");

        public final String extension;

        ArchiveFormat(String extension) {
            this.extension = extension;
        }
    }

    public static final class ArchiveEntryInfo {
        public final String path;
        public final long size;
        public final boolean isDirectory;

        public ArchiveEntryInfo(@NonNull String path, long size, boolean isDirectory) {
            this.path = path;
            this.size = size;
            this.isDirectory = isDirectory;
        }
    }

    public static final class DirStats {
        public int files;
        public int folders;
        public long totalBytes;
    }

    public static final class CopyResult {
        public final int copiedCount;
        public final int skippedCount;
        @Nullable public final String lastError;

        public CopyResult(int copiedCount, int skippedCount, @Nullable String lastError) {
            this.copiedCount = copiedCount;
            this.skippedCount = skippedCount;
            this.lastError = lastError;
        }
    }

    /**
     * Worst-case buffer size for streaming copy / compress operations.
     * 64 KB balances syscall overhead (fewer reads/writes) against memory
     * pressure on low-end devices. For very large files (>1 GB) this keeps
     * the inner copy loop to ~16 k iterations per GB instead of ~32 k.
     */
    private static final int BUFFER_SIZE = 65536;

    /** ZIP format hard limits (without ZIP64 extensions, which Android API < 24 lacks). */
    private static final long ZIP_MAX_SINGLE_FILE = 2L * 1024 * 1024 * 1024;        // 2 GiB
    private static final long ZIP_MAX_TOTAL_SIZE  = 4L * 1024 * 1024 * 1024 - 1;    // ~4 GiB

    private WorkspaceArchiveHelper() {}

    /**
     * Progress / cancellation callback for long-running compress operations.
     * Implementations should be lightweight (called once per ~64 KB chunk).
     */
    public interface CompressProgress {
        /**
         * Called from the compress worker thread before each file is added.
         * @param bytesWritten cumulative bytes written to the output stream
         * @param totalBytes    pre-computed total uncompressed size of all sources
         * @param currentFile   short label of the file currently being compressed
         */
        void onProgress(long bytesWritten, long totalBytes, String currentFile);

        /** @return true if the user requested cancellation; compress() will throw IOException. */
        boolean isCancelled();
    }

    public static boolean isArchiveFile(@NonNull File file) {
        if (!file.isFile()) return false;
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip")
            || lower.endsWith(".tar.gz")
            || lower.endsWith(".tgz")
            || lower.endsWith(".tar")
            || lower.endsWith(".gz")
            || lower.endsWith(".jar")
            || lower.endsWith(".apk");
    }

    @NonNull
    public static String stripArchiveExtension(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz")) return name.substring(0, name.length() - 7);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name + "_extracted";
    }

    @NonNull
    public static DirStats computeStats(@NonNull File target) {
        DirStats stats = new DirStats();
        collectStats(target, stats, 0);
        return stats;
    }

    private static void collectStats(@NonNull File file, @NonNull DirStats stats, int depth) {
        if (depth > 32) return;
        if (file.isFile()) {
            stats.files++;
            stats.totalBytes += file.length();
        } else if (file.isDirectory()) {
            if (depth > 0) stats.folders++;
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collectStats(child, stats, depth + 1);
                }
            }
        }
    }

    @NonNull
    public static CopyResult copyItems(@NonNull List<File> items, @NonNull File destDir, boolean autoRenameConflicts) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            return new CopyResult(0, items.size(), "Không thể tạo thư mục đích");
        }
        if (!destDir.isDirectory()) {
            return new CopyResult(0, items.size(), "Đích đến không phải là thư mục");
        }

        String destCanonical;
        try {
            destCanonical = destDir.getCanonicalPath();
        } catch (IOException e) {
            destCanonical = destDir.getAbsolutePath();
        }

        int copied = 0;
        int skipped = 0;
        String lastError = null;

        for (File src : items) {
            if (!src.exists()) {
                skipped++;
                continue;
            }
            if (src.isDirectory()) {
                String srcCanonical;
                try {
                    srcCanonical = src.getCanonicalPath();
                } catch (IOException e) {
                    srcCanonical = src.getAbsolutePath();
                }
                if (destCanonical.equals(srcCanonical) || destCanonical.startsWith(srcCanonical + File.separator)) {
                    skipped++;
                    lastError = "Không thể sao chép thư mục vào chính nó hoặc thư mục con";
                    continue;
                }
            }

            File target = new File(destDir, src.getName());
            if (target.exists()) {
                if (autoRenameConflicts) {
                    target = resolveUniqueTarget(destDir, src.getName(), src.isDirectory());
                } else {
                    skipped++;
                    lastError = "Tên \"" + src.getName() + "\" đã tồn tại ở thư mục đích";
                    continue;
                }
            }

            try {
                copyRecursively(src, target);
                copied++;
            } catch (IOException e) {
                skipped++;
                lastError = "Lỗi sao chép " + src.getName() + ": " + e.getMessage();
            }
        }
        return new CopyResult(copied, skipped, lastError);
    }

    @Nullable
    public static File duplicateItem(@NonNull File source) throws IOException {
        File parent = source.getParentFile();
        if (parent == null || !source.exists()) return null;
        File dest = resolveUniqueTarget(parent, source.getName(), source.isDirectory());
        copyRecursively(source, dest);
        return dest;
    }

    @NonNull
    public static File resolveUniqueTarget(@NonNull File dir, @NonNull String baseName, boolean isDirectory) {
        File candidate = new File(dir, baseName);
        if (!candidate.exists()) return candidate;

        String stem = baseName;
        String ext = "";
        if (!isDirectory) {
            int dot = baseName.lastIndexOf('.');
            if (dot > 0) {
                stem = baseName.substring(0, dot);
                ext = baseName.substring(dot);
            }
        }
        int idx = 1;
        while (candidate.exists()) {
            String suffix = idx == 1 ? "_copy" : "_copy" + idx;
            candidate = new File(dir, stem + suffix + ext);
            idx++;
        }
        return candidate;
    }

    public static void copyRecursively(@NonNull File source, @NonNull File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("Cannot create directory: " + target.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
        } else {
            byte[] buf = new byte[BUFFER_SIZE];
            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        }
    }

    public static void compress(@NonNull List<File> sources,
                                @NonNull File outArchive,
                                @NonNull ArchiveFormat format) throws IOException {
        compress(sources, outArchive, format, null);
    }

    /**
     * Compress one or more files / directories into an archive.
     *
     * <p>This overload accepts a {@link CompressProgress} callback so the caller can
     * show a progress dialog and let the user cancel. It also performs two safety
     * checks that the legacy 3-arg overload skipped and that caused failures on
     * files / folders larger than ~1 GB:
     *
     * <ol>
     *   <li><b>Free-space check</b>. Before opening any stream we compare the
     *       total uncompressed size of the sources against
     *       {@code outArchive.getParentFile().getUsableSpace()}. The worst case
     *       (already-compressed inputs) is that the archive is roughly the same
     *       size as the sources, so we require at least the full source size of
     *       free space. Failing this up front produces a readable Vietnamese
     *       error instead of a cryptic {@code ENOSPC} halfway through.</li>
     *
     *   <li><b>ZIP → TAR.GZ fallback for large inputs</b>. The classic ZIP
     *       format caps single entries at 4 GiB and the total archive at 4 GiB
     *       (32-bit offsets in the central directory). Android's
     *       {@link java.util.zip.ZipOutputStream} on API &lt; 24 does not emit
     *       ZIP64 extensions, so any single file &gt; 2 GiB or total &gt; 4 GiB
     *       throws {@link java.util.zip.ZipException}. Rather than crash, we
     *       silently switch the format to {@link ArchiveFormat#TAR_GZ} (which
     *       has no practical size limit on the TAR side and uses gzip's
     *       32-bit length wrap which is handled by all standard tools) and
     *       adjust the output file extension accordingly.</li>
     * </ol>
     *
     * @param progress optional callback; null = no progress / no cancellation
     * @return the actual file written (may differ from {@code outArchive} if
     *         the format was auto-switched — caller should use this return
     *         value when posting the success Toast).
     */
    @NonNull
    public static File compress(@NonNull List<File> sources,
                                @NonNull File outArchive,
                                @NonNull ArchiveFormat format,
                                @Nullable CompressProgress progress) throws IOException {
        File parent = outArchive.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Không thể tạo thư mục chứa file nén");
        }

        // ---- 1. Compute total uncompressed size --------------------------------
        long totalBytes = 0L;
        boolean hasSingleFileOverZipLimit = false;
        for (File src : sources) {
            if (!src.exists()) continue;
            DirStats stats = computeStats(src);
            totalBytes += stats.totalBytes;
            if (src.isFile() && src.length() > ZIP_MAX_SINGLE_FILE) {
                hasSingleFileOverZipLimit = true;
            }
            // Walk the tree once more to also detect nested > 2 GiB files
            // (computeStats only sums sizes; it doesn't flag individual big files).
            if (!hasSingleFileOverZipLimit) {
                hasSingleFileOverZipLimit = containsOversizedFile(src, ZIP_MAX_SINGLE_FILE);
            }
        }

        // ---- 2. Free-space check -----------------------------------------------
        // Worst case: archive is ~ same size as sources (already-compressed data).
        // Require at least the full source size of free space; if TAR or ZIP
        // actually compresses, the leftover space is unused, which is fine.
        if (parent != null && totalBytes > 0) {
            long usable = parent.getUsableSpace();
            if (usable > 0 && usable < totalBytes) {
                throw new IOException("Không đủ dung lượng trống. Cần ít nhất "
                    + formatSize(totalBytes) + " nhưng chỉ còn " + formatSize(usable)
                    + " tại " + parent.getAbsolutePath());
            }
        }

        // ---- 3. ZIP → TAR.GZ fallback for oversized inputs ---------------------
        if (format == ArchiveFormat.ZIP
                && (hasSingleFileOverZipLimit || totalBytes > ZIP_MAX_TOTAL_SIZE)) {
            // Switch format and rewrite the output path so the extension matches.
            format = ArchiveFormat.TAR_GZ;
            String baseName = stripArchiveExtension(outArchive.getName());
            outArchive = new File(outArchive.getParentFile(),
                baseName + ArchiveFormat.TAR_GZ.extension);
        }

        // ---- 4. Real compress --------------------------------------------------
        if (format == ArchiveFormat.ZIP) {
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(outArchive), BUFFER_SIZE))) {
                for (File src : sources) {
                    if (src.exists()) {
                        writeZipEntry(src, src.getName(), zos, outArchive, progress, totalBytes, new long[]{0L});
                    }
                }
            }
        } else {
            OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(outArchive), BUFFER_SIZE);
            OutputStream streamOut = format == ArchiveFormat.TAR_GZ
                ? new GZIPOutputStream(fileOut)
                : fileOut;
            try (OutputStream out = streamOut) {
                long[] written = {0L};
                for (File src : sources) {
                    if (src.exists()) {
                        writeTarEntry(src, src.getName(), out, outArchive, progress, totalBytes, written);
                    }
                }
                out.write(new byte[1024]);
            }
        }
        return outArchive;
    }

    /** Walks {@code root} and returns true if any single file exceeds {@code limit} bytes. */
    private static boolean containsOversizedFile(@NonNull File root, long limit) {
        if (root.isFile()) return root.length() > limit;
        if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (containsOversizedFile(child, limit)) return true;
            }
        }
        return false;
    }

    /**
     * Human-readable byte size, supports up to TiB. Public so dialogs and
     * callers can share the same formatting (and not stop at MB like the
     * previous version, which printed "1024.00 MB" for 1 GiB).
     */
    @NonNull
    public static String formatSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024L) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int idx = -1;
        do {
            v /= 1024.0;
            idx++;
        } while (v >= 1024.0 && idx < units.length - 1);
        return String.format(Locale.US, "%.2f %s", v, units[idx]);
    }

    @NonNull
    public static List<ArchiveEntryInfo> listArchiveEntries(@NonNull File archive) throws IOException {
        List<ArchiveEntryInfo> entries = new ArrayList<>();
        String lower = archive.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
                readTarEntries(in, null, entries, false);
            }
        } else if (lower.endsWith(".tar")) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(archive))) {
                readTarEntries(in, null, entries, false);
            }
        } else if (lower.endsWith(".gz")) {
            String innerName = stripArchiveExtension(archive.getName());
            entries.add(new ArchiveEntryInfo(innerName, archive.length(), false));
        } else {
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    entries.add(new ArchiveEntryInfo(entry.getName(), Math.max(0L, entry.getSize()), entry.isDirectory()));
                    zis.closeEntry();
                }
            }
        }
        return entries;
    }

    public static int extract(@NonNull File archive, @NonNull File destDir) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("Không thể tạo thư mục giải nén");
        }
        String lower = archive.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
                return readTarEntries(in, destDir, null, true);
            }
        } else if (lower.endsWith(".tar")) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(archive))) {
                return readTarEntries(in, destDir, null, true);
            }
        } else if (lower.endsWith(".gz")) {
            String outName = stripArchiveExtension(archive.getName());
            File outFile = safeResolveChild(destDir, outName);
            byte[] buf = new byte[BUFFER_SIZE];
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive)));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            return 1;
        } else {
            int count = 0;
            byte[] buf = new byte[BUFFER_SIZE];
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = safeResolveChild(destDir, entry.getName());
                    if (entry.isDirectory()) {
                        if (!outFile.exists() && !outFile.mkdirs()) {
                            throw new IOException("Không thể tạo thư mục: " + outFile);
                        }
                    } else {
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IOException("Không thể tạo thư mục: " + parent);
                        }
                        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                            int len;
                            while ((len = zis.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                        }
                        count++;
                    }
                    zis.closeEntry();
                }
            }
            return count;
        }
    }

    private static void writeZipEntry(@NonNull File file,
                                      @NonNull String entryPath,
                                      @NonNull ZipOutputStream zos,
                                      @NonNull File outArchive,
                                      @Nullable CompressProgress progress,
                                      long totalBytes,
                                      @NonNull long[] writtenAccum) throws IOException {
        if (file.getCanonicalPath().equals(outArchive.getCanonicalPath())) return;
        if (progress != null && progress.isCancelled()) {
            throw new IOException("Đã hủy nén");
        }
        if (file.isDirectory()) {
            String dirEntry = entryPath.endsWith("/") ? entryPath : entryPath + "/";
            zos.putNextEntry(new ZipEntry(dirEntry));
            zos.closeEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    writeZipEntry(child, dirEntry + child.getName(), zos, outArchive,
                        progress, totalBytes, writtenAccum);
                }
            }
        } else {
            ZipEntry entry = new ZipEntry(entryPath);
            entry.setTime(file.lastModified());
            zos.putNextEntry(entry);
            byte[] buf = new byte[BUFFER_SIZE];
            if (progress != null) progress.onProgress(writtenAccum[0], totalBytes, file.getName());
            try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
                int len;
                while ((len = in.read(buf)) > 0) {
                    if (progress != null && progress.isCancelled()) {
                        throw new IOException("Đã hủy nén");
                    }
                    zos.write(buf, 0, len);
                    writtenAccum[0] += len;
                }
            }
            zos.closeEntry();
        }
    }

    private static void writeTarEntry(@NonNull File file,
                                      @NonNull String entryPath,
                                      @NonNull OutputStream out,
                                      @NonNull File outArchive,
                                      @Nullable CompressProgress progress,
                                      long totalBytes,
                                      @NonNull long[] writtenAccum) throws IOException {
        if (file.getCanonicalPath().equals(outArchive.getCanonicalPath())) return;
        if (progress != null && progress.isCancelled()) {
            throw new IOException("Đã hủy nén");
        }
        boolean isDir = file.isDirectory();
        String normalizedPath = isDir && !entryPath.endsWith("/") ? entryPath + "/" : entryPath;
        byte[] pathBytes = normalizedPath.getBytes(StandardCharsets.UTF_8);

        if (pathBytes.length > 100) {
            // Write GNU LongName ('L') metadata block for paths > 100 bytes
            byte[] longNameBytes = Arrays.copyOf(pathBytes, pathBytes.length + 1);
            byte[] longHeader = buildTarHeader("././@LongLink", longNameBytes.length, 0L, (byte) 'L', 0644);
            out.write(longHeader);
            out.write(longNameBytes);
            int pad = (512 - (longNameBytes.length % 512)) % 512;
            if (pad > 0) out.write(new byte[pad]);
        }

        long size = isDir ? 0L : file.length();
        byte typeFlag = isDir ? (byte) '5' : (byte) '0';
        int mode = isDir ? 0755 : 0644;
        String headerName = pathBytes.length > 100
            ? new String(pathBytes, 0, 100, StandardCharsets.UTF_8)
            : normalizedPath;

        out.write(buildTarHeader(headerName, size, file.lastModified() / 1000L, typeFlag, mode));

        if (!isDir) {
            byte[] buf = new byte[BUFFER_SIZE];
            long written = 0;
            if (progress != null) progress.onProgress(writtenAccum[0], totalBytes, file.getName());
            try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
                int len;
                while ((len = in.read(buf)) > 0) {
                    if (progress != null && progress.isCancelled()) {
                        throw new IOException("Đã hủy nén");
                    }
                    out.write(buf, 0, len);
                    written += len;
                    writtenAccum[0] += len;
                }
            }
            int pad = (int) ((512 - (written % 512)) % 512);
            if (pad > 0) out.write(new byte[pad]);
        } else {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    writeTarEntry(child, normalizedPath + child.getName(), out, outArchive,
                        progress, totalBytes, writtenAccum);
                }
            }
        }
    }

    @NonNull
    private static byte[] buildTarHeader(@NonNull String name, long size, long mtimeSec, byte typeFlag, int mode) {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));

        writeOctal(mode, header, 100, 8);
        writeOctal(0, header, 108, 8);
        writeOctal(0, header, 116, 8);
        writeOctal(size, header, 124, 12);
        writeOctal(mtimeSec, header, 136, 12);

        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = typeFlag;
        byte[] ustar = "ustar\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ustar, 0, header, 257, 6);
        header[263] = '0';
        header[264] = '0';

        long sum = 0;
        for (byte b : header) sum += (b & 0xFF);
        writeOctal(sum, header, 148, 7);
        header[155] = ' ';
        return header;
    }

    private static void writeOctal(long value, byte[] buf, int offset, int length) {
        String oct = Long.toOctalString(Math.max(0L, value));
        int digits = length - 1;
        int start = offset + digits - oct.length();
        for (int i = offset; i < offset + digits; i++) {
            buf[i] = (i < start) ? (byte) '0' : (byte) oct.charAt(i - start);
        }
        buf[offset + digits] = 0;
    }

    private static int readTarEntries(@NonNull InputStream in,
                                      @Nullable File destDir,
                                      @Nullable List<ArchiveEntryInfo> listOut,
                                      boolean extractFiles) throws IOException {
        byte[] header = new byte[512];
        byte[] buf = new byte[BUFFER_SIZE];
        int extractedCount = 0;
        String pendingLongName = null;

        while (true) {
            int r = readFully(in, header, 512);
            if (r < 512 || isZeroBlock(header)) break;

            String name = parseTarString(header, 0, 100);
            String prefix = parseTarString(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            if (pendingLongName != null) {
                name = pendingLongName;
                pendingLongName = null;
            }

            long size = parseOctal(header, 124, 12);
            byte typeFlag = header[156];

            if (typeFlag == 'L') {
                byte[] longBytes = new byte[(int) Math.min(size, 65536)];
                readFully(in, longBytes, longBytes.length);
                long skipExtra = size - longBytes.length;
                if (skipExtra > 0) skipFully(in, skipExtra);
                skipPadding(in, size);
                int nul = longBytes.length;
                while (nul > 0 && longBytes[nul - 1] == 0) nul--;
                pendingLongName = new String(longBytes, 0, nul, StandardCharsets.UTF_8);
                continue;
            }

            if (typeFlag == 'x' || typeFlag == 'g') {
                skipFully(in, size);
                skipPadding(in, size);
                continue;
            }

            boolean isDir = (typeFlag == '5') || name.endsWith("/");
            if (listOut != null && !name.isEmpty()) {
                listOut.add(new ArchiveEntryInfo(name, size, isDir));
            }

            if (extractFiles && destDir != null && !name.isEmpty()) {
                File outFile = safeResolveChild(destDir, name);
                if (isDir) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Không thể tạo thư mục: " + outFile);
                    }
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Không thể tạo thư mục: " + parent);
                    }
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        long remaining = size;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buf.length, remaining);
                            int n = in.read(buf, 0, toRead);
                            if (n <= 0) break;
                            out.write(buf, 0, n);
                            remaining -= n;
                        }
                    }
                    extractedCount++;
                    skipPadding(in, size);
                    continue;
                }
            }

            if (size > 0) {
                skipFully(in, size);
                skipPadding(in, size);
            }
        }
        return extractedCount;
    }

    @NonNull
    private static File safeResolveChild(@NonNull File destDir, @NonNull String entryName) throws IOException {
        File outFile = new File(destDir, entryName);
        String destCanonical = destDir.getCanonicalPath();
        String outCanonical = outFile.getCanonicalPath();
        if (!outCanonical.equals(destCanonical) && !outCanonical.startsWith(destCanonical + File.separator)) {
            throw new IOException("Đường dẫn giải nén không an toàn: " + entryName);
        }
        return outFile;
    }

    private static int readFully(@NonNull InputStream in, byte[] dst, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(dst, total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void skipFully(@NonNull InputStream in, long bytes) throws IOException {
        byte[] tmp = new byte[4096];
        long remaining = bytes;
        while (remaining > 0) {
            int n = in.read(tmp, 0, (int) Math.min(tmp.length, remaining));
            if (n <= 0) break;
            remaining -= n;
        }
    }

    private static void skipPadding(@NonNull InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) skipFully(in, pad);
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    @NonNull
    private static String parseTarString(byte[] buf, int offset, int maxLen) {
        int len = 0;
        while (len < maxLen && buf[offset + len] != 0) len++;
        return new String(buf, offset, len, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] buf, int offset, int maxLen) {
        long result = 0;
        boolean started = false;
        for (int i = 0; i < maxLen; i++) {
            byte b = buf[offset + i];
            if (b == 0 || b == ' ') {
                if (started) break;
                continue;
            }
            if (b >= '0' && b <= '7') {
                started = true;
                result = (result << 3) + (b - '0');
            }
        }
        return result;
    }
}
