package com.rfidw.app.kmext;

import com.rfidw.app.csv.CsvStore;
import com.rfidw.app.data.DzsDatabase;
import com.rfidw.app.location.LocationCache;

import java.util.List;

/**
 * logika KM_EXT – dopočet nejbližšího km bodu při zápisu CSV (ne při indexaci okolí 4 km).
 *
 * <p>Odstranění celé funkce:
 * <ol>
 *   <li>smazat tento soubor ({@code app/.../kmext/KmExtLogic.java})</li>
 *   <li>v {@code DzsDatabase.java} odstranit blok {@code logika KM_EXT}</li>
 *   <li>v {@code CsvStore.java}, {@code CsvRecordBuilder.java}, {@code MainActivity.java}
 *       odstranit označené bloky {@code logika KM_EXT}</li>
 * </ol>
 */
public final class KmExtLogic {

    public static final class LookupResult {
        public final String kmExt;
        public final double distanceM;

        public LookupResult(String kmExt, double distanceM) {
            this.kmExt = kmExt != null ? kmExt : "";
            this.distanceM = distanceM;
        }

        static LookupResult empty() {
            return new LookupResult("", Double.MAX_VALUE);
        }

        boolean hasValue() {
            return !kmExt.isEmpty() && distanceM < Double.MAX_VALUE;
        }
    }

    private KmExtLogic() {}

    /** Doplní sloupec KM_EXT do řádku CSV podle aktuální GPS a RO_ID. */
    public static void attachToRow(DzsDatabase db, CsvStore.Row row, LocationCache.Snapshot gps) {
        if (row == null) return;
        if (db == null || gps == null || !gps.valid) {
            row.kmExt = "";
            return;
        }
        row.kmExt = resolveForRoIds(db, row.roId, gps.latitude, gps.longitude);
    }

    static String resolveForRoIds(DzsDatabase db, String roIdField,
                                  double latitude, double longitude) {
        if (db == null || roIdField == null || roIdField.trim().isEmpty()) {
            return "";
        }
        LookupResult best = LookupResult.empty();
        List<String> roIds = CsvStore.parseRoIds(roIdField);
        for (String roId : roIds) {
            if (roId == null || roId.isEmpty()) continue;
            LookupResult candidate = db.findNearestKmExtAtGps(roId, latitude, longitude);
            if (candidate.hasValue() && candidate.distanceM < best.distanceM) {
                best = candidate;
            }
        }
        return best.kmExt;
    }
}
