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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
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
import com.rfidw.app.csv.CsvRecordBuilder;
import com.rfidw.app.csv.CsvStorage;
import com.rfidw.app.csv.CsvStore;
import com.rfidw.app.data.DzsDatabase;
import com.rfidw.app.data.Tudu;
import com.rfidw.app.epc.EpcModel;
import com.rfidw.app.location.LocationCache;
import com.rfidw.app.rfid.UhfManager;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    // klávesy spouště čtečky (Chainway C5 a příbuzné)
    private static final int[] TRIGGER_KEYS = {139, 280, 293, 311, 312, 522, 523, 0x3E8};

    private static final String TAG = "MainActivity";
    private static final int REQUEST_LOCATION_PERMISSION = 1001;
    private static final int REQUEST_STORAGE_PERMISSION = 1002;
    private static final int REQUEST_CSV_STORAGE_PERMISSION = 1003;
    private static final String PREF_STORAGE_ACCESS_PROMPTED = "storageAccessPrompted";
    private static final String DEFAULT_DB_NAME = "DZS_PASPORT_TPI.sqlite";

    /** Výsledek automatického vyhledání DB ve Stažených / úložišti. */
    private static final class AutoDiscoveredDb {
        final File localFile;
        final Uri contentUri;
        final String displayName;

        AutoDiscoveredDb(File localFile, Uri contentUri, String displayName) {
            this.localFile = localFile;
            this.contentUri = contentUri;
            this.displayName = displayName;
        }
    }

    private static final int COLOR_STATUS_READY = 0xFF2E7D32;
    private static final int COLOR_STATUS_BUSY = 0xFF5F6A76;
    private static final int COLOR_STATUS_ERROR = 0xFFC62828;
    private static final int COLOR_STATUS_WARNING = 0xFFE65100;
    private static final int COLOR_STATUS_GPS_WAIT = 0xFFE65100;
    private static final int COLOR_STATUS_GPS_STALE = 0xFFF57C00;

    private static final int WF_STEP_EPC = 0;
    private static final int WF_STEP_CSV = 1;
    private static final int WF_STEP_PWD = 2;
    private static final int WF_STEP_LOCK = 3;
    private static final int WF_STATE_PENDING = 0;
    private static final int WF_STATE_ACTIVE = 1;
    private static final int WF_STATE_OK = 2;
    private static final int WF_STATE_FAIL = 3;
    private static final int WORKFLOW_DONE_DELAY_MS = 1500;
    private static final long STATUS_ALT_SHOW_MESSAGE_MS = 3000;
    private static final long STATUS_ALT_SHOW_INDICATORS_MS = 2000;
    private static final int POWER_PRESET_KOLEJI_DBM = 16;
    private static final int POWER_PRESET_RUCE_DBM = 1;
    private static final long DEFAULT_ID_RFID = 400;
    /** Výchozí NEW PWD v kartě 4 – změňte zde při budoucí úpravě hesla. */
    private static final String NEW_PWD_PRESET = "12345678";

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
    private int dbIndexProgressPercent;
    /** Režim výběru TUDU: true = GPS, false = ruční výběr ze seznamu. */
    private boolean gpsAutoSelection = true;
    /** Ruční výběr TUDU v GPS režimu – GPS lookup nepřepíše, dokud uživatel neklikne Načíst polohu. */
    private boolean gpsTuduLocked;
    /** Ruční výběr výhybky v GPS režimu – GPS lookup nepřepíše, dokud nejsou načteny všechny části. */
    private boolean gpsVyhybkaLocked;
    private volatile boolean gpsLookupInFlight;
    private volatile boolean proximityLoadInFlight;
    private boolean gpsLookupNoMatch;
    private boolean forceNextGpsLookup;
    private Double lastGpsLookupLat;
    private Double lastGpsLookupLon;
    private long lastGpsLookupTimeMs;
    private static final double GPS_LOOKUP_MIN_MOVE_M = 5.0;
    private static final long GPS_LOOKUP_MIN_INTERVAL_MS = 1000;
    private static final long GPS_LOOKUP_TIMEOUT_MS = 10_000;
    private static final long GPS_DB_LOAD_POLL_MS = 500;
    /** Krátké čekání na GPS při automatickém načtení – pak se DB otevře i bez fixu. */
    private static final long GPS_DB_LOAD_MAX_WAIT_AUTO_MS = 8_000;
    /** Při ručním výběru souboru lze počkat déle, ale ne donekonečna. */
    private static final long GPS_DB_LOAD_MAX_WAIT_MANUAL_MS = 20_000;
    private static final int GPS_NEARBY_TUDU_LIMIT = 10;
    /** Čip 5 = hranice TUDU (manuální zápis, mimo běžný cyklus výhybky). */
    private static final int CAST_TUDU_BOUNDARY = 5;
    private static final String PREFS_NAME = "rfidgogps";
    private static final String PREF_GPS_TEST_MODE = "gpsTestMode";
    private static final String PREF_TUDU_MODE_GPS = "tuduModeGps";
    private static final String PREF_EPC_TEMPLATE_MODE = "epcTemplateMode";
    private static final String PREF_TEST_LAT = "testLat";
    private static final String PREF_TEST_LON = "testLon";
    private static final String PREF_DB_SOURCE_PATH = "dbSourcePath";
    private static final String PREF_DB_DISPLAY_NAME = "dbDisplayName";
    private static final String PREF_DB_URI = "dbSourceUri";

    private CsvStore csvStore;
    private CsvAdapter csvAdapter;
    private SharedPreferences prefs;

    private boolean pendingAutoLoadAfterStorage;
    private boolean pendingCsvInit;
    private boolean step1Done, step2Done, step3Done, step2Failed;
    private boolean workflowRunning, chainWorkflow, scanDoneAwaitingConfirm;
    /** CSV obnoveno dřív než zdrojový soubor – posun na další čip/výhybku až po načtení TUDU. */
    private boolean pendingAdvanceFromCsv;
    /** Po obnově z CSV neobnovovat TUDU z posledního řádku – určit podle GPS. */
    private boolean skipCsvTuduRestore;
    private int activeStep;

    // view reference
    private TextView tvReaderStatus, tvGpsStatus, tvEpcPreview, tvEpcValid, tvSourceFile,
            tvCard1DbProgress, tvDbIndexProgress,
            tvWriteResult, tvCsvPath, tvPwdWriteResult, tvLockResult,
            tvSummaryTudu, tvSummaryVyhybka, tvSummaryCast,
            tvCastHintAction, tvCastHintPart,
            tvScanDoneVyhybka, tvScanDoneCast,
            tvLastRecordVyhybka, tvLastRecordCast,
            step1Circle, step2Circle, step3Circle, step1Label, step2Label;
    private View workflowStepIndicators, wfStepEpc, wfStepCsv, wfStepPwd, wfStepLock;
    private View[] wfStepViews;
    private final int[] wfStepStates = new int[4];
    private View summary1, colSummaryTudu, colSummaryVyhybka, castHintBox, scanDoneScrim,
            scanDoneDialog, deleteConfirmDialog, lastRecordBox, card1, topBar,
            card1DbProgress, cardDbIndex, kontrolaOverlay;
    private com.google.android.material.progressindicator.LinearProgressIndicator card1DbProgressBar,
            dbIndexProgressBar;
    private NestedScrollView mainScroll;
    private LinearLayout kontrolaCellsContainer;
    private View kontrolaMatchNav;
    private TextView tvSummaryVyhybkaLabel, tvKontrolaPrompt, tvKontrolaStatus, tvKontrolaHeader,
            tvKontrolaMatchIndex;
    private boolean kontrolaActive, kontrolaReading;
    private List<CsvStore.Row> kontrolaMatchRows = Collections.emptyList();
    private int kontrolaMatchIndex;
    private OnBackPressedCallback backPressedCallback;
    private BottomSheetBehavior<View> workflowBehavior;
    private EditText etAccessPwd, etPower, etPwdAccess, etPwdNew, etLockAccessPwd;
    private CheckBox cbAutoCsv, cbGpsTestMode;
    private View btnPickTestLocation;
    private TextView tvGpsTestModeHint;
    private boolean gpsTestMode;
    private MaterialButtonToggleGroup powerPresetGroup;
    private MaterialButtonToggleGroup tuduModeGroup;
    private MaterialButtonToggleGroup castBranchGroup;
    private MaterialButton btnCastJazyk, btnCastHlavni, btnCastVedlejsi;
    private MaterialButtonToggleGroup templateModeGroup;
    private MaterialButton btnGpsReloadLocation;
    private TextView tvTuduModeHint;
    private TextView tvTemplateModeHint;
    private View templateRowsBox;
    private boolean epcTemplateMode;
    private boolean tuduListFullyLoaded;
    private CastPartType selectedCastPartType = CastPartType.NONE;
    private int lastCastHintCast = -1;
    /** Režim manuálního zápisu hranice TUDU (čip 5). */
    private boolean tuduBoundaryMode;
    /** Po „Použít“ v hranici TUDU zobrazovat „objekt“ místo „výhybka“. */
    private boolean tuduBoundaryObjektLabels;
    private String tuduBoundaryVyhybkaLabel = "";
    private String tuduBoundaryKmExt = "";

    /** Typ části dvojvětvé 3částové výhybky – nezávisí na pořadí čipu. */
    private enum CastPartType { NONE, JAZYK, HLAVNI, VEDLEJSI }
    private int lastChip1WriteCount = 1;
    private Boolean powerPresetInKoleji;
    private boolean showGpsStatus;
    private boolean gpsUnavailableToastShown;
    private int lastTopBarHeight = -1;
    private Runnable gpsLookupTimeoutRunnable;
    private Runnable statusAlternationTick;
    private boolean statusAlternationActive;
    private boolean statusAlternationShowingMessage;

    // řádky šablony (kontejnery z include)
    private View[] rows = new View[7];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setContentView(R.layout.activity_main);

        bindViews();
        setupLocation();
        setupTopBarInsets();
        setupWorkflowSheet();
        setupCollapsibles();
        collapseWorkflowCards();
        setupTemplateRows();
        setupTemplateMode();
        setupCsv();
        setupListeners();
        setupCastBranchSelection();
        setupTuduSelectionMode();
        setupGpsReloadLocation();
        setupGpsTestMode();
        setupBackPressedHandler();
        setupKontrola();
        tryAutoLoadDefaultDatabase();

        etPower.setText("");
        etPwdNew.setText(NEW_PWD_PRESET);

        epc.idRfid = prefs.getLong("idRfid", -1);
        if (epc.idRfid < 0) {
            epc.idRfid = getSharedPreferences("rfidgo", MODE_PRIVATE).getLong("idRfid", DEFAULT_ID_RFID);
        }
        if (epc.idRfid < DEFAULT_ID_RFID) {
            epc.idRfid = DEFAULT_ID_RFID;
            prefs.edit().putLong("idRfid", epc.idRfid).apply();
        }
        refreshTemplate();
        updateSummary1();
        updatePowerPresetUi();
        updateStepIndicators();

        setActionStatusReady();
    }

    private void bindViews() {
        tvReaderStatus = findViewById(R.id.tvReaderStatus);
        workflowStepIndicators = findViewById(R.id.workflowStepIndicators);
        wfStepEpc = findViewById(R.id.wfStepEpc);
        wfStepCsv = findViewById(R.id.wfStepCsv);
        wfStepPwd = findViewById(R.id.wfStepPwd);
        wfStepLock = findViewById(R.id.wfStepLock);
        wfStepViews = new View[]{wfStepEpc, wfStepCsv, wfStepPwd, wfStepLock};
        resetWorkflowStepIndicators();
        tvGpsStatus = findViewById(R.id.tvGpsStatus);
        tvEpcPreview = findViewById(R.id.tvEpcPreview);
        tvEpcValid = findViewById(R.id.tvEpcValid);
        tvSourceFile = findViewById(R.id.tvSourceFile);
        tvCard1DbProgress = findViewById(R.id.tvCard1DbProgress);
        card1DbProgress = findViewById(R.id.card1DbProgress);
        card1DbProgressBar = findViewById(R.id.card1DbProgressBar);
        cardDbIndex = findViewById(R.id.cardDbIndex);
        tvDbIndexProgress = findViewById(R.id.tvDbIndexProgress);
        dbIndexProgressBar = findViewById(R.id.dbIndexProgressBar);
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
        tvSummaryVyhybkaLabel = findViewById(R.id.tvSummaryVyhybkaLabel);
        kontrolaOverlay = findViewById(R.id.kontrolaOverlay);
        kontrolaCellsContainer = findViewById(R.id.kontrolaCellsContainer);
        tvKontrolaPrompt = findViewById(R.id.tvKontrolaPrompt);
        tvKontrolaStatus = findViewById(R.id.tvKontrolaStatus);
        tvKontrolaHeader = findViewById(R.id.tvKontrolaHeader);
        kontrolaMatchNav = findViewById(R.id.kontrolaMatchNav);
        tvKontrolaMatchIndex = findViewById(R.id.tvKontrolaMatchIndex);
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
        templateModeGroup = findViewById(R.id.templateModeGroup);
        tvTemplateModeHint = findViewById(R.id.tvTemplateModeHint);
        templateRowsBox = findViewById(R.id.templateRowsBox);

        rows[0] = findViewById(R.id.row1);
        rows[1] = findViewById(R.id.row2);
        rows[2] = findViewById(R.id.row3);
        rows[3] = findViewById(R.id.row4);
        rows[4] = findViewById(R.id.row5);
        rows[5] = findViewById(R.id.row6);
        rows[6] = findViewById(R.id.row7);
    }

    private void setupTopBarInsets() {
        topBar.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int topInset = topBar.getHeight();
                if (topInset == lastTopBarHeight) return;
                lastTopBarHeight = topInset;
                applyMainScrollTopPadding(topInset);
                if (isOverlayDialogVisible()) {
                    applyOverlayScrimTopMargin();
                }
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
                    reloadCsvFromDiskIfChanged(true);
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

        colSummaryTudu.setOnClickListener(v -> {
            if (tuduBoundaryMode) showTuduBoundaryForm();
            else showTuduPicker();
        });
        colSummaryVyhybka.setOnClickListener(v -> {
            if (tuduBoundaryMode) showTuduBoundaryForm();
            else showVyhybkaPicker();
        });
    }

    private void updateWorkflowSheetOverlay(View sheet, boolean expanded) {
        updateWorkflowSheetElevation(sheet, expanded);
        if (isOverlayDialogVisible()) {
            showOverlayScrimBehindTopBar();
        }
    }

    private boolean isOverlayDialogVisible() {
        return scanDoneDialog.getVisibility() == View.VISIBLE
                || deleteConfirmDialog.getVisibility() == View.VISIBLE
                || kontrolaActive;
    }

    private void showOverlayScrimBehindTopBar() {
        scanDoneScrim.setElevation(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, SCAN_DONE_SCRIM_ELEVATION_OVER_SHEET_DP,
                getResources().getDisplayMetrics()));
        scanDoneScrim.setVisibility(View.VISIBLE);
        scanDoneScrim.setAlpha(1f);
        applyOverlayScrimTopMargin();
    }

    private void applyOverlayScrimTopMargin() {
        topBar.post(() -> {
            ViewGroup.MarginLayoutParams scrimLp =
                    (ViewGroup.MarginLayoutParams) scanDoneScrim.getLayoutParams();
            int topInset = topBar.getHeight();
            if (scrimLp.topMargin != topInset) {
                scrimLp.topMargin = topInset;
                scanDoneScrim.setLayoutParams(scrimLp);
            }
        });
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

    private void attachDbIndexProgressListener(DzsDatabase db) {
        if (db == null) {
            hideWorkflowDbIndexCard();
            return;
        }
        db.setIndexProgressListener((phase, percent) -> runOnUiThread(() -> {
            if (dzsDatabase != db) return;
            updateWorkflowDbIndexProgress(phase, percent);
        }));
    }

    private void updateWorkflowDbIndexProgress(String phase, int percent) {
        if (cardDbIndex == null) return;
        if (percent < 0) {
            cardDbIndex.setVisibility(View.VISIBLE);
            if (tvDbIndexProgress != null) {
                String text;
                if (percent == -2 && phase != null && !phase.isEmpty()) {
                    text = phase;
                } else {
                    text = getString(R.string.db_index_failed);
                }
                tvDbIndexProgress.setText(text);
            }
            if (dbIndexProgressBar != null) {
                dbIndexProgressBar.setVisibility(View.GONE);
            }
            return;
        }
        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped < dbIndexProgressPercent) {
            clamped = dbIndexProgressPercent;
        } else {
            dbIndexProgressPercent = clamped;
        }
        cardDbIndex.setVisibility(View.VISIBLE);
        if (dbIndexProgressBar != null) {
            dbIndexProgressBar.setVisibility(View.VISIBLE);
            dbIndexProgressBar.setProgressCompat(clamped, true);
        }
        String label = clamped >= 100
                ? getString(R.string.db_index_complete)
                : getString(R.string.db_index_progress, phase, clamped);
        if (tvDbIndexProgress != null) {
            tvDbIndexProgress.setText(label);
        }
    }

    private void hideWorkflowDbIndexCard() {
        dbIndexProgressPercent = 0;
        if (cardDbIndex != null) {
            cardDbIndex.setVisibility(View.GONE);
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

    private void scrollToCastHint() {
        if (mainScroll == null || castHintBox == null) return;
        if (castHintBox.getVisibility() != View.VISIBLE) return;
        mainScroll.post(() -> mainScroll.smoothScrollTo(0, castHintBox.getTop()));
    }

    private void promptCastBranchSelection() {
        scrollToCastHint();
        toast(getString(R.string.cast_branch_select));
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

    private static boolean isTuduBoundaryCast(int cast) {
        return cast == CAST_TUDU_BOUNDARY;
    }

    private void exitTuduBoundaryMode() {
        boolean wasBoundary = tuduBoundaryMode;
        tuduBoundaryMode = false;
        tuduBoundaryVyhybkaLabel = "";
        tuduBoundaryKmExt = "";
        clearTuduBoundaryObjektLabels();
        if (wasBoundary) {
            gpsTuduLocked = false;
            gpsVyhybkaLocked = false;
        }
    }

    private void clearTuduBoundaryObjektLabels() {
        if (!tuduBoundaryObjektLabels) return;
        tuduBoundaryObjektLabels = false;
        updateSummaryVyhybkaLabel();
    }

    private void setTuduBoundaryObjektLabels() {
        tuduBoundaryObjektLabels = true;
        updateSummaryVyhybkaLabel();
    }

    private void updateSummaryVyhybkaLabel() {
        if (tvSummaryVyhybkaLabel == null) return;
        tvSummaryVyhybkaLabel.setText(getString(tuduBoundaryObjektLabels
                ? R.string.summary_objekt_label
                : R.string.summary_vyhybka_label));
    }

    private String getVyhybkaLabelPrefix() {
        return getString(tuduBoundaryObjektLabels
                ? R.string.objekt_picker_prefix
                : R.string.vyhybka_picker_prefix);
    }

    private void showTuduBoundaryForm() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_tudu_boundary, null);
        com.google.android.material.textfield.TextInputEditText etTudu =
                dialogView.findViewById(R.id.etBoundaryTudu);
        com.google.android.material.textfield.TextInputEditText etVyhybka =
                dialogView.findViewById(R.id.etBoundaryVyhybka);
        com.google.android.material.textfield.TextInputEditText etKmExt =
                dialogView.findViewById(R.id.etBoundaryKmExt);
        View btnPickNearby = dialogView.findViewById(R.id.btnPickNearbyTudu);

        if (epc.tudu != null && !epc.tudu.isEmpty()) {
            etTudu.setText(epc.tudu);
        }
        if (tuduBoundaryVyhybkaLabel != null && !tuduBoundaryVyhybkaLabel.isEmpty()) {
            etVyhybka.setText(tuduBoundaryVyhybkaLabel);
        }
        if (tuduBoundaryKmExt != null && !tuduBoundaryKmExt.isEmpty()) {
            etKmExt.setText(tuduBoundaryKmExt);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Použít", null)
                .setNegativeButton("Zrušit", null)
                .create();

        btnPickNearby.setOnClickListener(v -> showNearbyFullTuduPickerForBoundary(etTudu));

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String tudu = etTudu.getText() != null ? etTudu.getText().toString().trim() : "";
            String vyhybka = etVyhybka.getText() != null ? etVyhybka.getText().toString().trim() : "";
            String kmExt = etKmExt.getText() != null ? etKmExt.getText().toString().trim() : "";
            if (tudu.isEmpty()) {
                toast(getString(R.string.tudu_boundary_tudu_required));
                return;
            }
            if (vyhybka.isEmpty()) {
                toast(getString(R.string.tudu_boundary_vyhybka_required));
                return;
            }
            applyTuduBoundaryForm(tudu, vyhybka, kmExt);
            dialog.dismiss();
        }));

        dialog.show();
        if (dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int width = (int) (metrics.widthPixels * 0.94f);
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void applyTuduBoundaryForm(String tudu, String vyhybkaLabel, String kmExt) {
        restoreTuduBoundaryFromRow(tudu, vyhybkaLabel, kmExt);
        setTuduBoundaryObjektLabels();
        refreshTemplate();
        updateStep1();
        updateSummary1();
        updateLastRecordPreview();
        toast(getString(R.string.tudu_boundary_active));
    }

    /** Obnoví režim hranice TUDU (čip 5) z CSV řádku nebo zadaných hodnot. */
    private void restoreTuduBoundaryFromRow(CsvStore.Row row) {
        restoreTuduBoundaryFromRow(
                row.tudu,
                row.vyhybka != null ? row.vyhybka : "",
                row.kmExt != null ? row.kmExt : "");
    }

    private void restoreTuduBoundaryFromRow(String tudu, String vyhybkaLabel, String kmExt) {
        tuduBoundaryMode = true;
        gpsTuduLocked = true;
        gpsVyhybkaLocked = true;
        tuduBoundaryVyhybkaLabel = vyhybkaLabel != null ? vyhybkaLabel.trim() : "";
        tuduBoundaryKmExt = kmExt != null ? kmExt.trim() : "";
        epc.tudu = tudu;
        epc.cast = CAST_TUDU_BOUNDARY;
        epc.vyhybka = parseInt(vyhybkaLabel, 0);
        currentTudu = resolveTuduForUdu(Tudu.uduCode(tudu));
        currentVyhybka = null;
        resetCastBranchSelection();
        setTuduBoundaryObjektLabels();
    }

    private void showNearbyFullTuduPickerForBoundary(
            com.google.android.material.textfield.TextInputEditText targetField) {
        if (dzsDatabase == null) {
            toast(getString(R.string.db_select_required));
            return;
        }
        if (locationCache == null || !locationCache.getSnapshot().valid) {
            toast(getString(R.string.tudu_picker_no_gps));
            return;
        }
        final double lat = locationCache.getSnapshot().latitude;
        final double lon = locationCache.getSnapshot().longitude;
        ensureProximityFromGpsIfNeeded(() -> gpsIo.execute(() -> {
            List<DzsDatabase.GpsMatch> matches = Collections.emptyList();
            try {
                if (dzsDatabase != null) {
                    matches = dzsDatabase.findNearestDistinctFullTudu(
                            lat, lon, GPS_NEARBY_TUDU_LIMIT);
                }
            } catch (Exception e) {
                Log.w(TAG, "Nearest full TUDU lookup selhal", e);
            }
            final List<DzsDatabase.GpsMatch> result = matches;
            ui.post(() -> {
                if (result.isEmpty()) {
                    toast(getString(R.string.gps_tudu_not_found));
                    return;
                }
                showNearbyFullTuduPickerDialog(result, targetField);
            });
        }));
    }

    private void showNearbyFullTuduPickerDialog(List<DzsDatabase.GpsMatch> matches,
                                                com.google.android.material.textfield.TextInputEditText targetField) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_tudu_picker, null);
        EditText etSearch = dialogView.findViewById(R.id.etTuduSearch);
        ListView listView = dialogView.findViewById(R.id.lvTudu);
        etSearch.setVisibility(View.GONE);

        List<String> labels = new ArrayList<>(matches.size());
        for (DzsDatabase.GpsMatch m : matches) {
            labels.add(m.tudu + " · " + formatDistanceM(m.distanceM));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, labels);
        listView.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.tudu_boundary_picker_title))
                .setView(dialogView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (targetField != null) {
                targetField.setText(matches.get(position).tudu);
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private void showNearbyTuduPicker() {
        if (locationCache == null || !locationCache.getSnapshot().valid) {
            toast(getString(R.string.tudu_picker_no_gps));
            return;
        }
        final double lat = locationCache.getSnapshot().latitude;
        final double lon = locationCache.getSnapshot().longitude;
        ensureProximityFromGpsIfNeeded(() -> gpsIo.execute(() -> {
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
        }));
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

        String preselectUdu = currentUduCode();
        for (int i = 0; i < matches.size(); i++) {
            if (Tudu.uduCode(matches.get(i).tudu).equals(preselectUdu)) {
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
        return Tudu.uduCode(match.tudu) + " · " + formatDistanceM(match.distanceM);
    }

    private String currentUduCode() {
        if (currentTudu != null) return currentTudu.uduCode();
        return Tudu.uduCode(epc.tudu);
    }

    private List<String> distinctUduCodesFromTuduList() {
        LinkedHashSet<String> udus = new LinkedHashSet<>();
        for (Tudu t : tuduList) {
            udus.add(t.uduCode());
        }
        return new ArrayList<>(udus);
    }

    private int countDistinctUduInList() {
        Set<String> udus = new HashSet<>();
        for (Tudu t : tuduList) {
            udus.add(t.uduCode());
        }
        return udus.size();
    }

    /**
     * Pro výběr stanice (UDU) vrátí konkrétní podtyp TUDU pro EPC/CSV.
     * Preferuje aktuálně používaný plný kód, jinak první shoda v seznamu.
     */
    private Tudu resolveTuduForUdu(String udu) {
        if (udu == null || udu.isEmpty()) return null;
        if (epc.tudu != null && Tudu.uduCode(epc.tudu).equals(udu)) {
            for (Tudu t : tuduList) {
                if (t.code.equals(epc.tudu)) return t;
            }
        }
        if (currentTudu != null && currentTudu.uduCode().equals(udu)) {
            return currentTudu;
        }
        for (Tudu t : tuduList) {
            if (t.uduCode().equals(udu)) return t;
        }
        if (dzsDatabase != null) {
            List<Tudu> loaded = dzsDatabase.loadTuduForUdu(udu);
            for (Tudu t : loaded) {
                mergeTuduIntoList(t);
            }
            if (!loaded.isEmpty()) {
                if (epc.tudu != null && Tudu.uduCode(epc.tudu).equals(udu)) {
                    for (Tudu t : loaded) {
                        if (t.code.equals(epc.tudu)) return t;
                    }
                }
                return loaded.get(0);
            }
        }
        return null;
    }

    private void mergeTuduIntoList(Tudu loaded) {
        for (Tudu existing : tuduList) {
            if (existing.code.equals(loaded.code)) {
                for (Tudu.Vyhybka v : loaded.vyhybky) {
                    Tudu.Vyhybka target = existing.findOrCreate(v.cislo, v.iob);
                    if (v.castMin > 0) target.castMin = Math.min(target.castMin, v.castMin);
                    if (v.castMax > 0) target.castMax = Math.max(target.castMax, v.castMax);
                    for (Tudu.Vyhybka.RoBranch branch : v.getRoBranches()) {
                        target.addRoBranch(branch.roId, branch.poloha, branch.kmExtChip1, branch.kmExtOther);
                    }
                    target.reconcileCastRangeFromBranches();
                }
                return;
            }
        }
        for (Tudu.Vyhybka v : loaded.vyhybky) {
            v.reconcileCastRangeFromBranches();
        }
        tuduList.add(loaded);
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
        for (String udu : distinctUduCodesFromTuduList()) {
            filteredCodes.add(udu);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, filteredCodes);
        listView.setAdapter(adapter);

        String preselectUdu = currentUduCode();
        int checked = filteredCodes.indexOf(preselectUdu);
        if (checked >= 0) listView.setItemChecked(checked, true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vyberte UDU")
                .setView(dialogView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            Tudu t = resolveTuduForUdu(filteredCodes.get(position));
            if (t != null) {
                skipCsvTuduRestore = false;
                if (pendingAdvanceFromCsv) {
                    selectTuduPreservingEpc(t);
                } else {
                    selectTudu(t);
                }
            }
            dialog.dismiss();
        });

        etSearch.addTextChangedListener(new SimpleWatcher(() -> {
            String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            filteredCodes.clear();
            for (String udu : distinctUduCodesFromTuduList()) {
                if (q.isEmpty() || udu.toLowerCase(Locale.ROOT).contains(q)) {
                    filteredCodes.add(udu);
                }
            }
            adapter.notifyDataSetChanged();
            String selectedUdu = currentUduCode();
            int pos = filteredCodes.indexOf(selectedUdu);
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
        final String uduCode = currentUduCode();
        if (uduCode.isEmpty()) {
            toast(getString(R.string.db_select_required));
            expandCard1Body();
            return;
        }
        final long loadId = dbLoadGeneration;
        if (dzsDatabase == null) {
            List<VyhybkaPickerItem> items = collectVyhybkaItemsForUdu(uduCode);
            if (items.isEmpty()) {
                toast(getString(R.string.db_select_required));
                expandCard1Body();
                return;
            }
            showVyhybkaPickerDialog(prepareVyhybkaPickerItems(items, null, false), 0);
            return;
        }

        final boolean withDistances = gpsAutoSelection
                && locationCache != null && locationCache.getSnapshot().valid;
        final double lat = withDistances ? locationCache.getSnapshot().latitude : 0;
        final double lon = withDistances ? locationCache.getSnapshot().longitude : 0;
        final DzsDatabase db = dzsDatabase;

        io.execute(() -> {
            List<VyhybkaPickerItem> loadedItems = Collections.emptyList();
            List<Tudu> loadedTudus = Collections.emptyList();
            Map<String, Double> distances = null;
            List<VyhybkaPickerPreparedItem> preparedItems = Collections.emptyList();
            int missingGps = 0;
            try {
                if (loadId == dbLoadGeneration && db != null) {
                    loadedTudus = db.loadTuduForUdu(uduCode);
                    loadedItems = buildVyhybkaPickerItems(loadedTudus);
                    if (withDistances && !loadedItems.isEmpty()) {
                        distances = db.findVyhybkaDistancesForUdu(loadedTudus, lat, lon);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Načtení výhybek pro picker selhalo", e);
            }
            final List<VyhybkaPickerItem> itemsSnapshot =
                    sortVyhybkaItemsForPicker(loadedItems, distances);
            final List<Tudu> tudusSnapshot = loadedTudus;
            final Map<String, Double> distancesSnapshot = distances;
            final boolean showDistanceHints = withDistances;
            if (!itemsSnapshot.isEmpty()) {
                preparedItems = prepareVyhybkaPickerItems(
                        itemsSnapshot, distancesSnapshot, showDistanceHints);
                missingGps = countVyhybkaPickerMissingGps(
                        preparedItems, distancesSnapshot, showDistanceHints);
            }
            final List<VyhybkaPickerPreparedItem> preparedSnapshot = preparedItems;
            final int missingGpsSnapshot = missingGps;
            runOnUiThreadIfAlive(loadId, () -> {
                for (Tudu t : tudusSnapshot) {
                    mergeTuduIntoList(t);
                }
                if (!tudusSnapshot.isEmpty()) {
                    syncCurrentVyhybkaAfterReload();
                }
                List<VyhybkaPickerPreparedItem> prepared = preparedSnapshot;
                int missingGpsCount = missingGpsSnapshot;
                if (prepared.isEmpty()) {
                    List<VyhybkaPickerItem> pickerItems = collectVyhybkaItemsForUdu(uduCode);
                    if (pickerItems.isEmpty()) {
                        toast(getString(R.string.db_select_required));
                        expandCard1Body();
                        return;
                    }
                    prepared = prepareVyhybkaPickerItems(
                            pickerItems, distancesSnapshot, showDistanceHints);
                    missingGpsCount = countVyhybkaPickerMissingGps(
                            prepared, distancesSnapshot, showDistanceHints);
                }
                showVyhybkaPickerDialog(prepared, missingGpsCount);
            });
        });
    }

    private List<VyhybkaPickerPreparedItem> prepareVyhybkaPickerItems(
            List<VyhybkaPickerItem> items, Map<String, Double> distancesM, boolean showDistanceHints) {
        List<VyhybkaPickerPreparedItem> prepared = new ArrayList<>(items.size());
        for (VyhybkaPickerItem item : items) {
            boolean complete = isVyhybkaCompleteInCsv(item.tuduCode, item.vyhybka);
            Double dist = distancesM != null
                    ? distancesM.get(vyhybkaPickerKey(item.tuduCode, item.vyhybka)) : null;
            CharSequence label = formatVyhybkaPickerLabel(
                    item.tuduCode, item.vyhybka, dist, showDistanceHints);
            prepared.add(new VyhybkaPickerPreparedItem(item, label, complete));
        }
        return prepared;
    }

    private int countVyhybkaPickerMissingGps(List<VyhybkaPickerPreparedItem> items,
                                             Map<String, Double> distancesM,
                                             boolean showDistanceHints) {
        if (!showDistanceHints || distancesM == null) return 0;
        int missing = 0;
        for (VyhybkaPickerPreparedItem item : items) {
            if (!distancesM.containsKey(
                    vyhybkaPickerKey(item.item.tuduCode, item.item.vyhybka))) {
                missing++;
            }
        }
        return missing;
    }

    private CharSequence formatVyhybkaPickerTitle(int totalCount, int missingGpsCount) {
        if (!tuduBoundaryObjektLabels) {
            if (totalCount <= 0) {
                return getString(R.string.vyhybka_picker_title);
            }
            if (missingGpsCount > 0) {
                return getString(R.string.vyhybka_picker_title_total_missing_gps,
                        totalCount, missingGpsCount);
            }
            return getString(R.string.vyhybka_picker_title_total, totalCount);
        }
        if (totalCount <= 0) {
            return getString(R.string.objekt_picker_title);
        }
        if (missingGpsCount > 0) {
            return getString(R.string.objekt_picker_title_total_missing_gps,
                    totalCount, missingGpsCount);
        }
        return getString(R.string.objekt_picker_title_total, totalCount);
    }

    private void showVyhybkaPickerDialog(List<VyhybkaPickerPreparedItem> itemsSource,
                                         int missingGpsCount) {
        final List<VyhybkaPickerPreparedItem> allItems = new ArrayList<>(itemsSource);
        final List<VyhybkaPickerPreparedItem> filteredItems = new ArrayList<>(allItems);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_tudu_picker, null);
        EditText etSearch = dialogView.findViewById(R.id.etTuduSearch);
        etSearch.setHint(R.string.vyhybka_search_hint);
        ListView listView = dialogView.findViewById(R.id.lvTudu);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ArrayAdapter<VyhybkaPickerPreparedItem> adapter = new ArrayAdapter<VyhybkaPickerPreparedItem>(this,
                android.R.layout.simple_list_item_single_choice, filteredItems) {
            @Override
            public boolean isEnabled(int position) {
                return !filteredItems.get(position).complete;
            }

            @Override
            public android.view.View getView(int position, android.view.View convertView,
                    android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                TextView tv = (TextView) view;
                VyhybkaPickerPreparedItem item = filteredItems.get(position);
                tv.setText(item.label);
                if (item.complete) {
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
            int checked = findVyhybkaPickerCheckedIndex(filteredItems);
            if (checked >= 0) {
                listView.setItemChecked(checked, true);
            }
        };
        refreshChecked.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(formatVyhybkaPickerTitle(allItems.size(), missingGpsCount))
                .setView(dialogView)
                .setNegativeButton("Zrušit", null)
                .create();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            VyhybkaPickerPreparedItem prepared = filteredItems.get(position);
            if (prepared.complete) {
                toast("výhybka je již zapsaná v CSV");
                return;
            }
            if (gpsAutoSelection) {
                gpsVyhybkaLocked = true;
            }
            VyhybkaPickerItem item = prepared.item;
            Tudu owner = findTuduByCode(item.tuduCode);
            if (owner != null) {
                currentTudu = owner;
                epc.tudu = owner.code;
            }
            selectVyhybka(item.vyhybka, true);
            dialog.dismiss();
        });

        etSearch.addTextChangedListener(new SimpleWatcher(() -> {
            String q = etSearch.getText().toString().trim();
            filteredItems.clear();
            if (q.isEmpty()) {
                filteredItems.addAll(allItems);
            } else {
                for (VyhybkaPickerPreparedItem item : allItems) {
                    if (item.item.vyhybka.displayLabel().contains(q)
                            || String.valueOf(item.item.vyhybka.cislo).contains(q)) {
                        filteredItems.add(item);
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
        exitTuduBoundaryMode();
        gpsTuduLocked = false;
        gpsVyhybkaLocked = false;
        clearTuduBoundaryObjektLabels();
        gpsLookupNoMatch = false;
        forceNextGpsLookup = true;
        lastGpsLookupLat = null;
        lastGpsLookupLon = null;
        lastGpsLookupTimeMs = 0;
        scheduleGpsTuduLookup();
    }

    /**
     * Obnoví GPS fix a v GPS režimu znovu určí TUDU/výhybku – volá se na začátku
     * hardwarového cyklu (EPC → CSV → heslo → zamčení), před nastavením workflowRunning.
     */
    private void refreshGpsAtWorkflowStart() {
        if (locationCache == null) return;
        ensureLocationPermission();
        if (!gpsTestMode) {
            locationCache.refresh(this);
        }
        showGpsStatus = true;
        refreshGpsStatus(false);
        if (gpsAutoSelection && dzsDatabase != null) {
            forceNextGpsLookup = true;
            lastGpsLookupLat = null;
            lastGpsLookupLon = null;
            lastGpsLookupTimeMs = 0;
            scheduleGpsTuduLookup();
        }
    }

    private List<VyhybkaPickerItem> sortVyhybkaItemsForPicker(List<VyhybkaPickerItem> source,
                                                              Map<String, Double> distancesM) {
        if (distancesM == null || distancesM.isEmpty()) {
            return source;
        }
        List<VyhybkaPickerItem> sorted = new ArrayList<>(source);
        sorted.sort((a, b) -> {
            Double da = distancesM.get(vyhybkaPickerKey(a.tuduCode, a.vyhybka));
            Double db = distancesM.get(vyhybkaPickerKey(b.tuduCode, b.vyhybka));
            if (da == null && db == null) {
                int tuduCmp = a.tuduCode.compareTo(b.tuduCode);
                return tuduCmp != 0 ? tuduCmp : Integer.compare(a.vyhybka.cislo, b.vyhybka.cislo);
            }
            if (da == null) return 1;
            if (db == null) return -1;
            int cmp = Double.compare(da, db);
            if (cmp != 0) return cmp;
            int tuduCmp = a.tuduCode.compareTo(b.tuduCode);
            return tuduCmp != 0 ? tuduCmp : Integer.compare(a.vyhybka.cislo, b.vyhybka.cislo);
        });
        return sorted;
    }

    private static final class VyhybkaPickerItem {
        final String tuduCode;
        final Tudu.Vyhybka vyhybka;

        VyhybkaPickerItem(String tuduCode, Tudu.Vyhybka vyhybka) {
            this.tuduCode = tuduCode;
            this.vyhybka = vyhybka;
        }

        @Override
        public String toString() {
            return vyhybka.displayLabel();
        }
    }

    private static final class VyhybkaPickerPreparedItem {
        final VyhybkaPickerItem item;
        final CharSequence label;
        final boolean complete;

        VyhybkaPickerPreparedItem(VyhybkaPickerItem item, CharSequence label, boolean complete) {
            this.item = item;
            this.label = label;
            this.complete = complete;
        }

        @Override
        public String toString() {
            return label.toString();
        }
    }

    private List<VyhybkaPickerItem> buildVyhybkaPickerItems(List<Tudu> tudus) {
        List<VyhybkaPickerItem> items = new ArrayList<>();
        for (Tudu t : tudus) {
            for (Tudu.Vyhybka v : t.vyhybky) {
                items.add(new VyhybkaPickerItem(t.code, v));
            }
        }
        return items;
    }

    private List<VyhybkaPickerItem> collectVyhybkaItemsForUdu(String uduCode) {
        List<VyhybkaPickerItem> items = new ArrayList<>();
        for (Tudu t : tuduList) {
            if (!t.uduCode().equals(uduCode)) continue;
            for (Tudu.Vyhybka v : t.vyhybky) {
                items.add(new VyhybkaPickerItem(t.code, v));
            }
        }
        return items;
    }

    private Tudu findTuduByCode(String tuduCode) {
        if (tuduCode == null || tuduCode.isEmpty()) return null;
        for (Tudu t : tuduList) {
            if (t.code.equals(tuduCode)) return t;
        }
        return null;
    }

    private static String vyhybkaPickerKey(String tuduCode, Tudu.Vyhybka v) {
        return tuduCode + "\0" + v.cislo + "\0" + v.iob;
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
        String iob = currentVyhybka != null ? currentVyhybka.iob : "";
        if (cislo <= 0) return;
        currentVyhybka = null;
        for (Tudu.Vyhybka v : currentTudu.vyhybky) {
            if (v.cislo == cislo && (iob.isEmpty() || v.iob.equals(iob))) {
                currentVyhybka = v;
                return;
            }
        }
        for (Tudu.Vyhybka v : currentTudu.vyhybky) {
            if (v.cislo == cislo) {
                currentVyhybka = v;
                break;
            }
        }
        if (currentVyhybka != null) {
            ensureVyhybkaRoBranches(currentTudu.code, currentVyhybka);
            currentVyhybka.reconcileCastRangeFromBranches();
        }
    }

    private int findVyhybkaPickerCheckedIndex(List<VyhybkaPickerPreparedItem> items) {
        String tuduCode = currentTudu != null ? currentTudu.code : epc.tudu;
        int cislo = currentVyhybka != null ? currentVyhybka.cislo : epc.vyhybka;
        String iob = currentVyhybka != null ? currentVyhybka.iob : "";
        if (cislo > 0) {
            for (int i = 0; i < items.size(); i++) {
                VyhybkaPickerItem item = items.get(i).item;
                if (!item.tuduCode.equals(tuduCode)) continue;
                if (item.vyhybka.cislo == cislo
                        && (iob.isEmpty() || item.vyhybka.iob.equals(iob))) return i;
            }
            for (int i = 0; i < items.size(); i++) {
                VyhybkaPickerItem item = items.get(i).item;
                if (item.tuduCode.equals(tuduCode) && item.vyhybka.cislo == cislo) return i;
            }
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).item.vyhybka.cislo == cislo) return i;
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

    // ---------- indikátor kroků workflow (horní řádek) ----------

    private static final class AlternatingStatus {
        final String text;
        final int color;

        AlternatingStatus(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private boolean shouldShowWorkflowStepIndicators() {
        return step1Done && isPowerPresetSelected();
    }

    private void showWorkflowStepIndicatorsOnly() {
        workflowStepIndicators.setVisibility(View.VISIBLE);
        tvReaderStatus.setVisibility(View.GONE);
    }

    private void showStatusTextOnly(String text, int color) {
        tvReaderStatus.setText(text);
        tvReaderStatus.setTextColor(color);
        tvReaderStatus.setVisibility(View.VISIBLE);
        workflowStepIndicators.setVisibility(View.GONE);
    }

    private void stopStatusAlternation() {
        statusAlternationActive = false;
        statusAlternationShowingMessage = false;
        if (statusAlternationTick != null) {
            ui.removeCallbacks(statusAlternationTick);
        }
    }

    private AlternatingStatus resolveAlternatingStatus() {
        if (!shouldShowWorkflowStepIndicators() || workflowRunning || scanDoneAwaitingConfirm) {
            return null;
        }
        return getWorkflowStepFailStatus();
    }

    private AlternatingStatus getWorkflowStepFailStatus() {
        for (int step = WF_STEP_LOCK; step >= WF_STEP_EPC; step--) {
            if (wfStepStates[step] != WF_STATE_FAIL) continue;
            switch (step) {
                case WF_STEP_EPC:
                    return new AlternatingStatus(
                            getString(R.string.epc_retry_status), COLOR_STATUS_ERROR);
                case WF_STEP_CSV:
                    return new AlternatingStatus("chyba CSV", COLOR_STATUS_ERROR);
                case WF_STEP_PWD:
                    return new AlternatingStatus("chyba hesla", COLOR_STATUS_ERROR);
                case WF_STEP_LOCK:
                    return new AlternatingStatus("chyba zamčení", COLOR_STATUS_ERROR);
                default:
                    break;
            }
        }
        return null;
    }

    private void scheduleStatusAlternationTick(long delayMs) {
        if (statusAlternationTick == null) {
            statusAlternationTick = () -> {
                if (!statusAlternationActive) return;
                AlternatingStatus alt = resolveAlternatingStatus();
                if (alt == null) {
                    stopStatusAlternation();
                    if (shouldShowWorkflowStepIndicators()) {
                        showWorkflowStepIndicatorsOnly();
                    }
                    return;
                }
                statusAlternationShowingMessage = !statusAlternationShowingMessage;
                if (statusAlternationShowingMessage) {
                    showStatusTextOnly(alt.text, alt.color);
                    scheduleStatusAlternationTick(STATUS_ALT_SHOW_MESSAGE_MS);
                } else {
                    showWorkflowStepIndicatorsOnly();
                    scheduleStatusAlternationTick(STATUS_ALT_SHOW_INDICATORS_MS);
                }
            };
        }
        ui.removeCallbacks(statusAlternationTick);
        ui.postDelayed(statusAlternationTick, delayMs);
    }

    private void startStatusAlternation(boolean showMessageFirst) {
        AlternatingStatus alt = resolveAlternatingStatus();
        if (alt == null) return;
        statusAlternationActive = true;
        statusAlternationShowingMessage = showMessageFirst;
        if (showMessageFirst) {
            showStatusTextOnly(alt.text, alt.color);
            scheduleStatusAlternationTick(STATUS_ALT_SHOW_MESSAGE_MS);
        } else {
            showWorkflowStepIndicatorsOnly();
            scheduleStatusAlternationTick(STATUS_ALT_SHOW_INDICATORS_MS);
        }
    }

    private void updateWorkflowStepIndicatorsVisibility() {
        if (!shouldShowWorkflowStepIndicators()) {
            stopStatusAlternation();
            workflowStepIndicators.setVisibility(View.GONE);
            return;
        }
        AlternatingStatus alt = resolveAlternatingStatus();
        if (alt != null) {
            if (!statusAlternationActive) {
                startStatusAlternation(true);
            }
            return;
        }
        stopStatusAlternation();
        showWorkflowStepIndicatorsOnly();
    }

    private void resetWorkflowStepIndicators() {
        for (int i = 0; i < wfStepStates.length; i++) {
            wfStepStates[i] = WF_STATE_PENDING;
            applyWorkflowStepCircle(i);
        }
    }

    private void setWorkflowStepState(int step, int state) {
        if (step < 0 || step >= wfStepStates.length) return;
        wfStepStates[step] = state;
        applyWorkflowStepCircle(step);
        updateWorkflowStepIndicatorsVisibility();
    }

    private void applyWorkflowStepCircle(int step) {
        int drawable;
        switch (wfStepStates[step]) {
            case WF_STATE_OK:
                drawable = R.drawable.wf_step_circle_done;
                break;
            case WF_STATE_FAIL:
                drawable = R.drawable.wf_step_circle_error;
                break;
            case WF_STATE_ACTIVE:
                drawable = R.drawable.wf_step_circle_active;
                break;
            default:
                drawable = R.drawable.wf_step_circle_pending;
                break;
        }
        wfStepViews[step].setBackgroundResource(drawable);
    }

    private boolean advanceWorkflowAfterEpcSuccess(String writtenEpc, String tid) {
        setWorkflowStepState(WF_STEP_EPC, WF_STATE_OK);
        if (cbAutoCsv.isChecked()) {
            setWorkflowStepState(WF_STEP_CSV, WF_STATE_ACTIVE);
            if (!saveRowToCsv(writtenEpc, tid)) {
                if (chainWorkflow) {
                    onWorkflowFailed(WF_STEP_CSV);
                } else {
                    setWorkflowStepState(WF_STEP_CSV, WF_STATE_FAIL);
                }
                return false;
            }
            setWorkflowStepState(WF_STEP_CSV, WF_STATE_OK);
            updateLastRecordPreview();
        } else {
            setWorkflowStepState(WF_STEP_CSV, WF_STATE_OK);
        }
        return true;
    }

    // ---------- indikátor kroků ----------

    private void updateStepIndicators() {
        boolean step1Warning = !step1Done;
        setStepCircle(step1Circle, step1Done, activeStep == 1 && !step1Warning,
                step1Warning, false, "1");
        boolean modeMissing = step1Done && !isPowerPresetSelected();
        setStepCircle(step2Circle, step2Done && !modeMissing, activeStep == 2 && !modeMissing,
                modeMissing, step2Failed, "2");
        setStepCircle(step3Circle, step3Done, activeStep == 3, false, false, "3");
        int muted = ContextCompat.getColor(this, R.color.text_muted);
        step1Label.setTextColor(step1Warning ? COLOR_STATUS_WARNING : muted);
        if (step2Failed) {
            step2Label.setTextColor(COLOR_STATUS_ERROR);
        } else if (modeMissing) {
            step2Label.setTextColor(COLOR_STATUS_WARNING);
        } else {
            step2Label.setTextColor(muted);
        }
    }

    private void setStepCircle(TextView circle, boolean done, boolean active,
            boolean warning, boolean failed, String number) {
        if (failed) {
            circle.setText(number);
            circle.setBackgroundResource(R.drawable.step_circle_error);
            circle.setTextColor(0xFFFFFFFF);
        } else if (warning) {
            circle.setText(number);
            circle.setBackgroundResource(R.drawable.step_circle_warning);
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
        if (tuduBoundaryMode) {
            step1Done = epc.tudu != null && !epc.tudu.isEmpty()
                    && tuduBoundaryVyhybkaLabel != null && !tuduBoundaryVyhybkaLabel.isEmpty();
        } else {
            step1Done = currentTudu != null && currentVyhybka != null
                    && epc.tudu != null && !epc.tudu.isEmpty();
        }
        updatePowerPresetUi();
        updateStepIndicators();
        if (!workflowRunning) {
            setActionStatusReady();
        }
    }

    private void updatePowerPresetUi() {
        boolean enabled = step1Done && !gpsLookupInFlight;
        boolean showPresets = step1Done || !gpsAutoSelection || tuduBoundaryMode;
        powerPresetGroup.setVisibility(showPresets ? View.VISIBLE : View.GONE);
        powerPresetGroup.setEnabled(enabled);
        for (int i = 0; i < powerPresetGroup.getChildCount(); i++) {
            powerPresetGroup.getChildAt(i).setEnabled(enabled);
        }
        if (!step1Done) {
            powerPresetInKoleji = null;
            powerPresetGroup.clearChecked();
            powerPresetGroup.setSelectionRequired(false);
        } else {
            if (powerPresetInKoleji == null) {
                int checkedId = powerPresetGroup.getCheckedButtonId();
                if (checkedId == R.id.btnPowerPresetKoleji) {
                    powerPresetInKoleji = true;
                } else if (checkedId == R.id.btnPowerPresetRuce) {
                    powerPresetInKoleji = false;
                }
            }
            if (powerPresetInKoleji != null) {
                int checkedId = powerPresetInKoleji
                        ? R.id.btnPowerPresetKoleji
                        : R.id.btnPowerPresetRuce;
                if (powerPresetGroup.getCheckedButtonId() != checkedId) {
                    powerPresetGroup.check(checkedId);
                }
                powerPresetGroup.setSelectionRequired(true);
            }
        }
    }

    private void updateSummary1() {
        updateSummaryVyhybkaLabel();
        String tuduPreview = epc.tudu == null || epc.tudu.isEmpty()
                ? "—" : Tudu.uduCode(epc.tudu);
        tvSummaryTudu.setText(tuduPreview);
        if (tuduBoundaryMode && tuduBoundaryVyhybkaLabel != null && !tuduBoundaryVyhybkaLabel.isEmpty()) {
            SpannableString vyhSpan = new SpannableString(tuduBoundaryVyhybkaLabel);
            applyVyhybkaAccent(vyhSpan, 0, tuduBoundaryVyhybkaLabel.length());
            tvSummaryVyhybka.setText(vyhSpan);
        } else if (epc.vyhybka > 0) {
            String vyhStr = vyhybkaDisplayLabel();
            SpannableString vyhSpan = new SpannableString(vyhStr);
            applyVyhybkaAccent(vyhSpan, 0, vyhStr.length());
            tvSummaryVyhybka.setText(vyhSpan);
        } else {
            tvSummaryVyhybka.setText("—");
        }
        if (epc.cast > 0) {
            if (tuduBoundaryMode || isTuduBoundaryCast(epc.cast)) {
                String current = String.valueOf(epc.cast);
                SpannableString span = new SpannableString(current);
                applyCastAccent(span, 0, current.length());
                tvSummaryCast.setText(span);
            } else {
                int total = castCountFor(currentVyhybka);
                if (currentVyhybka == null && epc.tudu != null && !epc.tudu.isEmpty() && epc.vyhybka > 0) {
                    int fromCsv = maxWrittenCastForVyhybka(epc.tudu, epc.vyhybka);
                    total = Math.max(total, Math.max(epc.cast, fromCsv));
                }
                String current = String.valueOf(epc.cast);
                String rest = "/" + total;
                SpannableString span = new SpannableString(current + rest);
                applyCastAccent(span, 0, current.length());
                int muted = ContextCompat.getColor(this, R.color.text_muted);
                span.setSpan(new ForegroundColorSpan(muted), current.length(), span.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvSummaryCast.setText(span);
            }
        } else {
            tvSummaryCast.setText("—");
        }
        updateCastHint();
    }

    private void updateLastRecordPreview() {
        CsvStore.Row last = csvStore != null ? csvStore.getLastRow() : null;
        if (last == null) {
            lastRecordBox.setVisibility(View.GONE);
            return;
        }

        int cast = parseInt(last.cast, 0);
        if (cast <= 0) {
            lastRecordBox.setVisibility(View.GONE);
            return;
        }
        if (!isTuduBoundaryCast(cast) && !hasLastRecordObjekt(last)) {
            lastRecordBox.setVisibility(View.GONE);
            return;
        }

        boolean boundaryRow = isTuduBoundaryCast(cast);
        String vyhPrefix = getString(boundaryRow || tuduBoundaryObjektLabels
                ? R.string.objekt_picker_prefix
                : R.string.vyhybka_picker_prefix);
        String castPrefix = getString(R.string.last_record_cast_prefix);
        int vyhybkaNum = parseInt(last.vyhybka, 0);
        String vyhStr = last.vyhybka != null && !last.vyhybka.isEmpty()
                ? last.vyhybka
                : Tudu.Vyhybka.formatDisplay(vyhybkaNum, "");

        SpannableString vyhSpan = new SpannableString(vyhPrefix + vyhStr);
        applyVyhybkaAccent(vyhSpan, vyhPrefix.length(), vyhSpan.length());
        tvLastRecordVyhybka.setText(vyhSpan);

        int total = castTotalForRow(last);
        String current = String.valueOf(cast);
        SpannableString castSpan;
        if (boundaryRow) {
            castSpan = new SpannableString(castPrefix + current);
            applyCastAccent(castSpan, castPrefix.length(), castSpan.length());
        } else {
            String rest = "/" + total;
            castSpan = new SpannableString(castPrefix + current + rest);
            int castValueStart = castPrefix.length();
            int castValueEnd = castValueStart + current.length();
            applyCastAccent(castSpan, castValueStart, castValueEnd);
            int muted = ContextCompat.getColor(this, R.color.text_muted);
            castSpan.setSpan(new ForegroundColorSpan(muted), castValueEnd, castSpan.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvLastRecordCast.setText(castSpan);

        lastRecordBox.setVisibility(View.VISIBLE);
    }

    private static boolean hasLastRecordObjekt(CsvStore.Row row) {
        if (row == null) return false;
        if (row.vyhybka != null && !row.vyhybka.trim().isEmpty()) return true;
        return parseInt(row.vyhybka, 0) > 0;
    }

    private int castCountFor(Tudu.Vyhybka v) {
        return v != null ? v.resolvedCastCount() : 3;
    }

    private Tudu.Vyhybka findVyhybkaForRow(CsvStore.Row row) {
        if (row == null || row.tudu == null || row.tudu.isEmpty()) return null;
        int cislo = parseInt(row.vyhybka, -1);
        if (cislo < 0) return null;
        String iob = parseVyhybkaIob(row.vyhybka);
        for (Tudu t : tuduList) {
            if (!t.code.equals(row.tudu)) continue;
            for (Tudu.Vyhybka v : t.vyhybky) {
                if (v.cislo != cislo) continue;
                if (iob.isEmpty() || v.iob.equals(iob)) return v;
            }
            for (Tudu.Vyhybka v : t.vyhybky) {
                if (v.cislo == cislo) return v;
            }
        }
        return null;
    }

    private static String parseVyhybkaIob(String vyhybkaLabel) {
        if (vyhybkaLabel == null) return "";
        String trimmed = vyhybkaLabel.trim();
        if (trimmed.length() < 2) return "";
        char last = Character.toUpperCase(trimmed.charAt(trimmed.length() - 1));
        if (Character.isDigit(last)) return "";
        return String.valueOf(last);
    }

    private int maxWrittenCastForVyhybka(String tuduCode, int cislo) {
        if (csvStore == null || tuduCode == null || tuduCode.isEmpty() || cislo < 0) return 0;
        int max = 0;
        for (int cast : csvStore.getWrittenCasts(tuduCode, cislo)) {
            if (cast > max) max = cast;
        }
        return max;
    }

    private int castTotalForRow(CsvStore.Row row) {
        if (row == null) return 3;
        int castInRow = parseInt(row.cast, 0);
        int fromCsv = maxWrittenCastForVyhybka(row.tudu, parseInt(row.vyhybka, -1));
        int fromPoloha = 0;
        Integer polohaMax = Tudu.Vyhybka.RoBranch.castMaxFromPoloha(row.poloha);
        if (polohaMax != null) fromPoloha = polohaMax;
        Tudu.Vyhybka v = findVyhybkaForRow(row);
        int fromVyhybka = castCountFor(v);
        return Math.max(Math.max(fromVyhybka, fromPoloha), Math.max(castInRow, fromCsv));
    }

    private void updateCastHint() {
        if (tuduBoundaryMode || currentVyhybka == null || epc.cast <= 0) {
            castHintBox.setVisibility(View.GONE);
            if (castBranchGroup != null) castBranchGroup.setVisibility(View.GONE);
            if (!workflowRunning) {
                updateWorkflowStepIndicatorsVisibility();
            }
            return;
        }
        int castCount = castCountFor(currentVyhybka);
        if (castCount != 3 && castCount != 4) {
            castHintBox.setVisibility(View.GONE);
            if (castBranchGroup != null) castBranchGroup.setVisibility(View.GONE);
            if (!workflowRunning) {
                updateWorkflowStepIndicatorsVisibility();
            }
            return;
        }
        ensureVyhybkaRoBranches(currentTudu != null ? currentTudu.code : null, currentVyhybka);
        boolean dualRo = isDualRoVyhybka(currentVyhybka);
        String partName = dualRo
                ? getString(R.string.cast_branch_select)
                : castPartName(epc.cast);
        if (partName == null) {
            castHintBox.setVisibility(View.GONE);
            if (castBranchGroup != null) castBranchGroup.setVisibility(View.GONE);
            if (!workflowRunning) {
                updateWorkflowStepIndicatorsVisibility();
            }
            return;
        }
        String prefix = getString(R.string.cast_hint_prefix);
        String chipLabel = getString(R.string.cast_hint_chip);
        String commaVyhybky = getString(R.string.cast_hint_comma_vyhybky);
        String castStr = String.valueOf(epc.cast);
        String vyhybkaStr = vyhybkaDisplayLabel();
        SpannableString span = new SpannableString(
                prefix + chipLabel + castStr + commaVyhybky + vyhybkaStr);

        int castStart = prefix.length() + chipLabel.length();
        int castEnd = castStart + castStr.length();
        applyCastAccent(span, castStart, castEnd);

        int vyhStart = castEnd + commaVyhybky.length();
        int vyhEnd = vyhStart + vyhybkaStr.length();
        applyVyhybkaAccent(span, vyhStart, vyhEnd);

        tvCastHintAction.setText(span);
        if (dualRo) {
            tvCastHintPart.setVisibility(View.GONE);
            castBranchGroup.setVisibility(View.VISIBLE);
            if (epc.cast != lastCastHintCast) {
                lastCastHintCast = epc.cast;
                if (!restoreCastPartFromCsv()) {
                    clearCastPartSelection();
                }
            } else if (isCastPartTypeSelected()) {
                applyCastPartType(selectedCastPartType);
            }
            updateCastPartButtonStates();
        } else {
            lastCastHintCast = -1;
            tvCastHintPart.setVisibility(View.VISIBLE);
            tvCastHintPart.setText(partName);
            castBranchGroup.setVisibility(View.GONE);
        }
        castHintBox.setVisibility(View.VISIBLE);
        if (!workflowRunning) {
            updateWorkflowStepIndicatorsVisibility();
        }
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
        String vyhPrefix = getVyhybkaLabelPrefix();
        String castPrefix = getString(R.string.scan_done_cast_prefix);
        String vyhStr;
        if (tuduBoundaryMode && tuduBoundaryVyhybkaLabel != null && !tuduBoundaryVyhybkaLabel.isEmpty()) {
            vyhStr = tuduBoundaryVyhybkaLabel;
        } else if (currentVyhybka != null && currentVyhybka.cislo == vyhybka) {
            vyhStr = currentVyhybka.displayLabel();
        } else {
            vyhStr = Tudu.Vyhybka.formatDisplay(vyhybka, "");
        }
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
            if (tuduBoundaryMode) {
                clearTuduBoundaryObjektLabels();
            }
            updateLastRecordPreview();
            step2Done = false;
            step2Failed = false;
            step3Done = false;
            resetWorkflowStepIndicators();
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
            resetWorkflowStepIndicators();
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
        if (isTuduBoundaryCast(cast)) {
            return getString(R.string.cast_part_5);
        }
        if (currentVyhybka != null && isFourPartVyhybka(currentVyhybka)) {
            return currentVyhybka.castFourPartLabel(cast);
        }
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
        stopStatusAlternation();
        showStatusTextOnly(text, color);
    }

    private void setActionStatusReady() {
        if (tuduBoundaryMode) {
            showGpsStatus = false;
            tvGpsStatus.setVisibility(View.INVISIBLE);
            if (!step1Done) {
                setActionStatus(getString(R.string.tudu_select_status), COLOR_STATUS_WARNING);
                updateStepIndicators();
                return;
            }
            if (!isPowerPresetSelected()) {
                setActionStatus(getString(R.string.power_preset_select_status), COLOR_STATUS_WARNING);
                updateStepIndicators();
                return;
            }
            updateWorkflowStepIndicatorsVisibility();
            updateStepIndicators();
            return;
        }
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
                stopStatusAlternation();
                tvReaderStatus.setVisibility(View.VISIBLE);
                workflowStepIndicators.setVisibility(View.GONE);
                refreshGpsStatus(false);
            } else {
                showGpsStatus = false;
                tvGpsStatus.setVisibility(View.INVISIBLE);
                setActionStatus(getString(R.string.tudu_select_status), COLOR_STATUS_WARNING);
            }
            return;
        }
        if (!isPowerPresetSelected()) {
            if (gpsAutoSelection) {
                showGpsStatus = true;
                refreshGpsStatus(false);
            } else {
                showGpsStatus = false;
                tvGpsStatus.setVisibility(View.INVISIBLE);
            }
            setActionStatus(getString(R.string.power_preset_select_status), COLOR_STATUS_WARNING);
            updateStepIndicators();
            return;
        }
        showGpsStatus = true;
        updateWorkflowStepIndicatorsVisibility();
        refreshGpsStatus();
    }

    private void refreshGpsStatus() {
        refreshGpsStatus(true);
    }

    private void refreshGpsStatus(boolean setReadyStatus) {
        if (!showGpsStatus || locationCache == null) {
            if (showGpsStatus) {
                tvGpsStatus.setVisibility(View.INVISIBLE);
            }
            return;
        }
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
        if (setReadyStatus && !shouldShowWorkflowStepIndicators()) {
            tvReaderStatus.setText(getString(R.string.status_ready));
            tvReaderStatus.setTextColor(COLOR_STATUS_READY);
        } else if (setReadyStatus) {
            updateWorkflowStepIndicatorsVisibility();
        }
    }

    private void setupLocation() {
        locationCache = new LocationCache(this);
        locationCache.setListener(() -> {
            ensureProximityFromGpsIfNeeded(() -> {
                scheduleGpsTuduLookup();
                if (!workflowRunning) {
                    setActionStatusReady();
                } else if (showGpsStatus) {
                    refreshGpsStatus();
                }
            });
        });
        ensureLocationPermission();
    }

    /**
     * Po otevření databáze bez GPS souřadnic index okolí 4 km ještě neexistuje.
     * Doplní ho podle aktuální (nebo testovací) polohy, pak zavolá {@code after}.
     */
    private void ensureProximityFromGpsIfNeeded(Runnable after) {
        if (dzsDatabase == null) {
            if (after != null) after.run();
            return;
        }
        if (dzsDatabase.isProximityIndexed()) {
            if (after != null) after.run();
            return;
        }
        if (locationCache == null) {
            if (after != null) after.run();
            return;
        }
        LocationCache.Snapshot snap = locationCache.getSnapshot();
        if (!snap.valid) {
            if (after != null) after.run();
            return;
        }
        if (proximityLoadInFlight) {
            if (after != null) after.run();
            return;
        }
        proximityLoadInFlight = true;
        final double lat = snap.latitude;
        final double lon = snap.longitude;
        gpsIo.execute(() -> {
            try {
                DzsDatabase db = dzsDatabase;
                if (db != null) {
                    db.ensureProximityLoaded(lat, lon);
                }
            } finally {
                proximityLoadInFlight = false;
                ui.post(() -> {
                    updateNearbyTuduCountInSourceLabel();
                    if (after != null) after.run();
                });
            }
        });
    }

    private void updateNearbyTuduCountInSourceLabel() {
        if (tvSourceFile == null || dzsDatabase == null || !gpsAutoSelection) return;
        CharSequence current = tvSourceFile.getText();
        if (current == null) return;
        String text = current.toString();
        int sep = text.indexOf("  •  UDU:");
        if (sep < 0) return;
        String displayName = text.substring(0, sep);
        int nearby = dzsDatabase.countDistinctTuduNearby();
        tvSourceFile.setText(getString(R.string.db_loaded_gps, displayName, nearby));
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
        if (requestCode == REQUEST_CSV_STORAGE_PERMISSION) {
            if (pendingCsvInit) {
                pendingCsvInit = false;
                initCsvStoreAsync();
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
        reloadCsvFromDiskIfChanged(false);
        publishCsvForMtpIfNeeded();
        ensureGpsForTuduLookup();
    }

    private void onWorkflowFailed(int failedStep) {
        workflowRunning = false;
        chainWorkflow = false;
        scanDoneAwaitingConfirm = false;
        activeStep = 2;
        step2Done = false;
        step2Failed = true;
        step3Done = false;
        if (failedStep >= 0 && failedStep < wfStepStates.length) {
            setWorkflowStepState(failedStep, WF_STATE_FAIL);
        }
        updateStepIndicators();
        updateWorkflowStepIndicatorsVisibility();
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
        if (!epcTemplateMode) {
            for (int i = 0; i < 7; i++) {
                ((TextView) rows[i].findViewById(R.id.tvHex)).setText("—");
            }
            tvEpcPreview.setText("----–----–----–----–----–----");
            tvEpcValid.setText(getString(R.string.epc_tid_mode_valid));
            tvEpcValid.setTextColor(0xFF2E7D32);
            return;
        }
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

    private void setupTemplateMode() {
        epcTemplateMode = prefs.getBoolean(PREF_EPC_TEMPLATE_MODE, false);
        if (templateModeGroup == null) return;
        templateModeGroup.check(epcTemplateMode ? R.id.btnTemplateOn : R.id.btnTemplateOff);
        updateTemplateModeUi();
        templateModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            onTemplateModeChanged(checkedId == R.id.btnTemplateOn);
        });
    }

    private void updateTemplateModeUi() {
        if (tvTemplateModeHint != null) {
            tvTemplateModeHint.setText(epcTemplateMode
                    ? getString(R.string.epc_template_mode_hint)
                    : getString(R.string.epc_tid_mode_hint));
        }
        if (templateRowsBox != null) {
            templateRowsBox.setVisibility(epcTemplateMode ? View.VISIBLE : View.GONE);
        }
        refreshHexAndPreview();
    }

    private void onTemplateModeChanged(boolean templateOn) {
        if (epcTemplateMode == templateOn) return;
        epcTemplateMode = templateOn;
        prefs.edit().putBoolean(PREF_EPC_TEMPLATE_MODE, templateOn).apply();
        updateTemplateModeUi();
    }

    // ---------- CSV ----------

    private void setupCsv() {
        tvCsvPath.setText(getString(R.string.csv_path_hint, CsvStorage.displayPath()));

        csvAdapter = new CsvAdapter();
        RecyclerView rv = findViewById(R.id.rvCsv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);
        rv.setAdapter(csvAdapter);

        if (needsCsvWritePermission()) {
            pendingCsvInit = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CSV_STORAGE_PERMISSION);
            return;
        }
        initCsvStoreAsync();
        maybePromptForAllFilesAccess();
    }

    /** Android 11+ – bez „přístupu ke všem souborům“ zůstává CSV jen v MediaStore (MTP ho neukáže). */
    private void maybePromptForAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        if (CsvStorage.canDirectWritePublicDownload()) return;
        if (prefs.getBoolean(PREF_STORAGE_ACCESS_PROMPTED, false)) return;
        prefs.edit().putBoolean(PREF_STORAGE_ACCESS_PROMPTED, true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.storage_access_title)
                .setMessage(R.string.storage_access_message)
                .setPositiveButton(R.string.storage_access_open, (d, w) -> openAllFilesAccessSettings())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openAllFilesAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void publishCsvForMtpIfNeeded() {
        if (csvStore == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        File file = csvStore.getFile();
        io.execute(() -> CsvStorage.publishForMtp(MainActivity.this, file, null));
    }

    private boolean needsCsvWritePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void initCsvStoreAsync() {
        File out = CsvStorage.resolveFile(this);
        io.execute(() -> {
            CsvStore loaded = new CsvStore(MainActivity.this, out);
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
        epc.idRfid = Math.max(DEFAULT_ID_RFID, csvStore.getMaxIdRfid() + 1);
        prefs.edit().putLong("idRfid", epc.idRfid).apply();
        syncCurrentVyhybka();
        if (tuduBoundaryMode) {
            lastCastHintCast = -1;
        } else if (currentVyhybka != null) {
            advanceCastAndVyhybka();
        } else {
            pendingAdvanceFromCsv = true;
        }
        refreshTemplate();
        updateStep1();
        updateSummary1();
        resetTagWorkflow();

        skipCsvTuduRestore = true;
        updateLastRecordPreview();
        if (dzsDatabase != null && gpsAutoSelection && !gpsTuduLocked && !gpsVyhybkaLocked) {
            ensureGpsForTuduLookup();
        }
        resyncCastRangeFromPersistedState();
    }

    /** Po načtení CSV/DB doplní rozsah částí výhybky a obnoví ukazatele čipů. */
    private void resyncCastRangeFromPersistedState() {
        if (csvStore == null || csvStore.size() == 0) return;
        CsvStore.Row last = csvStore.getLastRow();
        if (last == null) return;

        if (currentTudu == null && last.tudu != null) {
            for (Tudu t : tuduList) {
                if (t.code.equals(last.tudu)) {
                    currentTudu = t;
                    break;
                }
            }
        }
        if (currentTudu != null && dzsDatabase != null) {
            List<Tudu> loaded = dzsDatabase.loadTuduForUdu(currentTudu.uduCode());
            for (Tudu t : loaded) {
                mergeTuduIntoList(t);
            }
            if (currentTudu.code.equals(last.tudu)) {
                syncCurrentVyhybkaAfterReload();
            }
        }
        Tudu.Vyhybka rowVyhybka = findVyhybkaForRow(last);
        if (rowVyhybka != null) {
            rowVyhybka.ensureCastAtLeast(parseInt(last.cast, 0));
            rowVyhybka.applyCastRangeFromPoloha(last.poloha);
            if (currentTudu != null) {
                ensureVyhybkaRoBranches(currentTudu.code, rowVyhybka);
            }
            rowVyhybka.reconcileCastRangeFromBranches();
        }
        if (currentVyhybka == null && rowVyhybka != null) {
            currentVyhybka = rowVyhybka;
        }
        if (currentVyhybka != null && currentTudu != null) {
            ensureVyhybkaRoBranches(currentTudu.code, currentVyhybka);
            currentVyhybka.reconcileCastRangeFromBranches();
        }
        updateSummary1();
        updateLastRecordPreview();
        if (!workflowRunning) {
            updateStep1();
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

    private void reloadCsvFromDiskIfChanged(boolean showToast) {
        if (csvStore == null) return;
        io.execute(() -> {
            boolean changed = csvStore.reloadIfChanged();
            if (!changed) return;
            ui.post(() -> applyReloadedCsvState(showToast));
        });
    }

    private void applyReloadedCsvState(boolean showToast) {
        refreshCsvTable();
        if (csvStore.size() > 0) {
            restoreStateFromLoadedCsv();
        }
        if (showToast) {
            toast(getString(R.string.csv_reloaded_toast));
        }
    }

    private void persistCsvRowAsync(CsvStore.Row row) {
        if (csvStore == null) return;
        csvStore.upsert(row);
        refreshCsvTable();
        io.execute(() -> {
            try {
                csvStore.upsertAndPersist(row);
                ui.post(this::refreshCsvTable);
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
        findViewById(R.id.btnTuduBoundary).setOnClickListener(v -> showTuduBoundaryForm());

        powerPresetGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            onPowerPresetSelected(checkedId == R.id.btnPowerPresetKoleji);
        });
        findViewById(R.id.btnApplyPower).setOnClickListener(v -> applyPower());
        findViewById(R.id.btnWrite).setOnClickListener(v -> doWrite());
        findViewById(R.id.btnWritePwd).setOnClickListener(v -> doWritePassword());
        findViewById(R.id.btnLock).setOnClickListener(v -> doLock());
        findViewById(R.id.btnExportCsv).setOnClickListener(v -> exportCsv());
        findViewById(R.id.btnImportCsv).setOnClickListener(v -> pickCsvFile());
        findViewById(R.id.btnClearCsv).setOnClickListener(v -> showDeleteConfirmDialog());
        findViewById(R.id.btnDeleteLastRecord).setOnClickListener(v -> showDeleteConfirmDialog());
        findViewById(R.id.btnScanDoneContinue).setOnClickListener(v -> onScanDoneContinue());
        findViewById(R.id.btnScanDoneRetry).setOnClickListener(v -> onScanDoneRetry());
        findViewById(R.id.btnDeleteConfirmYes).setOnClickListener(v -> onDeleteConfirmYes());
        findViewById(R.id.btnDeleteConfirmNo).setOnClickListener(v -> onDeleteConfirmNo());
        findViewById(R.id.btnKontrola).setOnClickListener(v -> showKontrolaOverlay());
        findViewById(R.id.btnKontrolaClose).setOnClickListener(v -> hideKontrolaOverlay());
        findViewById(R.id.btnKontrolaMatchPrev).setOnClickListener(v -> showPreviousKontrolaMatch());
        findViewById(R.id.btnKontrolaMatchNext).setOnClickListener(v -> showNextKontrolaMatch());
    }

    private void setupCastBranchSelection() {
        castBranchGroup = findViewById(R.id.castBranchGroup);
        btnCastJazyk = findViewById(R.id.btnCastJazyk);
        btnCastHlavni = findViewById(R.id.btnCastHlavni);
        btnCastVedlejsi = findViewById(R.id.btnCastVedlejsi);
        if (castBranchGroup == null) return;
        castBranchGroup.setSelectionRequired(false);
        castBranchGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnCastJazyk) {
                selectedCastPartType = CastPartType.JAZYK;
            } else if (checkedId == R.id.btnCastHlavni) {
                selectedCastPartType = CastPartType.HLAVNI;
            } else if (checkedId == R.id.btnCastVedlejsi) {
                selectedCastPartType = CastPartType.VEDLEJSI;
            }
            if (!workflowRunning) {
                updateWorkflowStepIndicatorsVisibility();
            }
        });
    }

    private void resetCastBranchSelection() {
        selectedCastPartType = CastPartType.NONE;
        lastCastHintCast = -1;
        clearCastPartSelection();
    }

    private void clearCastPartSelection() {
        selectedCastPartType = CastPartType.NONE;
        if (castBranchGroup != null) {
            castBranchGroup.clearChecked();
        }
    }

    private boolean requiresCastBranchSelection() {
        return !tuduBoundaryMode && currentVyhybka != null && isDualRoVyhybka(currentVyhybka);
    }

    private boolean isCastPartTypeSelected() {
        return selectedCastPartType != CastPartType.NONE;
    }

    private boolean isCastBranchSelected() {
        return isCastPartTypeSelected();
    }

    private boolean requireCastBranchSelection() {
        if (!requiresCastBranchSelection() || isCastPartTypeSelected()) return true;
        promptCastBranchSelection();
        return false;
    }

    private void applyCastPartType(CastPartType type) {
        selectedCastPartType = type;
        if (castBranchGroup == null || type == CastPartType.NONE) {
            clearCastPartSelection();
            return;
        }
        int checkedId;
        switch (type) {
            case JAZYK:
                checkedId = R.id.btnCastJazyk;
                break;
            case HLAVNI:
                checkedId = R.id.btnCastHlavni;
                break;
            case VEDLEJSI:
                checkedId = R.id.btnCastVedlejsi;
                break;
            default:
                clearCastPartSelection();
                return;
        }
        if (castBranchGroup.getCheckedButtonId() != checkedId) {
            castBranchGroup.check(checkedId);
        }
    }

    /** Typy částí už zapsané u jiných čipů stejné výhybky (aktuální čip se nepočítá). */
    private Set<CastPartType> getUsedCastPartTypes(int excludeCast) {
        Set<CastPartType> used = new HashSet<>();
        if (csvStore == null || currentTudu == null || currentVyhybka == null) {
            return used;
        }
        for (CsvStore.Row row : csvStore.getRows()) {
            if (!currentTudu.code.equals(row.tudu)) continue;
            if (parseInt(row.vyhybka, -1) != currentVyhybka.cislo) continue;
            int cast = parseInt(row.cast, -1);
            if (cast == excludeCast) continue;
            CastPartType type = castPartTypeFromRow(row);
            if (type != CastPartType.NONE) used.add(type);
        }
        return used;
    }

    private void updateCastPartButtonStates() {
        if (!isDualRoVyhybka(currentVyhybka)) return;
        Set<CastPartType> used = getUsedCastPartTypes(epc.cast);
        if (btnCastJazyk != null) {
            btnCastJazyk.setEnabled(!used.contains(CastPartType.JAZYK));
        }
        if (btnCastHlavni != null) {
            btnCastHlavni.setEnabled(!used.contains(CastPartType.HLAVNI));
        }
        if (btnCastVedlejsi != null) {
            btnCastVedlejsi.setEnabled(!used.contains(CastPartType.VEDLEJSI));
        }
        if (isCastPartTypeSelected() && !isCastPartTypeAvailable(selectedCastPartType)) {
            clearCastPartSelection();
        }
    }

    private boolean isCastPartTypeAvailable(CastPartType type) {
        if (type == CastPartType.NONE) return false;
        Set<CastPartType> used = getUsedCastPartTypes(epc.cast);
        return !used.contains(type);
    }

    private CastPartType castPartTypeFromRow(CsvStore.Row row) {
        if (row == null) return CastPartType.NONE;
        boolean has1 = row.roId1 != null && !row.roId1.isEmpty();
        boolean has2 = row.roId2 != null && !row.roId2.isEmpty();
        if (has1 && has2) return CastPartType.JAZYK;
        if (has1) return CastPartType.HLAVNI;
        if (has2) return CastPartType.VEDLEJSI;
        if (row.poloha != null && !row.poloha.isEmpty()) {
            if (Tudu.Vyhybka.RoBranch.isHlavniPoloha(row.poloha)) return CastPartType.HLAVNI;
            if (Tudu.Vyhybka.RoBranch.isVedlejsiPoloha(row.poloha)) return CastPartType.VEDLEJSI;
        }
        return CastPartType.NONE;
    }

    private boolean restoreCastPartFromCsv() {
        if (csvStore == null || currentTudu == null || currentVyhybka == null
                || !isDualRoVyhybka(currentVyhybka)) {
            return false;
        }
        CsvStore.Row row = csvStore.findRowForCast(
                currentTudu.code, currentVyhybka.cislo, epc.cast);
        if (row == null) return false;
        CastPartType type = castPartTypeFromRow(row);
        if (type == CastPartType.NONE) return false;
        applyCastPartType(type);
        return true;
    }

    private void applyCastPartFromRow(CsvStore.Row row) {
        if (row == null || castBranchGroup == null || currentVyhybka == null
                || !isDualRoVyhybka(currentVyhybka)) {
            return;
        }
        CastPartType type = castPartTypeFromRow(row);
        if (type == CastPartType.NONE) return;
        applyCastPartType(type);
    }

    private boolean isDualRoVyhybka(Tudu.Vyhybka v) {
        return v != null && castCountFor(v) == 3 && v.hasDualRoBranches();
    }

    private boolean isFourPartVyhybka(Tudu.Vyhybka v) {
        return v != null && v.isFourPart();
    }

    private void ensureVyhybkaRoBranches(String tuduCode, Tudu.Vyhybka v) {
        if (tuduCode == null || tuduCode.isEmpty() || v == null) {
            return;
        }
        if (dzsDatabase == null) {
            v.reconcileCastRangeFromBranches();
            return;
        }
        if (!v.getRoBranches().isEmpty()) {
            int castCount = castCountFor(v);
            if (castCount == 3 && !v.hasDualRoBranches()) {
                // 3částová s jednou větví – zkusit doplnit druhou z DB
            } else if (castCount == 4 && !v.hasFourPartRoBranches()) {
                // 4částová – doplnit zápisové páry CA/CB nebo CG/CH z DB
            } else {
                v.reconcileCastRangeFromBranches();
                return;
            }
        }
        List<Tudu.Vyhybka.RoBranch> loaded = dzsDatabase.findRoBranchesForVyhybka(
                tuduCode, v.cislo, v.iob);
        for (Tudu.Vyhybka.RoBranch branch : loaded) {
            v.addRoBranch(branch.roId, branch.poloha, branch.kmExtChip1, branch.kmExtOther);
        }
        if (castCountFor(v) == 4 && !v.hasFourPartRoBranches()) {
            List<Tudu.Vyhybka.RoBranch> fromSql = dzsDatabase.queryRoBranchesForVyhybka(
                    tuduCode, v.cislo, v.iob);
            for (Tudu.Vyhybka.RoBranch branch : fromSql) {
                v.addRoBranch(branch.roId, branch.poloha, branch.kmExtChip1, branch.kmExtOther);
            }
        }
        v.reconcileCastRangeFromBranches();
    }

    private Tudu.Vyhybka.RoBranch resolveBranchForCast(int cast) {
        if (currentVyhybka == null || currentTudu == null) return null;
        ensureVyhybkaRoBranches(currentTudu.code, currentVyhybka);
        if (isFourPartVyhybka(currentVyhybka)) {
            return currentVyhybka.branchForFourPartCsv(cast);
        }
        List<Tudu.Vyhybka.RoBranch> branches = currentVyhybka.getRoBranches();
        if (branches.isEmpty()) return null;
        if (!isDualRoVyhybka(currentVyhybka)) {
            return branches.get(0);
        }
        if (!isCastPartTypeSelected()) return null;
        if (selectedCastPartType == CastPartType.JAZYK) return null;
        if (selectedCastPartType == CastPartType.HLAVNI) {
            Tudu.Vyhybka.RoBranch hlavni = currentVyhybka.findHlavniBranch();
            if (hlavni != null) return hlavni;
            for (Tudu.Vyhybka.RoBranch b : branches) {
                if (!b.isVedlejsi()) return b;
            }
        } else if (selectedCastPartType == CastPartType.VEDLEJSI) {
            Tudu.Vyhybka.RoBranch vedlejsi = currentVyhybka.findVedlejsiBranch();
            if (vedlejsi != null) return vedlejsi;
            for (Tudu.Vyhybka.RoBranch b : branches) {
                if (b.isVedlejsi()) return b;
            }
        }
        return branches.get(0);
    }

    private boolean hasCastWrittenOnAnyBranch(String tuduCode, Tudu.Vyhybka v, int cast) {
        if (csvStore == null) return false;
        for (Tudu.Vyhybka.RoBranch branch : v.getRoBranches()) {
            if (csvStore.hasWrittenCast(tuduCode, v.cislo, branch.roId, cast)) return true;
        }
        return csvStore.getWrittenCasts(tuduCode, v.cislo).contains(cast);
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
        int sep = text.indexOf("  •  UDU:");
        if (sep < 0) return;
        String displayName = text.substring(0, sep);
        int count = tuduListFullyLoaded ? countDistinctUduInList() : dzsDatabase.countDistinctTudu();
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
        ensureProximityFromGpsIfNeeded(() -> io.execute(() -> {
            List<DzsDatabase.GpsPoint> points = dzsDatabase != null
                    ? dzsDatabase.listGpsPoints() : Collections.emptyList();
            ui.post(() -> {
                if (points.isEmpty()) {
                    toast(getString(R.string.gps_test_no_gps_point));
                    return;
                }
                showTestLocationPickerDialog(points);
            });
        }));
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
        clearTuduBoundaryObjektLabels();
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

    private final androidx.activity.result.ActivityResultLauncher<Intent> csvPicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) importCsvFromUri(uri);
                        }
                    });

    private void pickSourceFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        picker.launch(i);
    }

    private void tryAutoLoadDefaultDatabase() {
        String savedName = prefs.getString(PREF_DB_DISPLAY_NAME, DEFAULT_DB_NAME);
        beginCard1DbLoad(savedName);
        // MediaStore (Android 10+) funguje bez READ_EXTERNAL_STORAGE – oprávnění jen pro přímý scan disku.
        if (isFreshInstallDbState() && needsStoragePermission()
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            pendingAutoLoadAfterStorage = true;
            ui.post(this::requestStoragePermissionIfNeeded);
        }
        io.execute(() -> {
            String savedPath = prefs.getString(PREF_DB_SOURCE_PATH, null);
            if (savedPath != null) {
                File saved = new File(savedPath);
                if (saved.isFile() && saved.canRead()) {
                    loadDatabaseFromPath(savedPath, savedName, false);
                    return;
                }
            }
            File dzsDir = getDzsStorageDir();
            for (String internalName : new String[]{"dzs_source.db", "dzs_auto_source.db"}) {
                File internal = new File(dzsDir, internalName);
                if (internal.isFile() && internal.canRead()) {
                    String display = "dzs_auto_source.db".equals(internalName)
                            ? DEFAULT_DB_NAME
                            : (savedName != null ? savedName : internal.getName());
                    loadDatabaseFromPath(internal.getAbsolutePath(), display, false);
                    return;
                }
            }
            AutoDiscoveredDb discovered = findDefaultDatabase();
            if (discovered != null) {
                loadDatabaseFromPath(
                        discovered.localFile.getAbsolutePath(),
                        discovered.displayName,
                        false,
                        discovered.contentUri);
                return;
            }
            String uriStr = prefs.getString(PREF_DB_URI, null);
            if (uriStr != null && tryLoadFromPersistedUri(Uri.parse(uriStr))) {
                return;
            }
            if (needsStoragePermission() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
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
                if (isFreshInstallDbState()) {
                    toast(getString(R.string.db_fresh_install_hint));
                }
            });
        });
    }

    /** Po přeinstalaci aplikace nejsou uložené cesty ani URI – spoléháme na Stažené soubory. */
    private boolean isFreshInstallDbState() {
        return prefs.getString(PREF_DB_SOURCE_PATH, null) == null
                && prefs.getString(PREF_DB_URI, null) == null;
    }

    private static boolean isSqliteDatabaseFileName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sqlite") || lower.endsWith(".sqlite3") || lower.endsWith(".db");
    }

    private static boolean isDefaultDatabaseFileName(String name) {
        return name != null && DEFAULT_DB_NAME.equalsIgnoreCase(name);
    }

    /** Preferované jméno souboru, případně jiná SQLite s DZS / PASPORT v názvu. */
    private static int scoreDatabaseFileName(String name) {
        if (isDefaultDatabaseFileName(name)) return 100;
        if (name == null || !isSqliteDatabaseFileName(name)) return 0;
        String lower = name.toLowerCase(Locale.ROOT);
        int score = 10;
        if (lower.contains("dzs") && lower.contains("pasport")) score = 80;
        else if (lower.contains("dzs") || lower.contains("pasport")) score = 50;
        return score;
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

    /**
     * Hledá výchozí SQLite databázi – po přeinstalaci aplikace spoléhá na soubor ve Stažených.
     * Na Androidu 10+ je MediaStore první (funguje bez oprávnění ke čtení úložiště).
     */
    private AutoDiscoveredDb findDefaultDatabase() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AutoDiscoveredDb fromMediaStore = discoverDefaultDatabaseFromMediaStore();
            if (fromMediaStore != null) return fromMediaStore;
        }
        File fromFilesystem = findDefaultDatabaseOnFilesystem();
        if (fromFilesystem != null) {
            return new AutoDiscoveredDb(fromFilesystem, null, fromFilesystem.getName());
        }
        return null;
    }

    private File findDefaultDatabaseOnFilesystem() {
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
        File best = null;
        int bestScore = 0;
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File exact = new File(dir, DEFAULT_DB_NAME);
            if (exact.isFile() && exact.canRead()) return exact;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File candidate : files) {
                if (!candidate.isFile() || !candidate.canRead()) continue;
                int score = scoreDatabaseFileName(candidate.getName());
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return bestScore > 0 ? best : null;
    }

    /** Android 10+ – Stažené soubory přes MediaStore (funguje i bez READ_EXTERNAL_STORAGE). */
    private AutoDiscoveredDb discoverDefaultDatabaseFromMediaStore() {
        Uri uri = findDefaultDatabaseUriViaMediaStore();
        if (uri == null) return null;
        try {
            String displayName = queryName(uri);
            File local = copyUriToCache(uri, "dzs_auto_source.db", false);
            return new AutoDiscoveredDb(local, uri, displayName);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Znovu načte databázi z trvale uložené URI (po restartu aplikace). */
    private boolean tryLoadFromPersistedUri(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        try {
            String name = queryName(uri);
            File dbFile = copyUriToCache(uri, "dzs_source.db", true);
            loadDatabaseFromPath(dbFile.getAbsolutePath(), name, false);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Auto-načtení z uložené URI selhalo", e);
            return false;
        }
    }

    private void persistDbSource(String path, String displayName, Uri uri) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(PREF_DB_SOURCE_PATH, path)
                .putString(PREF_DB_DISPLAY_NAME, displayName);
        if (uri != null) {
            editor.putString(PREF_DB_URI, uri.toString());
        } else {
            editor.remove(PREF_DB_URI);
        }
        editor.apply();
    }

    private Uri findDefaultDatabaseUriViaMediaStore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        Uri exact = findDatabaseUriInDownloadsByName(DEFAULT_DB_NAME);
        if (exact != null) return exact;
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = { MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME };
        Uri bestUri = null;
        int bestScore = 0;
        long bestModified = 0;
        String selection = MediaStore.Downloads.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.Downloads.DISPLAY_NAME + " LIKE ? OR "
                + MediaStore.Downloads.DISPLAY_NAME + " LIKE ?";
        String[] args = {"%.sqlite", "%.sqlite3", "%.db"};
        String sort = MediaStore.Downloads.DATE_MODIFIED + " DESC";
        try (Cursor c = getContentResolver().query(collection, projection, selection, args, sort)) {
            if (c == null) return null;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
            while (c.moveToNext()) {
                String name = c.getString(nameCol);
                int score = scoreDatabaseFileName(name);
                if (score <= 0) continue;
                long modified = 0;
                int modCol = c.getColumnIndex(MediaStore.Downloads.DATE_MODIFIED);
                if (modCol >= 0 && !c.isNull(modCol)) {
                    modified = c.getLong(modCol);
                }
                if (score > bestScore || (score == bestScore && modified > bestModified)) {
                    bestScore = score;
                    bestModified = modified;
                    bestUri = ContentUris.withAppendedId(collection, c.getLong(idCol));
                    if (score >= 100) break;
                }
            }
        } catch (Exception ignored) { }
        return bestUri;
    }

    private Uri findDatabaseUriInDownloadsByName(String displayName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || displayName == null) return null;
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
        String[] args = {displayName};
        try (Cursor c = getContentResolver().query(collection,
                new String[]{MediaStore.Downloads._ID},
                selection, args, MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (c == null || !c.moveToFirst()) return null;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            return ContentUris.withAppendedId(collection, c.getLong(idCol));
        } catch (Exception ignored) {
            return null;
        }
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
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        io.execute(() -> {
            try {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".db") && !lower.endsWith(".sqlite") && !lower.endsWith(".sqlite3")) {
                    throw new Exception(fileTypeError);
                }
                File dbFile = copyUriToCache(uri, "dzs_source.db", true);
                loadDatabaseFromPath(dbFile.getAbsolutePath(), name, true, uri);
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
        loadDatabaseFromPath(path, displayName, showErrorToast, null);
    }

    private void loadDatabaseFromPath(String path, String displayName, boolean showErrorToast, Uri sourceUri) {
        final long loadId = ++dbLoadGeneration;
        runOnUiThreadIfAlive(loadId, () -> {
            beginCard1DbLoad(displayName);
            hideWorkflowDbIndexCard();
        });
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
            boolean manualMode = !prefs.getBoolean(PREF_TUDU_MODE_GPS, true);
            Double initLat = null;
            Double initLon = null;
            if (!manualMode) {
                final CountDownLatch uiReady = new CountDownLatch(1);
                runOnUiThreadIfAlive(loadId, () -> {
                    ensureGpsForTuduLookup();
                    updateCard1DbProgress(getString(R.string.gps_db_wait), 5, true);
                    uiReady.countDown();
                });
                uiReady.await(3, TimeUnit.SECONDS);
                long gpsWaitMs = showErrorToast
                        ? GPS_DB_LOAD_MAX_WAIT_MANUAL_MS : GPS_DB_LOAD_MAX_WAIT_AUTO_MS;
                LocationCache.Snapshot snap = waitForGpsFix(loadId, gpsWaitMs);
                if (loadId != dbLoadGeneration) return;
                if (snap.valid) {
                    initLat = snap.latitude;
                    initLon = snap.longitude;
                    runOnUiThreadIfAlive(loadId, () ->
                            updateCard1DbProgress(getString(R.string.gps_db_indexing), 15, true));
                } else {
                    runOnUiThreadIfAlive(loadId, () ->
                            updateCard1DbProgress(getString(R.string.gps_db_open_without_fix), 15, true));
                }
            } else if (locationCache != null) {
                LocationCache.Snapshot snap = locationCache.getSnapshot();
                if (snap.valid) {
                    initLat = snap.latitude;
                    initLon = snap.longitude;
                }
            }
            DzsDatabase opened = DzsDatabase.open(path, getDzsStorageDir(), progress, initLat, initLon);
            if (loadId != dbLoadGeneration) {
                opened.close();
                return;
            }
            List<Tudu> loaded = new ArrayList<>();
            if (loadId != dbLoadGeneration) {
                opened.close();
                return;
            }
            final boolean autoMode = !manualMode;
            final DzsDatabase dbForCount = opened;
            runOnUiThreadIfAlive(loadId, () -> {
                dzsDatabase = opened;
                gpsAutoSelection = autoMode;
                tuduListFullyLoaded = false;
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
                tvSourceFile.setText(autoMode
                        ? getString(R.string.db_loaded_gps, displayName, 0)
                        : getString(R.string.db_loaded_manual, displayName, 0));
                pendingAutoLoadAfterStorage = false;
                persistDbSource(path, displayName, sourceUri);
                endCard1DbLoad();
                collapseCard1Body();
                scrollToCard1();
                onDatabaseLoaded();
            });
            if (!autoMode) {
                Thread countThread = new Thread(() -> {
                    int tuduCount = dbForCount.countDistinctTudu();
                    runOnUiThreadIfAlive(loadId, () -> {
                        if (dzsDatabase != dbForCount) return;
                        tvSourceFile.setText(getString(R.string.db_loaded_manual, displayName, tuduCount));
                    });
                }, "dzs-tudu-count");
                countThread.setDaemon(true);
                countThread.start();
            } else {
                runOnUiThreadIfAlive(loadId, () -> {
                    if (dzsDatabase != dbForCount) return;
                    int nearby = dbForCount.countDistinctTuduNearby();
                    tvSourceFile.setText(getString(R.string.db_loaded_gps, displayName, nearby));
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Načtení databáze přerušeno", e);
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

    private LocationCache.Snapshot waitForGpsFix(long loadId, long maxWaitMs) throws InterruptedException {
        long deadline = maxWaitMs > 0
                ? SystemClock.elapsedRealtime() + maxWaitMs
                : Long.MAX_VALUE;
        while (loadId == dbLoadGeneration) {
            if (locationCache != null) {
                LocationCache.Snapshot snap = locationCache.getSnapshot();
                if (snap.valid) return snap;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return LocationCache.Snapshot.empty();
            }
            Thread.sleep(GPS_DB_LOAD_POLL_MS);
        }
        return LocationCache.Snapshot.empty();
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
        attachDbIndexProgressListener(dzsDatabase);
        if (skipCsvTuduRestore) {
            skipCsvTuduRestore = false;
        }
        resyncCastRangeFromPersistedState();
        Runnable afterProximity = () -> {
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
            if (dzsDatabase != null && dzsDatabase.isProximityIndexed() && !dzsDatabase.hasProximityData()) {
                toast("V okolí GPS nebyla nalezena výhybka – zkuste ruční výběr UDU");
            } else if (!step1Done && locationCache != null && !locationCache.hasFix()) {
                if (gpsTestMode) {
                    if (!locationCache.hasTestOverride()) {
                        toast(getString(R.string.gps_test_enabled_toast));
                    }
                } else {
                    toast(getString(R.string.gps_tudu_wait));
                }
            }
        };
        ensureProximityFromGpsIfNeeded(afterProximity);
    }

    private void scheduleGpsTuduLookup() {
        if (!gpsAutoSelection || dzsDatabase == null || locationCache == null || gpsLookupInFlight
                || tuduBoundaryMode) {
            return;
        }
        if (!dzsDatabase.isProximityIndexed()) {
            ensureProximityFromGpsIfNeeded(this::scheduleGpsTuduLookupNow);
            return;
        }
        scheduleGpsTuduLookupNow();
    }

    private void scheduleGpsTuduLookupNow() {
        if (!gpsAutoSelection || dzsDatabase == null || locationCache == null || gpsLookupInFlight
                || tuduBoundaryMode) {
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
        exitTuduBoundaryMode();
        boolean uduChanged = epc.tudu == null || epc.tudu.isEmpty()
                || !Tudu.uduCode(match.tudu).equals(Tudu.uduCode(epc.tudu));

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
        if (!match.roId.isEmpty()) {
            v.addRoBranch(match.roId, match.poloha);
        }
        ensureVyhybkaRoBranches(tudu.code, v);
        if (gpsAutoSelection && isVyhybkaCompleteInCsv(tudu.code, v)) {
            Tudu.Vyhybka nearestIncomplete = nearestIncompleteVyhybkaByGps(tudu);
            if (nearestIncomplete != null) {
                v = nearestIncomplete;
            }
        }
        boolean vyhybkaChanged = epc.vyhybka != v.cislo;
        currentVyhybka = v;
        epc.vyhybka = v.cislo;
        if (vyhybkaChanged) {
            resetCastBranchSelection();
        }
        if (uduChanged || vyhybkaChanged || epc.cast <= 0
                || epc.cast < v.resolvedCastMin() || epc.cast > v.resolvedCastMax()) {
            epc.cast = firstMissingCast(tudu.code, v);
        }
        refreshTemplate();
        updateStep1();
        updateSummary1();
        updateLastRecordPreview();
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
        if (!targetIndex.exists()) {
            copyDirectory(legacyIndex, targetIndex);
        } else {
            mergeMissingCacheFiles(legacyIndex, targetIndex);
        }
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

    /** Přesune chybějící .idx a hash soubory ze starého cache adresáře. */
    private static void mergeMissingCacheFiles(File legacyDir, File targetDir) {
        if (!legacyDir.isDirectory()) return;
        if (!targetDir.exists() && !targetDir.mkdirs()) return;
        File[] children = legacyDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isFile()) continue;
            String name = child.getName();
            if (!name.endsWith(".idx") && !name.endsWith(".pidx") && !name.startsWith("hash")) continue;
            File dest = new File(targetDir, name);
            if (!dest.exists()) {
                child.renameTo(dest);
            }
        }
    }

    private File copyUriToCache(Uri uri, String fileName, boolean forceCopy) throws Exception {
        File out = new File(getDzsStorageDir(), fileName);
        if (!forceCopy) {
            long expectedSize = querySize(uri);
            long expectedModified = queryLastModified(uri);
            if (expectedSize > 0 && out.isFile() && out.length() == expectedSize
                    && (expectedModified <= 0 || out.lastModified() >= expectedModified)) {
                return out;
            }
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

    private long queryLastModified(Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try (Cursor c = getContentResolver().query(uri,
                    new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                    null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) {
                    return c.getLong(0);
                }
            } catch (Exception ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try (Cursor c = getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DATE_MODIFIED}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) {
                    return c.getLong(0) * 1000L;
                }
            } catch (Exception ignored) {
            }
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
                    if (epc.cast > v.resolvedCastMax() || isVyhybkaCompleteInCsv(t.code, v)) {
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
        Tudu.Vyhybka first = gpsAutoSelection
                ? nearestIncompleteVyhybkaByGps(t)
                : null;
        if (first == null) {
            first = firstAvailableVyhybka(t);
        }
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
        exitTuduBoundaryMode();
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
        exitTuduBoundaryMode();
        boolean vyhybkaChanged = currentVyhybka == null || currentVyhybka.cislo != v.cislo;
        if (currentTudu != null) {
            ensureVyhybkaRoBranches(currentTudu.code, v);
        }
        currentVyhybka = v;
        epc.vyhybka = v.cislo;
        if (vyhybkaChanged) {
            resetCastBranchSelection();
        }
        if (resetCast) {
            epc.cast = currentTudu != null
                    ? firstMissingCast(currentTudu.code, v)
                    : v.castMin;
        }
        refreshTemplate();
        updateStep1();
        updateSummary1();
    }

    /** Po smazání posledního řádku nastaví další čip k zápisu podle zbývajícího CSV. */
    private void restoreStateAfterCsvRemoval() {
        if (csvStore == null || csvStore.size() == 0) {
            exitTuduBoundaryMode();
            resetCastBranchSelection();
            updateStep1();
            updateSummary1();
            resetTagWorkflow();
            return;
        }
        CsvStore.Row last = csvStore.getLastRow();
        if (last == null) return;
        applyRowToEpc(last);
        epc.idRfid = Math.max(DEFAULT_ID_RFID, csvStore.getMaxIdRfid() + 1);
        prefs.edit().putLong("idRfid", epc.idRfid).apply();
        syncCurrentVyhybka();
        if (tuduBoundaryMode) {
            lastCastHintCast = -1;
        } else if (currentVyhybka != null) {
            lastCastHintCast = -1;
            advanceCastAndVyhybka();
        } else {
            pendingAdvanceFromCsv = true;
        }
        refreshTemplate();
        updateStep1();
        updateSummary1();
        resetTagWorkflow();
        resyncCastRangeFromPersistedState();
        if (!tuduBoundaryMode && gpsAutoSelection && dzsDatabase != null) {
            forceNextGpsLookup = true;
            lastGpsLookupLat = null;
            lastGpsLookupLon = null;
            lastGpsLookupTimeMs = 0;
            ensureGpsForTuduLookup();
        }
    }

    private void applyRowToEpc(CsvStore.Row row) {
        epc.tudu = row.tudu;
        epc.vyhybka = parseInt(row.vyhybka, epc.vyhybka);
        epc.cast = parseInt(row.cast, epc.cast);
        epc.idRfid = parseLong(row.idRfid, epc.idRfid);

        if (isTuduBoundaryCast(epc.cast)) {
            restoreTuduBoundaryFromRow(row);
            return;
        }
        exitTuduBoundaryMode();

        currentTudu = row.tudu != null && !row.tudu.isEmpty()
                ? resolveTuduForUdu(Tudu.uduCode(row.tudu))
                : null;
        currentVyhybka = null;
        if (currentTudu != null) {
            for (Tudu.Vyhybka v : currentTudu.vyhybky) {
                if (v.cislo == epc.vyhybka) {
                    currentVyhybka = v;
                    break;
                }
            }
        }
        if (currentTudu != null && currentVyhybka != null) {
            currentVyhybka.ensureCastAtLeast(epc.cast);
            currentVyhybka.applyCastRangeFromPoloha(row.poloha);
            List<String> roIds = CsvStore.rowRoIds(row);
            boolean hasRoIds = !(roIds.size() == 1 && roIds.get(0).isEmpty());
            if (hasRoIds) {
                if (!row.roId1.isEmpty()) {
                    currentVyhybka.addRoBranch(row.roId1,
                            row.poloha != null ? row.poloha : "");
                }
                if (!row.roId2.isEmpty()) {
                    currentVyhybka.addRoBranch(row.roId2, "");
                }
            } else {
                ensureVyhybkaRoBranches(currentTudu.code, currentVyhybka);
            }
            if (isDualRoVyhybka(currentVyhybka)) {
                applyCastPartFromRow(row);
            }
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
        restoreStateAfterCsvRemoval();
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
            if (chainWorkflow) onWorkflowFailed(-1);
            return;
        }
        if (!requireCastBranchSelection()) {
            if (chainWorkflow) onWorkflowFailed(-1);
            return;
        }
        if (epcTemplateMode && !epc.isValid()) {
            toast("EPC není validní");
            if (chainWorkflow) onWorkflowFailed(-1);
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            if (chainWorkflow) onWorkflowFailed(-1);
            return;
        }
        if (!chainWorkflow) {
            setWorkflowStepState(WF_STEP_EPC, WF_STATE_ACTIVE);
            updateWorkflowStepIndicatorsVisibility();
        }
        final String pwd = etAccessPwd.getText().toString().trim();
        tvWriteResult.setText("Zapisuji…");
        tvWriteResult.setTextColor(0xFF5F6A76);

        if (epcTemplateMode) {
            final String newEpc = epc.buildEpc();
            io.execute(() -> {
                UhfManager.WriteResult r = uhf.writeEpc(pwd, newEpc);
                ui.post(() -> onWriteDone(r, newEpc));
            });
        } else {
            io.execute(() -> {
                UhfManager.WriteResult r = uhf.writeEpcFromTid(pwd);
                String written = r.writtenEpc != null ? r.writtenEpc : "";
                ui.post(() -> onWriteDone(r, written));
            });
        }
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

            if (chainWorkflow) {
                if (!advanceWorkflowAfterEpcSuccess(writtenEpc, r.tid)) return;
                setWorkflowStepState(WF_STEP_PWD, WF_STATE_ACTIVE);
                doWritePassword();
            } else {
                setWorkflowStepState(WF_STEP_EPC, WF_STATE_OK);
                if (cbAutoCsv.isChecked()) {
                    setWorkflowStepState(WF_STEP_CSV, WF_STATE_ACTIVE);
                    if (!saveRowToCsv(writtenEpc, r.tid)) {
                        setWorkflowStepState(WF_STEP_CSV, WF_STATE_FAIL);
                    } else {
                        setWorkflowStepState(WF_STEP_CSV, WF_STATE_OK);
                    }
                } else {
                    setWorkflowStepState(WF_STEP_CSV, WF_STATE_OK);
                }
                onTagCycleComplete();
                setActionStatusReady();
            }
        } else {
            tvWriteResult.setTextColor(0xFFC62828);
            tvWriteResult.setText("✗ " + r.message);
            if (chainWorkflow) onWorkflowFailed(WF_STEP_EPC);
            else {
                setWorkflowStepState(WF_STEP_EPC, WF_STATE_FAIL);
                updateWorkflowStepIndicatorsVisibility();
            }
        }
    }

    // ---------- zápis access hesla ----------

    private void doWritePassword() {
        if (scanDoneAwaitingConfirm) return;
        if (!requirePowerPreset()) {
            if (chainWorkflow) onWorkflowFailed(-1);
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            if (chainWorkflow) onWorkflowFailed(-1);
            return;
        }
        final String accessPwd = etPwdAccess.getText().toString().trim();
        final String newPwd = etPwdNew.getText().toString().trim();
        if (!newPwd.matches("[0-9A-Fa-f]{8}")) {
            toast("NEW PWD musí mít 8 hex znaků");
            if (chainWorkflow) onWorkflowFailed(WF_STEP_PWD);
            return;
        }
        if (!chainWorkflow) {
            setWorkflowStepState(WF_STEP_PWD, WF_STATE_ACTIVE);
            updateWorkflowStepIndicatorsVisibility();
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
                setWorkflowStepState(WF_STEP_PWD, WF_STATE_OK);
                setWorkflowStepState(WF_STEP_LOCK, WF_STATE_ACTIVE);
                doLock();
            } else {
                setWorkflowStepState(WF_STEP_PWD, WF_STATE_OK);
                setActionStatusReady();
            }
        } else {
            tvPwdWriteResult.setTextColor(0xFFC62828);
            tvPwdWriteResult.setText("✗ " + r.message);
            if (chainWorkflow) onWorkflowFailed(WF_STEP_PWD);
            else {
                setWorkflowStepState(WF_STEP_PWD, WF_STATE_FAIL);
                updateWorkflowStepIndicatorsVisibility();
            }
        }
    }

    // ---------- zamčení tagu ----------

    private void doLock() {
        if (scanDoneAwaitingConfirm) return;
        if (!requirePowerPreset()) {
            if (chainWorkflow) onWorkflowFailed(-1);
            return;
        }
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            if (chainWorkflow) onWorkflowFailed(-1);
            return;
        }
        if (!chainWorkflow) {
            setWorkflowStepState(WF_STEP_LOCK, WF_STATE_ACTIVE);
            updateWorkflowStepIndicatorsVisibility();
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
                setWorkflowStepState(WF_STEP_LOCK, WF_STATE_OK);
                updateStepIndicators();
                updateWorkflowStepIndicatorsVisibility();
                showScanDoneNotification(epc.vyhybka, epc.cast);
            } else {
                step2Done = true;
                scanDoneAwaitingConfirm = true;
                setWorkflowStepState(WF_STEP_LOCK, WF_STATE_OK);
                updateStepIndicators();
                setActionStatusReady();
                showScanDoneNotification(epc.vyhybka, epc.cast);
            }
        } else {
            tvLockResult.setTextColor(0xFFC62828);
            tvLockResult.setText("✗ " + r.message);
            if (chainWorkflow) onWorkflowFailed(WF_STEP_LOCK);
            else {
                setWorkflowStepState(WF_STEP_LOCK, WF_STATE_FAIL);
                updateWorkflowStepIndicatorsVisibility();
            }
        }
    }

    private boolean saveRowToCsv(String epc24, String tid) {
        if (csvStore == null) return false;
        if (tuduBoundaryMode) {
            return saveTuduBoundaryRowToCsv(epc24, tid);
        }
        try {
            int cast = epc.cast;
            ensureVyhybkaRoBranches(currentTudu.code, currentVyhybka);
            lastChip1WriteCount = 1;

            boolean dualRo = isDualRoVyhybka(currentVyhybka);
            Tudu.Vyhybka.RoBranch branch = dualRo && selectedCastPartType == CastPartType.JAZYK
                    ? null : resolveBranchForCast(cast);
            if (dualRo && !isCastPartTypeSelected()) {
                return false;
            }
            if (isFourPartVyhybka(currentVyhybka) && branch == null) {
                branch = currentVyhybka.branchForFourPartCsv(cast);
            }
            if (isFourPartVyhybka(currentVyhybka) && branch == null) {
                return false;
            }
            if (!dualRo && cast >= 2 && branch == null) {
                return false;
            }
            if (dualRo && selectedCastPartType == CastPartType.HLAVNI
                    && currentVyhybka.findHlavniBranch() == null) {
                return false;
            }
            if (dualRo && selectedCastPartType == CastPartType.VEDLEJSI
                    && currentVyhybka.findVedlejsiBranch() == null) {
                return false;
            }
            CsvStore.Row row = buildCsvRow(epc24, tid, branch);
            persistCsvRowAsync(row);
            return true;
        } catch (Exception e) {
            toast("CSV: " + e.getMessage());
            return false;
        }
    }

    private boolean saveTuduBoundaryRowToCsv(String epc24, String tid) {
        try {
            LocationCache.Snapshot gps = locationCache != null
                    ? locationCache.getSnapshot() : LocationCache.Snapshot.empty();
            String latitude = "";
            String longitude = "";
            String accuracyM = "";
            String gpsTime = "";
            if (gps.valid) {
                latitude = LocationCache.formatLatitude(gps.latitude);
                longitude = LocationCache.formatLongitude(gps.longitude);
                accuracyM = LocationCache.formatAccuracyM(gps.accuracyM);
                gpsTime = LocationCache.formatGpsDate(gps.gpsTimeMs);
            } else if (!gpsUnavailableToastShown) {
                gpsUnavailableToastShown = true;
                toast(getString(R.string.gps_unavailable_toast));
            }
            CsvStore.Row row = CsvRecordBuilder.build(
                    epc.idRfid,
                    epc24,
                    tid,
                    epc.tudu,
                    tuduBoundaryVyhybkaLabel,
                    CAST_TUDU_BOUNDARY,
                    "",
                    "",
                    "",
                    tuduBoundaryKmExt != null ? tuduBoundaryKmExt : "",
                    latitude,
                    longitude,
                    accuracyM,
                    gpsTime);
            persistCsvRowAsync(row);
            return true;
        } catch (Exception e) {
            toast("CSV: " + e.getMessage());
            return false;
        }
    }

    /** Sestaví řádek CSV z provozního stavu – nezávisle na rozložení šablony EPC. */
    private CsvStore.Row buildCsvRow(String epc24, String tid, Tudu.Vyhybka.RoBranch branch) {
        LocationCache.Snapshot gps = locationCache != null
                ? locationCache.getSnapshot() : LocationCache.Snapshot.empty();
        String latitude = "";
        String longitude = "";
        String accuracyM = "";
        String gpsTime = "";
        if (gps.valid) {
            latitude = LocationCache.formatLatitude(gps.latitude);
            longitude = LocationCache.formatLongitude(gps.longitude);
            accuracyM = LocationCache.formatAccuracyM(gps.accuracyM);
            gpsTime = LocationCache.formatGpsDate(gps.gpsTimeMs);
        } else if (!gpsUnavailableToastShown) {
            gpsUnavailableToastShown = true;
            toast(getString(R.string.gps_unavailable_toast));
        }
        RoKmColumns roKm = resolveRoKmColumns(epc.cast, branch);
        String poloha = branch != null ? branch.poloha : "";
        if (currentVyhybka != null && isFourPartVyhybka(currentVyhybka)) {
            String label = currentVyhybka.castFourPartLabel(epc.cast);
            if (label != null) poloha = label;
        }
        return CsvRecordBuilder.build(
                epc.idRfid,
                epc24,
                tid,
                epc.tudu,
                csvVyhybkaLabel(String.valueOf(epc.vyhybka)),
                epc.cast,
                poloha,
                roKm.roId1,
                roKm.roId2,
                roKm.kmExt,
                latitude,
                longitude,
                accuracyM,
                gpsTime);
    }

    private static final class RoKmColumns {
        final String roId1;
        final String roId2;
        final String kmExt;

        RoKmColumns(String roId1, String roId2, String kmExt) {
            this.roId1 = roId1 != null ? roId1 : "";
            this.roId2 = roId2 != null ? roId2 : "";
            this.kmExt = kmExt != null ? kmExt : "";
        }
    }

    /**
     * RO_ID_1 / RO_ID_2:
     * jazyk u dvojvětvé výhybky vyplní oba sloupce (hlavní JAx/JCx, vedlejší JBx/JDx),
     * hlavní jen RO_ID_1, vedlejší jen RO_ID_2 – nezávisle na pořadí čipu.
     * 4částová: čipy 1–2 = RO_ID_1 z CA nebo CG, čipy 3–4 = RO_ID_2 z CB nebo CH.
     * KM_EXT: jazyk = KM_REF, hlavní/vedlejší = druhá hodnota z OD/DO.
     */
    private RoKmColumns resolveRoKmColumns(int cast, Tudu.Vyhybka.RoBranch branch) {
        if (currentVyhybka == null) {
            return new RoKmColumns("", "", "");
        }
        if (isFourPartVyhybka(currentVyhybka)) {
            String roId1 = "";
            String roId2 = "";
            if (cast <= 2 && branch != null) {
                roId1 = branch.roId;
            } else if (cast >= 3 && branch != null) {
                roId2 = branch.roId;
            }
            return new RoKmColumns(roId1, roId2, "");
        }
        Tudu.Vyhybka.RoBranch hlavni = currentVyhybka.findHlavniBranch();
        Tudu.Vyhybka.RoBranch vedlejsi = currentVyhybka.findVedlejsiBranch();
        String roId1 = "";
        String roId2 = "";
        if (isDualRoVyhybka(currentVyhybka)) {
            if (selectedCastPartType == CastPartType.JAZYK) {
                roId1 = hlavni != null ? hlavni.roId : "";
                roId2 = vedlejsi != null ? vedlejsi.roId : "";
            } else if (branch != null) {
                if (branch.isHlavni()) {
                    roId1 = branch.roId;
                } else if (branch.isVedlejsi()) {
                    roId2 = branch.roId;
                } else {
                    roId1 = branch.roId;
                }
            }
            return new RoKmColumns(roId1, roId2, resolveKmExtForDualRo(selectedCastPartType, branch));
        }
        if (branch != null) {
            if (branch.isHlavni()) {
                roId1 = branch.roId;
            } else if (branch.isVedlejsi()) {
                roId2 = branch.roId;
            } else {
                roId1 = branch.roId;
            }
        }
        return new RoKmColumns(roId1, roId2, resolveKmExtForCast(cast, branch));
    }

    /** KM_EXT z OD/DO/KM_REF: čip 1 = KM_REF, čipy 2–3 = druhá hodnota podle RO_ID. U 4částové zatím prázdné. */
    private String resolveKmExtForCast(int cast, Tudu.Vyhybka.RoBranch branch) {
        if (currentVyhybka != null && isFourPartVyhybka(currentVyhybka)) {
            return "";
        }
        if (branch == null) return "";
        if (cast == 1) return branch.kmExtChip1;
        return branch.kmExtOther.isEmpty() ? branch.kmExtChip1 : branch.kmExtOther;
    }

    /** KM_EXT pro dvojvětvou 3částovou výhybku podle zvoleného typu části. */
    private String resolveKmExtForDualRo(CastPartType partType, Tudu.Vyhybka.RoBranch branch) {
        if (partType == CastPartType.JAZYK) {
            return sharedKmExtChip1(currentVyhybka.getRoBranches());
        }
        if (branch == null) return "";
        return branch.kmExtOther.isEmpty() ? branch.kmExtChip1 : branch.kmExtOther;
    }

    /** U čipu 1 je KM_REF pro obě větve stejný – stačí jedna hodnota. */
    private static String sharedKmExtChip1(List<Tudu.Vyhybka.RoBranch> branches) {
        for (Tudu.Vyhybka.RoBranch b : branches) {
            if (!b.kmExtChip1.isEmpty()) return b.kmExtChip1.trim();
        }
        return "";
    }

    /** Po dokončení zápisu tagu (EPC samostatně, nebo celý řetězec EPC→heslo→lock). */
    private void onTagCycleComplete() {
        maybeClearGpsVyhybkaLock();
        epc.idRfid += Math.max(1, lastChip1WriteCount);
        lastChip1WriteCount = 1;
        prefs.edit().putLong("idRfid", epc.idRfid).apply();
        if (!tuduBoundaryMode) {
            advanceCastAndVyhybka();
        }
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
        if (tuduBoundaryMode) return;
        syncCurrentVyhybka();
        if (currentVyhybka != null) {
            int next = epc.cast + 1;
            if (next > currentVyhybka.resolvedCastMax()) {
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
        if (currentTudu == null) return;
        syncCurrentVyhybka();
        int currentCislo = currentVyhybka != null ? currentVyhybka.cislo : epc.vyhybka;

        if (gpsAutoSelection) {
            Tudu.Vyhybka next = nearestIncompleteVyhybkaByGps(currentTudu);
            if (next != null && next.cislo != currentCislo) {
                selectVyhybka(next, true);
                gpsVyhybkaLocked = true;
                return;
            }
            if (next == null) {
                toast("Poslední výhybka v UDU – cyklus dokončen.");
                return;
            }
        }

        if (currentTudu.vyhybky.isEmpty()) return;
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
        toast("Poslední výhybka v UDU – cyklus dokončen.");
    }

    /** V GPS režimu vrátí nejbližší nedokončenou výhybku v rámci TUDU (podle vzdálenosti). */
    private Tudu.Vyhybka nearestIncompleteVyhybkaByGps(Tudu tudu) {
        if (tudu == null || !gpsAutoSelection || locationCache == null
                || !locationCache.getSnapshot().valid || dzsDatabase == null) {
            return null;
        }
        LocationCache.Snapshot snap = locationCache.getSnapshot();
        Map<Integer, Double> distances;
        try {
            distances = dzsDatabase.findVyhybkaDistancesForTudu(
                    tudu.code, snap.latitude, snap.longitude);
        } catch (Exception e) {
            Log.w(TAG, "Vzdálenosti výhybek selhaly", e);
            return null;
        }
        if (distances == null || distances.isEmpty()) return null;

        List<Integer> sortedCisla = new ArrayList<>(distances.keySet());
        sortedCisla.sort((a, b) -> {
            int cmp = Double.compare(distances.get(a), distances.get(b));
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        for (int cislo : sortedCisla) {
            Tudu.Vyhybka v = tudu.findOrCreate(cislo);
            if (!isVyhybkaCompleteInCsv(tudu.code, v)) {
                return v;
            }
        }
        return null;
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
        if (csvStore == null) {
            return castCountFor(v);
        }
        ensureVyhybkaRoBranches(tuduCode, v);
        if (isDualRoVyhybka(v)) {
            int missing = 0;
            if (!hasCastWrittenOnAnyBranch(tuduCode, v, 1)) missing++;
            if (!hasCastWrittenOnAnyBranch(tuduCode, v, 2)) missing++;
            if (!hasCastWrittenOnAnyBranch(tuduCode, v, 3)) missing++;
            return missing;
        }
        Set<Integer> written = getWrittenCastsForVyhybka(tuduCode, v);
        int missing = 0;
        for (int c = v.resolvedCastMin(); c <= v.resolvedCastMax(); c++) {
            if (!written.contains(c)) missing++;
        }
        return missing;
    }

    private int countWrittenCasts(String tuduCode, Tudu.Vyhybka v) {
        Set<Integer> written = getWrittenCastsForVyhybka(tuduCode, v);
        int count = 0;
        for (int c = v.resolvedCastMin(); c <= v.resolvedCastMax(); c++) {
            if (written.contains(c)) count++;
        }
        return count;
    }

    private boolean isVyhybkaPartialInCsv(String tuduCode, Tudu.Vyhybka v) {
        int written = countWrittenCasts(tuduCode, v);
        return written > 0 && written < castCountFor(v);
    }

    private String vyhybkaDisplayLabel() {
        if (currentVyhybka != null) return currentVyhybka.displayLabel();
        if (epc.vyhybka > 0) return Tudu.Vyhybka.formatDisplay(epc.vyhybka, "");
        return "";
    }

    private String csvVyhybkaLabel(String epcVyhybka) {
        int cislo = parseInt(epcVyhybka, -1);
        if (currentVyhybka != null && currentVyhybka.cislo == cislo) {
            return currentVyhybka.displayLabel();
        }
        return epcVyhybka;
    }

    private CharSequence formatVyhybkaPickerLabel(String tuduCode, Tudu.Vyhybka v) {
        return formatVyhybkaPickerLabel(tuduCode, v, null, false);
    }

    private CharSequence formatVyhybkaPickerLabel(String tuduCode, Tudu.Vyhybka v, Double distanceM) {
        return formatVyhybkaPickerLabel(tuduCode, v, distanceM, distanceM != null);
    }

    private CharSequence formatVyhybkaPickerLabel(String tuduCode, Tudu.Vyhybka v,
                                                   Double distanceM, boolean showDistanceHints) {
        CharSequence base = formatVyhybkaPickerLabelCore(tuduCode, v);
        if (!showDistanceHints) return base;
        if (distanceM != null) {
            return appendVyhybkaLabelSuffix(base, " · " + formatDistanceM(distanceM));
        }
        return appendVyhybkaLabelSuffix(base, " · " + getString(R.string.vyhybka_picker_no_gps));
    }

    private CharSequence appendVyhybkaLabelSuffix(CharSequence base, String suffix) {
        String full = base.toString() + suffix;
        if (!(base instanceof SpannableString)) {
            return full;
        }
        SpannableString orig = (SpannableString) base;
        SpannableString span = new SpannableString(full);
        TextUtils.copySpansFrom(orig, 0, orig.length(), null, span, 0);
        return span;
    }

    private CharSequence formatVyhybkaPickerLabelCore(String tuduCode, Tudu.Vyhybka v) {
        String prefix = getVyhybkaLabelPrefix();
        String cisloStr = v.displayLabel();
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
        ensureVyhybkaRoBranches(tuduCode, v);
        if (csvStore != null && isDualRoVyhybka(v)) {
            if (!hasCastWrittenOnAnyBranch(tuduCode, v, 1)) return 1;
            if (!hasCastWrittenOnAnyBranch(tuduCode, v, 2)) return 2;
            if (!hasCastWrittenOnAnyBranch(tuduCode, v, 3)) return 3;
            return v.resolvedCastMax() + 1;
        }
        Set<Integer> written = getWrittenCastsForVyhybka(tuduCode, v);
        for (int c = v.resolvedCastMin(); c <= v.resolvedCastMax(); c++) {
            if (!written.contains(c)) return c;
        }
        return v.castMin;
    }

    private Set<Integer> getWrittenCastsForVyhybka(String tuduCode, Tudu.Vyhybka v) {
        if (csvStore == null) return Collections.emptySet();
        return csvStore.getWrittenCasts(tuduCode, v.cislo);
    }

    private void pickCsvFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        csvPicker.launch(i);
    }

    private void importCsvFromUri(Uri uri) {
        io.execute(() -> {
            try {
                File dest = CsvStorage.resolveFile(this);
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new Exception("Soubor nelze otevřít");
                    CsvStorage.copyTo(this, in);
                }
                CsvStore loaded = new CsvStore(MainActivity.this, dest);
                ui.post(() -> {
                    csvStore = loaded;
                    applyReloadedCsvState(true);
                });
            } catch (Exception e) {
                ui.post(() -> toast("Import CSV: " + e.getMessage()));
            }
        });
    }

    // ---------- export CSV ----------

    private void exportCsv() {
        if (csvStore == null) {
            toast("Tabulka se ještě načítá");
            return;
        }
        try {
            File f = csvStore.getFile();
            if (!CsvStorage.isPresent(this, f) || csvStore.size() == 0) {
                toast("Tabulka je prázdná");
                return;
            }
            Uri uri;
            if (f.isFile()) {
                uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", f);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                uri = CsvStorage.getMediaStoreUri(this);
                if (uri == null) {
                    toast("Soubor CSV není dostupný na disku");
                    return;
                }
            } else {
                toast("Export selhal: soubor nenalezen");
                return;
            }
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
        if (kontrolaActive) {
            runKontrolaRead();
            return;
        }
        if (workflowRunning || scanDoneAwaitingConfirm || deleteConfirmDialog.getVisibility() == View.VISIBLE) return;
        if (!requirePowerPreset()) return;
        if (epcTemplateMode && !epc.isValid()) {
            toast("EPC není validní");
            return;
        }
        if (!requireCastBranchSelection()) return;
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            return;
        }
        final String newPwd = etPwdNew.getText().toString().trim();
        if (!newPwd.matches("[0-9A-Fa-f]{8}")) {
            toast("NEW PWD musí mít 8 hex znaků");
            return;
        }
        refreshGpsAtWorkflowStart();
        chainWorkflow = true;
        workflowRunning = true;
        activeStep = 2;
        step2Done = false;
        step2Failed = false;
        step3Done = false;
        resetWorkflowStepIndicators();
        updateStepIndicators();
        setWorkflowStepState(WF_STEP_EPC, WF_STATE_ACTIVE);
        updateWorkflowStepIndicatorsVisibility();
        doWrite();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() == 0) {
            for (int k : TRIGGER_KEYS) {
                if (k == keyCode) {
                    if (kontrolaActive) {
                        runKontrolaRead();
                    } else if (scanDoneAwaitingConfirm) {
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

    private void setupBackPressedHandler() {
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (kontrolaActive) {
                    hideKontrolaOverlay();
                } else if (deleteConfirmDialog.getVisibility() == View.VISIBLE) {
                    onDeleteConfirmNo();
                } else if (scanDoneAwaitingConfirm) {
                    onScanDoneContinue();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
    }

    private void setupKontrola() {
        resetKontrolaDisplay();
    }

    private void showKontrolaOverlay() {
        if (kontrolaActive) return;
        kontrolaActive = true;
        kontrolaReading = false;
        resetKontrolaDisplay();
        kontrolaOverlay.setVisibility(View.VISIBLE);
        if (backPressedCallback != null) {
            backPressedCallback.setEnabled(true);
        }
        if (!uhf.isReady()) {
            initReaderAsync();
        }
    }

    private void hideKontrolaOverlay() {
        if (!kontrolaActive) return;
        kontrolaActive = false;
        kontrolaReading = false;
        kontrolaOverlay.setVisibility(View.GONE);
        resetKontrolaDisplay();
        if (backPressedCallback != null) {
            backPressedCallback.setEnabled(
                    deleteConfirmDialog.getVisibility() == View.VISIBLE || scanDoneAwaitingConfirm);
        }
    }

    private void resetKontrolaDisplay() {
        if (tvKontrolaPrompt == null) return;
        kontrolaMatchRows = Collections.emptyList();
        kontrolaMatchIndex = 0;
        tvKontrolaPrompt.setVisibility(View.VISIBLE);
        tvKontrolaPrompt.setText(R.string.kontrola_scan_prompt);
        tvKontrolaStatus.setVisibility(View.GONE);
        tvKontrolaHeader.setVisibility(View.GONE);
        if (kontrolaMatchNav != null) kontrolaMatchNav.setVisibility(View.GONE);
        kontrolaCellsContainer.removeAllViews();
    }

    private void runKontrolaRead() {
        if (!kontrolaActive || kontrolaReading) return;
        if (!requirePowerPreset()) return;
        if (!uhf.isReady()) {
            toast("Čtečka není připravena");
            return;
        }
        kontrolaReading = true;
        tvKontrolaPrompt.setText(R.string.kontrola_reading);
        tvKontrolaStatus.setVisibility(View.GONE);
        tvKontrolaHeader.setVisibility(View.GONE);
        if (kontrolaMatchNav != null) kontrolaMatchNav.setVisibility(View.GONE);
        kontrolaCellsContainer.removeAllViews();
        io.execute(() -> {
            UHFTAGInfo info = uhf.readSingle();
            ui.post(() -> onKontrolaReadDone(info));
        });
    }

    private void onKontrolaReadDone(UHFTAGInfo info) {
        kontrolaReading = false;
        if (!kontrolaActive) return;
        if (info == null) {
            tvKontrolaPrompt.setText(R.string.kontrola_no_tag);
            tvKontrolaStatus.setVisibility(View.GONE);
            return;
        }
        String epcHex = info.getEPC();
        String tidHex = info.getTid();
        if (csvStore == null) {
            tvKontrolaPrompt.setText(R.string.kontrola_not_in_csv);
            return;
        }
        csvStore.reloadIfChanged();
        List<CsvStore.Row> matches = csvStore.findAllRowsByTag(epcHex, tidHex);
        if (matches.isEmpty()) {
            tvKontrolaPrompt.setText(R.string.kontrola_not_in_csv);
            tvKontrolaStatus.setVisibility(View.VISIBLE);
            StringBuilder detail = new StringBuilder();
            if (epcHex != null && !epcHex.isEmpty()) detail.append("EPC ").append(epcHex);
            if (tidHex != null && !tidHex.isEmpty()) {
                if (detail.length() > 0) detail.append("\n");
                detail.append("TID ").append(tidHex);
            }
            tvKontrolaStatus.setText(detail.toString());
            return;
        }
        kontrolaMatchRows = matches;
        kontrolaMatchIndex = 0;
        showKontrolaMatchPage();
    }

    private void showKontrolaMatchPage() {
        if (kontrolaMatchRows.isEmpty()) return;
        CsvStore.Row matched = kontrolaMatchRows.get(kontrolaMatchIndex);
        updateKontrolaMatchNav();
        displayKontrolaResults(matched);
    }

    private void updateKontrolaMatchNav() {
        if (kontrolaMatchNav == null || tvKontrolaMatchIndex == null) return;
        int count = kontrolaMatchRows.size();
        if (count <= 1) {
            kontrolaMatchNav.setVisibility(View.GONE);
            return;
        }
        kontrolaMatchNav.setVisibility(View.VISIBLE);
        tvKontrolaMatchIndex.setText(
                getString(R.string.kontrola_match_index, kontrolaMatchIndex + 1, count));
        View prev = findViewById(R.id.btnKontrolaMatchPrev);
        View next = findViewById(R.id.btnKontrolaMatchNext);
        if (prev != null) prev.setEnabled(kontrolaMatchIndex > 0);
        if (next != null) next.setEnabled(kontrolaMatchIndex < count - 1);
    }

    private void showPreviousKontrolaMatch() {
        if (kontrolaMatchIndex <= 0) return;
        kontrolaMatchIndex--;
        showKontrolaMatchPage();
    }

    private void showNextKontrolaMatch() {
        if (kontrolaMatchIndex >= kontrolaMatchRows.size() - 1) return;
        kontrolaMatchIndex++;
        showKontrolaMatchPage();
    }

    private void displayKontrolaResults(CsvStore.Row matched) {
        tvKontrolaPrompt.setVisibility(View.GONE);
        tvKontrolaHeader.setVisibility(View.VISIBLE);
        int cast = parseInt(matched.cast, 0);
        tvKontrolaHeader.setText(getString(R.string.kontrola_chip_header, cast));
        kontrolaCellsContainer.removeAllViews();

        Tudu.Vyhybka vyhybka = findVyhybkaForRow(matched);
        kontrolaCellsContainer.addView(buildKontrolaChipDetail(matched, vyhybka));
        tvKontrolaStatus.setVisibility(View.GONE);
    }

    private View buildKontrolaChipDetail(CsvStore.Row row, Tudu.Vyhybka vyhybka) {
        View cell = getLayoutInflater().inflate(R.layout.item_kontrola_cell, kontrolaCellsContainer, false);
        TextView title = cell.findViewById(R.id.tvKontrolaCellTitle);
        TextView detail = cell.findViewById(R.id.tvKontrolaCellDetail);

        int cast = parseInt(row.cast, 0);
        title.setText(getString(R.string.kontrola_chip_header, cast));
        detail.setText(buildKontrolaFieldLines(row, vyhybka));
        return cell;
    }

    private String buildKontrolaFieldLines(CsvStore.Row row, Tudu.Vyhybka vyhybka) {
        int cast = parseInt(row.cast, 0);
        String empty = getString(R.string.kontrola_empty_value);
        StringBuilder lines = new StringBuilder();
        appendKontrolaField(lines, getString(R.string.kontrola_field_chlivecky),
                kontrolaCastPartName(cast, vyhybka));
        appendKontrolaField(lines, "ID_RFID", row.idRfid);
        appendKontrolaField(lines, "EPC", row.epc);
        appendKontrolaField(lines, "TID", row.tid);
        appendKontrolaField(lines, "TUDU", row.tudu);
        appendKontrolaField(lines, "POZICE", row.cast);
        appendKontrolaField(lines, "POLOHA", row.poloha);
        appendKontrolaField(lines, "RO_ID_1", row.roId1);
        appendKontrolaField(lines, "RO_ID_2", row.roId2);
        appendKontrolaField(lines, "KM_EXT", row.kmExt);
        if (lines.length() > 0 && lines.charAt(lines.length() - 1) == '\n') {
            lines.setLength(lines.length() - 1);
        }
        return lines.length() == 0 ? empty : lines.toString();
    }

    private void appendKontrolaField(StringBuilder lines, String label, String value) {
        String display = value != null && !value.trim().isEmpty()
                ? value.trim()
                : getString(R.string.kontrola_empty_value);
        lines.append(getString(R.string.kontrola_field_line, label, display)).append('\n');
    }

    private String kontrolaCastPartName(int cast, Tudu.Vyhybka vyhybka) {
        if (isTuduBoundaryCast(cast)) {
            return getString(R.string.cast_part_5);
        }
        if (vyhybka != null && isFourPartVyhybka(vyhybka)) {
            return vyhybka.castFourPartLabel(cast);
        }
        switch (cast) {
            case 1: return getString(R.string.cast_part_1);
            case 2: return getString(R.string.cast_part_2);
            case 3: return getString(R.string.cast_part_3);
            default: return String.valueOf(cast);
        }
    }

    @Override
    protected void onDestroy() {
        dbLoadGeneration++;
        cancelGpsLookupTimeout();
        stopStatusAlternation();
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
