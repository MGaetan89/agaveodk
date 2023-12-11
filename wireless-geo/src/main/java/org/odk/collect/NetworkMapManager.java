package org.odk.collect;

import static android.location.LocationManager.GPS_PROVIDER;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.media.MediaPlayer;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import org.odk.collect.db.DBException;
//import org.odk.collect.strings.R;

import org.odk.collect.db.DatabaseHelper;
import org.odk.collect.db.MxcDatabaseHelper;
import org.odk.collect.model.ConcurrentLinkedHashMap;
import org.odk.collect.model.Network;
import org.odk.collect.receivers.BatteryLevelReceiver;
import org.odk.collect.receivers.BluetoothReceiver;
import org.odk.collect.receivers.PhoneState;
import org.odk.collect.receivers.WifiReceiver;
import org.odk.collect.receivers.GNSSListener;
import org.odk.collect.ui.WToast;
import org.odk.collect.util.FileAccess;
import org.odk.collect.util.PreferenceKeys;
import org.odk.collect.util.FileUtility;


import org.odk.collect.permissions.PermissionsProvider;

import timber.log.Timber;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;

import org.odk.collect.wirelessgeo.R;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class NetworkMapManager {
    private GnssStatus.Callback gnssStatusCallback = null;
    private GnssMeasurementsEvent.Callback gnssMeasurementsCallback = null;
    public static final long DEFAULT_SPEECH_PERIOD = 60L;
    public static final long DEFAULT_RESET_WIFI_PERIOD = 90000L;
    public static final long LOCATION_UPDATE_INTERVAL = 1000L;
    public static final long SCAN_STILL_DEFAULT = 3000L;
    public static final long SCAN_DEFAULT = 2000L;
    public static final long SCAN_FAST_DEFAULT = 1000L;
    public static final long SCAN_P_DEFAULT = 30000L;
    public static final long OG_BT_SCAN_STILL_DEFAULT = 5000L;
    public static final long OG_BT_SCAN_DEFAULT = 5000L;
    public static final long OG_BT_SCAN_FAST_DEFAULT = 5000L;
    static Locale ORIG_LOCALE = Locale.getDefault();
    public static final String ENCODING = "ISO-8859-1";
    public static final String WIFI_LOCK_NAME = "odkWifiLock";

    public static final String NETWORK_FILE_FIELD = "env_data";
    public static final long DEFAULT_BATTERY_KILL_PERCENT = 2L;
    public static final boolean DEBUG_CELL_DATA = false;
    public static final boolean DEBUG_BLUETOOTH_DATA = false;
    public static final boolean ENABLE_DEBUG_LOGGING = false;

    public static LocationListener STATIC_LOCATION_LISTENER = null;

    private static final int PERMISSIONS_REQUEST = 1;private static final int ACTION_WIFI_CODE = 2;

    public static class State {
        public MxcDatabaseHelper mxcDbHelper;
        public DatabaseHelper dbHelper;
        ServiceConnection serviceConnection;
        AtomicBoolean finishing;
        AtomicBoolean transferring;
        AtomicBoolean serializing;
        WifiManager.WifiLock wifiLock;
        GNSSListener GNSSListener;
        WifiReceiver wifiReceiver;
        BluetoothReceiver bluetoothReceiver;
        NumberFormat numberFormat0;
        NumberFormat numberFormat1;
        NumberFormat numberFormat8;
        TextToSpeech tts;
        boolean ttsChecked = false;
        boolean inEmulator;
        PhoneState phoneState;
//        ObservationUploader observationUploader;
//        SetNetworkListAdapter listAdapter;
        String previousStatus;
//        int currentTab = R.id.nav_list;
        int previousTab = 0;
        private boolean screenLocked = false;
        private PowerManager.WakeLock wakeLock;
        private int logPointer = 0;
        private final String[] logs = new String[25];
        Matcher bssidLogExclusions;
        Matcher bssidDisplayExclusions;
        int uiMode;
        AtomicBoolean uiRestart;
        AtomicBoolean ttsNag = new AtomicBoolean(true);
//        WiGLEApiManager apiManager;
        ConcurrentLinkedHashMap<String, Network> networkCache;
        Map<Integer, String> btVendors;
        Map<Integer, String> btMfgrIds;
    }

    private State state;
    private Context context;
    private Activity activity;

    private BatteryLevelReceiver batteryLevelReceiver;

    public static class NetworkState {
        public Location location;
        public int runNets;
        public int runCells;
        public int runBt;
        public long newNets;
        public long newWifi;
        public long newCells;
        public long newBt;
        public int currNets;
        public int currCells;
        public int currBt;
        public int preQueueSize;
        public long dbNets;
        public long dbLocs;
        public long currWifiScanDurMs;
        public DatabaseHelper dbHelper;
        public Set<String> runNetworks;
        public Set<String> runBtNetworks;
//        public QueryArgs queryArgs;
        public ConcurrentLinkedHashMap<String,Network> networkCache;
//        public OUI oui;
    }
    public static final NetworkState networkState = new NetworkState();
    private static NetworkMapManager networkMapManager;

    public void setupNetworkMapManager(Context mContext, Activity mActivity) {
        this.context = mContext;
        this.activity = mActivity;

        Timber.i("MAIN onCreate. state:  " + state);
        //DEBUG:
        /*StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyLog()
                        .build());
        StrictMode.setVmPolicy(
                new StrictMode.VmPolicy.Builder()
                        .detectLeakedClosableObjects()
                        .detectLeakedSqlLiteObjects()
                        .penaltyLog()
                        .build());*/
        //END DEBUG
        final int THREAD_ID = 31973;
        TrafficStats.setThreadStatsTag(THREAD_ID);
        final SharedPreferences prefs = getSharedPreferences(PreferenceKeys.SHARED_PREFS, Context.MODE_PRIVATE);

        setupPermissions();

        // do some of our own error handling, write a file with the stack
//        final Thread.UncaughtExceptionHandler origHandler = Thread.getDefaultUncaughtExceptionHandler();
//        if (!(origHandler instanceof WigleUncaughtExceptionHandler)) {
//            Thread.setDefaultUncaughtExceptionHandler(
//                    new WigleUncaughtExceptionHandler(getApplicationContext(), origHandler));
//        }

        // test the error reporting
        // if( true ){ throw new RuntimeException( "weee" ); }

        // force the retained fragments to live
//        final FragmentManager fm = getSupportFragmentManager();
//        fm.executePendingTransactions();
//        StateFragment stateFragment = (StateFragment) fm.findFragmentByTag(STATE_FRAGMENT_TAG);

        Timber.i("MAIN: creating new state");
        state = new State();
        state.finishing = new AtomicBoolean(false);
        state.transferring = new AtomicBoolean(false);
        state.uiRestart = new AtomicBoolean(false);
        state.serializing = new AtomicBoolean(false);

        // new run, reset
        final float prevRun = prefs.getFloat(PreferenceKeys.PREF_DISTANCE_RUN, 0f);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putFloat(PreferenceKeys.PREF_DISTANCE_RUN, 0f);
        edit.putLong(PreferenceKeys.PREF_STARTTIME_RUN, System.currentTimeMillis());
        edit.putLong(PreferenceKeys.PREF_STARTTIME_CURRENT_SCAN, System.currentTimeMillis());
        edit.putLong(PreferenceKeys.PREF_CUMULATIVE_SCANTIME_RUN, 0L);
        edit.putFloat(PreferenceKeys.PREF_DISTANCE_PREV_RUN, prevRun);
        edit.apply();

        Timber.i("MAIN: powerManager setup");
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (state.wakeLock == null) {
            state.wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "wiglewifiwardriving:DoNotDimScreen");
            if (state.wakeLock.isHeld()) {
                state.wakeLock.release();
            }
        }

        final String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);

        // DO NOT turn these into |=, they will cause older dalvik verifiers to freak out
        state.inEmulator = id == null;
        state.inEmulator = state.inEmulator || "sdk".equals(android.os.Build.PRODUCT);
        state.inEmulator = state.inEmulator || "google_sdk".equals(android.os.Build.PRODUCT);

//        state.uiMode = getResources().getConfiguration().uiMode;

        Timber.i("MAIN:\tid: '" + id + "' inEmulator: " + state.inEmulator + " product: " + android.os.Build.PRODUCT);
        Timber.i("MAIN:\tandroid release: '" + Build.VERSION.RELEASE);

        if (state.numberFormat0 == null) {
            state.numberFormat0 = NumberFormat.getNumberInstance(Locale.US);
            if (state.numberFormat0 instanceof DecimalFormat) {
                state.numberFormat0.setMaximumFractionDigits(0);
            }
        }

        if (state.numberFormat1 == null) {
            state.numberFormat1 = NumberFormat.getNumberInstance(Locale.US);
            if (state.numberFormat1 instanceof DecimalFormat) {
                state.numberFormat1.setMaximumFractionDigits(1);
            }
        }

        if (state.numberFormat8 == null) {
            state.numberFormat8 = NumberFormat.getNumberInstance(Locale.US);
            if (state.numberFormat8 instanceof DecimalFormat) {
                state.numberFormat8.setMaximumFractionDigits(8);
                state.numberFormat8.setMinimumFractionDigits(8);
            }
        }

//        Timber.i("MAIN: setupService");
//        setupService();
        Timber.i("MAIN: checkStorage");
        checkStorage();
        Timber.i("MAIN: setupCache");
        setupCache();
        Timber.i("MAIN: setupDatabase");
        setupDatabase(prefs);
        Timber.i("MAIN: setupBattery");
        setupBattery();
//        Timber.i("MAIN: setupSound");
//        setupSound();
        Timber.i("MAIN: setupActivationDialog");
        setupActivationDialog(prefs);
        Timber.i("MAIN: setupBluetooth");
        setupBluetooth(prefs);
        Timber.i("MAIN: setupWifi");
        setupWifi(prefs);
        Timber.i("MAIN: setupLocation"); // must be after setupWifi
        setupLocation(prefs);
        Timber.i("MAIN: setup tabs");
//        if (savedInstanceState == null) {
//            setupFragments();
//        }
//        setupFilters(prefs);

        Timber.i("MAIN: first install check");
        // ALIBI: don't inherit MxC implant failures from backups.
//        if (InstallUtility.isFirstInstall(this)) {
//            SharedPreferences mySPrefs = PreferenceManager.getDefaultSharedPreferences(this.context);
//            SharedPreferences.Editor editor = mySPrefs.edit();
//            editor.remove(ListFragment.PREF_MXC_REINSTALL_ATTEMPTED);
//            if (!isImperialUnitsLocale()) {
//                editor.putBoolean(PreferenceKeys.PREF_METRIC, true);
//            }
//            editor.apply();
//        }

        Timber.i("MAIN: cell data check");
        //TODO: if we can determine whether DB needs updating, we can avoid copying every time
        //if (!state.mxcDbHelper.isPresent()) {
        state.mxcDbHelper.implantMxcDatabase(this.context, isFinishing());
        //}

        Timber.i("MAIN: keystore check");
        // rksh 20160202 - api/authuser secure preferences storage
//        checkInitKeystore(prefs);

        // show the list by default
//        selectFragment(state.currentTab);
        Timber.i("MAIN: onCreate setup complete");
    }

    private void setupPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            final List<String> permissionsNeeded = new ArrayList<>();
            final List<String> permissionsList = new ArrayList<>();
            if (!addPermission(permissionsList, Manifest.permission.ACCESS_FINE_LOCATION)) {
                permissionsNeeded.add(context.getString(R.string.gps_permission));
            }
            if (!addPermission(permissionsList, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                permissionsNeeded.add(context.getString(R.string.cell_permission));
            }
            addPermission(permissionsList, Manifest.permission.BLUETOOTH);
            addPermission(permissionsList, Manifest.permission.READ_PHONE_STATE);
            addPermission(permissionsList, Manifest.permission.BLUETOOTH_SCAN);
            addPermission(permissionsList, Manifest.permission.BLUETOOTH_CONNECT);
            addPermission(permissionsList, Manifest.permission.POST_NOTIFICATIONS);
            addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE);
            if (!permissionsList.isEmpty()) {
                // The permission is NOT already granted.
                // Check if the user has been asked about this permission already and denied
                // it. If so, we want to give more explanation about why the permission is needed.
                // 20170324 rksh: disabled due to
                // https://stackoverflow.com/questions/35453759/android-screen-overlay-detected-message-if-user-is-trying-to-grant-a-permissio
                /*String message = mainActivity.getString(R.string.please_allow);
                for (int i = 0; i < permissionsNeeded.size(); i++) {
                    if (i > 0) message += ", ";
                    message += permissionsNeeded.get(i);
                }

                if (permissionsList.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    message = mainActivity.getString(R.string.allow_storage);
                } */

                Timber.i("no permission for " + permissionsNeeded);

                // Fire off an async request to actually get the permission
                // This will show the standard permission request dialog UI
                activity.requestPermissions(permissionsList.toArray(new String[permissionsList.size()]),
                        PERMISSIONS_REQUEST);
            }
        }
    }

    private boolean addPermission(List<String> permissionsList, String permission) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission);
            // Check for Rationale Option
            if (!activity.shouldShowRequestPermissionRationale(permission))
                return false;
        }
        return true;
    }

    public void checkStorage() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            boolean safe;
            boolean external = FileUtility.hasSD();
            if (external) {
                safe = FileUtility.checkExternalStorageDangerZone();
            } else {
                safe = FileUtility.checkInternalStorageDangerZone();
            }
            handler.post(() -> {
                if (!safe) {
                    AlertDialog.Builder iseDlgBuilder = new AlertDialog.Builder(getContext());
                    iseDlgBuilder.setMessage(external?R.string.no_external_space_message:R.string.no_internal_space_message)
                            .setTitle(external?R.string.no_external_space_title:R.string.no_internal_space_title)
                            .setCancelable(true)
                            .setPositiveButton(R.string.ok, (dialog, which) -> dialog.dismiss());
                    final Dialog dialog = iseDlgBuilder.create();
                    if (!isFinishing()) {
                        dialog.show();
                    }
                }
            });
        });
    }

    private void setupCache() {
        final long maxMemory = Runtime.getRuntime().maxMemory();
        int cacheSize = 128;
        if (maxMemory > 400_000_000L) {
            cacheSize = 4000; // cap at 4,000
        }
        else if (maxMemory > 50_000_000L) {
            cacheSize = (int)(maxMemory / 100_000); // 100MiB == 1000 cache
        }
        Timber.i("Heap: maxMemory: " + maxMemory + " cacheSize: " + cacheSize);
        networkState.networkCache = new ConcurrentLinkedHashMap<>(cacheSize);
    }

    private void setupDatabase(final SharedPreferences prefs) {
        // could be set by nonconfig retain
        if (state.dbHelper == null) {
            state.dbHelper = new DatabaseHelper(context, prefs);
            //state.dbHelper.checkDB();
            state.dbHelper.start();
        }
        if (state.mxcDbHelper == null) {
            state.mxcDbHelper = new MxcDatabaseHelper(context, prefs);
        }
    }

    private void setupWifi(final SharedPreferences prefs) {
        final WifiManager wifiManager = (WifiManager) this.context.
                getSystemService(Context.WIFI_SERVICE);
        final SharedPreferences.Editor edit = prefs.edit();

        // keep track of for later
        boolean turnedWifiOn = false;
        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            turnedWifiOn = true;
            // switch this to androidx call when it becomes available
            if (Build.VERSION.SDK_INT >= 29) {
                final Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                (activity).startActivityForResult(panelIntent, ACTION_WIFI_CODE);
            } else {
                // open wifi setting pages after a few seconds
                new Handler().postDelayed(() -> {
                    WToast.showOverActivity(activity, R.string.app_name,
                            context.getString(R.string.turn_on_wifi), Toast.LENGTH_LONG);
                    context.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }, 3000);
            }
        }
        edit.apply();

        if (state.wifiReceiver == null) {
            Timber.i("\tnew wifiReceiver");
            // wifi scan listener
            // this receiver is the main workhorse of the entire app
            state.wifiReceiver = new WifiReceiver(this, state.dbHelper);
            state.wifiReceiver.setupWifiTimer(turnedWifiOn);
        }

        // register wifi receiver
        setupWifiReceiverIntent();

        if (state.wifiLock == null && wifiManager != null) {
            Timber.i("\tlock wifi radio on");
            // lock the radio on (only works in 28 (P) and lower)
            state.wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_SCAN_ONLY, WIFI_LOCK_NAME);
            state.wifiLock.acquire();
        }
    }

    private void setupWifiReceiverIntent() {
        // register
        Timber.i("register BroadcastReceiver");
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        context.registerReceiver(state.wifiReceiver, intentFilter);
    }

    public void setupBluetooth(final SharedPreferences prefs) {
        try {
            final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) {
                Timber.i("No bluetooth adapter");
                return;
            }
            final SharedPreferences.Editor edit = prefs.edit();
            if (prefs.getBoolean(PreferenceKeys.PREF_SCAN_BT, true)) {
                //NB: almost certainly getting specious 'false' answers to isEnabled.
                //  BluetoothAdapter.STATE_TURNING_OFF also a possible match
                if (bt.getState() == BluetoothAdapter.STATE_OFF ||
                        bt.getState() == BluetoothAdapter.STATE_TURNING_OFF) {
                    Timber.i("Enable bluetooth");
                    edit.putBoolean(PreferenceKeys.PREF_BT_WAS_OFF, true);
                    bt.enable();
                } else {
                    edit.putBoolean(PreferenceKeys.PREF_BT_WAS_OFF, false);
                }
                edit.apply();
                if (state.bluetoothReceiver == null) {
                    Timber.i("\tnew bluetoothReceiver");
                    // dynamically detect BTLE feature - prevents occasional NPEs
                    boolean hasLeSupport = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
                    if (hasLeSupport) {
                        //initialize the two global maps.
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader((context.getAssets().open("btmember.yaml"))))) {
                            Constructor constructor = new Constructor(new LoaderOptions());
                            Yaml yaml = new Yaml(constructor);
                            final HashMap<String,Object> data = yaml.load(reader);
                            final List<LinkedHashMap<String, Object>> entries = (List<LinkedHashMap<String, Object>>) data.get("uuids");
                            state.btVendors = new HashMap<>();
                            if (null != entries) {
                                for (LinkedHashMap<String, Object> entry : entries) {
                                    state.btVendors.put((Integer) entry.get("uuid"), (String) entry.get("name"));
                                }
                                Timber.i("BLE members initialized: "+entries.size()+" entries");
                            }
                        } catch (IOException e) {
                            Timber.e(e, "Failed to load BLE member yaml:");
                        }

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader((context.getAssets().open("btco.yaml"))))) {
                            Constructor constructor = new Constructor(new LoaderOptions());
                            Yaml yaml = new Yaml(constructor);
                            final HashMap<String, Object> data = yaml.load(reader);
                            final List<LinkedHashMap<String, Object>> entries = (List<LinkedHashMap<String, Object>>) data.get("company_identifiers");
                            state.btMfgrIds = new HashMap<>();
                            if (null != entries) {
                                for (LinkedHashMap<String, Object> entry : entries) {
                                    state.btMfgrIds.put((Integer) entry.get("value"), (String) entry.get("name"));
                                }
                                Timber.i("BLE mfgrs initialized: "+entries.size()+" entries");
                            }
                        } catch (IOException e) {
                            Timber.e(e,"Failed to load BLE mfgr yaml: ");
                        }
                    }
                    // bluetooth scan listener
                    // this receiver is the main workhorse of bluetooth scanning
                    state.bluetoothReceiver = new BluetoothReceiver(state.dbHelper,
                            hasLeSupport, prefs);
                    state.bluetoothReceiver.setupBluetoothTimer(true);
                }
                Timber.i("\tregister bluetooth BroadcastReceiver");
                final IntentFilter intentFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                context.registerReceiver(state.bluetoothReceiver, intentFilter);
            }
        } catch (SecurityException e) {
            Timber.e(e,"exception initializing bluetooth: ");
        } catch (Exception e) {
            //ALIBI: there's a lot of wonkiness in real-world BT adapters
            //  seeing them go null during this block after null check passes,
            Timber.e(e,"failure initializing bluetooth: ");
        }
    }

    private void setupLocation(final SharedPreferences prefs) {
        final LocationManager locationManager = (LocationManager) context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);

        try {
            // check if there is a gps
            Timber.i("\tGNSS HW: "+ LocationManagerCompat.getGnssHardwareModelName(locationManager)+" year: "+LocationManagerCompat.getGnssYearOfHardware(locationManager)+ " enabled: "+ LocationManagerCompat.isLocationEnabled(locationManager));
            final LocationProvider locProvider = locationManager.getProvider(GPS_PROVIDER);

            if (locProvider == null) {
                WToast.showOverActivity(this.activity, R.string.app_name, context.getString(R.string.no_gps_device), Toast.LENGTH_LONG);
            } else if (!locationManager.isProviderEnabled(GPS_PROVIDER)) {
                // gps exists, but isn't on
                WToast.showOverActivity(this.activity, R.string.app_name, context.getString(R.string.turn_on_gps), Toast.LENGTH_LONG);

                final Intent myIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                try {
                    context.startActivity(myIntent);
                } catch (Exception ex) {
                    Timber.e(ex, "exception trying to start location activity: " + ex);
                }
            }
        } catch (final SecurityException ex) {
            Timber.i("Security exception in setupLocation: " + ex);
            return;
        }

        if (state.GNSSListener == null) {
            // force a listener to be created
            boolean logRoutes = prefs.getBoolean(PreferenceKeys.PREF_LOG_ROUTES, false);
            if (logRoutes) {
                startRouteLogging(prefs);
            }
            boolean displayRoute = prefs.getBoolean(PreferenceKeys.PREF_VISUALIZE_ROUTE, false);
            if (displayRoute) {
                startRouteMapping(prefs);
            }
            internalHandleScanChange(prefs.getBoolean(PreferenceKeys.PREF_SCAN_RUNNING, true));
        }
    }

    /**
     * When we start a new run/start logging for a run, provide the new run a new ID
     * @param prefs current sharedPreferences
     */
    public void startRouteLogging(SharedPreferences prefs) {
        // ALIBI: we initialize this value to 0L on table setup as well.
        long lastRouteId = prefs.getLong(PreferenceKeys.PREF_ROUTE_DB_RUN, 0L);
        long routeId = lastRouteId+1L; //ALIBI: always skips the default 0 run id. (see vis below)
        final SharedPreferences.Editor edit = prefs.edit();
        edit.putLong(PreferenceKeys.PREF_ROUTE_DB_RUN, routeId);
        edit.apply();
    }

    /**
     * since we do the prefs check on logging, no real need to do anything here
     */
    public void endRouteLogging() {
        //TODO: null operation for now
    }

    public void startRouteMapping(SharedPreferences prefs) {
        boolean logRoutes = prefs.getBoolean(PreferenceKeys.PREF_LOG_ROUTES, false);
        //ALIBI: we'll piggyback off the current route, if we're logging it
        if (!logRoutes) {
            if (state != null && state.dbHelper != null) {
                try {
                    state.dbHelper.clearDefaultRoute();
                } catch (DBException dbe) {
                    Timber.w(dbe, "unable to clear default route on start-viz: ");
                }
            }
        }
    }

    public void endRouteMapping(SharedPreferences prefs) {
        boolean logRoutes = prefs.getBoolean(PreferenceKeys.PREF_LOG_ROUTES, false);
        if (!logRoutes) {
            if (state != null && state.dbHelper != null) {
                try {
                    state.dbHelper.clearDefaultRoute();
                } catch (DBException dbe) {
                    Timber.w(dbe, "unable to clear default route on end-viz: ");
                }
            }
        }
    }
    private void setupBattery() {
        if (batteryLevelReceiver == null) {
            batteryLevelReceiver = new BatteryLevelReceiver();
            IntentFilter batteryLevelFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            context.registerReceiver(batteryLevelReceiver, batteryLevelFilter);
        }
    }

    private void setupActivationDialog(final SharedPreferences prefs) {
        final boolean willActivateBt = canBtBeActivated();
        final boolean willActivateWifi = canWifiBeActivated();
        final boolean useBt = prefs.getBoolean(PreferenceKeys.PREF_SCAN_BT, true);
        final boolean alertVersions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            if ((willActivateBt && useBt) || willActivateWifi || alertVersions) {
                String activationMessages = "";

                if (willActivateBt && useBt) {
                    if (activationMessages.length() > 0) activationMessages += "\n";
                    activationMessages += context.getString(R.string.turn_on_bt);
                    if (willActivateWifi) {
                        activationMessages += "\n";
                    }
                }

                if (willActivateWifi) {
                    activationMessages += context.getString(R.string.turn_on_wifi);
                }
                // tell user, cuz this takes a little while
                if (!activationMessages.isEmpty()) {
                    String finalActivationMessages = activationMessages;
                    handler.post(() -> {
                        WToast.showOverActivity(activity, R.string.app_name, finalActivationMessages, Toast.LENGTH_LONG);
                    });
                }
            }
        });
    }

    private boolean canBtBeActivated() {
        try {
            final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) {
                Timber.i("No bluetooth adapter");
                return false;
            }
            if (!bt.isEnabled()) {
                return true;
            }
        } catch (java.lang.SecurityException sex) {
            Timber.w("bt activation security exception");
        }
        return false;
    }
    private boolean canWifiBeActivated() {
        final WifiManager wifiManager = (WifiManager) this.getContext().
                getSystemService(Context.WIFI_SERVICE);
        if (null == wifiManager) {
            return false;
        }
        return !wifiManager.isWifiEnabled() && !state.inEmulator;
    }
    public GNSSListener getGPSListener() {
        return state.GNSSListener;
    }
    public PhoneState getPhoneState() {
        return state.phoneState;
    }

    public boolean isFinishing() {
        return state.finishing.get();
    }

    public boolean isSerializing() {
        return state.serializing.get();
    }


    public boolean isScanning() {
        final SharedPreferences prefs = getSharedPreferences(PreferenceKeys.SHARED_PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(PreferenceKeys.PREF_SCAN_RUNNING, true);
    }

    public boolean isTransferring() {
        return state.transferring.get();
    }

    public BatteryLevelReceiver getBatteryLevelReceiver() {
        return batteryLevelReceiver;
    }

    public long getLocationSetPeriod() {
        final SharedPreferences prefs = getSharedPreferences(PreferenceKeys.SHARED_PREFS, Context.MODE_PRIVATE);
        long setPeriod = prefs.getLong(PreferenceKeys.GPS_SCAN_PERIOD, LOCATION_UPDATE_INTERVAL);
        if (setPeriod == 0) {
            if (state.wifiReceiver == null) {
                setPeriod = LOCATION_UPDATE_INTERVAL;
            }
            else {
                setPeriod = Math.max(state.wifiReceiver.getScanPeriod(), LOCATION_UPDATE_INTERVAL);
            }
        }
        setPeriod = 40000;
        return setPeriod;
    }

    public void reportError(String error) {
        //TODO: implement
//        final MainActivity mainActivity = MainActivity.getMainActivity();
//        final Intent errorReportIntent = new Intent( mainActivity, ErrorReportActivity.class );
//        errorReportIntent.putExtra( ERROR_REPORT_DIALOG, error );
//        mainActivity.startActivity( errorReportIntent );
    }

    public static NetworkMapManager getManager() {
        if (networkMapManager == null) {
            networkMapManager = new NetworkMapManager();
        }
        return networkMapManager;
    }

    public static void sleep(final long sleep) {
        try {
            Thread.sleep(sleep);
        } catch (final InterruptedException ex) {
            // no worries
        }
    }

    public static void writeError(final Thread thread, final Throwable throwable, final Context context) {
        writeError(thread, throwable, context, null);
    }

    public static void writeError(final Thread thread, final Throwable throwable, final Context context, final String detail) {
        try {
            final String error = "Thread: " + thread + " throwable: " + throwable;
            Timber.e(throwable, error);
            final File stackPath = FileUtility.getErrorStackPath(context);
            if (stackPath.exists() && stackPath.canWrite()) {
                //noinspection ResultOfMethodCallIgnored
                stackPath.mkdirs();
                final File file = new File(stackPath, FileUtility.ERROR_STACK_FILE_PREFIX + "_" + System.currentTimeMillis() + ".txt");
                Timber.e("Writing stackfile to: " + file.getAbsolutePath());
                if (!file.exists()) {
                    if (!file.createNewFile()) {
                        throw new IOException("Cannot create file: " + file);
                    }
                }

                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(file);
                    PackageInfo pi = null;
                    try {
                        final PackageManager pm = context.getPackageManager();
                        pi = pm.getPackageInfo(context.getPackageName(), 0);
                    } catch (Throwable er) {
                        handleErrorError(fos, er);
                    }

                    try {
                        StringBuilder builder = new StringBuilder("WigleWifi error log - ");
                        final DateFormat format = SimpleDateFormat.getDateTimeInstance();
                        builder.append(format.format(new Date())).append("\n");
                        if (pi != null) {
                            builder.append("versionName: ").append(pi.versionName).append("\n");
                            builder.append("packageName: ").append(pi.packageName).append("\n");
                        }
                        if (detail != null) {
                            builder.append("detail: ").append(detail).append("\n");
                        }
                        builder.append("MODEL: ").append(android.os.Build.MODEL).append("\n");
                        builder.append("RELEASE: ").append(android.os.Build.VERSION.RELEASE).append("\n");

                        builder.append("BOARD: ").append(android.os.Build.BOARD).append("\n");
                        builder.append("BRAND: ").append(android.os.Build.BRAND).append("\n");
                        // android 1.6 android.os.Build.CPU_ABI;
                        builder.append("DEVICE: ").append(android.os.Build.DEVICE).append("\n");
                        builder.append("DISPLAY: ").append(android.os.Build.DISPLAY).append("\n");
                        builder.append("FINGERPRINT: ").append(android.os.Build.FINGERPRINT).append("\n");
                        builder.append("HOST: ").append(android.os.Build.HOST).append("\n");
                        builder.append("ID: ").append(android.os.Build.ID).append("\n");
                        // android 1.6: android.os.Build.MANUFACTURER;
                        builder.append("PRODUCT: ").append(android.os.Build.PRODUCT).append("\n");
                        builder.append("TAGS: ").append(android.os.Build.TAGS).append("\n");
                        builder.append("TIME: ").append(android.os.Build.TIME).append("\n");
                        builder.append("TYPE: ").append(android.os.Build.TYPE).append("\n");
                        builder.append("USER: ").append(android.os.Build.USER).append("\n");

                        // write to file
                        fos.write(builder.toString().getBytes(ENCODING));
                    } catch (Throwable er) {
                        handleErrorError(fos, er);
                    }

                    try {
                        final String baseErrorMessage = NetworkMapManager.getBaseErrorMessage(throwable, false);
                        fos.write("baseError: ".getBytes(ENCODING));
                        fos.write(baseErrorMessage.getBytes(ENCODING));
                        fos.write("\n\n".getBytes(ENCODING));
                    } catch (Throwable er) {
                        handleErrorError(fos, er);
                    }

                    try {
                        throwable.printStackTrace(new PrintStream(fos));
                        fos.write((error + "\n\n").getBytes(ENCODING));
                    } catch (Throwable er) {
                        handleErrorError(fos, er);
                    }

                    try {
                        for (final String log : getLogLines()) {
                            fos.write(log.getBytes(ENCODING));
                            fos.write("\n".getBytes(ENCODING));
                        }
                    } catch (Throwable er) {
                        // ohwell
                        Timber.e(er, "error getting logs for error: " + er);
                    }
                } finally {
                    // can't try-with-resources and support api 14
                    try {
                        if (fos != null) fos.close();
                    } catch (final Exception ex) {
                        Timber.e(ex,"error closing fos: " + ex);
                    }
                }

            }
        } catch (final Exception ex) {
            Timber.e(ex, "error logging error: " + ex);
            ex.printStackTrace();
        }
    }

    private static void handleErrorError(FileOutputStream fos, Throwable er) throws IOException {
        final String errorMessage = "error getting data for error: " + er;
        Timber.e(er, errorMessage);
        fos.write((errorMessage + "\n\n").getBytes(ENCODING));
        er.printStackTrace(new PrintStream(fos));
    }

    public static Throwable getBaseThrowable(final Throwable throwable) {
        Throwable retval = throwable;
        while (retval.getCause() != null) {
            retval = retval.getCause();
        }
        return retval;
    }

    public static String getBaseErrorMessage(Throwable throwable, final boolean withNewLine) {
        throwable = getBaseThrowable(throwable);
        final String newline = withNewLine ? "\n" : " ";
        return throwable.getClass().getSimpleName() + ":" + newline + throwable.getMessage();
    }

    public static Iterable<String> getLogLines() {
        final State state = getStaticState();
        return () -> {
            // Collections.emptyIterator() requires api 19, but this works.
            if (state == null) return Collections.emptyIterator();

            return new Iterator<String>() {
                int currentPointer = state.logPointer;
                final int maxPointer = currentPointer + state.logs.length;

                @Override
                public boolean hasNext() {
                    return state.logs[currentPointer % state.logs.length] != null && currentPointer < maxPointer;
                }

                @Override
                public String next() {
                    final String retval = state.logs[currentPointer % state.logs.length];
                    currentPointer++;
                    return retval;
                }
            };
        };
    }

    public static State getStaticState() {
        final NetworkMapManager mgr = getManager();
        return mgr == null ? null : mgr.getState();
    }

    public State getState() {
        return state;
    }

    public static ConcurrentLinkedHashMap<String, Network> getNetworkCache() {
        return networkState.networkCache;
    }

    public Context getContext() {
        return context;
    }

    public SharedPreferences getSharedPreferences(String var1, int var2) {
        return context.getSharedPreferences(var1, var2);
    }

    private void internalHandleScanChange(final boolean isScanning) {
        Timber.i("\tmain internalHandleScanChange: isScanning now: " + isScanning);

        if (isScanning) {
            if (state.wifiReceiver != null) {
                state.wifiReceiver.updateLastScanResponseTime();
            }
            // turn on location updates
            Timber.i("turn ON location updates");
            this.setLocationUpdates(getLocationSetPeriod(), 0f);

            if (!state.wifiLock.isHeld()) {
                state.wifiLock.acquire();
            }
        } else {
            // turn off location updates
            Timber.i("turn OFF location updates");
            this.setLocationUpdates(0L, 0f);
            state.GNSSListener.handleScanStop();
            if (state.wifiLock.isHeld()) {
                try {
                    state.wifiLock.release();
                } catch (SecurityException ex) {
                    // a case where we have a leftover lock from another run?
                    Timber.i("\texception releasing wifilock: " + ex);
                }
            }
        }
//        if (null != state && null != state.wigleService) {
//            state.wigleService.setupNotification();
//        }
    }

    /**
     * resets the gps listener to the requested update time and distance.
     * an updateIntervalMillis of <= 0 will not register for updates.
     */
    public void setLocationUpdates(final long updateIntervalMillis, final float updateMeters) {
        try {
            internalSetLocationUpdates(updateIntervalMillis, updateMeters);
        } catch (final SecurityException ex) {
            Timber.e(ex, "Security exception in setLocationUpdates: " + ex);
        }
    }

    public void setLocationUpdates() {
        final long setPeriod = getLocationSetPeriod();
        setLocationUpdates(setPeriod, 0f);
    }

    public Object getSystemService(String telephonyService) {
        return context.getSystemService(telephonyService);
    }

    public Matcher getBssidFilterMatcher(final String addressKey) {
        if (null != state) {
            if (PreferenceKeys.PREF_EXCLUDE_DISPLAY_ADDRS.equals(addressKey)) {
                return state.bssidDisplayExclusions;
            } else if (PreferenceKeys.PREF_EXCLUDE_LOG_ADDRS.equals(addressKey)) {
                return state.bssidLogExclusions;
            }
        }
        return null;
    }

    public void bluetoothScan() {
        if (state.bluetoothReceiver != null) {
            state.bluetoothReceiver.bluetoothScan();
        }
    }

    public void scheduleScan() {
        state.wifiReceiver.scheduleScan();
    }

    @SuppressLint("MissingPermission")
    private void internalSetLocationUpdates(final long updateIntervalMillis, final float updateMeters)
            throws SecurityException {
        final LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        if (state.GNSSListener != null) {
            // remove any old requests
            locationManager.removeUpdates(state.GNSSListener);
            if (gnssStatusCallback != null) {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            }
            if (gnssMeasurementsCallback != null) {
                locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback);
            }
        }

        // create a new listener to try and get around the gps stopping bug
        state.GNSSListener = new GNSSListener(this, state.dbHelper);
        state.GNSSListener.setMapListener(STATIC_LOCATION_LISTENER);
        final SharedPreferences prefs = getSharedPreferences(PreferenceKeys.SHARED_PREFS, Context.MODE_PRIVATE);

        try {
            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onStarted() {
                    Timber.i("Location listener started");
                }

                @Override
                public void onStopped() {
                    Timber.i("Location listener stopped");
                    state.GNSSListener.handleScanStop();
                }

                @Override
                public void onFirstFix(int ttffMillis) {
                }

                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    if (null != state && null != state.GNSSListener && !isFinishing()) {
                        Timber.i("Location status changed" + status);
                        state.GNSSListener.onGnssStatusChanged(status);
                    }
                }
            };
            locationManager.registerGnssStatusCallback(gnssStatusCallback);

            // gnss full tracking option, available in android sdk 31
            final boolean useGnssFull = prefs.getBoolean(PreferenceKeys.PREF_GPS_GNSS_FULL, false);
            if (useGnssFull && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                gnssMeasurementsCallback = new GnssMeasurementsEvent.Callback() {
                    @Override
                    public void onGnssMeasurementsReceived(GnssMeasurementsEvent eventArgs) {
                        Timber.d("GnssMeasurements clock: " + eventArgs.getClock());
                    }
                };

                final GnssMeasurementRequest request = new GnssMeasurementRequest.Builder()
                        .setFullTracking(useGnssFull).build();
                locationManager.registerGnssMeasurementsCallback(request,
                        ContextCompat.getMainExecutor(context),
                        gnssMeasurementsCallback
                );
            }
        } catch (final SecurityException ex) {
            Timber.i(ex, "\tSecurity exception adding status listener: " + ex);
        } catch (final Exception ex) {
            Timber.e(ex, "Error registering for gnss: " + ex);
        }

        final boolean useNetworkLoc = prefs.getBoolean(PreferenceKeys.PREF_USE_NETWORK_LOC, false);

        final List<String> providers = locationManager.getAllProviders();
        if (providers != null) {
            for (String provider : providers) {
                Timber.i("\tavailable provider: " + provider + " updateIntervalMillis: " + updateIntervalMillis);
                if (!useNetworkLoc && LocationManager.NETWORK_PROVIDER.equals(provider)) {
                    // skip!
                    continue;
                }
                if (!"passive".equals(provider) && updateIntervalMillis > 0L) {
                    Timber.i("\tusing provider: " + provider);
                    try {
                        Timber.i("using Listener: " + state.GNSSListener);
                        locationManager.requestLocationUpdates(provider, updateIntervalMillis, updateMeters, state.GNSSListener);
                    } catch (final SecurityException ex) {
                        Timber.i(ex,"\tSecurity exception adding status listener: " + ex);
                    }
                }
            }

            if (updateIntervalMillis <= 0L) {
                Timber.i("removing location listener: " + state.GNSSListener);
                try {
                    locationManager.removeUpdates(state.GNSSListener);
                    if (gnssStatusCallback != null) {
                        locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
                    }
                    if (gnssMeasurementsCallback != null) {
                        locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback);
                    }
                    locationManager.removeUpdates(state.GNSSListener);
                } catch (final SecurityException ex) {
                    Timber.i(ex,"Security exception removing status listener: " + ex);
                }
            }
        }
    }

    public void stopScans() {
        // turn off location updates
        Timber.i("turn OFF scans");
        state.serializing = new AtomicBoolean(true);
        state.bluetoothReceiver.stopScanning();
        this.setLocationUpdates(0L, 0f);
        state.GNSSListener.handleScanStop();
        if (state.wifiLock.isHeld()) {
            try {
                state.wifiLock.release();
            } catch (SecurityException ex) {
                // a case where we have a leftover lock from another run?
                Timber.i("\texception releasing wifilock: " + ex);
            }
        }
    }

    public class NetworkFile {
        private long maxID;
        private Uri filepath;

        public NetworkFile(long maxID, String filepath) {
            this.maxID = maxID;
            this.filepath = Uri.parse(filepath);
        }

        public String getID() {
            return String.valueOf(maxID);
        }

        public String getFilename() {
            return filepath.getLastPathSegment();
        }
        public String getPath() {
            return filepath.toString();
        }
    }
    public NetworkFile writeFile(final Bundle bundle) throws InterruptedException {

        final Object[] fileFilename = new Object[2];
        try (final OutputStream fos = FileAccess.getOutputStream( context, bundle, fileFilename )) {
            final File file = (File) fileFilename[0];
            final String filename = (String) fileFilename[1];

            // write file
            long maxId = FileUtility.writeFile(context, state.dbHelper, fos, bundle);

            final boolean hasSD = FileUtility.hasSD();

            final String absolutePath = hasSD ? file.getAbsolutePath() : context.getFileStreamPath(filename).getAbsolutePath();

            Timber.i("filepath: " + absolutePath);

            File gz = new File(absolutePath);
            String dirPath;
            dirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath();

            File externalFile = new File(dirPath);
            FileUtility.copyFile(gz, externalFile);

            return new NetworkFile(maxId, absolutePath);
        } catch (Exception e) {
            Timber.e(e,"Exception");
            throw new RuntimeException(e);
        }
    }



}