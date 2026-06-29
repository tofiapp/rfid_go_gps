package com.rfidw.preindex;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class DzsPreindexer {

    private static final int MAGIC = 0x445A5349;
    private static final int VERSION = 5;

    private static final String TABLE_GPS = "DZS_SUPERTRA_GPS_KM";
    private static final String TABLE_RO = "DZS_SUPER_RO_TPI";

    static final class RoEntry {
        final String tudu;
        final int vyhybka;

        RoEntry(String tudu, int vyhybka) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
        }
    }

    static final class GpsEntry {
        final String pairKey;
        final double latitude;
        final double longitude;

        GpsEntry(String pairKey, double latitude, double longitude) {
            this.pairKey = pairKey;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class VyhybkaGpsEntry {
        final String tudu;
        final int vyhybka;
        final double latitude;
        final double longitude;

        VyhybkaGpsEntry(String tudu, int vyhybka, double latitude, double longitude) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class RoVyhybkaRow {
        final String tudu;
        final int vyhybka;
        final String superZId;
        final String superDId;
        final Integer kmkInt;

        RoVyhybkaRow(String tudu, int vyhybka, String superZId, String superDId, Integer kmkInt) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.superZId = superZId;
            this.superDId = superDId;
            this.kmkInt = kmkInt;
        }
    }

    static final class Result {
        final Map<String, RoEntry> ro;
        final List<GpsEntry> gps;
        final List<VyhybkaGpsEntry> vyhybkaGps;

        Result(Map<String, RoEntry> ro, List<GpsEntry> gps, List<VyhybkaGpsEntry> vyhybkaGps) {
            this.ro = ro;
            this.gps = gps;
            this.vyhybkaGps = vyhybkaGps;
        }
    }

    static final class Columns {
        final String gpsSuperZ;
        final String gpsSuperD;
        final String gpsLat;
        final String gpsLon;
        final String roSuperZ;
        final String roSuperD;
        final String roTudu;
        final String roVyhybka;
        final String roVyhybkaFallback;
        final String roPoloha;
        final String gpsKmInt;
        final String roKmkInt;

        Columns(String gpsSuperZ, String gpsSuperD, String gpsLat, String gpsLon,
                String roSuperZ, String roSuperD, String roTudu, String roVyhybka,
                String roVyhybkaFallback, String roPoloha, String gpsKmInt, String roKmkInt) {
            this.gpsSuperZ = gpsSuperZ;
            this.gpsSuperD = gpsSuperD;
            this.gpsLat = gpsLat;
            this.gpsLon = gpsLon;
            this.roSuperZ = roSuperZ;
            this.roSuperD = roSuperD;
            this.roTudu = roTudu;
            this.roVyhybka = roVyhybka;
            this.roVyhybkaFallback = roVyhybkaFallback;
            this.roPoloha = roPoloha;
            this.gpsKmInt = gpsKmInt;
            this.roKmkInt = roKmkInt;
        }
    }

    private DzsPreindexer() {
    }

    static Result index(File dbFile, boolean stats) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            Columns cols = resolveColumns(conn);
        if (stats) {
                System.out.println("Sloupce GPS: Z=" + cols.gpsSuperZ + " D=" + cols.gpsSuperD
                        + " lat=" + cols.gpsLat + " lon=" + cols.gpsLon
                        + " km=" + cols.gpsKmInt);
                System.out.println("Sloupce RO: Z=" + cols.roSuperZ + " D=" + cols.roSuperD
                        + " tudu=" + cols.roTudu + " vyhybka=" + cols.roVyhybka
                        + " kmk=" + cols.roKmkInt);
            }
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS _dzs_gps_zd ON " + TABLE_GPS
                        + " (" + cols.gpsSuperZ + ", " + cols.gpsSuperD + ")");
            }

            RoBuild roBuild = buildRoIndexAndRows(conn, cols);
            List<GpsEntry> gps = buildGpsIndex(conn, cols, roBuild.ro);
            List<VyhybkaGpsEntry> vyhybkaGps = buildVyhybkaGpsIndex(conn, cols, roBuild.rows, gps);
            return new Result(roBuild.ro, gps, vyhybkaGps);
        }
    }

    static String cacheFileName(File dbFile) {
        return "dzs_" + Long.toHexString(dbFile.length())
                + "_" + Long.toHexString(dbFile.lastModified()) + ".idx";
    }

    static void writeIndex(File dbFile, Result result, File idxFile) throws Exception {
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new FileOutputStream(idxFile)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(dbFile.length());
            out.writeLong(dbFile.lastModified());
            out.writeInt(result.ro.size());
            for (Map.Entry<String, RoEntry> e : result.ro.entrySet()) {
                out.writeUTF(e.getKey());
                out.writeUTF(e.getValue().tudu);
                out.writeInt(e.getValue().vyhybka);
            }
            out.writeInt(result.gps.size());
            for (GpsEntry gps : result.gps) {
                out.writeUTF(gps.pairKey);
                out.writeDouble(gps.latitude);
                out.writeDouble(gps.longitude);
            }
            out.writeInt(result.vyhybkaGps.size());
            for (VyhybkaGpsEntry entry : result.vyhybkaGps) {
                out.writeUTF(entry.tudu);
                out.writeInt(entry.vyhybka);
                out.writeDouble(entry.latitude);
                out.writeDouble(entry.longitude);
            }
            out.flush();
        }
    }

    static boolean verifyIndex(File dbFile, File idxFile) throws Exception {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new FileInputStream(idxFile)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                System.err.println("CHYBA: magic/verze");
                return false;
            }
            if (in.readLong() != dbFile.length() || in.readLong() != dbFile.lastModified()) {
                System.err.println("CHYBA: otisk DB nesedí");
                return false;
            }
            int roCount = in.readInt();
            for (int i = 0; i < roCount; i++) {
                in.readUTF();
                in.readUTF();
                in.readInt();
            }
            int gpsCount = in.readInt();
            for (int i = 0; i < gpsCount; i++) {
                in.readUTF();
                in.readDouble();
                in.readDouble();
            }
            int vyhybkaCount = in.readInt();
            for (int i = 0; i < vyhybkaCount; i++) {
                in.readUTF();
                in.readInt();
                in.readDouble();
                in.readDouble();
            }
            return in.read() < 0;
        }
    }

    private static final class RoBuild {
        final Map<String, RoEntry> ro;
        final List<RoVyhybkaRow> rows;

        RoBuild(Map<String, RoEntry> ro, List<RoVyhybkaRow> rows) {
            this.ro = ro;
            this.rows = rows;
        }
    }

    private static RoBuild buildRoIndexAndRows(Connection conn, Columns cols) throws SQLException {
        String vyhybkaExpr = vyhybkaExpr(cols, null);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(cols.roSuperZ).append(", ").append(cols.roSuperD).append(", ")
                .append(cols.roTudu).append(", ").append(vyhybkaExpr);
        if (cols.roKmkInt != null) sql.append(", ").append(cols.roKmkInt);
        sql.append(" FROM ").append(TABLE_RO)
                .append(" WHERE ").append(cols.roTudu).append(" IS NOT NULL AND ")
                .append(cols.roTudu).append(" <> ''")
                .append(" AND ").append(vyhybkaExpr).append(" IS NOT NULL");
        appendPolohaFilter(sql, cols, null);

        Map<String, RoEntry> ro = new LinkedHashMap<>();
        List<RoVyhybkaRow> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) {
                String superZ = normalizeId(rs.getObject(1));
                String superD = normalizeId(rs.getObject(2));
                String tudu = trimText(rs.getString(3));
                Integer vyhybka = readInt(rs.getObject(4));
                if (superZ == null || superD == null || tudu == null || vyhybka == null) continue;
                ro.put(pairKey(superZ, superD), new RoEntry(tudu, vyhybka));
                Integer kmk = cols.roKmkInt != null ? readInt(rs.getObject(5)) : null;
                rows.add(new RoVyhybkaRow(tudu, vyhybka, superZ, superD, kmk));
            }
        }
        return new RoBuild(ro, rows);
    }

    private static List<GpsEntry> buildGpsIndex(Connection conn, Columns cols,
                                                Map<String, RoEntry> ro) throws SQLException {
        if (ro.isEmpty()) return List.of();

        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE IF NOT EXISTS _dzs_ro_pairs ("
                    + "super_z_id TEXT NOT NULL, super_d_id TEXT NOT NULL,"
                    + "PRIMARY KEY (super_z_id, super_d_id))");
            st.execute("DELETE FROM _dzs_ro_pairs");
        }

        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT OR IGNORE INTO _dzs_ro_pairs (super_z_id, super_d_id) VALUES (?, ?)")) {
            for (String key : ro.keySet()) {
                String[] ids = splitPairKey(key);
                insert.setString(1, ids[0]);
                insert.setString(2, ids[1]);
                insert.executeUpdate();
            }
        }

        String latExpr = "CAST(REPLACE(g." + cols.gpsLat + ", ',', '.') AS REAL)";
        String lonExpr = "CAST(REPLACE(g." + cols.gpsLon + ", ',', '.') AS REAL)";
        String sqlCast = "SELECT g." + cols.gpsSuperZ + ", g." + cols.gpsSuperD + ", "
                + latExpr + ", " + lonExpr
                + " FROM " + TABLE_GPS + " g"
                + " INNER JOIN ("
                + "   SELECT g2." + cols.gpsSuperZ + ", g2." + cols.gpsSuperD + ", MIN(g2.rowid) AS rid"
                + "   FROM " + TABLE_GPS + " g2"
                + "   INNER JOIN _dzs_ro_pairs rp"
                + "     ON g2." + cols.gpsSuperZ + " = rp.super_z_id"
                + "     AND g2." + cols.gpsSuperD + " = rp.super_d_id"
                + "   GROUP BY g2." + cols.gpsSuperZ + ", g2." + cols.gpsSuperD
                + " ) agg ON g.rowid = agg.rid";

        List<GpsEntry> gps = readGpsRows(conn, sqlCast);
        if (!gps.isEmpty()) return gps;

        String sqlPlain = "SELECT g." + cols.gpsSuperZ + ", g." + cols.gpsSuperD + ", "
                + "g." + cols.gpsLat + ", g." + cols.gpsLon
                + " FROM " + TABLE_GPS + " g"
                + " INNER JOIN ("
                + "   SELECT g2." + cols.gpsSuperZ + ", g2." + cols.gpsSuperD + ", MIN(g2.rowid) AS rid"
                + "   FROM " + TABLE_GPS + " g2"
                + "   INNER JOIN _dzs_ro_pairs rp"
                + "     ON g2." + cols.gpsSuperZ + " = rp.super_z_id"
                + "     AND g2." + cols.gpsSuperD + " = rp.super_d_id"
                + "   GROUP BY g2." + cols.gpsSuperZ + ", g2." + cols.gpsSuperD
                + " ) agg ON g.rowid = agg.rid";
        gps = readGpsRows(conn, sqlPlain);
        if (!gps.isEmpty()) return gps;

        String sqlOne = "SELECT " + cols.gpsLat + ", " + cols.gpsLon
                + " FROM " + TABLE_GPS
                + " WHERE " + cols.gpsSuperZ + " = ? AND " + cols.gpsSuperD + " = ? LIMIT 1";
        List<GpsEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sqlOne)) {
            for (String key : ro.keySet()) {
                String[] ids = splitPairKey(key);
                ps.setString(1, ids[0]);
                ps.setString(2, ids[1]);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) continue;
                    Double lat = readDouble(rs.getObject(1));
                    Double lon = readDouble(rs.getObject(2));
                    if (lat != null && lon != null) {
                        out.add(new GpsEntry(key, lat, lon));
                    }
                }
            }
        }
        return out;
    }

    private static List<GpsEntry> readGpsRows(Connection conn, String sql) {
        List<GpsEntry> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String superZ = normalizeId(rs.getObject(1));
                String superD = normalizeId(rs.getObject(2));
                Double lat = readDouble(rs.getObject(3));
                Double lon = readDouble(rs.getObject(4));
                if (superZ == null || superD == null || lat == null || lon == null) continue;
                out.add(new GpsEntry(pairKey(superZ, superD), lat, lon));
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return out;
    }

    private static List<VyhybkaGpsEntry> buildVyhybkaGpsIndex(Connection conn, Columns cols,
                                                              List<RoVyhybkaRow> roRows,
                                                              List<GpsEntry> gps) throws SQLException {
        Map<String, double[]> gpsByPair = new HashMap<>();
        for (GpsEntry entry : gps) {
            gpsByPair.put(entry.pairKey, new double[]{entry.latitude, entry.longitude});
        }
        Map<String, double[]> gpsByTriple = buildGpsByTripleKey(conn, cols, roRows);

        Map<String, VyhybkaGpsEntry> byTuduVyhybka = new LinkedHashMap<>();
        for (RoVyhybkaRow row : roRows) {
            Double lat = null;
            Double lon = null;
            boolean fromTriple = false;
            if (!gpsByTriple.isEmpty() && row.kmkInt != null) {
                double[] coords = gpsByTriple.get(tripleKey(row.superZId, row.superDId, row.kmkInt));
                if (coords != null) {
                    lat = coords[0];
                    lon = coords[1];
                    fromTriple = true;
                }
            }
            if (lat == null || lon == null) {
                double[] coords = gpsByPair.get(pairKey(row.superZId, row.superDId));
                if (coords == null) continue;
                lat = coords[0];
                lon = coords[1];
            }

            String key = row.tudu + "\0" + row.vyhybka;
            VyhybkaGpsEntry existing = byTuduVyhybka.get(key);
            if (existing != null && !fromTriple) continue;
            byTuduVyhybka.put(key, new VyhybkaGpsEntry(row.tudu, row.vyhybka, lat, lon));
        }
        return new ArrayList<>(byTuduVyhybka.values());
    }

    private static Map<String, double[]> buildGpsByTripleKey(Connection conn, Columns cols,
                                                             List<RoVyhybkaRow> roRows)
            throws SQLException {
        if (cols.gpsKmInt == null || roRows.isEmpty()) return Map.of();

        Map<String, Set<Integer>> kmByPair = new HashMap<>();
        for (RoVyhybkaRow row : roRows) {
            if (row.kmkInt == null) continue;
            kmByPair.computeIfAbsent(pairKey(row.superZId, row.superDId), k -> new HashSet<>())
                    .add(row.kmkInt);
        }
        if (kmByPair.isEmpty()) return Map.of();

        String kmExpr = kmIntExpr(cols.gpsKmInt);
        String latExpr = "CAST(REPLACE(" + cols.gpsLat + ", ',', '.') AS REAL)";
        String lonExpr = "CAST(REPLACE(" + cols.gpsLon + ", ',', '.') AS REAL)";
        String sqlExact = "SELECT " + latExpr + ", " + lonExpr + " FROM " + TABLE_GPS
                + " WHERE " + cols.gpsSuperZ + " = ? AND " + cols.gpsSuperD + " = ?"
                + " AND " + kmExpr + " = ? LIMIT 1";
        String sqlRange = "SELECT " + latExpr + ", " + lonExpr + " FROM " + TABLE_GPS
                + " WHERE " + cols.gpsSuperZ + " = ? AND " + cols.gpsSuperD + " = ?"
                + " AND " + kmExpr + " >= ? AND " + kmExpr + " < ? LIMIT 1";
        String sqlRound = "SELECT " + latExpr + ", " + lonExpr + " FROM " + TABLE_GPS
                + " WHERE " + cols.gpsSuperZ + " = ? AND " + cols.gpsSuperD + " = ?"
                + " AND CAST(ROUND(" + kmExpr + ") AS INTEGER) = ? LIMIT 1";
        String sqlNearest = "SELECT " + latExpr + ", " + lonExpr + " FROM " + TABLE_GPS
                + " WHERE " + cols.gpsSuperZ + " = ? AND " + cols.gpsSuperD + " = ?"
                + " AND " + kmExpr + " IS NOT NULL"
                + " ORDER BY ABS(" + kmExpr + " - ?) LIMIT 1";

        Map<String, double[]> out = new HashMap<>();
        conn.setAutoCommit(false);
        try {
            for (Map.Entry<String, Set<Integer>> entry : kmByPair.entrySet()) {
                String[] ids = splitPairKey(entry.getKey());
                for (int kmk : entry.getValue()) {
                    String triple = tripleKey(ids[0], ids[1], kmk);
                    if (out.containsKey(triple)) continue;

                    double[] coords = queryTripleGps(conn, sqlExact, ids[0], ids[1], String.valueOf(kmk));
                    if (coords == null) {
                        coords = queryTripleGps(conn, sqlRange, ids[0], ids[1],
                                String.valueOf(kmk - 0.5), String.valueOf(kmk + 0.5));
                    }
                    if (coords == null) {
                        coords = queryTripleGps(conn, sqlRound, ids[0], ids[1], String.valueOf(kmk));
                    }
                    if (coords == null) {
                        coords = queryTripleGps(conn, sqlNearest, ids[0], ids[1], String.valueOf(kmk));
                    }
                    if (coords != null) {
                        out.put(triple, coords);
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        return out;
    }

    private static double[] queryTripleGps(Connection conn, String sql,
                                           String superZ, String superD, String... extra)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, superZ);
            ps.setString(2, superD);
            for (int i = 0; i < extra.length; i++) {
                ps.setString(3 + i, extra[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Double lat = readDouble(rs.getObject(1));
                Double lon = readDouble(rs.getObject(2));
                if (lat == null || lon == null) return null;
                return new double[]{lat, lon};
            }
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static Columns resolveColumns(Connection conn) throws SQLException {
        List<String> gpsCols = tableColumns(conn, TABLE_GPS);
        List<String> roCols = tableColumns(conn, TABLE_RO);
        if (gpsCols.isEmpty()) throw new SQLException("Tabulka " + TABLE_GPS + " neexistuje");
        if (roCols.isEmpty()) throw new SQLException("Tabulka " + TABLE_RO + " neexistuje");

        String vyhybka = findColumn(roCols, "COBJEKT", "VYHYBKA", "VYH_CISLO",
                "CISLO_VYHYBKY", "CIS_VYHYBKY", "VYHYBKA_CISLO");
        String vyhybkaFallback = null;
        if (!vyhybka.equalsIgnoreCase("VYHYBKA")) {
            vyhybkaFallback = findOptionalColumn(roCols, "VYHYBKA", "VYH_CISLO",
                    "CISLO_VYHYBKY", "CIS_VYHYBKY", "VYHYBKA_CISLO");
        }

        return new Columns(
                requireColumn(gpsCols, "SUPER_Z_ID"),
                requireColumn(gpsCols, "SUPER_D_ID"),
                findRequiredColumn(gpsCols, "LAT", "LAN", "LATITUDE", "GPS_LAT", "SIRKA", "GPS_SIRKA", "Y"),
                findRequiredColumn(gpsCols, "LON", "LONGITUDE", "LNG", "GPS_LON", "DELKA", "GPS_DELKA", "X"),
                requireColumn(roCols, "SUPER_Z_ID"),
                requireColumn(roCols, "SUPER_D_ID"),
                findRequiredColumn(roCols, "TUDU", "TUDU_KOD", "TUDU_CODE"),
                vyhybka,
                vyhybkaFallback,
                findOptionalColumn(roCols, "POLOHA"),
                findOptionalColumn(gpsCols, "KM_INT", "KM", "KILOMETR", "KMK"),
                findOptionalColumn(roCols, "KMK_INT", "KMK", "KM_INT", "KM", "KILOMETR")
        );
    }

    private static String vyhybkaExpr(Columns cols, String alias) {
        String prefix = alias == null ? "" : alias + ".";
        String primary = "NULLIF(TRIM(CAST(" + prefix + cols.roVyhybka + " AS TEXT)), '')";
        if (cols.roVyhybkaFallback == null) return primary;
        String fallback = "NULLIF(TRIM(CAST(" + prefix + cols.roVyhybkaFallback + " AS TEXT)), '')";
        return "COALESCE(" + primary + ", " + fallback + ")";
    }

    private static void appendPolohaFilter(StringBuilder sql, Columns cols, String alias) {
        if (cols.roPoloha == null) return;
        String prefix = alias == null ? "" : alias + ".";
        String expr = "TRIM(CAST(" + prefix + cols.roPoloha + " AS TEXT))";
        sql.append(" AND ").append(prefix).append(cols.roPoloha).append(" IS NOT NULL")
                .append(" AND ").append(expr).append(" <> ''")
                .append(" AND UPPER(").append(expr).append(") <> 'NULL'");
    }

    private static String kmIntExpr(String column) {
        return "CAST(REPLACE(TRIM(CAST(" + column + " AS TEXT)), ',', '.') AS REAL)";
    }

    private static String pairKey(String superZ, String superD) {
        return superZ + "|" + superD;
    }

    private static String tripleKey(String superZ, String superD, int km) {
        return superZ + "|" + superD + "|" + km;
    }

    private static String[] splitPairKey(String key) {
        int sep = key.indexOf('|');
        if (sep < 0) return new String[]{key, ""};
        return new String[]{key.substring(0, sep), key.substring(sep + 1)};
    }

    private static List<String> tableColumns(Connection conn, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                out.add(rs.getString(2));
            }
        }
        return out;
    }

    private static String requireColumn(List<String> cols, String name) throws SQLException {
        return findRequiredColumn(cols, name);
    }

    private static String findRequiredColumn(List<String> cols, String... candidates) throws SQLException {
        String hit = findOptionalColumn(cols, candidates);
        if (hit == null) {
            throw new SQLException("Chybí sloupec " + candidates[0]);
        }
        return hit;
    }

    private static String findOptionalColumn(List<String> cols, String... candidates) {
        Map<String, String> byUpper = new HashMap<>();
        for (String col : cols) {
            byUpper.put(col.toUpperCase(Locale.ROOT), col);
        }
        for (String candidate : candidates) {
            String hit = byUpper.get(candidate.toUpperCase(Locale.ROOT));
            if (hit != null) return hit;
        }
        return null;
    }

    private static String findColumn(List<String> cols, String... candidates) throws SQLException {
        return findRequiredColumn(cols, candidates);
    }

    private static String normalizeId(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            double numeric = Double.parseDouble(text.replace(',', '.'));
            if (!Double.isNaN(numeric) && !Double.isInfinite(numeric)) {
                long asLong = Math.round(numeric);
                if (Math.abs(numeric - asLong) < 1e-6) {
                    return String.valueOf(asLong);
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return text;
    }

    private static String trimText(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer readInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return (int) Math.round(((Number) value).doubleValue());
        }
        String text = String.valueOf(value).trim().replace(',', '.');
        if (text.isEmpty()) return null;
        try {
            return (int) Math.round(Double.parseDouble(text));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double readDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String text = String.valueOf(value).trim().replace(',', '.');
        if (text.isEmpty()) return null;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
