package com.rfidw.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.rfidw.app.R;
import com.rfidw.app.csv.CsvStore;

import java.util.ArrayList;
import java.util.List;

public class CsvAdapter extends RecyclerView.Adapter<CsvAdapter.VH> {

    private final List<CsvStore.Row> data = new ArrayList<>();

    public void setData(List<CsvStore.Row> rows) {
        data.clear();
        data.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_csv_item, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        CsvStore.Row r = data.get(position);
        h.cId.setText(r.idRfid);
        h.cEpc.setText(r.epc);
        h.cTid.setText(r.tid);
        h.cRok.setText(r.rok);
        h.cTudu.setText(r.tudu);
        h.cVyh.setText(r.vyhybka);
        h.cCast.setText(r.cast);
        h.itemView.setBackgroundColor(position % 2 == 0 ? 0xFFFFFFFF : 0xFFF5F8FC);
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView cId, cEpc, cTid, cRok, cTudu, cVyh, cCast;
        VH(@NonNull View v) {
            super(v);
            cId = v.findViewById(R.id.cId);
            cEpc = v.findViewById(R.id.cEpc);
            cTid = v.findViewById(R.id.cTid);
            cRok = v.findViewById(R.id.cRok);
            cTudu = v.findViewById(R.id.cTudu);
            cVyh = v.findViewById(R.id.cVyh);
            cCast = v.findViewById(R.id.cCast);
        }
    }
}
