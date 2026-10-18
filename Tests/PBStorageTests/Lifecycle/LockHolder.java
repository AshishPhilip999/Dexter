package PBStorageTests.Lifecycle;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;

/*
Run as a separate program by CloseAndLockTests: opens the storage at args[0],
writes key 1, prints "READY <pid>", then holds the storage open until a line
arrives on stdin, closes it and prints "CLOSED".
*/
public final class LockHolder {
    public static void main(String[] args) throws Exception {
        PBKeyStorageProvider<Integer> storage = new PBKeyStorageProvider<>(args[0], Integer.class);
        storage.write(1, new byte[] { 42 });
        System.out.println("READY " + ProcessHandle.current().pid());
        System.out.flush();

        new BufferedReader(new InputStreamReader(System.in)).readLine();
        storage.close();
        System.out.println("CLOSED");
        System.out.flush();
    }
}
