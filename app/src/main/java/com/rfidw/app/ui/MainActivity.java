package com.rfidw.app.ui;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import com.rfidw.app.R;
import com.rfidw.app.csv.CsvStore;
import com.rfidw.app.data.DzsDatabase;
import com.rfidw.app.data.Tudu;
import com.rfidw.app.epc.EpcModel;
import com.rfidw.app.location.LocationCache;
import com.rfidw.app.rfid.UhfManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    // klávesy spouště čtečky (Chainway C5 a příbuzné)
    private static final int[] TRIGGER_KEYS = {139, 280, 293, 311, 312, 522, 523, 0x3E8};

    private static final String TAG = "MainActivity";
    private static final int REQUEST_LOCATION_PERMISSION = 1001;
    private static final int REQUEST_STORAGE_PERMISSION = 1002;
    private static final String DEFAULT_DB_NAME = "DZS_PASPORT_TPI.sqlite";

    private static final int COLOR_STATUS_READY = 0xFF2E7D32;
    private static final int COLOR_STATUS_BUSY = 0xFF5F6A76;
    private static final int COLOR_STATUS_ERROR = 0xFFC62828;
    private static final int COLOR_STATUS_GPS_WAIT = 0xFFE65100;
    private static final int COLOR_STATUS_GPS_STALE = 0xFFF57C00;
    private static final int WORKFLOW_DONE_DELAY_MS = 1500;
    private static final int POWER_PRESET_KOLEJI_DBM = 16;
    private static final int POWER_PRESET_RUCE_DBM = 1;

    private final UhfManager uhf = new UhfManager();
    private final EpcModel epc = new EpcModel();
    private LocationCache locationCache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService gpsIo = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private List<Tudu> tuduList = new ArrayList<>();
    private Tudu currentTudu;
    private Tudu.Vyhybka currentVyhybka;
    private DzsDatabase dzsDatabase;
    /** Zruší zastaralé UI callbacky po novém načtení nebo zničení aktivity. */
    private volatile long dbLoadGeneration;
    private int card1DbProgressPercent;
    /** Režim výběru TUDU: true = GPS, false = ruční výběr ze seznamu. */
    private boolean gpsAutoSelection = true;
    /** Ruční výběr TUDU v GPS režimu – GPS lookup nepřepíše, dokud uživatel neklikne Načíst polohu. */
    private boolean gpsTuduLocked;
    /** Ruční výběr výhybky v GPS režimu – GPS lookup nepřepíše, dokud nejsou načteny všechny části. */
    private boolean gpsVyhybkaLocked;
    private volatile boolean gpsLookupInFlight;
    private boolean gpsLookupNoMatch;
    private boolean forceNextGpsLookup;
    private Double lastGpsLookupLat;
    private Double lastGpsLookupLon;
    private long lastGpsLookupTimeMs;
    private static final double GPS_LOOKUP_MIN_MOVE_M = 5.0;
    private static final long GPS_LOOKUP_MIN_INTERVAL_MS = 1000;
    private static final long GPS_LOOKUP_TIMEOUT_MS = 10_000;
    private static final int GPS_NEARBY_TUDU_LIMIT = 10;
    private static final String PREF_GPS_TEST_MODE = "gpsTestMode";
    private static final String PREF_TUDU_MODE_GPS = "tuduModeGps";
    private static final String PREF_TEST_LAT = "testLat";
    private static final String PREF_TEST_LON = "testLon";

    private CsvStore csvStore;
    private CsvAdapter csvAdapter;
    private SharedPreferences prefs;

    private boolean pendingAutoLoadAfterStorage;
    private boolean step1Done, step2Done, step3Done, step2Failed;
    private boolean workflowRunning, chainWorkflow, scanDoneAwaitingConfirm, lastRecordUnlocked;
    /** CSV obnoveno dřív než zdrojový soubor – posun na další čip/výhybku až po načtení TUDU. */
    private boolean pendingAdvanceFromCsv;
    /** Po obnově z CSV neobnovovat TUDU z posledního řádku – určit podle GPS. */
    private boolean skipCsvTuduRestore;
    private int activeStep;

    // view reference
    private TextView tvReaderStatus, tvGpsStatus, tvEpcPreview, tvEpcValid, tvSourceFile,
            tvCard1DbProgress,
            tvWriteResult, tvCsvPath, tvPwdWriteResult, tvLockResult,
            tvSummaryTudu, tvSummaryVyhybka, tvSummaryCast,
            tvCastHintAction, tvCastHintPart,
            tvScanDoneVyhybka, tvScanDoneCast,
            tvLastRecordVyhybka, tvLastRecordCast,
            step1Circle, step2Circle, step3Circle, step1Label, step2Label;
    private View summary1, colSummaryTudu, colSummaryVyhybka, castHintBox, scanDoneScrim,
            scanDoneDialog, deleteConfirmDialog, lastRecordBox, card1, topBar,
            card1DbProgress;
    private com.google.android.material.progressindicator.LinearProgressIndicator card1DbProgressBar;
    private NestedScrollView mainScroll;
    private BottomSheetBehavior<View> workflowBehavior;
    private EditText etAccessPwd, etPower, etPwdAccess, etPwdNew, etLockAccessPwd;
    private CheckBox cbAutoCsv, cbGpsTestMode;
    private View btnPickTestLocation;
    private TextView tvGpsTestModeHint;
    private boolean gpsTestMode;
    private MaterialButtonToggleGroup powerPresetGroup;
    private MaterialButtonToggleGroup tuduModeGroup;
    private MaterialButton btnGpsReloadLocation;
    private TextView tvTuduModeHint;
    private boolean tuduListFullyLoaded;
    private Boolean powerPresetInKoleji;
    private boolean showGpsStatus;
    private boolean gpsUnavailableToastShown;
    private int lastTopBarHeight = -1;
    private Runnable gpsLookupTimeoutRunnable;

    // řádky šablony (kontejnery z include)
    private View[] rows = new View[7];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("rfidgogps", MODE_PRIVATE);

        bindViews();
        setupLocation();
        setupTopBarInsets();
        setupWorkflowSheet();
        setupCollapsibles();
        collapseWorkflowCards();
        setupTemplateRows();
        setupCsv();
        setupListeners();
        setupTuduSelectionMode();
        setupGpsReloadLocation();
        setupGpsTestMode();
        tryAutoLoadDefaultDatabase();

        etPower.setText("");

        epc.idRfid = prefs.getLong("idRfid", -1);
        if (epc.idRfid < 0) {
            epc.idRfid = getSharedPreferences("rfidgo", MODE_PRIVATE).getLong("idRfid", 1);
        }
        refreshTemplate();
        updateSummary1();
        updatePowerPresetUi();
        updateStepIndicators();

        setActionStatusReady();
    }

    private void bindViews() {
        tvReaderStatus = findViewById(R.id.tvReaderStatus);
        tvGpsStatus = findViewById(R.id.tvGpsStatus);
        tvEpcPreview = findViewById(R.id.tvEpcPreview);
        tvEpcValid = findViewById(R.id.tvEpcValid);
        tvSourceFile = findViewById(R.id.tvSourceFile);
        tvCard1DbProgress = findViewById(R.id.tvCard1DbProgress);
        card1DbProgress = findViewById(R.id.card1DbProgress);
        card1DbProgressBar = findViewById(R.id.card1DbProgressBar);
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
        cbGpsTestMode = findViewById(R.id.cbGpsTestMode);
        btnPickTestLocation = findViewById(R.id.btnPickTestLocation);
        tvGpsTestModeHint = findViewById(R.id.tvGpsTestModeHint);
        powerPresetGroup = findViewById(R.id.powerPresetGroup);
        tuduModeGroup = findViewById(R.id.tuduModeGroup);
        btnGpsReloadLocation = findViewById(R.id.btnGpsReloadLocation);
        tvTuduModeHint = findViewById(R.id.tvTuduModeHint);

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
                    getString(R.string.status_ready),
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
        topBar.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int topInset = topBar.getHeight();
                if (topInset == lastTopBarHeight) return;
                lastTopBarHeight = topInset;
                applyMainScrollTopPadding(topInset);
            }
        });
    }

    private void applyMainScrollTopPadding(int topInset) {
        int gap = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, CARD1_TOP_GAP_DP, getResources().getDisplayMetrics());
        mainScroll.setPadding(
                mainScroll.getPaddingLeft(),
                topInset + gap,
                mainScroll.getPaddingRight(),
                mainScroll.getPaddingBottom());
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

    private void beginCard1DbLoad(String displayName) {
        collapseCard1Body();
        scrollToCard1();
        card1DbProgressPercent = 0;
        if (card1DbProgress != null) {
            card1DbProgress.setVisibility(View.VISIBLE);
        }
        updateCard1DbProgress(getString(R.string.db_loading_phase, displayName), 0, true);
    }

    private void updateCard1DbProgress(String phase, int percent) {
        updateCard1DbProgress(phase, percent, false);
    }

    private void updateCard1DbProgress(String phase, int percent, boolean force) {
        int clamped = Math.max(0, Math.min(100, percent));
        if (!force && clamped < card1DbProgressPercent) {
            clamped = card1DbProgressPercent;
        } else {
            card1DbProgressPercent = clamped;
        }
        String label = getString(R.string.db_loading_progress, phase, clamped);
        if (tvCard1DbProgress != null) {
            tvCard1DbProgress.setText(label);
        }
        if (card1DbProgressBar != null) {
            card1DbProgressBar.setProgressCompat(clamped, true);
        }
        if (tvSourceFile != null) {
            tvSourceFile.setText(label);
        }
    }

    private void endCard1DbLoad() {
        if (card1DbProgress != null) {
            card1DbProgress.setVisibility(View.GONE);
        }
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
        if (dzsDatabase == null) {
            toast(getString(R.string.db_select_required));
            expandCard1Body();
            return;
        }
        if (gpsAutoSelection) {
            showNearbyTuduPicker();
        } else {
            ensureFullTuduListLoaded(this::showFullTuduPicker);
        }
    }

    private void showNearbyTuduPicker() {
        if (locationCache == null || !locationCache.getSnapshot().valid) {
            toast(getString(R.string.tudu_picker_no_gps));
            return;
        }
        final double lat = locationCache.getSnapshot().latitude;
        final double lon = locationCache.getSnapshot().longitude;
        gpsIo.execute(() -> {
            List<DzsDatabase.GpsMatch> matches = Collections.emptyList();
            try {
                if (dzsDatabase != null) {
                    matches = dzsDatabase.findNearestDistinctTudu(lat, lon, GPS_NEARBY_TUDU_LIMIT);
                }
            } catch (Exception e) {
                Log.w(TAG, "Nearest TUDU lookup selhal", e);
            }
            final List<DzsDatabase.GpsMatch> result = matches;
            ui.post(() -> {
                if (result.isEmpty()) {
                    toast(getString(R.string.gps_tudu_not_found));
                    return;
                }
                showNearbyTuduPickerDialog(result);
            });
        });
    }

    private void showNearbyTuduPickerDialog(List<DzsDatabase.GpsMatch> matches) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_tudu_picker, null);
        EditText etSearch = dialogView.findViewById(R.id.etTuduSearch);
        ListView listView = dialogView.findViewById(R.id.lvTudu);
        etSearch.setVisibility(View.GONE);

        List<String> labels = new ArrayList<>(matches.size());
        for (DzsDatabase.GpsMatch m : matches) {
            labels.add(formatNearbyTuduLabel(m));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, labels);
        listView.setAdapter(adapter);

        String preselect = currentTudu != null ? currentTudu.code
                : (epc.tudu != null ? epc.tudu : "");
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).tudu.equals(preselect)) {
                listView.setItemChecked(i, true);
                break;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.tudu_picker_nearby_title))
                .setView(dialogView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            gpsTuduLocked = true;
            applyGpsMatch(matches.get(position));
            dialog.dismiss();
        });

        dialog.show();
    }

    private String formatNearbyTuduLabel(DzsDatabase.GpsMatch match) {
        return match.tudu + " · " + formatDistanceM(match.distanceM);
    }

    private void showFullTuduPicker() {
        if (tuduList.isEmpty()) {
            toast(getString(R.string.db_select_required));
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

        String preselect = currentTudu != null ? currentTudu.code
                : (epc.tudu != null ? epc.tudu : "");
        int checked = filteredCodes.indexOf(preselect);
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
                    skipCsvTuduRestore = false;
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
            String selected = currentTudu != null ? currentTudu.code
                    : (epc.tudu != null ? epc.tudu : "");
            int pos = filteredCodes.indexOf(selected);
            if (pos >= 0) listView.setItemChecked(pos, true);
        }));

        dialog.show();
        etSearch.requestFocus();
    }

    private void ensureFullTuduListLoaded(Runnable onReady) {
        if (tuduListFullyLoaded && !tuduList.isEmpty()) {
            onReady.run();
            return;
        }
        if (dzsDatabase == null) {
            toast(getString(R.string.db_select_required));
            expandCard1Body();
            return;
        }
        toast(getString(R.string.tudu_loading_list));
        io.execute(() -> {
            List<Tudu> loaded = Collections.emptyList();
            try {
                if (dzsDatabase != null) {
                    loaded = dzsDatabase.loadAllTudu();
                }
            } catch (Exception e) {
                Log.w(TAG, "Načtení seznamu TUDU selhalo", e);
            }
            final List<Tudu> result = loaded;
            ui.post(() -> {
                if (dzsDatabase == null) return;
                tuduList = result;
                tuduListFullyLoaded = true;
                if (result.isEmpty()) {
                    toast(getString(R.string.db_select_required));
                    expandCard1Body();
                    return;
                }
                onReady.run();
            });
        });
    }

    private void showVyhybkaPicker() {
        if (currentTudu == null) {
            toast(getString(R.string.db_select_required));
            expandCard1Body();
            return;
        }
        final String tuduCode = currentTudu.code;
        final long loadId = dbLoadGeneration;
        if (dzsDatabase == null) {
            if (currentTudu.vyhybky.isEmpty()) {
                toast(getString(R.string.db_select_required));
                expandCard1Body();
                return;
            }
            showVyhybkaPickerDialog(tuduCode, currentTudu.vyhybky, null);
            return;
        }

        final boolean withDistances = gpsAutoSelection
                && locationCache != null && locationCache.getSnapshot().valid;
        final double lat = withDistances ? locationCache.getSnapshot().latitude : 0;
        final double lon = withDistances ? locationCache.getSnapshot().longitude : 0;
        final DzsDatabase db = dzsDatabase;

        io.execute(() -> {
            List<Tudu.Vyhybka> loadedVyhybky = Collections.emptyList();
            Map<Integer, Double> distances = null;
            try {
                if (loadId == dbLoadGeneration && db != null) {
                    List<Tudu> loaded = db.loadTuduForCodes(Collections.singleton(tuduCode));
                    if (!loaded.isEmpty()) {
                        loadedVyhybky = new ArrayList<>(loaded.get(0).vyhybky);
                    }
                    if (withDistances && !loadedVyhybky.isEmpty()) {
                        distances = db.findVyhybkaDistancesForTudu(tuduCode, lat, lon);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Načtení výhybek pro picker selhalo", e);
            }
            final List<Tudu.Vyhybka> vyhybkySnapshot = sortVyhybkyForPicker(loadedVyhybky, distances);
            final Map<Integer, Double> distancesSnapshot = distances;
            runOnUiThreadIfAlive(loadId, () -> {
                if (!vyhybkySnapshot.isEmpty()) {
                    mergeLoadedTuduVyhybky(tuduCode, vyhybkySnapshot);
                    syncCurrentVyhybkaAfterReload();
                }
                List<Tudu.Vyhybka> pickerVyhybky = !vyhybkySnapshot.isEmpty()
                        ? vyhybkySnapshot
                        : (currentTudu != null
                                ? new ArrayList<>(currentTudu.vyhybky) : Collections.emptyList());
                if (pickerVyhybky.isEmpty()) {
                    toast(getString(R.string.db_select_required));
                    expandCard1Body();
                    return;
                }
                showVyhybkaPickerDialog(tuduCode, pickerVyhybky, distancesSnapshot);
            });
        });
    }

    private void showVyhybkaPickerDialog(String tuduCode, List<Tudu.Vyhybka> vyhybkySource,
                                         Map<Integer, Double> distancesM) {
        final List<Tudu.Vyhybka> allVyhybky = new ArrayList<>(vyhybkySource);
        final List<Tudu.Vyhybka> filteredVyhybky = new ArrayList<>(allVyhybky);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_tudu_picker, null);
        EditText etSearch = dialogView.findViewById(R.id.etTuduSearch);
        etSearch.setHint(R.string.vyhybka_search_hint);
        ListView listView = dialogView.findViewById(R.id.lvTudu);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ArrayAdapter<Tudu.Vyhybka> adapter = new ArrayAdapter<Tudu.Vyhybka>(this,
                android.R.layout.simple_list_item_single_choice, filteredVyhybky) {
            @Override
            public boolean isEnabled(int position) {
                return !isVyhybkaCompleteInCsv(tuduCode, filteredVyhybky.get(position));
            }

            @Override
            public android.view.View getView(int position, android.view.View convertView,
                    android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                TextView tv = (TextView) view;
                Tudu.Vyhybka v = filteredVyhybky.get(position);
                boolean done = isVyhybkaCompleteInCsv(tuduCode, v);
                Double dist = distancesM != null ? distancesM.get(v.cislo) : null;
                tv.setText(formatVyhybkaPickerLabel(tuduCode, v, dist));
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

        Runnable refreshChecked = () -> {
            int checked = findVyhybkaPickerCheckedIndex(filteredVyhybky);
            if (checked >= 0) {
                listView.setItemChecked(checked, true);
            }
        };
        refreshChecked.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vyberte výhybku")
                .setView(dialogView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (isVyhybkaCompleteInCsv(tuduCode, filteredVyhybky.get(position))) {
                toast("výhybka je již zapsaná v CSV");
                return;
            }
            if (gpsAutoSelection) {
                gpsVyhybkaLocked = true;
            }
            selectVyhybka(filteredVyhybky.get(position), true);
            dialog.dismiss();
        });

        etSearch.addTextChangedListener(new SimpleWatcher(() -> {
            String q = etSearch.getText().toString().trim();
            filteredVyhybky.clear();
            if (q.isEmpty()) {
                filteredVyhybky.addAll(allVyhybky);
            } else {
                for (Tudu.Vyhybka vyhybka : allVyhybky) {
                    if (String.valueOf(vyhybka.cislo).contains(q)) {
                        filteredVyhybky.add(vyhybka);
                    }
                }
            }
            adapter.notifyDataSetChanged();
            refreshChecked.run();
        }));

        dialog.show();
        etSearch.requestFocus();
    }

    private void setupGpsReloadLocation() {
        if (btnGpsReloadLocation == null) return;
        btnGpsReloadLocation.setOnClickListener(v -> onGpsReloadLocation());
        updateGpsReloadButtonVisibility();
    }

    private void updateGpsReloadButtonVisibility() {
        if (btnGpsReloadLocation == null) return;
        btnGpsReloadLocation.setVisibility(gpsAutoSelection ? View.VISIBLE : View.GONE);
    }

    private void onGpsReloadLocation() {
        if (!gpsAutoSelection || dzsDatabase == null) return;
        if (locationCache == null || !locationCache.getSnapshot().valid) {
            toast(getString(R.string.tudu_picker_no_gps));
            return;
        }
        gpsTuduLocked = false;
        gpsVyhybkaLocked = false;
        gpsLookupNoMatch = false;
        forceNextGpsLookup = true;
        lastGpsLookupLat = null;
        lastGpsLookupLon = null;
        lastGpsLookupTimeMs = 0;
        scheduleGpsTuduLookup();
    }

    private List<Tudu.Vyhybka> sortVyhybkyForPicker(List<Tudu.Vyhybka> source,
                                                      Map<Integer, Double> distancesM) {
        if (distancesM == null || distancesM.isEmpty()) {
            return source;
        }
        List<Tudu.Vyhybka> sorted = new ArrayList<>(source);
        sorted.sort((a, b) -> {
            Double da = distancesM.get(a.cislo);
            Double db = distancesM.get(b.cislo);
            if (da == null && db == null) return Integer.compare(a.cislo, b.cislo);
            if (da == null) return 1;
            if (db == null) return -1;
            int cmp = Double.compare(da, db);
            return cmp != 0 ? cmp : Integer.compare(a.cislo, b.cislo);
        });
        return sorted;
    }

    private String formatDistanceM(double distanceM) {
        if (distanceM < 1000) {
            return String.format(Locale.ROOT, "%.0f m", distanceM);
        }
        return String.format(Locale.ROOT, "%.1f km", distanceM / 1000.0);
    }

    private void maybeClearGpsVyhybkaLock() {
        if (!gpsVyhybkaLocked || !gpsAutoSelection) return;
        if (currentTudu == null || currentVyhybka == null) return;
        if (isVyhybkaCompleteInCsv(currentTudu.code, currentVyhybka)) {
            gpsVyhybkaLocked = false;
        }
    }

    private void ensureTuduDetailsLoaded(String tuduCode, Runnable onReady) {
        if (dzsDatabase == null || tuduCode == null || tuduCode.isEmpty()) {
            onReady.run();
            return;
        }
        io.execute(() -> {
            List<Tudu.Vyhybka> loadedVyhybky = Collections.emptyList();
            try {
                List<Tudu> loaded = dzsDatabase.loadTuduForCodes(Collections.singleton(tuduCode));
                if (!loaded.isEmpty()) {
                    loadedVyhybky = new ArrayList<>(loaded.get(0).vyhybky);
                }
            } catch (Exception e) {
                Log.w(TAG, "Načtení TUDU " + tuduCode + " selhalo", e);
            }
            final List<Tudu.Vyhybka> result = loadedVyhybky;
            ui.post(() -> {
                if (!result.isEmpty()) {
                    mergeLoadedTuduVyhybky(tuduCode, result);
                    syncCurrentVyhybkaAfterReload();
                }
                onReady.run();
            });
        });
    }

    private void mergeLoadedTuduVyhybky(String tuduCode, List<Tudu.Vyhybka> loadedVyhybky) {
        if (tuduCode == null || tuduCode.isEmpty() || loadedVyhybky == null || loadedVyhybky.isEmpty()) {
            return;
        }
        Tudu existing = null;
        for (Tudu t : tuduList) {
            if (t.code.equals(tuduCode)) {
                existing = t;
                break;
            }
        }
        if (existing != null) {
            existing.vyhybky.clear();
            existing.vyhybky.addAll(loadedVyhybky);
        } else {
            Tudu loadedTudu = new Tudu(tuduCode);
            loadedTudu.vyhybky.addAll(loadedVyhybky);
            tuduList.add(loadedTudu);
            existing = loadedTudu;
        }
        if (currentTudu != null && currentTudu.code.equals(tuduCode)) {
            currentTudu = existing;
        }
    }

    private void syncCurrentVyhybkaAfterReload() {
        if (currentTudu == null) return;
        int cislo = currentVyhybka != null ? currentVyhybka.cislo : epc.vyhybka;
        if (cislo <= 0) return;
        currentVyhybka = null;
        for (Tudu.Vyhybka v : currentTudu.vyhybky) {
            if (v.cislo == cislo) {
                currentVyhybka = v;
                break;
            }
        }
    }

    private int findVyhybkaPickerCheckedIndex(List<Tudu.Vyhybka> vyhybky) {
        int cislo = currentVyhybka != null ? currentVyhybka.cislo : epc.vyhybka;
        if (cislo > 0) {
            for (int i = 0; i < vyhybky.size(); i++) {
                if (vyhybky.get(i).cislo == cislo) return i;
            }
        }
        return 0;
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
        updatePowerPresetUi();
        updateStepIndicators();
        if (!workflowRunning) {
            setActionStatusReady();
        }
    }

    private void updatePowerPresetUi() {
        boolean enabled = step1Done && !gpsLookupInFlight;
        powerPresetGroup.setEnabled(enabled);
        for (int i = 0; i < powerPresetGroup.getChildCount(); i++) {
            powerPresetGroup.getChildAt(i).setEnabled(enabled);
        }
        if (!step1Done) {
            powerPresetInKoleji = null;
            powerPresetGroup.clearChecked();
            powerPresetGroup.setSelectionRequired(false);
        } else if (powerPresetInKoleji != null) {
            int checkedId = powerPresetInKoleji
                    ? R.id.btnPowerPresetKoleji
                    : R.id.btnPowerPresetRuce;
            if (powerPresetGroup.getCheckedButtonId() != checkedId) {
                powerPresetGroup.check(checkedId);
            }
            powerPresetGroup.setSelectionRequired(true);
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
        showGpsStatus = false;
        tvGpsStatus.setVisibility(View.GONE);
        tvReaderStatus.setText(text);
        tvReaderStatus.setTextColor(color);
    }

    private void setActionStatusReady() {
        if (!step1Done) {
            if (gpsAutoSelection) {
                showGpsStatus = true;
                if (gpsTestMode && locationCache != null && locationCache.hasTestOverride()) {
                    tvReaderStatus.setText(getString(R.string.gps_test_status));
                    tvReaderStatus.setTextColor(COLOR_STATUS_READY);
                } else if (locationCache != null && locationCache.hasFix()) {
                    if (gpsLookupNoMatch) {
                        tvReaderStatus.setText(getString(R.string.gps_tudu_not_found));
                        tvReaderStatus.setTextColor(COLOR_STATUS_ERROR);
                    } else if (gpsLookupInFlight) {
                        tvReaderStatus.setText(getString(R.string.gps_tudu_lookup));
                        tvReaderStatus.setTextColor(COLOR_STATUS_GPS_WAIT);
                    } else if (dzsDatabase == null) {
                        tvReaderStatus.setText(getString(R.string.gps_tudu_wait));
                        tvReaderStatus.setTextColor(COLOR_STATUS_GPS_WAIT);
                    } else {
                        tvReaderStatus.setText(getString(R.string.gps_tudu_lookup));
                        tvReaderStatus.setTextColor(COLOR_STATUS_GPS_WAIT);
                    }
                } else {
                    tvReaderStatus.setText(getString(R.string.gps_tudu_wait));
                    tvReaderStatus.setTextColor(COLOR_STATUS_GPS_WAIT);
                }
                refreshGpsStatus(false);
            } else {
                setActionStatus(getString(R.string.tudu_select_status), COLOR_STATUS_ERROR);
            }
            return;
        }
        if (!isPowerPresetSelected()) {
            setActionStatus(getString(R.string.power_preset_select_status), COLOR_STATUS_ERROR);
            if (gpsAutoSelection) {
                showGpsStatus = true;
                refreshGpsStatus(false);
            }
            updateStepIndicators();
            return;
        }
        showGpsStatus = true;
        tvReaderStatus.setText(getString(R.string.status_ready));
        refreshGpsStatus();
    }

    private void refreshGpsStatus() {
        refreshGpsStatus(true);
    }

    private void refreshGpsStatus(boolean setReadyStatus) {
        if (!showGpsStatus || locationCache == null) return;
        tvGpsStatus.setVisibility(View.VISIBLE);
        int color;
        if (!locationCache.hasFix()) {
            color = COLOR_STATUS_GPS_WAIT;
        } else if (locationCache.isStale()) {
            color = COLOR_STATUS_GPS_STALE;
        } else {
            color = COLOR_STATUS_READY;
        }
        tvGpsStatus.setText(locationCache.formatStatusText());
        tvGpsStatus.setTextColor(color);
        if (setReadyStatus) {
            tvReaderStatus.setText(getString(R.string.status_ready));
            tvReaderStatus.setTextColor(COLOR_STATUS_READY);
        }
    }

    private void setupLocation() {
        locationCache = new LocationCache(this);
        locationCache.setListener(() -> {
            scheduleGpsTuduLookup();
            if (!workflowRunning) {
                setActionStatusReady();
            } else if (showGpsStatus) {
                refreshGpsStatus();
            }
        });
        ensureLocationPermission();
    }

    private void ensureGpsForTuduLookup() {
        ensureLocationPermission();
        if (locationCache != null) {
            locationCache.start(this);
            if (gpsTestMode) {
                restoreTestLocationFromPrefs();
            } else {
                locationCache.refresh(this);
            }
            gpsLookupNoMatch = false;
            scheduleGpsTuduLookup();
            if (!workflowRunning) {
                setActionStatusReady();
            }
        }
    }

    private void ensureLocationPermission() {
        if (locationCache == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationCache.start(this);
            return;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                REQUEST_LOCATION_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                locationCache.start(this);
                ensureGpsForTuduLookup();
            } else if (!workflowRunning) {
                setActionStatusReady();
            }
            return;
        }
        if (requestCode != REQUEST_STORAGE_PERMISSION) return;
        if (!pendingAutoLoadAfterStorage) return;
        pendingAutoLoadAfterStorage = false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            tryAutoLoadDefaultDatabase();
        } else {
            endCard1DbLoad();
            expandCard1Body();
            tvSourceFile.setText(R.string.db_none);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureGpsForTuduLookup();
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
        File out = new File(getExternalFilesDir(null), "rfid_go_gps_output.csv");
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
        skipCsvTuduRestore = true;
        updateLastRecordPreview();
        if (dzsDatabase != null && gpsAutoSelection) {
            ensureGpsForTuduLookup();
        }
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

    private void setupGpsTestMode() {
        gpsTestMode = prefs.getBoolean(PREF_GPS_TEST_MODE, false);
        cbGpsTestMode.setChecked(gpsTestMode);
        updateGpsTestModeUi();
        cbGpsTestMode.setOnCheckedChangeListener((buttonView, isChecked) -> onGpsTestModeChanged(isChecked));
        btnPickTestLocation.setOnClickListener(v -> showTestLocationPicker());
        if (gpsTestMode) {
            restoreTestLocationFromPrefs();
        }
    }

    private void setupTuduSelectionMode() {
        boolean gpsMode = prefs.getBoolean(PREF_TUDU_MODE_GPS, true);
        gpsAutoSelection = gpsMode;
        tuduModeGroup.check(gpsMode ? R.id.btnTuduModeGps : R.id.btnTuduModeManual);
        updateTuduModeUi();
        tuduModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            onTuduModeChanged(checkedId == R.id.btnTuduModeGps);
        });
    }

    private void updateTuduModeUi() {
        if (tvTuduModeHint == null) return;
        tvTuduModeHint.setText(gpsAutoSelection
                ? getString(R.string.tudu_mode_gps_hint)
                : getString(R.string.tudu_mode_manual_hint));
        updateGpsReloadButtonVisibility();
    }

    private void onTuduModeChanged(boolean gpsMode) {
        if (gpsAutoSelection == gpsMode) return;
        gpsAutoSelection = gpsMode;
        prefs.edit().putBoolean(PREF_TUDU_MODE_GPS, gpsMode).apply();
        if (!gpsMode) {
            gpsTuduLocked = false;
            gpsVyhybkaLocked = false;
        }
        updateTuduModeUi();
        if (gpsMode) {
            gpsLookupNoMatch = false;
            forceNextGpsLookup = true;
            lastGpsLookupLat = null;
            lastGpsLookupLon = null;
            lastGpsLookupTimeMs = 0;
            if (dzsDatabase != null) {
                ensureGpsForTuduLookup();
            }
        } else if (dzsDatabase != null) {
            ensureFullTuduListLoaded(() -> {
                if (!tuduListFullyLoaded) return;
                updateSourceFileLabel();
            });
        }
        if (!workflowRunning) {
            setActionStatusReady();
        }
    }

    private void updateSourceFileLabel() {
        if (tvSourceFile == null || dzsDatabase == null) return;
        CharSequence current = tvSourceFile.getText();
        if (current == null) return;
        String text = current.toString();
        int sep = text.indexOf("  •  TUDU:");
        if (sep < 0) return;
        String displayName = text.substring(0, sep);
        int count = tuduListFullyLoaded ? tuduList.size() : dzsDatabase.countDistinctTudu();
        tvSourceFile.setText(gpsAutoSelection
                ? getString(R.string.db_loaded_gps, displayName, count)
                : getString(R.string.db_loaded_manual, displayName, count));
    }

    private void updateGpsTestModeUi() {
        int visibility = gpsTestMode ? View.VISIBLE : View.GONE;
        tvGpsTestModeHint.setVisibility(visibility);
        btnPickTestLocation.setVisibility(visibility);
    }

    private void onGpsTestModeChanged(boolean enabled) {
        gpsTestMode = enabled;
        prefs.edit().putBoolean(PREF_GPS_TEST_MODE, enabled).apply();
        updateGpsTestModeUi();
        if (locationCache == null) return;
        if (enabled) {
            if (!gpsAutoSelection) {
                tuduModeGroup.check(R.id.btnTuduModeGps);
                onTuduModeChanged(true);
            }
            gpsLookupNoMatch = false;
            lastGpsLookupLat = null;
            lastGpsLookupLon = null;
            lastGpsLookupTimeMs = 0;
            toast(getString(R.string.gps_test_enabled_toast));
            if (restoreTestLocationFromPrefs()) {
                scheduleGpsTuduLookup();
            }
        } else {
            clearSavedTestLocation();
            locationCache.clearTestOverride();
            locationCache.refresh(this);
            gpsLookupNoMatch = false;
            lastGpsLookupLat = null;
            lastGpsLookupLon = null;
            lastGpsLookupTimeMs = 0;
            scheduleGpsTuduLookup();
        }
        if (!workflowRunning) {
            setActionStatusReady();
        }
    }

    private void saveTestLocationToPrefs(double lat, double lon) {
        prefs.edit()
                .putLong(PREF_TEST_LAT, Double.doubleToRawLongBits(lat))
                .putLong(PREF_TEST_LON, Double.doubleToRawLongBits(lon))
                .apply();
    }

    private void clearSavedTestLocation() {
        prefs.edit()
                .remove(PREF_TEST_LAT)
                .remove(PREF_TEST_LON)
                .apply();
    }

    private boolean restoreTestLocationFromPrefs() {
        if (!gpsTestMode || locationCache == null) return false;
        if (!prefs.contains(PREF_TEST_LAT) || !prefs.contains(PREF_TEST_LON)) return false;
        double lat = Double.longBitsToDouble(prefs.getLong(PREF_TEST_LAT, 0));
        double lon = Double.longBitsToDouble(prefs.getLong(PREF_TEST_LON, 0));
        locationCache.setTestOverride(lat, lon);
        return true;
    }

    private void showTestLocationPicker() {
        if (dzsDatabase == null) {
            toast(getString(R.string.db_select_required));
            expandCard1Body();
            return;
        }
        io.execute(() -> {
            List<DzsDatabase.GpsPoint> points = dzsDatabase.listGpsPoints();
            ui.post(() -> {
                if (points.isEmpty()) {
                    toast(getString(R.string.gps_test_no_gps_point));
                    return;
                }
                showTestLocationPickerDialog(points);
            });
        });
    }

    private void showTestLocationPickerDialog(List<DzsDatabase.GpsPoint> points) {
        ListView listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        ArrayAdapter<DzsDatabase.GpsPoint> adapter = new ArrayAdapter<DzsDatabase.GpsPoint>(this,
                android.R.layout.simple_list_item_single_choice, points) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                DzsDatabase.GpsPoint point = points.get(position);
                String coords = String.format(Locale.getDefault(), "%.4f° %.4f°", point.latitude, point.longitude);
                if (point.label.isEmpty()) {
                    tv.setText(coords);
                } else {
                    tv.setText(coords + " → " + point.label);
                }
                return tv;
            }
        };
        listView.setAdapter(adapter);

        double savedLat = prefs.contains(PREF_TEST_LAT)
                ? Double.longBitsToDouble(prefs.getLong(PREF_TEST_LAT, 0)) : Double.NaN;
        double savedLon = prefs.contains(PREF_TEST_LON)
                ? Double.longBitsToDouble(prefs.getLong(PREF_TEST_LON, 0)) : Double.NaN;
        for (int i = 0; i < points.size(); i++) {
            DzsDatabase.GpsPoint point = points.get(i);
            if (Math.abs(point.latitude - savedLat) < 1e-6
                    && Math.abs(point.longitude - savedLon) < 1e-6) {
                listView.setItemChecked(i, true);
                break;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.gps_test_pick_title)
                .setView(listView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            DzsDatabase.GpsPoint point = points.get(position);
            applyTestCoordinates(point.latitude, point.longitude);
            dialog.dismiss();
        });
        dialog.show();
    }

    private void applyTestCoordinates(double lat, double lon) {
        if (!gpsAutoSelection) {
            tuduModeGroup.check(R.id.btnTuduModeGps);
            onTuduModeChanged(true);
        }
        gpsTestMode = true;
        cbGpsTestMode.setChecked(true);
        updateGpsTestModeUi();
        prefs.edit().putBoolean(PREF_GPS_TEST_MODE, true).apply();
        saveTestLocationToPrefs(lat, lon);
        if (locationCache != null) {
            locationCache.setTestOverride(lat, lon);
        }
        lastGpsLookupLat = null;
        lastGpsLookupLon = null;
        lastGpsLookupTimeMs = 0;
        gpsLookupNoMatch = false;
        scheduleGpsTuduLookup();
        if (!workflowRunning) {
            setActionStatusReady();
        }
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

    private void tryAutoLoadDefaultDatabase() {
        beginCard1DbLoad(DEFAULT_DB_NAME);
        io.execute(() -> {
            File found = findDefaultDatabaseFile();
            if (found != null) {
                loadDatabaseFromPath(found.getAbsolutePath(), DEFAULT_DB_NAME, false);
                return;
            }
            if (needsStoragePermission()) {
                pendingAutoLoadAfterStorage = true;
                ui.post(() -> {
                    endCard1DbLoad();
                    expandCard1Body();
                    requestStoragePermissionIfNeeded();
                });
                return;
            }
            ui.post(() -> {
                endCard1DbLoad();
                expandCard1Body();
                tvSourceFile.setText(R.string.db_none);
            });
        });
    }

    private boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermissionIfNeeded() {
        if (!needsStoragePermission()) return;
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                REQUEST_STORAGE_PERMISSION);
    }

    private File findDefaultDatabaseFile() {
        List<File> dirs = new ArrayList<>();
        File ext = getExternalFilesDir(null);
        if (ext != null) dirs.add(ext);
        File appDownloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (appDownloads != null) dirs.add(appDownloads);
        dirs.add(getFilesDir());
        if (canReadSharedStorage()) {
            File pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (pubDownloads != null) dirs.add(pubDownloads);
            File root = Environment.getExternalStorageDirectory();
            if (root != null) dirs.add(root);
            dirs.add(new File("/storage/emulated/0"));
            dirs.add(new File("/storage/emulated/0/Download"));
        }
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File candidate = new File(dir, DEFAULT_DB_NAME);
            if (candidate.isFile() && candidate.canRead()) return candidate;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File fromMediaStore = copyDefaultDatabaseFromMediaStore();
            if (fromMediaStore != null) return fromMediaStore;
        }
        return null;
    }

    /** Android 10+ – hledání v Stažených souborech přes MediaStore (funguje i bez READ_EXTERNAL_STORAGE). */
    private File copyDefaultDatabaseFromMediaStore() {
        Uri uri = findDefaultDatabaseUriViaMediaStore();
        if (uri == null) return null;
        try {
            return copyUriToCache(uri, "dzs_auto_source.db");
        } catch (Exception ignored) {
            return null;
        }
    }

    private Uri findDefaultDatabaseUriViaMediaStore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = { MediaStore.Downloads._ID };
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=?";
        String[] args = { DEFAULT_DB_NAME };
        try (Cursor c = getContentResolver().query(collection, projection, selection, args, null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return ContentUris.withAppendedId(collection, id);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean canReadSharedStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        if (Build.VERSION.SDK_INT >= 33) return false;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void loadSource(Uri uri) {
        String name = queryName(uri);
        final String fileTypeError = getString(R.string.db_file_type_error);
        beginCard1DbLoad(name);
        io.execute(() -> {
            try {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".db") && !lower.endsWith(".sqlite") && !lower.endsWith(".sqlite3")) {
                    throw new Exception(fileTypeError);
                }
                File dbFile = copyUriToCache(uri, "dzs_source.db");
                loadDatabaseFromPath(dbFile.getAbsolutePath(), name, true);
            } catch (Exception e) {
                runOnUiThreadIfAlive(0, () -> {
                    endCard1DbLoad();
                    expandCard1Body();
                    tvSourceFile.setText("Chyba načtení: " + e.getMessage());
                    toast("Chyba načtení databáze");
                });
            }
        });
    }

    private void loadDatabaseFromPath(String path, String displayName, boolean showErrorToast) {
        final long loadId = ++dbLoadGeneration;
        runOnUiThreadIfAlive(loadId, () -> beginCard1DbLoad(displayName));
        DzsDatabase previous;
        synchronized (this) {
            previous = dzsDatabase;
            dzsDatabase = null;
        }
        if (previous != null) {
            try {
                previous.close();
            } catch (Exception ignored) {
            }
        }
        try {
            final AtomicInteger progressSeq = new AtomicInteger(0);
            final AtomicInteger appliedProgressSeq = new AtomicInteger(0);
            DzsDatabase.OpenProgressListener progress = (phase, percent) -> {
                int seq = progressSeq.incrementAndGet();
                runOnUiThreadIfAlive(loadId, () -> {
                    if (seq < appliedProgressSeq.get()) return;
                    appliedProgressSeq.set(seq);
                    updateCard1DbProgress(phase, percent);
                });
            };
            DzsDatabase opened = DzsDatabase.open(path, getDzsStorageDir(), progress);
            if (loadId != dbLoadGeneration) {
                opened.close();
                return;
            }
            int tuduCount = opened.countDistinctTudu();
            boolean manualMode = !prefs.getBoolean(PREF_TUDU_MODE_GPS, true);
            List<Tudu> loaded;
            if (manualMode) {
                runOnUiThreadIfAlive(loadId, () ->
                        updateCard1DbProgress("Načítám seznam TUDU", 98));
                loaded = opened.loadAllTudu();
            } else {
                loaded = new ArrayList<>();
            }
            if (loadId != dbLoadGeneration) {
                opened.close();
                return;
            }
            runOnUiThreadIfAlive(loadId, () -> {
                dzsDatabase = opened;
                gpsAutoSelection = !manualMode;
                tuduListFullyLoaded = manualMode;
                gpsLookupNoMatch = false;
                forceNextGpsLookup = true;
                lastGpsLookupLat = null;
                lastGpsLookupLon = null;
                lastGpsLookupTimeMs = 0;
                powerPresetInKoleji = null;
                powerPresetGroup.clearChecked();
                powerPresetGroup.setSelectionRequired(false);
                updatePowerPresetUi();
                tuduList = loaded;
                tuduModeGroup.check(gpsAutoSelection ? R.id.btnTuduModeGps : R.id.btnTuduModeManual);
                updateTuduModeUi();
                tvSourceFile.setText(gpsAutoSelection
                        ? getString(R.string.db_loaded_gps, displayName, tuduCount)
                        : getString(R.string.db_loaded_manual, displayName, tuduCount));
                endCard1DbLoad();
                collapseCard1Body();
                scrollToCard1();
                onDatabaseLoaded();
            });
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Načtení databáze – nedostatek paměti", e);
            runOnUiThreadIfAlive(loadId, () -> {
                endCard1DbLoad();
                expandCard1Body();
                tvSourceFile.setText("Chyba načtení: nedostatek paměti při indexaci databáze");
                if (showErrorToast) toast("Chyba načtení databáze – nedostatek paměti");
            });
        } catch (Exception e) {
            Log.w(TAG, "Načtení databáze selhalo", e);
            runOnUiThreadIfAlive(loadId, () -> {
                endCard1DbLoad();
                expandCard1Body();
                tvSourceFile.setText("Chyba načtení: " + e.getMessage());
                if (showErrorToast) toast("Chyba načtení databáze");
            });
        }
    }

    private void runOnUiThreadIfAlive(long loadId, Runnable action) {
        if (isFinishing() || isDestroyed()) return;
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (loadId != 0 && loadId != dbLoadGeneration) return;
            action.run();
        });
    }

    private void onDatabaseLoaded() {
        if (skipCsvTuduRestore) {
            skipCsvTuduRestore = false;
        }
        if (!gpsAutoSelection) {
            if (epc.tudu != null && !epc.tudu.isEmpty()) {
                for (Tudu t : tuduList) {
                    if (t.code.equals(epc.tudu)) {
                        selectTuduPreservingEpc(t);
                        return;
                    }
                }
            }
            ensureGpsForTuduLookup();
            return;
        }
        ensureGpsForTuduLookup();
        if (dzsDatabase != null && dzsDatabase.countDistinctTudu() == 0) {
            toast("Databáze neobsahuje žádné TUDU – zkouším určit podle GPS…");
        } else if (!step1Done && locationCache != null && !locationCache.hasFix()) {
            if (gpsTestMode) {
                if (!locationCache.hasTestOverride()) {
                    toast(getString(R.string.gps_test_enabled_toast));
                }
            } else {
                toast(getString(R.string.gps_tudu_wait));
            }
        }
    }

    private void scheduleGpsTuduLookup() {
        if (!gpsAutoSelection || dzsDatabase == null || locationCache == null || gpsLookupInFlight) {
            return;
        }
        if (gpsTuduLocked || gpsVyhybkaLocked) {
            return;
        }
        if (workflowRunning || scanDoneAwaitingConfirm) {
            return;
        }
        LocationCache.Snapshot snap = locationCache.getSnapshot();
        if (!snap.valid) return;
        long now = System.currentTimeMillis();
        boolean force = forceNextGpsLookup;
        if (!force && lastGpsLookupLat != null && lastGpsLookupLon != null) {
            double moved = haversineM(lastGpsLookupLat, lastGpsLookupLon, snap.latitude, snap.longitude);
            if (moved < GPS_LOOKUP_MIN_MOVE_M && now - lastGpsLookupTimeMs < GPS_LOOKUP_MIN_INTERVAL_MS) {
                return;
            }
        }
        forceNextGpsLookup = false;
        gpsLookupInFlight = true;
        gpsLookupNoMatch = false;
        updatePowerPresetUi();
        if (!workflowRunning) {
            setActionStatusReady();
        }
        final double lat = snap.latitude;
        final double lon = snap.longitude;
        cancelGpsLookupTimeout();
        gpsLookupTimeoutRunnable = () -> {
            if (!gpsLookupInFlight) return;
            gpsLookupInFlight = false;
            gpsLookupNoMatch = true;
            updatePowerPresetUi();
            Log.w(TAG, "GPS TUDU lookup timeout po " + GPS_LOOKUP_TIMEOUT_MS + " ms");
            if (!workflowRunning) {
                setActionStatusReady();
            }
        };
        ui.postDelayed(gpsLookupTimeoutRunnable, GPS_LOOKUP_TIMEOUT_MS);
        gpsIo.execute(() -> {
            DzsDatabase.GpsMatch match = null;
            try {
                if (dzsDatabase != null) {
                    match = dzsDatabase.findNearest(lat, lon);
                }
            } catch (Exception e) {
                Log.w(TAG, "GPS TUDU lookup selhal", e);
                match = null;
            }
            final DzsDatabase.GpsMatch result = match;
            final long loadId = dbLoadGeneration;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                cancelGpsLookupTimeout();
                gpsLookupInFlight = false;
                updatePowerPresetUi();
                if (loadId != dbLoadGeneration || !gpsAutoSelection || dzsDatabase == null) return;
                if (result == null) {
                    gpsLookupNoMatch = locationCache != null && locationCache.hasFix();
                    if (!workflowRunning) {
                        setActionStatusReady();
                    }
                    return;
                }
                gpsLookupNoMatch = false;
                lastGpsLookupLat = lat;
                lastGpsLookupLon = lon;
                lastGpsLookupTimeMs = System.currentTimeMillis();
                applyGpsMatch(result);
                if (!workflowRunning) {
                    setActionStatusReady();
                }
            });
        });
    }

    private void cancelGpsLookupTimeout() {
        if (gpsLookupTimeoutRunnable != null) {
            ui.removeCallbacks(gpsLookupTimeoutRunnable);
            gpsLookupTimeoutRunnable = null;
        }
    }

    private void applyGpsMatch(DzsDatabase.GpsMatch match) {
        pendingAdvanceFromCsv = false;
        boolean tuduChanged = epc.tudu == null || !match.tudu.equals(epc.tudu);
        boolean vyhybkaChanged = epc.vyhybka != match.vyhybka;

        Tudu tudu = null;
        for (Tudu t : tuduList) {
            if (t.code.equals(match.tudu)) {
                tudu = t;
                break;
            }
        }
        if (tudu == null) {
            tudu = new Tudu(match.tudu);
            tudu.findOrCreate(match.vyhybka);
            tuduList.add(tudu);
        }
        currentTudu = tudu;
        epc.tudu = match.tudu;
        Tudu.Vyhybka v = tudu.findOrCreate(match.vyhybka);
        currentVyhybka = v;
        epc.vyhybka = match.vyhybka;
        if (tuduChanged || vyhybkaChanged || epc.cast <= 0
                || epc.cast < v.castMin || epc.cast > v.castMax) {
            epc.cast = firstMissingCast(tudu.code, v);
        }
        refreshTemplate();
        updateStep1();
        updateSummary1();
    }

    private void closeDzsDatabase() {
        if (dzsDatabase != null) {
            try {
                dzsDatabase.close();
            } catch (Exception ignored) { }
            dzsDatabase = null;
        }
    }

    /** Trvalé úložiště indexu a kopií DB – nesmí být v getCacheDir() (systém ho může smazat). */
    private File getDzsStorageDir() {
        File dir = new File(getFilesDir(), "dzs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        migrateLegacyDzsCache(dir);
        return dir;
    }

    /** Jednorázový přesun indexu z getCacheDir(), který Android při úsporách místa maže. */
    private void migrateLegacyDzsCache(File targetDir) {
        File legacyIndex = new File(getCacheDir(), "dzs_index");
        if (!legacyIndex.isDirectory()) return;
        File targetIndex = new File(targetDir, "dzs_index");
        if (targetIndex.exists()) return;
        copyDirectory(legacyIndex, targetIndex);
        for (String name : new String[]{"dzs_source.db", "dzs_auto_source.db"}) {
            File legacyFile = new File(getCacheDir(), name);
            if (!legacyFile.isFile()) continue;
            File targetFile = new File(targetDir, name);
            if (!targetFile.exists()) {
                legacyFile.renameTo(targetFile);
            }
        }
        for (File legacy : getCacheDir().listFiles()) {
            if (legacy == null || !legacy.isFile()) continue;
            String n = legacy.getName();
            if (!n.startsWith("sqlite_") || !n.endsWith(".db")) continue;
            File targetFile = new File(targetDir, n);
            if (!targetFile.exists()) {
                legacy.renameTo(targetFile);
            }
        }
    }

    private static void copyDirectory(File source, File target) {
        if (!source.isDirectory()) return;
        if (!target.exists() && !target.mkdirs()) return;
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            File dest = new File(target, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, dest);
            } else if (!dest.exists()) {
                child.renameTo(dest);
            }
        }
    }

    private File copyUriToCache(Uri uri, String fileName) throws Exception {
        File out = new File(getDzsStorageDir(), fileName);
        long expectedSize = querySize(uri);
        if (expectedSize > 0 && out.isFile() && out.length() == expectedSize) {
            return out;
        }
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out, false)) {
            if (in == null) throw new Exception("Soubor nelze otevřít");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out;
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
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
                selectFirstAvailableVyhybka(t);
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
        selectFirstAvailableVyhybka(t);
    }

    private void selectFirstAvailableVyhybka(Tudu t) {
        Tudu.Vyhybka first = firstAvailableVyhybka(t);
        if (first != null) {
            selectVyhybka(first, true);
        } else if (!t.vyhybky.isEmpty()) {
            selectVyhybka(t.vyhybky.get(0), true);
        } else {
            currentVyhybka = null;
            epc.vyhybka = 0;
            refreshTemplate();
            updateStep1();
            updateSummary1();
        }
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
        if (!step1Done) return;
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
            LocationCache.Snapshot gps = locationCache != null ? locationCache.getSnapshot() : LocationCache.Snapshot.empty();
            if (gps.valid) {
                row.latitude = LocationCache.formatLatitude(gps.latitude);
                row.longitude = LocationCache.formatLongitude(gps.longitude);
                row.accuracyM = LocationCache.formatAccuracyM(gps.accuracyM);
                row.gpsTime = LocationCache.formatGpsTime(gps.gpsTimeMs);
            } else {
                row.latitude = "";
                row.longitude = "";
                row.accuracyM = "";
                row.gpsTime = "";
                if (!gpsUnavailableToastShown) {
                    gpsUnavailableToastShown = true;
                    toast(getString(R.string.gps_unavailable_toast));
                }
            }
            csvStore.upsert(row);
            persistCsvAsync();
            refreshCsvTable();
        } catch (Exception e) {
            toast("CSV: " + e.getMessage());
        }
    }

    /** Po dokončení zápisu tagu (EPC samostatně, nebo celý řetězec EPC→heslo→lock). */
    private void onTagCycleComplete() {
        maybeClearGpsVyhybkaLock();
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
        return formatVyhybkaPickerLabel(tuduCode, v, null);
    }

    private CharSequence formatVyhybkaPickerLabel(String tuduCode, Tudu.Vyhybka v, Double distanceM) {
        CharSequence base = formatVyhybkaPickerLabelCore(tuduCode, v);
        if (distanceM == null) return base;
        String full = base.toString() + " · " + formatDistanceM(distanceM);
        if (!(base instanceof SpannableString)) {
            return full;
        }
        SpannableString orig = (SpannableString) base;
        SpannableString span = new SpannableString(full);
        TextUtils.copySpansFrom(orig, 0, orig.length(), null, span, 0);
        return span;
    }

    private CharSequence formatVyhybkaPickerLabelCore(String tuduCode, Tudu.Vyhybka v) {
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
        dbLoadGeneration++;
        cancelGpsLookupTimeout();
        if (locationCache != null) locationCache.stop();
        closeDzsDatabase();
        super.onDestroy();
        io.execute(uhf::free);
        io.shutdown();
        gpsIo.shutdown();
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
