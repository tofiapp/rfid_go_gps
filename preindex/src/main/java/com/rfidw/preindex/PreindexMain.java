package com.rfidw.preindex;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Předindexace DZS SQLite databáze na PC (bez Androidu).
 *
 * <pre>
 *   ./gradlew :preindex:jar
 *   java -jar preindex/build/libs/preindex-dzs-1.0.jar DZS_PASPORT_TPI.sqlite
 *   java -jar preindex-dzs.jar DZS_PASPORT_TPI.sqlite -o ./output --stats --verify
 * </pre>
 */
public final class PreindexMain {

    private PreindexMain() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || hasFlag(args, "-h") || hasFlag(args, "--help")) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
            return;
        }

        String dbArg = null;
        String outputDir = null;
        boolean stats = false;
        boolean verify = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-o".equals(arg) || "--output".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Chybí hodnota za " + arg);
                    System.exit(1);
                }
                outputDir = args[++i];
            } else if ("--stats".equals(arg)) {
                stats = true;
            } else if ("--verify".equals(arg)) {
                verify = true;
            } else if (!arg.startsWith("-")) {
                dbArg = arg;
            } else {
                System.err.println("Neznámý přepínač: " + arg);
                System.exit(1);
            }
        }

        if (dbArg == null) {
            System.err.println("Chybí cesta k databázi.");
            printUsage();
            System.exit(1);
        }

        File dbFile = new File(dbArg).getAbsoluteFile();
        if (!dbFile.isFile()) {
            System.err.println("Soubor nenalezen: " + dbFile.getAbsolutePath());
            System.exit(1);
        }

        File outDir = outputDir != null
                ? new File(outputDir).getAbsoluteFile()
                : dbFile.getParentFile();
        if (!outDir.exists() && !outDir.mkdirs()) {
            System.err.println("Nelze vytvořit výstupní složku: " + outDir.getAbsolutePath());
            System.exit(1);
        }

        long t0 = System.nanoTime();
        try {
            DzsPreindexer.Result result = DzsPreindexer.index(dbFile, stats);
            String contentHash = DzsPreindexer.computeContentHash(dbFile);
            String idxName = DzsPreindexer.cacheFileName(contentHash);
            File idxFile = new File(outDir, idxName);
            DzsPreindexer.writeIndex(dbFile, contentHash, result, idxFile);

            File sidecar = new File(dbFile.getAbsolutePath() + ".idx");
            if (!sidecar.getAbsoluteFile().equals(idxFile.getAbsoluteFile())) {
                Files.copy(idxFile.toPath(), sidecar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            double elapsed = (System.nanoTime() - t0) / 1_000_000_000.0;
            System.out.println("Hotovo: " + idxFile.getAbsolutePath());
            System.out.println("  Sidecar:     " + sidecar.getAbsolutePath());
            System.out.println("  RO záznamů:  " + result.roCount());
            System.out.println("  Výhybky GPS: " + result.vyhybkaGpsCount());
            System.out.printf("  Čas:         %.1f s%n", elapsed);
            System.out.println("  SHA-256:     " + contentHash);
            System.out.println("  Velikost DB: " + dbFile.length() + " B");

            if (verify) {
                boolean ok = DzsPreindexer.verifyIndex(dbFile, contentHash, idxFile);
                System.out.println("Ověření: " + (ok ? "OK" : "SELHALO"));
                System.exit(ok ? 0 : 2);
            }
        } catch (Exception e) {
            System.err.println("Chyba: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) return true;
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("Předindexace DZS databáze pro RFID Go GPS");
        System.out.println();
        System.out.println("Použití:");
        System.out.println("  java -jar preindex-dzs.jar DZS_PASPORT_TPI.sqlite");
        System.out.println("  java -jar preindex-dzs.jar DZS_PASPORT_TPI.sqlite -o ./output --stats --verify");
        System.out.println();
        System.out.println("Přepínače:");
        System.out.println("  -o, --output DIR   Výstupní adresář (výchozí: složka databáze)");
        System.out.println("  --stats            Vypiš detekované sloupce");
        System.out.println("  --verify           Ověř vygenerovaný soubor .idx");
        System.out.println();
        System.out.println("Na telefon zkopírujte oba soubory do stejné složky (např. Stažené soubory):");
        System.out.println("  DZS_PASPORT_TPI.sqlite");
        System.out.println("  DZS_PASPORT_TPI.sqlite.idx   (nebo dzs_<sha256>.idx)");
    }
}
