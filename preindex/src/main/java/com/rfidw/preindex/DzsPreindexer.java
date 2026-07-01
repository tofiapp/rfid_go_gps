package com.rfidw.preindex;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Předindexace DZS databáze – stejná logika jako {@code DzsDatabase} / {@code DzsIndexCache} v aplikaci.
 */
final class DzsPreindexer {

    private static final int MAGIC = 0x445A5349;
    private static final int VERSION = 17;
    private static final int HASH_HEX_LEN = 64;
    private static final int CAST_UNSPECIFIED = -1;

    private static final String TABLE_GPS = "DZS_SUPERTRA_GPS_KM";
    private static final String TABLE_RO = "DZS_SUPER_RO_TPI";
    private static final String TEMP_RO_GPS_LOOKUP = "_dzs_ro_gps_lookup";

    static final class RoEntry {
        final String pairKey;
        final String tudu;
        final int vyhybka;
        final String iob;
        final String roId;
        final int castMin;
        final int castMax;

        RoEntry(String pairKey, String tudu, int vyhybka, String iob, String roId, int castMin, int castMax) {
            this.pairKey = pairKey;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.iob = iob != null ? iob : "";
            this.roId = roId;
            this.castMin = castMin;
            this.castMax = castMax;
        }
    }

    static final class VyhybkaGpsEntry {
        final String pairKey;
        final String tudu;
        final int vyhybka;
        final float latitude;
        final float longitude;

        VyhybkaGpsEntry(String pairKey, String tudu, int vyhybka, float latitude, float longitude) {
            this.pairKey = pairKey;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class Result {
        final List<RoEntry> roEntries;
        final List<VyhybkaGpsEntry> vyhybkaGps;

        Result(List<RoEntry> roEntries, List<VyhybkaGpsEntry> vyhybkaGps) {
            this.roEntries = roEntries;
            this.vyhybkaGps = vyhybkaGps;
        }

        int roCount() {
            return roEntries.size();
        }

        int vyhybkaGpsCount() {
            return vyhybkaGps.size();
        }
    }

    private static final class CastRange {
        final Integer castMin;
        final Integer castMax;

        CastRange(Integer castMin, Integer castMax) {
            this.castMin = castMin;
            this.castMax = castMax;
        }
    }

    private static final class RoIndexEntry {
        final String tudu;
        final int vyhybka;
        final String iob;
        final String roId;
        final Integer castMin;
        final Integer castMax;

        RoIndexEntry(String tudu, int vyhybka, String iob, String roId, Integer castMin, Integer castMax) {
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.iob = iob != null ? iob : "";
            this.roId = roId;
            this.castMin = castMin;
            this.castMax = castMax;
        }
    }

    private static final class GpsColumns {
        final String superZId;
        final String superDId;
        final String latitude;
        final String longitude;
        final String roId;

        GpsColumns(String superZId, String superDId, String latitude, String longitude, String roId) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.roId = roId;
        }
    }

    private static final class RoColumns {
        final String superZId;
        final String superDId;
        final String tudu;
        final String vyhybka;
        final String vyhybkaFallback;
        final String castMin;
        final String castMax;
        final String poloha;
        final String iob;
        final String roId;

        RoColumns(String superZId, String superDId, String tudu, String vyhybka,
                  String vyhybkaFallback, String castMin, String castMax, String poloha,
                  String iob, String roId) {
            this.superZId = superZId;
            this.superDId = superDId;
            this.tudu = tudu;
            this.vyhybka = vyhybka;
            this.vyhybkaFallback = vyhybkaFallback;
            this.castMin = castMin;
            this.castMax = castMax;
            this.poloha = poloha;
            this.iob = iob;
            this.roId = roId;
        }
    }

    private DzsPreindexer() {
    }

    static String cacheFileName(String contentHash) {
        return "dzs_" + contentHash + ".idx";
    }

    static String computeContentHash(File dbFile) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            try (InputStream in = new FileInputStream(dbFile)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(HASH_HEX_LEN);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Nelze spočítat otisk databáze", e);
        }
    }

    static Result index(File dbFile, boolean stats) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA cache_size = -64000");
                st.execute("PRAGMA temp_store = MEMORY");
            }

            GpsColumns gpsColumns = resolveGpsColumns(conn);
            RoColumns roColumns = resolveRoColumns(conn);
            if (stats) {
                System.out.println("GPS: Z=" + gpsColumns.superZId + " D=" + gpsColumns.superDId
                        + " lat=" + gpsColumns.latitude + " lon=" + gpsColumns.longitude
                        + " roId=" + gpsColumns.roId);
                System.out.println("RO: Z=" + roColumns.superZId + " D=" + roColumns.superDId
                        + " tudu=" + roColumns.tudu + " vyhybka=" + roColumns.vyhybka
                        + " roId=" + roColumns.roId);
            }

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS _dzs_gps_zd ON " + TABLE_GPS
                        + " (" + gpsColumns.superZId + ", " + gpsColumns.superDId + ")");
                st.execute("CREATE INDEX IF NOT EXISTS _dzs_gps_ro ON " + TABLE_GPS
                        + " (" + gpsColumns.superZId + ", " + gpsColumns.superDId
                        + ", " + gpsColumns.roId + ")");
            }

            Map<String, List<RoIndexEntry>> roByPairKey = new HashMap<>();
            Map<String, RoIndexEntry> roByRoKey = new HashMap<>();
            buildRoIndex(conn, roColumns, roByPairKey, roByRoKey);
            if (roByRoKey.isEmpty()) {
                throw new SQLException("V databázi chybí platné páry RO_ID mezi tabulkami výhybek a GPS");
            }

            List<VyhybkaGpsEntry> vyhybkaGps = buildVyhybkaGpsStore(conn, gpsColumns, roByRoKey);
            if (vyhybkaGps.isEmpty()) {
                vyhybkaGps = buildVyhybkaGpsStorePerEntry(conn, gpsColumns, roByRoKey);
            }

            List<RoEntry> roEntries = new ArrayList<>();
            for (Map.Entry<String, List<RoIndexEntry>> e : roByPairKey.entrySet()) {
                for (RoIndexEntry ro : e.getValue()) {
                    int castMin = ro.castMin != null ? ro.castMin : CAST_UNSPECIFIED;
                    int castMax = ro.castMax != null ? ro.castMax : CAST_UNSPECIFIED;
                    roEntries.add(new RoEntry(e.getKey(), ro.tudu, ro.vyhybka, ro.iob, ro.roId, castMin, castMax));
                }
            }
            return new Result(roEntries, vyhybkaGps);
        }
    }

    static void writeIndex(File dbFile, String contentHash, Result result, File idxFile) throws IOException {
        File parent = idxFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Nelze vytvořit složku: " + parent.getAbsolutePath());
        }
        File tmp = new File(parent, idxFile.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(dbFile.length());
            out.writeUTF(contentHash);
            out.writeInt(result.roCount());
            for (RoEntry ro : result.roEntries) {
                out.writeUTF(ro.pairKey);
                out.writeUTF(ro.tudu);
                out.writeInt(ro.vyhybka);
                out.writeUTF(ro.iob);
                out.writeUTF(ro.roId);
                out.writeInt(ro.castMin);
                out.writeInt(ro.castMax);
            }
            out.writeInt(result.vyhybkaGpsCount());
            for (VyhybkaGpsEntry gps : result.vyhybkaGps) {
                out.writeUTF(gps.pairKey);
                out.writeUTF(gps.tudu);
                out.writeInt(gps.vyhybka);
                out.writeFloat(gps.latitude);
                out.writeFloat(gps.longitude);
            }
            out.flush();
        }
        if (!tmp.renameTo(idxFile)) {
            tmp.delete();
            throw new IOException("Nelze uložit index: " + idxFile.getAbsolutePath());
        }
    }

    static boolean verifyIndex(File dbFile, String contentHash, File idxFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(idxFile)))) {
            if (in.readInt() != MAGIC) return false;
            if (in.readInt() != VERSION) return false;
            long cachedSize = in.readLong();
            String cachedHash = in.readUTF();
            if (cachedSize != dbFile.length() || !contentHash.equals(cachedHash)) return false;
            int roCount = in.readInt();
            boolean hasRoId = false;
            for (int i = 0; i < roCount; i++) {
                in.readUTF(); // pairKey
                in.readUTF(); // tudu
                in.readInt(); // vyhybka
                in.readUTF(); // iob
                String roId = in.readUTF();
                in.readInt(); // castMin
                in.readInt(); // castMax
                if (roId != null && !roId.isEmpty()) hasRoId = true;
            }
            if (!hasRoId) return false;
            int gpsCount = in.readInt();
            for (int i = 0; i < gpsCount; i++) {
                in.readUTF();
                in.readUTF();
                in.readInt();
                in.readFloat();
                in.readFloat();
            }
            return true;
        }
    }

    private static void buildRoIndex(Connection conn, RoColumns roColumns,
                                     Map<String, List<RoIndexEntry>> byPairKey,
                                     Map<String, RoIndexEntry> byRoKey) throws SQLException {
        String vyhybkaExpr = vyhybkaSelectExpr(roColumns, null);
        String roIdExpr = "TRIM(CAST(" + roColumns.roId + " AS TEXT))";
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(roColumns.superZId).append(", ").append(roColumns.superDId).append(", ")
                .append(roColumns.tudu).append(", ").append(vyhybkaExpr).append(", ")
                .append(roColumns.roId);
        if (roColumns.castMin != null) sql.append(", ").append(roColumns.castMin);
        if (roColumns.castMax != null) sql.append(", ").append(roColumns.castMax);
        if (roColumns.poloha != null) sql.append(", ").append(roColumns.poloha);
        if (roColumns.iob != null) sql.append(", ").append(roColumns.iob);
        sql.append(" FROM ").append(TABLE_RO)
                .append(" WHERE ").append(roColumns.tudu).append(" IS NOT NULL AND ")
                .append(roColumns.tudu).append(" <> ''")
                .append(" AND ").append(vyhybkaExpr).append(" IS NOT NULL")
                .append(" AND ").append(roColumns.roId).append(" IS NOT NULL")
                .append(" AND ").append(roIdExpr).append(" <> ''");
        appendPolohaFilter(sql, roColumns, null);

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) {
                String superZId = normalizeId(rs.getObject(1));
                String superDId = normalizeId(rs.getObject(2));
                String tudu = trimText(rs.getString(3));
                Integer vyhybka = readInt(rs.getObject(4));
                String roId = normalizeId(rs.getObject(5));
                if (superZId == null || superDId == null || tudu == null
                        || vyhybka == null || roId == null) {
                    continue;
                }
                int col = 6;
                Integer castMin = roColumns.castMin != null ? readInt(rs.getObject(col++)) : null;
                Integer castMax = roColumns.castMax != null ? readInt(rs.getObject(col++)) : null;
                String poloha = roColumns.poloha != null ? trimText(rs.getString(col++)) : null;
                String iob = roColumns.iob != null ? normalizeIob(rs.getString(col++)) : "";
                CastRange cast = resolveCastRange(castMin, castMax, poloha);
                RoIndexEntry entry = new RoIndexEntry(tudu, vyhybka, iob, roId, cast.castMin, cast.castMax);
                String key = pairKey(superZId, superDId);
                byPairKey.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
                byRoKey.put(roKey(superZId, superDId, roId), entry);
            }
        }
    }

    private static List<VyhybkaGpsEntry> buildVyhybkaGpsStore(Connection conn, GpsColumns gpsColumns,
                                                            Map<String, RoIndexEntry> roByRoKey)
            throws SQLException {
        if (!populateRoGpsLookupTempTable(conn, roByRoKey)) {
            return new ArrayList<>();
        }
        String roIdExpr = "TRIM(CAST(gps." + gpsColumns.roId + " AS TEXT))";
        String sql = "SELECT ro.tudu, ro.vyhybka, gps." + gpsColumns.latitude + ", gps."
                + gpsColumns.longitude + ", ro.super_z_id, ro.super_d_id, ro.ro_id"
                + " FROM " + TEMP_RO_GPS_LOOKUP + " ro"
                + " INNER JOIN " + TABLE_GPS + " gps"
                + " ON gps." + gpsColumns.superZId + " = ro.super_z_id"
                + " AND gps." + gpsColumns.superDId + " = ro.super_d_id"
                + " AND " + roIdExpr + " = ro.ro_id";

        List<VyhybkaGpsEntry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String tudu = rs.getString(1);
                Integer vyhybka = readInt(rs.getObject(2));
                Double lat = readDouble(rs.getObject(3));
                Double lon = readDouble(rs.getObject(4));
                String superZId = normalizeId(rs.getObject(5));
                String superDId = normalizeId(rs.getObject(6));
                String roId = normalizeId(rs.getObject(7));
                if (tudu == null || vyhybka == null || lat == null || lon == null) continue;
                if (superZId == null || superDId == null || roId == null) continue;
                if (!seen.add(roKey(superZId, superDId, roId))) continue;
                out.add(new VyhybkaGpsEntry(pairKey(superZId, superDId), tudu, vyhybka,
                        lat.floatValue(), lon.floatValue()));
            }
        }
        return out;
    }

    private static List<VyhybkaGpsEntry> buildVyhybkaGpsStorePerEntry(Connection conn, GpsColumns gpsColumns,
                                                                      Map<String, RoIndexEntry> roByRoKey)
            throws SQLException {
        List<VyhybkaGpsEntry> out = new ArrayList<>();
        String sql = "SELECT " + gpsColumns.latitude + ", " + gpsColumns.longitude
                + " FROM " + TABLE_GPS
                + " WHERE " + gpsColumns.superZId + " = ? AND " + gpsColumns.superDId + " = ?"
                + " AND " + gpsColumns.roId + " = ? LIMIT 1";
        for (Map.Entry<String, RoIndexEntry> e : roByRoKey.entrySet()) {
            String[] ids = splitRoKey(e.getKey());
            RoIndexEntry ro = e.getValue();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ids[0]);
                ps.setString(2, ids[1]);
                ps.setString(3, ro.roId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Double lat = readDouble(rs.getObject(1));
                        Double lon = readDouble(rs.getObject(2));
                        if (lat != null && lon != null) {
                            out.add(new VyhybkaGpsEntry(pairKey(ids[0], ids[1]), ro.tudu, ro.vyhybka,
                                    lat.floatValue(), lon.floatValue()));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean populateRoGpsLookupTempTable(Connection conn,
                                                        Map<String, RoIndexEntry> roByRoKey)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE IF NOT EXISTS " + TEMP_RO_GPS_LOOKUP
                    + " (super_z_id TEXT NOT NULL, super_d_id TEXT NOT NULL, ro_id TEXT NOT NULL,"
                    + " tudu TEXT NOT NULL, vyhybka INTEGER NOT NULL,"
                    + " PRIMARY KEY (super_z_id, super_d_id, ro_id))");
            st.execute("DELETE FROM " + TEMP_RO_GPS_LOOKUP);
        } catch (SQLException e) {
            return false;
        }

        String insertSql = "INSERT OR IGNORE INTO " + TEMP_RO_GPS_LOOKUP
                + " (super_z_id, super_d_id, ro_id, tudu, vyhybka) VALUES (?, ?, ?, ?, ?)";
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (Map.Entry<String, RoIndexEntry> e : roByRoKey.entrySet()) {
                String[] ids = splitRoKey(e.getKey());
                RoIndexEntry ro = e.getValue();
                if (ro.roId == null) continue;
                ps.setString(1, ids[0]);
                ps.setString(2, ids[1]);
                ps.setString(3, ro.roId);
                ps.setString(4, ro.tudu);
                ps.setLong(5, ro.vyhybka);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
            return true;
        } catch (SQLException e) {
            conn.rollback();
            return false;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static GpsColumns resolveGpsColumns(Connection conn) throws SQLException {
        List<String> cols = tableColumns(conn, TABLE_GPS);
        if (cols.isEmpty()) throw new SQLException("Tabulka " + TABLE_GPS + " neexistuje");
        return new GpsColumns(
                requireColumn(cols, "SUPER_Z_ID"),
                requireColumn(cols, "SUPER_D_ID"),
                findRequiredColumn(cols, "LAT", "LAN", "LATITUDE", "GPS_LAT", "SIRKA", "GPS_SIRKA", "Y"),
                findRequiredColumn(cols, "LON", "LONGITUDE", "LNG", "GPS_LON", "DELKA", "GPS_DELKA", "X"),
                requireColumn(cols, "RO_ID")
        );
    }

    private static RoColumns resolveRoColumns(Connection conn) throws SQLException {
        List<String> cols = tableColumns(conn, TABLE_RO);
        if (cols.isEmpty()) throw new SQLException("Tabulka " + TABLE_RO + " neexistuje");
        String vyhybka = findRequiredColumn(cols, "COBJEKT", "VYHYBKA", "VYH_CISLO", "CISLO_VYHYBKY",
                "CIS_VYHYBKY", "VYHYBKA_CISLO");
        String vyhybkaFallback = null;
        if (!vyhybka.equalsIgnoreCase("VYHYBKA")) {
            vyhybkaFallback = findOptionalColumn(cols, "VYHYBKA", "VYH_CISLO", "CISLO_VYHYBKY",
                    "CIS_VYHYBKY", "VYHYBKA_CISLO");
        }
        return new RoColumns(
                requireColumn(cols, "SUPER_Z_ID"),
                requireColumn(cols, "SUPER_D_ID"),
                findRequiredColumn(cols, "TUDU", "TUDU_KOD", "TUDU_CODE"),
                vyhybka,
                vyhybkaFallback,
                findOptionalColumn(cols, "CAST_MIN", "CASTMIN"),
                findOptionalColumn(cols, "CAST_MAX", "CASTMAX"),
                findOptionalColumn(cols, "POLOHA"),
                findOptionalColumn(cols, "IOB"),
                requireColumn(cols, "RO_ID")
        );
    }

    private static CastRange resolveCastRange(Integer castMin, Integer castMax, String poloha) {
        if (castMax != null) {
            int min = castMin != null ? castMin : 1;
            return new CastRange(min, castMax);
        }
        Integer fromPoloha = castMaxFromPoloha(poloha);
        if (fromPoloha != null) {
            return new CastRange(castMin != null ? castMin : 1, fromPoloha);
        }
        if (castMin != null) {
            return new CastRange(castMin, null);
        }
        return new CastRange(null, null);
    }

    private static Integer castMaxFromPoloha(String poloha) {
        if (poloha == null || poloha.isEmpty()) return null;
        char first = Character.toUpperCase(poloha.trim().charAt(0));
        if (first == 'J') return 3;
        if (first == 'C') return 4;
        return null;
    }

    private static String vyhybkaSelectExpr(RoColumns cols, String tableAlias) {
        String prefix = tableAlias == null || tableAlias.isEmpty() ? "" : tableAlias + ".";
        String primary = "NULLIF(TRIM(CAST(" + prefix + cols.vyhybka + " AS TEXT)), '')";
        if (cols.vyhybkaFallback == null) return primary;
        String fallback = "NULLIF(TRIM(CAST(" + prefix + cols.vyhybkaFallback + " AS TEXT)), '')";
        return "COALESCE(" + primary + ", " + fallback + ")";
    }

    private static void appendPolohaFilter(StringBuilder sql, RoColumns cols, String tableAlias) {
        if (cols.poloha == null) return;
        String prefix = tableAlias == null || tableAlias.isEmpty() ? "" : tableAlias + ".";
        String expr = "TRIM(CAST(" + prefix + cols.poloha + " AS TEXT))";
        sql.append(" AND ").append(prefix).append(cols.poloha).append(" IS NOT NULL")
                .append(" AND ").append(expr).append(" <> ''")
                .append(" AND UPPER(").append(expr).append(") <> 'NULL'");
    }

    private static String pairKey(String superZId, String superDId) {
        return superZId + "|" + superDId;
    }

    private static String roKey(String superZId, String superDId, String roId) {
        return pairKey(superZId, superDId) + "|" + roId;
    }

    private static String[] splitRoKey(String roKeyStr) {
        int lastSep = roKeyStr.lastIndexOf('|');
        if (lastSep < 0) return new String[]{"", "", roKeyStr};
        int pairSep = roKeyStr.indexOf('|');
        if (pairSep < 0 || pairSep == lastSep) return new String[]{"", "", roKeyStr};
        return new String[]{
                roKeyStr.substring(0, pairSep),
                roKeyStr.substring(pairSep + 1, lastSep),
                roKeyStr.substring(lastSep + 1)
        };
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

    private static String normalizeIob(String iob) {
        if (iob == null) return "";
        String trimmed = iob.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return "";
        return trimmed.substring(0, 1);
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
