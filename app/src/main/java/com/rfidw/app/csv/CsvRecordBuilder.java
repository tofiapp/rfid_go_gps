package com.rfidw.app.csv;

/**
 * Sestavení řádku CSV z provozních hodnot aplikace.
 * Nezávislé na šabloně EPC – sloupce a formát zůstávají stabilní
 * i při budoucí změně rozložení EPC.
 */
public final class CsvRecordBuilder {

    private CsvRecordBuilder() {}

    public static CsvStore.Row build(
            long idRfid,
            String epc24,
            String tid,
            String tudu,
            String vyhybkaLabel,
            int cast,
            String poloha,
            String roId1,
            String roId2,
            String kmExt,
            String latitude,
            String longitude,
            String accuracyM,
            String gpsTime) {
        CsvStore.Row row = new CsvStore.Row();
        row.idRfid = String.valueOf(idRfid);
        row.epc = epc24 != null ? epc24 : "";
        row.tid = tid != null ? tid : "";
        row.tudu = tudu != null ? tudu : "";
        row.vyhybka = vyhybkaLabel != null ? vyhybkaLabel : "";
        row.cast = String.valueOf(cast);
        row.poloha = poloha != null ? poloha : "";
        row.roId1 = roId1 != null ? roId1 : "";
        row.roId2 = roId2 != null ? roId2 : "";
        row.kmExt = kmExt != null ? kmExt : "";
        row.latitude = latitude != null ? latitude : "";
        row.longitude = longitude != null ? longitude : "";
        row.accuracyM = accuracyM != null ? accuracyM : "";
        row.gpsTime = gpsTime != null ? gpsTime : "";
        return row;
    }
}
