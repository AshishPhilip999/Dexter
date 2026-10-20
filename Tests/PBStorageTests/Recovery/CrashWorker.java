package PBStorageTests.Recovery;

import java.io.PrintStream;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import Utility.StorageProviders.PageBasedStorageProvider.Durability;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;

/*
Run as a separate program by RecoveryTests and killed part-way:
    CrashWorker <path> <seed> <NORMAL|SYNC> <compactEvery (0 = never)> [keys]
Runs CrashScript's operations forever, printing "OP i" once operation i has
returned (flushed every 25 operations, so the last line seen is a lower bound).
*/
public final class CrashWorker {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        long seed = Long.parseLong(args[1]);
        Durability durability = Durability.valueOf(args[2]);
        int compactEvery = Integer.parseInt(args[3]);
        int keys = args.length > 4 ? Integer.parseInt(args[4]) : CrashScript.KEYS;
        PrintStream out = System.out;

        PBKeyStorageProvider<Integer> storage = new PBKeyStorageProvider<>(path, Integer.class, durability);
        out.println("READY");
        out.flush();

        for (int i = 0; ; i++) {
            PBResult result = CrashScript.apply(storage, CrashScript.op(seed, i, keys));
            if (!result.hasSucceeded() && result.getStatus() != PBFail.KEY_NOT_FOUND) {
                out.println("FAILED " + i + " " + result);
                out.flush();
                System.exit(3);
            }
            if (compactEvery > 0 && i % compactEvery == compactEvery - 1) {
                PBResult compacted = storage.compact();
                if (!compacted.hasSucceeded()) {
                    out.println("FAILED compact " + compacted);
                    out.flush();
                    System.exit(3);
                }
            }
            out.println("OP " + i);
            if (i % 25 == 0) {
                out.flush();
            }
        }
    }
}
