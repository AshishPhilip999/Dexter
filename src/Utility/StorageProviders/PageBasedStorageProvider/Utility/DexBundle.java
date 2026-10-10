package Utility.StorageProviders.PageBasedStorageProvider.Utility;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import Utility.Constants.Extension;

/*
A storage lives in one folder with the .dex extension, so it can be copied,
moved or deleted as a single item:

    users.dex/
        data.pb      records (data pages)
        index.pbi    key index and free-space index (keyed storage only)

The path given to a provider may include ".dex" or not: "data/users" and
"data/users.dex" both mean the folder data/users.dex. The folder is created if
needed; its parent folder must already exist.

Storages written before .dex folders existed (loose users.pb / users.pbi next
to the path) are moved into the folder the first time they are opened.
*/
public final class DexBundle {
    public static final String DATA_FILE = "data";
    public static final String INDEX_FILE = "index";

    private DexBundle() {
    }

    /*
    The .dex folder for a storage path.
    */
    public static Path folderOf(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Storage path cannot be empty");
        }
        String extension = Extension.DEX.getValue();
        return Paths.get(path.endsWith(extension) ? path : path + extension);
    }

    /*
    Makes sure the .dex folder exists (moving old loose files into it) and
    returns it.
    */
    public static Path open(String path) throws IOException {
        Path folder = folderOf(path);

        if (Files.exists(folder) && !Files.isDirectory(folder)) {
            throw new IOException("Storage path " + folder + " exists but is not a .dex folder");
        }

        if (!Files.isDirectory(folder)) {
            Files.createDirectory(folder);
            migrateLooseFiles(path, folder);
        }
        return folder;
    }

    /*
    Path of the data file without its extension, e.g. users.dex/data.
    */
    public static String dataPath(Path folder) {
        return folder.resolve(DATA_FILE).toString();
    }

    /*
    Path of the index file without its extension, e.g. users.dex/index.
    */
    public static String indexPath(Path folder) {
        return folder.resolve(INDEX_FILE).toString();
    }

    /*
    Moves users.pb / users.pbi (written before .dex folders) into the new folder.
    */
    private static void migrateLooseFiles(String path, Path folder) throws IOException {
        String base = path.endsWith(Extension.DEX.getValue())
            ? path.substring(0, path.length() - Extension.DEX.getValue().length())
            : path;

        Path looseData = Paths.get(base + Extension.PB.getValue());
        Path looseIndex = Paths.get(base + Extension.PBI.getValue());

        if (Files.isRegularFile(looseData)) {
            Files.move(looseData, folder.resolve(DATA_FILE + Extension.PB.getValue()));
            if (Files.isRegularFile(looseIndex)) {
                Files.move(looseIndex, folder.resolve(INDEX_FILE + Extension.PBI.getValue()));
            }
        }
    }
}
