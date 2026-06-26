package com.rfidw.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButtonToggleGroup;

import com.rfidw.app.R;
import com.rfidw.app.csv.CsvStore;
import com.rfidw.app.data.Tudu;
import com.rfidw.app.data.TuduLoader;
import com.rfidw.app.epc.EpcModel;
import com.rfidw.app.rfid.UhfManager;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // klávesy spouště čtečky (Chainway C5 a příbuzné)
    private static final int[] TRIGGER_KEYS = {139, 280, 293, 311, 312, 522, 523, 0x3E8};

    private static final int COLOR_STATUS_READY = 0xFF2E7D32;
    private static final int COLOR_STATUS_BUSY = 0xFF5F6A76;
    private static final int COLOR_STATUS_ERROR = 0xFFC62828;
    private static final int WORKFLOW_DONE_DELAY_MS = 1500;
    private static final int POWER_PRESET_KOLEJI_DBM = 16;
    private static final int POWER_PRESET_RUCE_DBM = 1;

    private final UhfManager uhf = new UhfManager();
    private final EpcModel epc = new EpcModel();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<Tudu> tuduList = new ArrayList<>();
    private Tudu currentTudu;
    private Tudu.Vyhybka currentVyhybka;

    private CsvStore csvStore;
    private CsvAdapter csvAdapter;
    private SharedPreferences prefs;

    private boolean step1Done, step2Done, step3Done, step2Failed;
    private boolean workflowRunning, chainWorkflow, scanDoneAwaitingConfirm, lastRecordUnlocked;
    /** CSV obnoveno dřív než zdrojový soubor – posun na další čip/výhybku až po načtení TUDU. */
    private boolean pendingAdvanceFromCsv;
    /** Po obnově z CSV vyžadovat ruční výběr TUDU (bez auto-výběru podle posledního záznamu). */
    private boolean requireManualTuduSelection;
    private int activeStep;

    // view reference
    private TextView tvReaderStatus, tvEpcPreview, tvEpcValid, tvSourceFile,
            tvWriteResult, tvCsvPath, tvPwdWriteResult, tvLockResult,
            tvSummaryTudu, tvSummaryVyhybka, tvSummaryCast,
            tvCastHintAction, tvCastHintPart,
            tvScanDoneVyhybka, tvScanDoneCast,
            tvLastRecordVyhybka, tvLastRecordCast,
            step1Circle, step2Circle, step3Circle, step1Label, step2Label;
    private View summary1, colSummaryTudu, colSummaryVyhybka, castHintBox, scanDoneScrim,
            scanDoneDialog, deleteConfirmDialog, lastRecordBox, card1, topBar;
    private NestedScrollView mainScroll;
    private BottomSheetBehavior<View> workflowBehavior;
    private EditText etAccessPwd, etPower, etPwdAccess, etPwdNew, etLockAccessPwd;
    private CheckBox cbAutoCsv;
    private MaterialButtonToggleGroup powerPresetGroup;
    private Boolean powerPresetInKoleji;

    // řádky šablony (kontejnery z include)
    private View[] rows = new View[7];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("rfidgo", MODE_PRIVATE);

        bindViews();
        setupTopBarInsets();
        setupWorkflowSheet();
        setupCollapsibles();
        collapseWorkflowCards();
        setupTemplateRows();
        setupCsv();
        setupListeners();

        etPower.setText("");

        epc.idRfid = prefs.getLong("idRfid", 1);
        refreshTemplate();
        updateSummary1();
        updateStepIndicators();

        setActionStatusReady();
    }

    private void bindViews() {
        tvReaderStatus = findViewById(R.id.tvReaderStatus);
        tvEpcPreview = findViewById(R.id.tvEpcPreview);
        tvEpcValid = findViewById(R.id.tvEpcValid);
        tvSourceFile = findViewById(R.id.tvSourceFile);
        tvWriteResult = findViewById(R.id.tvWriteResult);
        tvCsvPath = findViewById(R.id.tvCsvPath);
        tvPwdWriteResult = findViewById(R.id.tvPwdWriteResult);
        tvLockResult = findViewById(R.id.tvLockResult);
        tvSummaryTudu = findViewById(R.id.tvSummaryTudu);
        tvSummaryVyhybka = findViewById(R.id.tvSummaryVyhybka);
        tvSummaryCast = findViewById(R.id.tvSummaryCast);
        castHintBox = findViewById(R.id.castHintBox);
        tvCastHintAction = findViewById(R.id.tvCastHintAction);
        tvCastHintPart = findViewById(R.id.tvCastHintPart);
        summary1 = findViewById(R.id.summary1);
        colSummaryTudu = findViewById(R.id.colSummaryTudu);
        colSummaryVyhybka = findViewById(R.id.colSummaryVyhybka);
        step1Circle = findViewById(R.id.step1Circle);
        step2Circle = findViewById(R.id.step2Circle);
        step1Label = findViewById(R.id.step1Label);
        step2Label = findViewById(R.id.step2Label);
        step3Circle = findViewById(R.id.step3Circle);
        scanDoneScrim = findViewById(R.id.scanDoneScrim);
        scanDoneDialog = findViewById(R.id.scanDoneDialog);
        deleteConfirmDialog = findViewById(R.id.deleteConfirmDialog);
        tvScanDoneVyhybka = findViewById(R.id.tvScanDoneVyhybka);
        tvScanDoneCast = findViewById(R.id.tvScanDoneCast);
        lastRecordBox = findViewById(R.id.lastRecordBox);
        tvLastRecordVyhybka = findViewById(R.id.tvLastRecordVyhybka);
        tvLastRecordCast = findViewById(R.id.tvLastRecordCast);
        mainScroll = findViewById(R.id.mainScroll);
        card1 = findViewById(R.id.card1);
        topBar = findViewById(R.id.topBar);
        etAccessPwd = findViewById(R.id.etAccessPwd);
        etPower = findViewById(R.id.etPower);
        etPwdAccess = findViewById(R.id.etPwdAccess);
        etPwdNew = findViewById(R.id.etPwdNew);
        etLockAccessPwd = findViewById(R.id.etLockAccessPwd);
        cbAutoCsv = findViewById(R.id.cbAutoCsv);
        powerPresetGroup = findViewById(R.id.powerPresetGroup);

        rows[0] = findViewById(R.id.row1);
        rows[1] = findViewById(R.id.row2);
        rows[2] = findViewById(R.id.row3);
        rows[3] = findViewById(R.id.row4);
        rows[4] = findViewById(R.id.row5);
        rows[5] = findViewById(R.id.row6);
        rows[6] = findViewById(R.id.row7);
    }

    private void setupTopBarInsets() {
        tvReaderStatus.post(() -> {
            float maxWidth = 0f;
            String[] statusTexts = {
                    getString(R.string.tudu_select_status),
                    getString(R.string.power_preset_select_status),
                    "připraveno",
                    "zapisuji EPC…",
                    "zapisuji heslo…",
                    "zamykám…",
                    getString(R.string.epc_retry_status),
                    "chyba hesla",
                    "chyba zamčení",
                    "nedostupná",
                    "inicializuji…"
            };
            for (String text : statusTexts) {
                maxWidth = Math.max(maxWidth, tvReaderStatus.getPaint().measureText(text));
            }
            tvReaderStatus.setMinWidth((int) Math.ceil(maxWidth));
        });
        topBar.post(() -> {
            int topInset = topBar.getHeight();
            int gap = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, CARD1_TOP_GAP_DP, getResources().getDisplayMetrics());
            mainScroll.setPadding(
                    mainScroll.getPaddingLeft(),
                    topInset + gap,
                    mainScroll.getPaddingRight(),
                    mainScroll.getPaddingBottom());
        });
    }

    // ---------- rozbalovací karty a spodní panel ----------

    /** Nad mainScroll (24dp); při rozbalení nad topBar (40dp). */
    private static final float WORKFLOW_SHEET_ELEVATION_COLLAPSED_DP = 28f;
    private static final float WORKFLOW_SHEET_ELEVATION_EXPANDED_DP = 44f;
    private static final float SCAN_DONE_SCRIM_ELEVATION_DP = 34f;
    private static final float SCAN_DONE_SCRIM_ELEVATION_OVER_SHEET_DP = 46f;
    private static final float CARD1_TOP_GAP_DP = 8f;

    private void setupWorkflowSheet() {
        View sheet = findViewById(R.id.workflowSheet);
        View workflowContent = findViewById(R.id.workflowSheetContent);
        workflowBehavior = BottomSheetBehavior.from(sheet);
        workflowBehavior.setHideable(false);
        workflowBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);

        workflowBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(View bottomSheet, int newState) {
                boolean expanded = newState == BottomSheetBehavior.STATE_EXPANDED;
                if (!expanded && newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    workflowContent.setVisibility(View.GONE);
                    workflowContent.setAlpha(1f);
                } else if (expanded) {
                    workflowContent.setVisibility(View.VISIBLE);
                    workflowContent.setAlpha(1f);
                }
                updateWorkflowSheetOverlay(bottomSheet, expanded);
            }

            @Override
            public void onSlide(View bottomSheet, float slideOffset) {
                boolean sliding = slideOffset > 0f;
                updateWorkflowSheetElevation(bottomSheet, sliding);
                if (!sliding) {
                    workflowContent.setVisibility(View.GONE);
                    workflowContent.setAlpha(1f);
                    return;
                }
                workflowContent.setVisibility(View.VISIBLE);
                workflowContent.setAlpha(Math.min(1f, slideOffset * 1.5f));
            }
        });

        findViewById(R.id.workflowSheetHandle).setOnClickListener(v -> {
            if (workflowBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                workflowBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            } else {
                workflowBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        colSummaryTudu.setOnClickListener(v -> showTuduPicker());
        colSummaryVyhybka.setOnClickListener(v -> showVyhybkaPicker());
    }

    private void updateWorkflowSheetOverlay(View sheet, boolean expanded) {
        updateWorkflowSheetElevation(sheet, expanded);
        if (isOverlayDialogVisible()) {
            showOverlayScrimBehindTopBar();
        }
    }

    private boolean isOverlayDialogVisible() {
        return scanDoneDialog.getVisibility() == View.VISIBLE
                || deleteConfirmDialog.getVisibility() == View.VISIBLE;
    }

    private void showOverlayScrimBehindTopBar() {
        ViewGroup.MarginLayoutParams scrimLp =
                (ViewGroup.MarginLayoutParams) scanDoneScrim.getLayoutParams();
        scrimLp.topMargin = topBar.getHeight();
        scanDoneScrim.setLayoutParams(scrimLp);
        scanDoneScrim.setElevation(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, SCAN_DONE_SCRIM_ELEVATION_OVER_SHEET_DP,
                getResources().getDisplayMetrics()));
        scanDoneScrim.setVisibility(View.VISIBLE);
        scanDoneScrim.setAlpha(1f);
    }

    private void resetOverlayScrimElevation() {
        scanDoneScrim.setElevation(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, SCAN_DONE_SCRIM_ELEVATION_DP,
                getResources().getDisplayMetrics()));
    }

    private void updateWorkflowSheetElevation(View sheet, boolean expanded) {
        float dp = expanded
                ? WORKFLOW_SHEET_ELEVATION_EXPANDED_DP
                : WORKFLOW_SHEET_ELEVATION_COLLAPSED_DP;
        sheet.setElevation(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()));
    }

    private void expandCard1Body() {
        View body = findViewById(R.id.body1);
        TextView header = findViewById(R.id.header1);
        body.setVisibility(View.VISIBLE);
        String t = header.getText().toString();
        if (t.startsWith("▸")) header.setText("▾" + t.substring(1));
    }

    private void collapseCard1Body() {
        collapseCard(R.id.header1, R.id.body1);
    }

    private void collapseCard(int headerId, int bodyId) {
        View body = findViewById(bodyId);
        TextView header = findViewById(headerId);
        body.setVisibility(View.GONE);
        String t = header.getText().toString();
        if (t.startsWith("▾")) header.setText("▸" + t.substring(1));
    }

    private void collapseWorkflowCards() {
        collapseCard(R.id.header2, R.id.body2);
        collapseCard(R.id.header3, R.id.body3);
        collapseCard(R.id.header4, R.id.body4);
        collapseCard(R.id.header5, R.id.body5);
    }

    private void scrollToCard1() {
        if (mainScroll == null || card1 == null) return;
        mainScroll.post(() -> mainScroll.smoothScrollTo(0, card1.getTop()));
    }

    private void showTuduPicker() {
        if (tuduList.isEmpty()) {
            toast("Nejdříve vyberte soubor se zdrojem dat");
            expandCard1Body();
            return;
        }

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_tudu_picker, null);
        EditText etSearch = dialogView.findViewById(R.id.etTuduSearch);
        ListView listView = dialogView.findViewById(R.id.lvTudu);

        List<String> filteredCodes = new ArrayList<>();
        for (Tudu t : tuduList) filteredCodes.add(t.code);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, filteredCodes);
        listView.setAdapter(adapter);

        int checked = -1;
        if (!requireManualTuduSelection) {
            String preselect = currentTudu != null ? currentTudu.code
                    : (epc.tudu != null ? epc.tudu : "");
            checked = filteredCodes.indexOf(preselect);
        }
        if (checked >= 0) listView.setItemChecked(checked, true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vyberte TUDU")
                .setView(dialogView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            String code = filteredCodes.get(position);
            for (Tudu t : tuduList) {
                if (t.code.equals(code)) {
                    requireManualTuduSelection = false;
                    if (pendingAdvanceFromCsv) {
                        selectTuduPreservingEpc(t);
                    } else {
                        selectTudu(t);
                    }
                    break;
                }
            }
            dialog.dismiss();
        });

        etSearch.addTextChangedListener(new SimpleWatcher(() -> {
            String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            filteredCodes.clear();
            for (Tudu t : tuduList) {
                if (q.isEmpty() || t.code.toLowerCase(Locale.ROOT).contains(q)) {
                    filteredCodes.add(t.code);
                }
            }
            adapter.notifyDataSetChanged();
            if (!requireManualTuduSelection) {
                String selected = currentTudu != null ? currentTudu.code
                        : (epc.tudu != null ? epc.tudu : "");
                int pos = filteredCodes.indexOf(selected);
                if (pos >= 0) listView.setItemChecked(pos, true);
            }
        }));

        dialog.show();
        etSearch.requestFocus();
    }

    private void showVyhybkaPicker() {
        if (currentTudu == null || currentTudu.vyhybky.isEmpty()) {
            toast("TUDU nemá výhybky – vyberte soubor nebo TUDU");
            expandCard1Body();
            return;
        }
        final String tuduCode = currentTudu.code;
        final List<Tudu.Vyhybka> vyhybky = currentTudu.vyhybky;

        ListView listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        ArrayAdapter<Tudu.Vyhybka> adapter = new ArrayAdapter<Tudu.Vyhybka>(this,
                android.R.layout.simple_list_item_single_choice, vyhybky) {
            @Override
            public boolean isEnabled(int position) {
                return !isVyhybkaCompleteInCsv(tuduCode, vyhybky.get(position));
            }

            @Override
            public android.view.View getView(int position, android.view.View convertView,
                    android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                TextView tv = (TextView) view;
                Tudu.Vyhybka v = vyhybky.get(position);
                boolean done = isVyhybkaCompleteInCsv(tuduCode, v);
                tv.setText(formatVyhybkaPickerLabel(tuduCode, v));
                if (done) {
                    tv.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_muted));
                    tv.setAlpha(0.45f);
                } else {
                    tv.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text));
                    tv.setAlpha(1f);
                }
                return view;
            }
        };
        listView.setAdapter(adapter);

        int checked = currentVyhybka != null ? vyhybky.indexOf(currentVyhybka) : 0;
        if (checked < 0) checked = 0;
        listView.setItemChecked(checked, true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vyberte výhybku")
                .setView(listView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (isVyhybkaCompleteInCsv(tuduCode, vyhybky.get(position))) {
                toast("výhybka je již zapsaná v CSV");
                return;
            }
            selectVyhybka(vyhybky.get(position), true);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void setupCollapsibles() {
        toggle(R.id.header1, R.id.body1, 0);
        toggle(R.id.header2, R.id.body2, 0);
        toggle(R.id.header3, R.id.body3, 0);
        toggle(R.id.header4, R.id.body4, 0);
        toggle(R.id.header5, R.id.body5, 0);
    }

    private void toggle(int headerId, int bodyId, int summaryId) {
        TextView header = findViewById(headerId);
        View body = findViewById(bodyId);
        View summary = summaryId != 0 ? findViewById(summaryId) : null;
        header.setOnClickListener(v -> {
            boolean vis = body.getVisibility() == View.VISIBLE;
            body.setVisibility(vis ? View.GONE : View.VISIBLE);
            String t = header.getText().toString();
            header.setText((vis ? "▸" : "▾") + t.substring(1));
            if (summary != null) {
                summary.setVisibility(vis ? View.VISIBLE : View.GONE);
            }
        });
    }

    // ---------- indikátor kroků ----------

    private void updateStepIndicators() {
        boolean step1Error = !step1Done;
        setStepCircle(step1Circle, step1Done, activeStep == 1 && !step1Error, step1Error, "1");
        boolean modeMissing = step1Done && !isPowerPresetSelected();
        boolean step2Error = step2Failed || modeMissing;
        setStepCircle(step2Circle, step2Done && !modeMissing, activeStep == 2 && !modeMissing,
                step2Error, "2");
        setStepCircle(step3Circle, step3Done, activeStep == 3, false, "3");
        int muted = ContextCompat.getColor(this, R.color.text_muted);
        step1Label.setTextColor(step1Error ? COLOR_STATUS_ERROR : muted);
        step2Label.setTextColor(step2Error ? COLOR_STATUS_ERROR : muted);
    }

    private void setStepCircle(TextView circle, boolean done, boolean active, boolean failed, String number) {
        if (failed) {
            circle.setText(number);
            circle.setBackgroundResource(R.drawable.step_circle_error);
            circle.setTextColor(0xFFFFFFFF);
        } else if (done) {
            circle.setText("✓");
            circle.setBackgroundResource(R.drawable.step_circle_done);
            circle.setTextColor(0xFFFFFFFF);
        } else if (active) {
            circle.setText(number);
            circle.setBackgroundResource(R.drawable.step_circle_active);
            circle.setTextColor(0xFFFFFFFF);
        } else {
            circle.setText(number);
            circle.setBackgroundResource(R.drawable.step_circle_pending);
            circle.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }
    }

    private void updateStep1() {
        step1Done = currentTudu != null && currentVyhybka != null
                && epc.tudu != null && !epc.tudu.isEmpty();
        updateStepIndicators();
        if (!workflowRunning) {
            setActionStatusReady();
        }
    }

    private void updateSummary1() {
        tvSummaryTudu.setText(epc.tudu == null || epc.tudu.isEmpty() ? "—" : epc.tudu);
        if (epc.vyhybka > 0) {
            String vyhStr = String.valueOf(epc.vyhybka);
            SpannableString vyhSpan = new SpannableString(vyhStr);
            applyVyhybkaAccent(vyhSpan, 0, vyhStr.length());
            tvSummaryVyhybka.setText(vyhSpan);
        } else {
            tvSummaryVyhybka.setText("—");
        }
        if (epc.cast > 0) {
            int total = currentVyhybka != null
                    ? currentVyhybka.castMax - currentVyhybka.castMin + 1
                    : 3;
            String current = String.valueOf(epc.cast);
            String rest = "/" + total;
            SpannableString span = new SpannableString(current + rest);
            applyCastAccent(span, 0, current.length());
            int muted = ContextCompat.getColor(this, R.color.text_muted);
            span.setSpan(new ForegroundColorSpan(muted), current.length(), span.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvSummaryCast.setText(span);
        } else {
            tvSummaryCast.setText("—");
        }
        updateCastHint();
    }

    private void updateLastRecordPreview() {
        if (!lastRecordUnlocked) {
            lastRecordBox.setVisibility(View.GONE);
            return;
        }
        CsvStore.Row last = csvStore != null ? csvStore.getLastRow() : null;
        if (last == null) {
            lastRecordBox.setVisibility(View.GONE);
            return;
        }

        int vyhybka = parseInt(last.vyhybka, 0);
        int cast = parseInt(last.cast, 0);
        if (vyhybka <= 0 || cast <= 0) {
            lastRecordBox.setVisibility(View.GONE);
            return;
        }

        String vyhPrefix = getString(R.string.last_record_vyhybka_prefix);
        String castPrefix = getString(R.string.last_record_cast_prefix);
        String vyhStr = String.valueOf(vyhybka);
        int total = castTotalForRow(last);
        String current = String.valueOf(cast);
        String rest = "/" + total;

        SpannableString vyhSpan = new SpannableString(vyhPrefix + vyhStr);
        applyVyhybkaAccent(vyhSpan, vyhPrefix.length(), vyhSpan.length());
        tvLastRecordVyhybka.setText(vyhSpan);

        SpannableString castSpan = new SpannableString(castPrefix + current + rest);
        int castValueStart = castPrefix.length();
        int castValueEnd = castValueStart + current.length();
        applyCastAccent(castSpan, castValueStart, castValueEnd);
        int muted = ContextCompat.getColor(this, R.color.text_muted);
        castSpan.setSpan(new ForegroundColorSpan(muted), castValueEnd, castSpan.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvLastRecordCast.setText(castSpan);

        lastRecordBox.setVisibility(View.VISIBLE);
    }

    private int castTotalForRow(CsvStore.Row row) {
        if (row == null) return 3;
        for (Tudu t : tuduList) {
            if (!t.code.equals(row.tudu)) continue;
            for (Tudu.Vyhybka v : t.vyhybky) {
                if (v.cislo == parseInt(row.vyhybka, -1)) {
                    return v.castMax - v.castMin + 1;
                }
            }
        }
        return 3;
    }

    private void updateCastHint() {
        if (currentVyhybka == null || epc.cast <= 0
                || currentVyhybka.castMax - currentVyhybka.castMin + 1 != 3) {
            castHintBox.setVisibility(View.GONE);
            return;
        }
        String partName = castPartName(epc.cast);
        if (partName == null) {
            castHintBox.setVisibility(View.GONE);
            return;
        }
        String prefix = getString(R.string.cast_hint_prefix);
        String chipLabel = getString(R.string.cast_hint_chip);
        String commaVyhybky = getString(R.string.cast_hint_comma_vyhybky);
        String castStr = String.valueOf(epc.cast);
        String vyhybkaStr = String.valueOf(epc.vyhybka);
        SpannableString span = new SpannableString(
                prefix + chipLabel + castStr + commaVyhybky + vyhybkaStr);

        int castStart = prefix.length() + chipLabel.length();
        int castEnd = castStart + castStr.length();
        applyCastAccent(span, castStart, castEnd);

        int vyhStart = castEnd + commaVyhybky.length();
        int vyhEnd = vyhStart + vyhybkaStr.length();
        applyVyhybkaAccent(span, vyhStart, vyhEnd);

        tvCastHintAction.setText(span);
        tvCastHintPart.setText(partName);
        castHintBox.setVisibility(View.VISIBLE);
    }

    private void applyCastAccent(SpannableString span, int start, int end) {
        int color = ContextCompat.getColor(this, R.color.accent);
        span.setSpan(new ForegroundColorSpan(color), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void applyVyhybkaAccent(SpannableString span, int start, int end) {
        int color = ContextCompat.getColor(this, R.color.vyhybka_accent);
        span.setSpan(new ForegroundColorSpan(color), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void showScanDoneNotification(int vyhybka, int cast) {
        String vyhPrefix = getString(R.string.scan_done_vyhybka_prefix);
        String castPrefix = getString(R.string.scan_done_cast_prefix);
        String vyhStr = String.valueOf(vyhybka);
        String castStr = String.valueOf(cast);

        SpannableString vyhSpan = new SpannableString(vyhPrefix + vyhStr);
        applyVyhybkaAccent(vyhSpan, vyhPrefix.length(), vyhSpan.length());
        tvScanDoneVyhybka.setText(vyhSpan);

        SpannableString castSpan = new SpannableString(castPrefix + castStr);
        applyCastAccent(castSpan, castPrefix.length(), castSpan.length());
        tvScanDoneCast.setText(castSpan);

        step3Done = true;
        updateStepIndicators();

        scanDoneScrim.setAlpha(0f);
        scanDoneDialog.setAlpha(0f);
        showOverlayScrimBehindTopBar();
        scanDoneScrim.animate().alpha(1f).setDuration(200).start();
        scanDoneDialog.setVisibility(View.VISIBLE);
        scanDoneDialog.animate().alpha(1f).setDuration(200).start();
    }

    private void onScanDoneContinue() {
        if (!scanDoneAwaitingConfirm) return;
        scanDoneAwaitingConfirm = false;
        hideScanDoneNotification(() -> {
            onTagCycleComplete();
            lastRecordUnlocked = true;
            updateLastRecordPreview();
            step2Done = false;
            step2Failed = false;
            step3Done = false;
            updateStepIndicators();
            setActionStatusReady();
        });
    }

    private void onScanDoneRetry() {
        if (!scanDoneAwaitingConfirm) return;
        scanDoneAwaitingConfirm = false;
        hideScanDoneNotification(() -> {
            step2Done = false;
            step2Failed = false;
            step3Done = false;
            updateStepIndicators();
            refreshTemplate();
            updateSummary1();
            setActionStatusReady();
        });
    }

    private void hideScanDoneNotification() {
        hideScanDoneNotification(null);
    }

    private void hideScanDoneNotification(Runnable onHidden) {
        if (scanDoneDialog.getVisibility() != View.VISIBLE) {
            if (onHidden != null) onHidden.run();
            return;
        }
        scanDoneScrim.animate().alpha(0f).setDuration(150).start();
        scanDoneDialog.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            scanDoneDialog.setVisibility(View.GONE);
            scanDoneScrim.setAlpha(1f);
            scanDoneDialog.setAlpha(1f);
            if (deleteConfirmDialog.getVisibility() != View.VISIBLE) {
                scanDoneScrim.setVisibility(View.GONE);
                resetOverlayScrimElevation();
            }
            if (onHidden != null) onHidden.run();
        }).start();
    }

    private void showDeleteConfirmDialog() {
        if (csvStore == null) {
            toast("Tabulka se ještě načítá");
            return;
        }
        if (csvStore.getLastRow() == null) {
            toast("Tabulka je prázdná");
            return;
        }
        deleteConfirmDialog.setAlpha(0f);
        showOverlayScrimBehindTopBar();
        scanDoneScrim.animate().alpha(1f).setDuration(200).start();
        deleteConfirmDialog.setVisibility(View.VISIBLE);
        deleteConfirmDialog.animate().alpha(1f).setDuration(200).start();
    }

    private void onDeleteConfirmYes() {
        hideDeleteConfirmDialog(this::deleteLastCsvRow);
    }

    private void onDeleteConfirmNo() {
        hideDeleteConfirmDialog(null);
    }

    private void hideDeleteConfirmDialog(Runnable onHidden) {
        if (deleteConfirmDialog.getVisibility() != View.VISIBLE) {
            if (onHidden != null) onHidden.run();
            return;
        }
        scanDoneScrim.animate().alpha(0f).setDuration(150).start();
        deleteConfirmDialog.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (scanDoneDialog.getVisibility() != View.VISIBLE) {
                scanDoneScrim.setVisibility(View.GONE);
                resetOverlayScrimElevation();
            }
            deleteConfirmDialog.setVisibility(View.GONE);
            scanDoneScrim.setAlpha(1f);
            deleteConfirmDialog.setAlpha(1f);
            if (onHidden != null) onHidden.run();
        }).start();
    }

    private String castPartName(int cast) {
        switch (cast) {
            case 1: return getString(R.string.cast_part_1);
            case 2: return getString(R.string.cast_part_2);
            case 3: return getString(R.string.cast_part_3);
            default: return null;
        }
    }

    private void resetTagWorkflow() {
        workflowRunning = false;
        chainWorkflow = false;
        scanDoneAwaitingConfirm = false;
        activeStep = 0;
        step2Done = false;
        step2Failed = false;
        step3Done = false;
        updateStepIndicators();
        setActionStatusReady();
    }

    private void setActionStatus(String text, int color) {
        tvReaderStatus.setText(text);
        tvReaderStatus.setTextColor(color);
    }

    private void setActionStatusReady() {
        if (!step1Done) {
            setActionStatus(getString(R.string.tudu_select_status), COLOR_STATUS_ERROR);
            return;
        }
        if (!isPowerPresetSelected()) {
            setActionStatus(getString(R.string.power_preset_select_status), COLOR_STATUS_ERROR);
            updateStepIndicators();
            return;
        }
        setActionStatus("připraveno", COLOR_STATUS_READY);
    }

    private void onWorkflowFailed(String status) {
        workflowRunning = false;
        chainWorkflow = false;
        scanDoneAwaitingConfirm = false;
        activeStep = 2;
        step2Done = false;
        step2Failed = true;
        step3Done = false;
        updateStepIndicators();
        setActionStatus(status, COLOR_STATUS_ERROR);
        ui.postDelayed(() -> {
            step2Failed = false;
            activeStep = 0;
            updateStepIndicators();
            setActionStatusReady();
        }, WORKFLOW_DONE_DELAY_MS + 500);
    }

    // ---------- šablona EPC ----------

    private void setupTemplateRows() {
        String[] idx = {"1", "2", "3", "4", "5", "6", "7"};
        String[] names = {
                epc.nameYear, epc.nameTudu14, epc.nameTudu5, epc.nameTudu6,
                epc.nameVyhybka, epc.nameCast, epc.nameIdRfid
        };
        for (int i = 0; i < 7; i++) {
            View row = rows[i];
            ((TextView) row.findViewById(R.id.tvIdx)).setText(idx[i]);
            EditText etName = row.findViewById(R.id.etName);
            etName.setText(names[i]);
            EditText etVal = row.findViewById(R.id.etValue);

            boolean editableValue = (i == 0 || i == 5 || i == 6);
            etVal.setFocusable(editableValue);
            etVal.setFocusableInTouchMode(editableValue);
            etVal.setClickable(editableValue);
            if (i == 0) etVal.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            if (i == 5 || i == 6) etVal.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        }

        valueWatcher(0, s -> { epc.year = s; });
        valueWatcher(5, s -> {
            epc.cast = parseInt(s, epc.cast);
            updateSummary1();
        });
        valueWatcher(6, s -> { epc.idRfid = parseLong(s, epc.idRfid); });

        nameWatcher(0, s -> epc.nameYear = s);
        nameWatcher(1, s -> epc.nameTudu14 = s);
        nameWatcher(2, s -> epc.nameTudu5 = s);
        nameWatcher(3, s -> epc.nameTudu6 = s);
        nameWatcher(4, s -> epc.nameVyhybka = s);
        nameWatcher(5, s -> epc.nameCast = s);
        nameWatcher(6, s -> epc.nameIdRfid = s);
    }

    private interface StrCb { void on(String s); }

    private void valueWatcher(int rowIdx, StrCb cb) {
        EditText et = rows[rowIdx].findViewById(R.id.etValue);
        et.addTextChangedListener(new SimpleWatcher(() -> {
            cb.on(et.getText().toString().trim());
            refreshHexAndPreview();
        }));
    }

    private void nameWatcher(int rowIdx, StrCb cb) {
        EditText et = rows[rowIdx].findViewById(R.id.etName);
        et.addTextChangedListener(new SimpleWatcher(() ->
                cb.on(et.getText().toString().trim())));
    }

    private void refreshTemplate() {
        setValue(0, epc.year);
        setValue(1, epc.f2Tudu14());
        setValue(2, tuduCharOr(4));
        setValue(3, tuduCharOr(5));
        setValue(4, String.valueOf(epc.vyhybka));
        setValue(5, String.valueOf(epc.cast));
        setValue(6, String.valueOf(epc.idRfid));
        refreshHexAndPreview();
    }

    private String tuduCharOr(int idx) {
        String t = epc.tudu == null ? "" : epc.tudu;
        return t.length() > idx ? String.valueOf(t.charAt(idx)) : "-";
    }

    private void setValue(int rowIdx, String v) {
        EditText et = rows[rowIdx].findViewById(R.id.etValue);
        if (!et.getText().toString().equals(v)) et.setText(v);
    }

    private void refreshHexAndPreview() {
        String[] hex = {
                epc.f1Year(), epc.f2Tudu14(), epc.f3Tudu5(), epc.f4Tudu6(),
                epc.f5Vyhybka(), epc.f6Cast(), epc.f7IdRfid()
        };
        for (int i = 0; i < 7; i++) {
            ((TextView) rows[i].findViewById(R.id.tvHex)).setText(hex[i]);
        }
        tvEpcPreview.setText(epc.buildEpcPreview());
        if (epc.isValid()) {
            tvEpcValid.setText("✓ EPC validní (24 hex znaků)");
            tvEpcValid.setTextColor(0xFF2E7D32);
        } else {
            tvEpcValid.setText("✗ EPC není validní – zkontrolujte hodnoty");
            tvEpcValid.setTextColor(0xFFC62828);
        }
    }

    // ---------- CSV ----------

    private void setupCsv() {
        File out = new File(getExternalFilesDir(null), "rfid_go_output.csv");
        tvCsvPath.setText(out.getAbsolutePath());

        csvAdapter = new CsvAdapter();
        RecyclerView rv = findViewById(R.id.rvCsv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setHasFixedSize(true);
        rv.setItemAnimator(null);
        rv.setAdapter(csvAdapter);

        io.execute(() -> {
            CsvStore loaded = new CsvStore(out);
            ui.post(() -> {
                csvStore = loaded;
                refreshCsvTable();
                if (csvStore.size() > 0) {
                    restoreStateFromLoadedCsv();
                }
            });
        });
    }

    /** Obnoví stav šablony a náhled posledního záznamu z již načteného CSV. */
    private void restoreStateFromLoadedCsv() {
        if (csvStore == null || csvStore.size() == 0) return;
        CsvStore.Row last = csvStore.getLastRow();
        if (last == null) return;

        applyRowToEpc(last);
        epc.idRfid = csvStore.getMaxIdRfid() + 1;
        prefs.edit().putLong("idRfid", epc.idRfid).apply();
        syncCurrentVyhybka();
        if (currentVyhybka != null) {
            advanceCastAndVyhybka();
        } else {
            pendingAdvanceFromCsv = true;
        }
        refreshTemplate();
        updateStep1();
        updateSummary1();
        resetTagWorkflow();

        lastRecordUnlocked = true;
        requireManualTuduSelection = true;
        updateLastRecordPreview();
    }

    private void persistCsvAsync() {
        if (csvStore == null) return;
        io.execute(() -> {
            try {
                csvStore.persist();
            } catch (Exception e) {
                ui.post(() -> toast("CSV uložení: " + e.getMessage()));
            }
        });
    }

    private void refreshCsvTable() {
        if (csvAdapter != null && csvStore != null) {
            csvAdapter.setData(csvStore.getLastRows(5));
        }
    }

    // ---------- listenery ----------

    private void setupListeners() {
        findViewById(R.id.btnPickSource).setOnClickListener(v -> pickSourceFile());

        powerPresetGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            onPowerPresetSelected(checkedId == R.id.btnPowerPresetKoleji);
        });
        findViewById(R.id.btnApplyPower).setOnClickListener(v -> applyPower());
        findViewById(R.id.btnWrite).setOnClickListener(v -> doWrite());
        findViewById(R.id.btnWritePwd).setOnClickListener(v -> doWritePassword());
        findViewById(R.id.btnLock).setOnClickListener(v -> doLock());
        findViewById(R.id.btnExportCsv).setOnClickListener(v -> exportCsv());
        findViewById(R.id.btnClearCsv).setOnClickListener(v -> showDeleteConfirmDialog());
        findViewById(R.id.btnDeleteLastRecord).setOnClickListener(v -> showDeleteConfirmDialog());
        findViewById(R.id.btnScanDoneContinue).setOnClickListener(v -> onScanDoneContinue());
        findViewById(R.id.btnScanDoneRetry).setOnClickListener(v -> onScanDoneRetry());
        findViewById(R.id.btnDeleteConfirmYes).setOnClickListener(v -> onDeleteConfirmYes());
        findViewById(R.id.btnDeleteConfirmNo).setOnClickListener(v -> onDeleteConfirmNo());
    }

    // ---------- výběr souboru / TUDU ----------

    private final androidx.activity.result.ActivityResultLauncher<Intent> picker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) loadSource(uri);
                        }
                    });

    private void pickSourceFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        picker.launch(i);
    }

    private void loadSource(Uri uri) {
        String name = queryName(uri);
        tvSourceFile.setText("Načítám: " + name);
        io.execute(() -> {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                List<Tudu> loaded = TuduLoader.load(in, name);
                ui.post(() -> {
                    tuduList = loaded;
                    tvSourceFile.setText(name + "  •  TUDU: " + loaded.size());
                    collapseCard1Body();
                    scrollToCard1();
                    onTuduListLoaded();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    tvSourceFile.setText("Chyba načtení: " + e.getMessage());
                    toast("Chyba načtení souboru");
                });
            }
        });
    }

    private void onTuduListLoaded() {
        if (tuduList.isEmpty()) return;
        if (requireManualTuduSelection) {
            showTuduPicker();
            return;
        }
        if (epc.tudu != null && !epc.tudu.isEmpty()) {
            for (Tudu t : tuduList) {
                if (t.code.equals(epc.tudu)) {
                    selectTuduPreservingEpc(t);
                    return;
                }
            }
        }
        showTuduPicker();
    }

    private void selectTuduPreservingEpc(Tudu t) {
        currentTudu = t;
        epc.tudu = t.code;
        if (pendingAdvanceFromCsv) {
            pendingAdvanceFromCsv = false;
            syncCurrentVyhybka();
            if (currentVyhybka != null) {
                advanceCastAndVyhybka();
            } else {
                Tudu.Vyhybka first = firstAvailableVyhybka(t);
                selectVyhybka(first != null ? first : t.vyhybky.get(0), true);
                return;
            }
            refreshTemplate();
            updateStep1();
            updateSummary1();
            return;
        }
        if (epc.vyhybka > 0) {
            for (Tudu.Vyhybka v : t.vyhybky) {
                if (v.cislo == epc.vyhybka) {
                    if (epc.cast > v.castMax || isVyhybkaCompleteInCsv(t.code, v)) {
                        advanceToNextVyhybka();
                    } else {
                        int expected = firstMissingCast(t.code, v);
                        selectVyhybka(v, epc.cast != expected);
                    }
                    return;
                }
            }
        }
        Tudu.Vyhybka first = firstAvailableVyhybka(t);
        selectVyhybka(first != null ? first : t.vyhybky.get(0), true);
    }

    private void selectTudu(Tudu t) {
        pendingAdvanceFromCsv = false;
        currentTudu = t;
        epc.tudu = t.code;
        if (!t.vyhybky.isEmpty()) {
            Tudu.Vyhybka first = firstAvailableVyhybka(t);
            selectVyhybka(first != null ? first : t.vyhybky.get(0), true);
        } else {
            currentVyhybka = null;
            refreshTemplate();
            updateStep1();
            updateSummary1();
        }
    }

    private void selectVyhybka(Tudu.Vyhybka v, boolean resetCast) {
        currentVyhybka = v;
        epc.vyhybka = v.cislo;
        if (resetCast) {
            epc.cast = currentTudu != null
                    ? firstMissingCast(currentTudu.code, v)
                    : v.castMin;
        }
        refreshTemplate();
        updateStep1();
        updateSummary1();
    }

    private void restoreSelectionFromRow(CsvStore.Row row) {
        if (row == null) return;
        applyRowToEpc(row);
        prefs.edit().putLong("idRfid", epc.idRfid).apply();
        refreshTemplate();
        updateStep1();
        updateSummary1();
        resetTagWorkflow();
    }

    private void applyRowToEpc(CsvStore.Row row) {
        epc.year = row.rok;
        epc.tudu = row.tudu;
        epc.vyhybka = parseInt(row.vyhybka, epc.vyhybka);
        epc.cast = parseInt(row.cast, epc.cast);
        epc.idRfid = parseLong(row.idRfid, epc.idRfid);

        currentTudu = null;
        currentVyhybka = null;
        for (int i = 0; i < tuduList.size(); i++) {
            if (!tuduList.get(i).code.equals(row.tudu)) continue;
            currentTudu = tuduList.get(i);
            for (Tudu.Vyhybka v : currentTudu.vyhybky) {
                if (v.cislo == epc.vyhybka) {
                    currentVyhybka = v;
                    break;
                }
            }
            break;
        }
    }

    private void deleteLastCsvRow() {
        if (csvStore == null) {
            toast("Tabulka se ještě načítá");
            return;
        }
        CsvStore.Row last = csvStore.removeLast();
        if (last == null) {
            toast("Tabulka je prázdná");
            return;
        }
        persistCsvAsync();
        refreshCsvTable();
        restoreSelectionFromRow(last);
        updateLastRecordPreview();
        toast("Poslední záznam vymazán");
    }

    // ---------- zápis EPC ----------

    private void applyPower() {
        if (!requirePowerPreset()) return;
        int p = parseInt(etPower.getText().toString().trim(), POWER_PRESET_KOLEJI_DBM);
        applyPowerValue(p, true);
    }

    private void applyPowerValue(int dbm, boolean showToast) {
        io.execute(() -> {
            boolean ok = uhf.setPower(dbm);
            if (showToast) {
                ui.post(() -> toast(ok ? ("Výkon nastaven na " + dbm + " dBm") : "Nastavení výkonu selhalo"));
            }
        });
    }

    private boolean isPowerPresetSelected() {
        return powerPresetInKoleji != null;
    }

    private boolean requirePowerPreset() {
        if (isPowerPresetSelected()) return true;
        toast(getString(R.string.power_preset_required));
        return false;
    }

    private void onPowerPresetSelected(boolean inKoleji) {
        powerPresetInKoleji = inKoleji;
        int power = inKoleji ? POWER_PRESET_KOLEJI_DBM : POWER_PRESET_RUCE_DBM;
        etPower.setText(String.valueOf(power));
        powerPresetGroup.setSelectionRequired(true);
        updateStepIndicators();
        if (!uhf.isReady()) {
            initReaderAsync();
        } else {
            applyPowerValue(power, false);
            setActionStatusReady();
        }
    }

    private void doWrite() {
        if (scanDoneAwaitingConfirm) return;
        if (!requirePowerPreset()) {
            if (chainWorkflow) onWorkflowFailed(getString(R.string.power_preset_required));
            return;
        }
        if (!epc.isValid()) {
            toast("EPC není validní");
            if (chainWorkflow) onWorkflowFailed("EPC není validní");
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            if (chainWorkflow) onWorkflowFailed("čtečka nedostupná");
            return;
        }
        if (!chainWorkflow) {
            setActionStatus("zapisuji EPC…", COLOR_STATUS_BUSY);
        }
        final String pwd = etAccessPwd.getText().toString().trim();
        final String newEpc = epc.buildEpc();
        tvWriteResult.setText("Zapisuji…");
        tvWriteResult.setTextColor(0xFF5F6A76);

        io.execute(() -> {
            UhfManager.WriteResult r = uhf.writeEpc(pwd, newEpc);
            ui.post(() -> onWriteDone(r, newEpc));
        });
    }

    private void onWriteDone(UhfManager.WriteResult r, String writtenEpc) {
        if (r.success) {
            if (r.presetPasswordUsed != null) {
                resetAccessPasswordFields();
            }
            tvWriteResult.setTextColor(0xFF2E7D32);
            tvWriteResult.setText("✓ " + r.message
                    + (r.oldEpc != null ? ("\nPůvodní EPC: " + r.oldEpc) : "")
                    + (r.tid != null ? ("\nTID: " + r.tid) : ""));

            if (cbAutoCsv.isChecked()) saveRowToCsv(writtenEpc, r.tid);

            if (chainWorkflow) {
                setActionStatus("zapisuji heslo…", COLOR_STATUS_BUSY);
                doWritePassword();
            } else {
                onTagCycleComplete();
                setActionStatusReady();
            }
        } else {
            tvWriteResult.setTextColor(0xFFC62828);
            tvWriteResult.setText("✗ " + r.message);
            if (chainWorkflow) onWorkflowFailed(getString(R.string.epc_retry_status));
            else setActionStatus(getString(R.string.epc_retry_status), COLOR_STATUS_ERROR);
        }
    }

    // ---------- zápis access hesla ----------

    private void doWritePassword() {
        if (scanDoneAwaitingConfirm) return;
        if (!requirePowerPreset()) {
            if (chainWorkflow) onWorkflowFailed(getString(R.string.power_preset_required));
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            if (chainWorkflow) onWorkflowFailed("čtečka nedostupná");
            return;
        }
        final String accessPwd = etPwdAccess.getText().toString().trim();
        final String newPwd = etPwdNew.getText().toString().trim();
        if (!newPwd.matches("[0-9A-Fa-f]{8}")) {
            toast("NEW PWD musí mít 8 hex znaků");
            if (chainWorkflow) onWorkflowFailed("neplatné heslo");
            return;
        }
        if (!chainWorkflow) {
            setActionStatus("zapisuji heslo…", COLOR_STATUS_BUSY);
        }
        tvPwdWriteResult.setText("Zapisuji heslo…");
        tvPwdWriteResult.setTextColor(0xFF5F6A76);

        io.execute(() -> {
            UhfManager.WriteResult r = uhf.writeAccessPassword(accessPwd, newPwd);
            ui.post(() -> onPwdWriteDone(r));
        });
    }

    private void onPwdWriteDone(UhfManager.WriteResult r) {
        if (r.success) {
            if (r.presetPasswordUsed != null) {
                resetAccessPasswordFields();
            }
            tvPwdWriteResult.setTextColor(0xFF2E7D32);
            tvPwdWriteResult.setText("✓ " + r.message
                    + (r.oldEpc != null ? ("\nEPC: " + r.oldEpc) : "")
                    + (r.tid != null ? ("\nTID: " + r.tid) : ""));
            etLockAccessPwd.setText(etPwdNew.getText().toString().trim().toUpperCase());
            if (chainWorkflow) {
                setActionStatus("zamykám…", COLOR_STATUS_BUSY);
                doLock();
            } else {
                setActionStatusReady();
            }
        } else {
            tvPwdWriteResult.setTextColor(0xFFC62828);
            tvPwdWriteResult.setText("✗ " + r.message);
            if (chainWorkflow) onWorkflowFailed("chyba hesla");
            else setActionStatus("chyba hesla", COLOR_STATUS_ERROR);
        }
    }

    // ---------- zamčení tagu ----------

    private void doLock() {
        if (scanDoneAwaitingConfirm) return;
        if (!requirePowerPreset()) {
            if (chainWorkflow) onWorkflowFailed(getString(R.string.power_preset_required));
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            if (chainWorkflow) onWorkflowFailed("čtečka nedostupná");
            return;
        }
        if (!chainWorkflow) {
            setActionStatus("zamykám…", COLOR_STATUS_BUSY);
        }
        final String accessPwd = etLockAccessPwd.getText().toString().trim();
        final String lockCode = getString(R.string.lock_code_value);
        tvLockResult.setText("Zamykám…");
        tvLockResult.setTextColor(0xFF5F6A76);

        io.execute(() -> {
            UhfManager.WriteResult r = uhf.lockTag(accessPwd, lockCode);
            ui.post(() -> onLockDone(r));
        });
    }

    private void onLockDone(UhfManager.WriteResult r) {
        if (r.success) {
            tvLockResult.setTextColor(0xFF2E7D32);
            tvLockResult.setText("✓ " + r.message
                    + (r.oldEpc != null ? ("\nEPC: " + r.oldEpc) : "")
                    + (r.tid != null ? ("\nTID: " + r.tid) : ""));
            if (chainWorkflow) {
                workflowRunning = false;
                chainWorkflow = false;
                activeStep = 0;
                step2Done = true;
                scanDoneAwaitingConfirm = true;
                updateStepIndicators();
                setActionStatusReady();
                showScanDoneNotification(epc.vyhybka, epc.cast);
            } else {
                step2Done = true;
                scanDoneAwaitingConfirm = true;
                updateStepIndicators();
                setActionStatusReady();
                showScanDoneNotification(epc.vyhybka, epc.cast);
            }
        } else {
            tvLockResult.setTextColor(0xFFC62828);
            tvLockResult.setText("✗ " + r.message);
            if (chainWorkflow) onWorkflowFailed("chyba zamčení");
            else setActionStatus("chyba zamčení", COLOR_STATUS_ERROR);
        }
    }

    private void saveRowToCsv(String epc24, String tid) {
        if (csvStore == null) return;
        try {
            EpcModel.Decoded d = EpcModel.decode(epc24);
            CsvStore.Row row = new CsvStore.Row();
            row.idRfid = d.idRfid;
            row.epc = d.epc;
            row.tid = tid == null ? "" : tid;
            row.rok = d.rok;
            row.tudu = d.tudu;
            row.vyhybka = d.vyhybka;
            row.cast = d.cast;
            csvStore.upsert(row);
            persistCsvAsync();
            refreshCsvTable();
        } catch (Exception e) {
            toast("CSV: " + e.getMessage());
        }
    }

    /** Po dokončení zápisu tagu (EPC samostatně, nebo celý řetězec EPC→heslo→lock). */
    private void onTagCycleComplete() {
        epc.idRfid += 1;
        prefs.edit().putLong("idRfid", epc.idRfid).apply();
        advanceCastAndVyhybka();
        refreshTemplate();
        updateSummary1();
        resetAccessPasswordFields();
    }

    /** Vrátí access hesla na výchozí hodnotu pro další tag (preset se zkusí automaticky). */
    private void resetAccessPasswordFields() {
        String def = UhfManager.DEFAULT_ACCESS_PASSWORD;
        etAccessPwd.setText(def);
        etPwdAccess.setText(def);
        etLockAccessPwd.setText(def);
    }

    private void advanceCastAndVyhybka() {
        syncCurrentVyhybka();
        if (currentVyhybka != null) {
            int next = epc.cast + 1;
            if (next > currentVyhybka.castMax) {
                advanceToNextVyhybka();
            } else {
                epc.cast = next;
            }
        } else {
            epc.cast += 1;
        }
    }

    private void syncCurrentVyhybka() {
        if (currentVyhybka != null || currentTudu == null || epc.vyhybka <= 0) return;
        int idx = findVyhybkaIndex(epc.vyhybka);
        if (idx >= 0) currentVyhybka = currentTudu.vyhybky.get(idx);
    }

    private int findVyhybkaIndex(int cislo) {
        if (currentTudu == null) return -1;
        for (int i = 0; i < currentTudu.vyhybky.size(); i++) {
            if (currentTudu.vyhybky.get(i).cislo == cislo) return i;
        }
        return -1;
    }

    private void advanceToNextVyhybka() {
        if (currentTudu == null || currentTudu.vyhybky.isEmpty()) return;
        syncCurrentVyhybka();
        int idx = currentVyhybka != null
                ? findVyhybkaIndex(currentVyhybka.cislo)
                : findVyhybkaIndex(epc.vyhybka);
        if (idx < 0) return;
        for (int i = idx + 1; i < currentTudu.vyhybky.size(); i++) {
            Tudu.Vyhybka next = currentTudu.vyhybky.get(i);
            if (!isVyhybkaCompleteInCsv(currentTudu.code, next)) {
                selectVyhybka(next, true);
                return;
            }
        }
        toast("Poslední výhybka v TUDU – cyklus dokončen.");
    }

    private Tudu.Vyhybka firstAvailableVyhybka(Tudu t) {
        for (Tudu.Vyhybka v : t.vyhybky) {
            if (!isVyhybkaCompleteInCsv(t.code, v)) return v;
        }
        return null;
    }

    private boolean isVyhybkaCompleteInCsv(String tuduCode, Tudu.Vyhybka v) {
        return countMissingCasts(tuduCode, v) == 0;
    }

    private int countMissingCasts(String tuduCode, Tudu.Vyhybka v) {
        Set<Integer> written = getWrittenCastsForVyhybka(tuduCode, v);
        int missing = 0;
        for (int c = v.castMin; c <= v.castMax; c++) {
            if (!written.contains(c)) missing++;
        }
        return missing;
    }

    private int countWrittenCasts(String tuduCode, Tudu.Vyhybka v) {
        Set<Integer> written = getWrittenCastsForVyhybka(tuduCode, v);
        int count = 0;
        for (int c = v.castMin; c <= v.castMax; c++) {
            if (written.contains(c)) count++;
        }
        return count;
    }

    private boolean isVyhybkaPartialInCsv(String tuduCode, Tudu.Vyhybka v) {
        int written = countWrittenCasts(tuduCode, v);
        return written > 0 && written < v.castMax - v.castMin + 1;
    }

    private CharSequence formatVyhybkaPickerLabel(String tuduCode, Tudu.Vyhybka v) {
        String prefix = getString(R.string.vyhybka_picker_prefix);
        String cisloStr = String.valueOf(v.cislo);
        if (!isVyhybkaPartialInCsv(tuduCode, v)) {
            SpannableString span = new SpannableString(prefix + cisloStr);
            applyVyhybkaAccent(span, prefix.length(), prefix.length() + cisloStr.length());
            return span;
        }

        int missing = countMissingCasts(tuduCode, v);
        String missingStr = String.valueOf(missing);
        String sep = getString(R.string.vyhybka_picker_missing_sep);
        String missingPrefix = getString(R.string.vyhybka_picker_missing_prefix);
        String missingSuffix = missingCastSuffix(missing);
        String full = prefix + cisloStr + sep + missingPrefix + missingStr + missingSuffix;

        SpannableString span = new SpannableString(full);
        int cisloStart = prefix.length();
        int cisloEnd = cisloStart + cisloStr.length();
        applyVyhybkaAccent(span, cisloStart, cisloEnd);
        int missingStart = cisloEnd + sep.length() + missingPrefix.length();
        int missingEnd = missingStart + missingStr.length();
        applyCastAccent(span, missingStart, missingEnd);
        return span;
    }

    private String missingCastSuffix(int count) {
        if (count == 1) return getString(R.string.vyhybka_picker_missing_one);
        if (count >= 2 && count <= 4) return getString(R.string.vyhybka_picker_missing_few);
        return getString(R.string.vyhybka_picker_missing_many);
    }

    private int firstMissingCast(String tuduCode, Tudu.Vyhybka v) {
        Set<Integer> written = getWrittenCastsForVyhybka(tuduCode, v);
        for (int c = v.castMin; c <= v.castMax; c++) {
            if (!written.contains(c)) return c;
        }
        return v.castMin;
    }

    private Set<Integer> getWrittenCastsForVyhybka(String tuduCode, Tudu.Vyhybka v) {
        if (csvStore == null) return Collections.emptySet();
        return csvStore.getWrittenCasts(tuduCode, v.cislo);
    }

    // ---------- export CSV ----------

    private void exportCsv() {
        if (csvStore == null) {
            toast("Tabulka se ještě načítá");
            return;
        }
        try {
            File f = csvStore.getFile();
            if (!f.exists() || csvStore.size() == 0) {
                toast("Tabulka je prázdná");
                return;
            }
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Sdílet CSV"));
        } catch (Exception e) {
            toast("Export selhal: " + e.getMessage());
        }
    }

    // ---------- čtečka ----------

    private void initReaderAsync() {
        if (!isPowerPresetSelected()) return;
        setActionStatus("inicializuji…", COLOR_STATUS_BUSY);
        final int power = powerPresetInKoleji ? POWER_PRESET_KOLEJI_DBM : POWER_PRESET_RUCE_DBM;
        io.execute(() -> {
            boolean ok = uhf.init(this);
            ui.post(() -> {
                if (ok) {
                    setActionStatusReady();
                    applyPowerValue(power, false);
                } else {
                    setActionStatus("nedostupná", COLOR_STATUS_ERROR);
                }
            });
        });
    }

    private void runTriggerAction() {
        if (workflowRunning || scanDoneAwaitingConfirm || deleteConfirmDialog.getVisibility() == View.VISIBLE) return;
        if (!requirePowerPreset()) return;
        if (!epc.isValid()) {
            toast("EPC není validní");
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            return;
        }
        final String newPwd = etPwdNew.getText().toString().trim();
        if (!newPwd.matches("[0-9A-Fa-f]{8}")) {
            toast("NEW PWD musí mít 8 hex znaků");
            return;
        }
        chainWorkflow = true;
        workflowRunning = true;
        activeStep = 2;
        step2Done = false;
        step2Failed = false;
        step3Done = false;
        updateStepIndicators();
        setActionStatus("zapisuji EPC…", COLOR_STATUS_BUSY);
        doWrite();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0) {
            for (int k : TRIGGER_KEYS) {
                if (k == keyCode) {
                    if (scanDoneAwaitingConfirm) {
                        onScanDoneContinue();
                    } else if (deleteConfirmDialog.getVisibility() != View.VISIBLE) {
                        runTriggerAction();
                    }
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.execute(uhf::free);
        io.shutdown();
    }

    // ---------- pomocné ----------

    private String queryName(Uri uri) {
        String name = "soubor";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) { }
        return name;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.replaceAll("[^0-9-]", "")); } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.replaceAll("[^0-9-]", "")); } catch (Exception e) { return def; }
    }

    static class SimpleWatcher implements TextWatcher {
        private final Runnable r;
        SimpleWatcher(Runnable r) { this.r = r; }
        public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        public void onTextChanged(CharSequence s, int a, int b, int c) { }
        public void afterTextChanged(Editable s) { r.run(); }
    }
}
