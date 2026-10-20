package PBStorageTests.Recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import Utility.StorageProviders.PageBasedStorageProvider.Durability;
import Utility.StorageProviders.PageBasedStorageProvider.Journal.Journal;
import Utility.StorageProviders.PageBasedStorageProvider.Journal.JournaledFile;

/*
The journal layer on its own: a JournaledFile must always read exactly like a
plain byte array that receives the same writes and truncations -- including an
operation's own uncommitted writes, regions cut off by a truncation, and after
commit, rollback, checkpoint and a crash followed by replay.
*/
public class JournalTests {
    @TempDir
    Path dir;

    /*
    A plain in-memory file: what JournaledFile must look like.
    */
    static final class Model {
        byte[] bytes = new byte[0];

        Model copy() {
            Model m = new Model();
            m.bytes = this.bytes.clone();
            return m;
        }

        void write(long position, byte[] data) {
            int end = (int) position + data.length;
            if (end > this.bytes.length) {
                this.bytes = Arrays.copyOf(this.bytes, end);
            }
            System.arraycopy(data, 0, this.bytes, (int) position, data.length);
        }

        void truncate(long length) {
            if (length < this.bytes.length) {
                this.bytes = Arrays.copyOf(this.bytes, (int) length);
            }
        }
    }

    private static void assertSame(Model model, JournaledFile file, String when) throws Exception {
        assertEquals(model.bytes.length, file.length(), when + ": length");
        if (model.bytes.length > 0) {
            assertArrayEquals(model.bytes, file.read(0, model.bytes.length), when + ": content");
        }
    }

    private static byte[] randomBytes(Random random, int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    @Test
    public void readsMatchAPlainFileThroughEveryStep() throws Exception {
        Random random = new Random(42);
        Path folder = Files.createDirectories(this.dir.resolve("j.dex"));
        Journal journal = Journal.open(folder, Durability.NORMAL);
        JournaledFile file = journal.openFile(Journal.DATA_FILE);

        Model current = new Model();     // what reads must show now
        Model committed = new Model();   // what survives a rollback or a crash

        for (int step = 0; step < 4_000; step++) {
            int action = random.nextInt(100);
            if (action < 45) {
                long position = random.nextInt(60_000);
                byte[] data = randomBytes(random, 1 + random.nextInt(9_000));
                file.write(position, data);
                current.write(position, data);
            } else if (action < 52) {
                long length = random.nextInt(Math.max(1, current.bytes.length + 1));
                file.truncate(length);
                current.truncate(length);
            } else if (action < 75) {
                if (current.bytes.length > 0) {
                    int position = random.nextInt(current.bytes.length);
                    int length = 1 + random.nextInt(current.bytes.length - position);
                    assertArrayEquals(Arrays.copyOfRange(current.bytes, position, position + length),
                        file.read(position, length), "step " + step + ": read " + position + "+" + length);
                }
            } else if (action < 85) {
                journal.commit();
                committed = current.copy();
            } else if (action < 92) {
                journal.rollback();
                current = committed.copy();
                assertSame(current, file, "step " + step + " after rollback");
            } else if (action < 96) {
                journal.commit();
                committed = current.copy();
                journal.checkpoint();
                assertArrayEquals(committed.bytes, Files.readAllBytes(folder.resolve("data.pb")),
                    "step " + step + ": the file on disk after a checkpoint");
            } else {
                // A crash: uncommitted changes are lost, committed ones are replayed.
                journal.commit();
                committed = current.copy();
                file.close();
                journal.close();
                journal = Journal.open(folder, Durability.NORMAL);
                file = journal.openFile(Journal.DATA_FILE);
                assertArrayEquals(committed.bytes, Files.readAllBytes(folder.resolve("data.pb")),
                    "step " + step + ": replayed into the file");
            }
            if (step % 50 == 0) {
                assertSame(current, file, "step " + step);
            }
        }
        file.close();
        journal.close();
    }

    @Test
    public void bytesCutOffByATruncationReadAsZeros() throws Exception {
        Path folder = Files.createDirectories(this.dir.resolve("t.dex"));
        Journal journal = Journal.open(folder, Durability.NORMAL);
        JournaledFile file = journal.openFile(Journal.DATA_FILE);
        byte[] ones = new byte[20_000];
        Arrays.fill(ones, (byte) 1);
        file.write(0, ones);
        journal.commit();
        journal.checkpoint();   // the ones are on disk

        file.truncate(100);
        file.write(15_000, new byte[] { 7 });

        byte[] gap = file.read(100, 14_900);
        assertArrayEquals(new byte[14_900], gap, "not the old bytes still on disk");
        journal.commit();
        assertArrayEquals(new byte[14_900], file.read(100, 14_900), "after commit");
        journal.checkpoint();
        assertArrayEquals(new byte[14_900], file.read(100, 14_900), "after checkpoint");
        assertEquals(15_001, Files.size(folder.resolve("data.pb")));
        file.close();
        journal.close();
    }

    /*
    One operation cuts the file short and then writes back bytes identical to
    what was there before. Replay cuts first, so those bytes must still be in
    the record even though, compared with before, they "didn't change".
    */
    @Test
    public void bytesRewrittenAfterATruncationSurviveReplay() throws Exception {
        Path folder = Files.createDirectories(this.dir.resolve("r.dex"));
        Journal journal = Journal.open(folder, Durability.NORMAL);
        JournaledFile file = journal.openFile(Journal.DATA_FILE);
        byte[] pattern = new byte[12_000];
        new Random(3).nextBytes(pattern);
        file.write(0, pattern);
        journal.commit();
        journal.checkpoint();

        file.read(0, 12_000);                                  // (reads change nothing)
        file.write(5_000, new byte[] { pattern[5_000] });      // touch a block first
        file.truncate(1_000);
        file.write(1_000, Arrays.copyOfRange(pattern, 1_000, 12_000));   // the same bytes again
        journal.commit();

        file.close();
        journal.close();                                       // crash: the files still hold the old bytes
        Journal reopened = Journal.open(folder, Durability.NORMAL);
        assertEquals(1, reopened.getRecoveredOperations());
        assertArrayEquals(pattern, Files.readAllBytes(folder.resolve("data.pb")));
        reopened.close();
    }

    @Test
    public void anOperationReadsItsOwnUncommittedWrites() throws Exception {
        Path folder = Files.createDirectories(this.dir.resolve("p.dex"));
        Journal journal = Journal.open(folder, Durability.NORMAL);
        JournaledFile file = journal.openFile(Journal.DATA_FILE);
        file.write(0, new byte[10_000]);
        journal.commit();

        file.write(5_000, new byte[] { 1, 2, 3 });
        assertArrayEquals(new byte[] { 1, 2, 3 }, file.read(5_000, 3));
        journal.rollback();
        assertArrayEquals(new byte[3], file.read(5_000, 3));
        assertThrows(EOFException.class, () -> file.read(9_999, 2));
        file.close();
        journal.close();
    }
}
