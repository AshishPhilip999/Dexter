package Utility.StorageProviders.PageBasedStorageProvider.Utility;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import Utility.StorageProviders.PageBasedStorageProvider.StorageInUseException;

/*
Exclusive lock on a storage's dex.lock file, held while the storage is open.

The lock is taken through the operating system (FileChannel.tryLock), so it
works across programs, and the operating system drops it when the program
exits or crashes: there is no stale lock to clean up. Inside one program, a
second lock on the same file is refused too.

The lock covers one byte past the file's text, so the text (the holder's
process id, for error messages) stays readable on Windows, where a locked
region can't be read by other programs.

Locks on network file systems (NFS, SMB) depend on the server and may not be
enforced.
*/
public final class DexLock implements Closeable {
    private static final long LOCK_POSITION = 4096;

    private final FileChannel channel;
    private final FileLock lock;

    private DexLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /*
    Locks the storage in folder, or throws StorageInUseException if it is
    already locked.
    */
    public static DexLock acquire(Path folder) throws IOException {
        Path lockFile = DexBundle.lockFile(folder);
        FileChannel channel = FileChannel.open(
            lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE
        );

        FileLock lock;
        try {
            lock = channel.tryLock(LOCK_POSITION, 1, false);
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new StorageInUseException(folder, "in this program (close() the other instance first)");
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }

        if (lock == null) {
            String holder = readHolder(lockFile);
            channel.close();
            throw new StorageInUseException(folder, "by another program" + (holder.isEmpty() ? "" : " (" + holder + ")"));
        }

        try {
            byte[] text = ("pid " + ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.UTF_8);
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(text), 0);
        } catch (IOException | RuntimeException e) {
            // Only informative; the lock itself is held.
        }
        return new DexLock(channel, lock);
    }

    /*
    Releases the lock. Safe to call more than once.
    */
    @Override
    public void close() throws IOException {
        try {
            if (this.lock.isValid()) {
                this.lock.release();
            }
        } finally {
            this.channel.close();
        }
    }

    /*
    The text the current holder wrote, e.g. "pid 4242", or "" if unreadable.
    */
    private static String readHolder(Path lockFile) {
        try {
            String text = new String(Files.readAllBytes(lockFile), StandardCharsets.UTF_8).trim();
            return text.length() > 100 ? "" : text;
        } catch (Exception e) {
            return "";
        }
    }
}
