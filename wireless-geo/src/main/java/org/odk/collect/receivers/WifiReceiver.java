package org.odk.collect.receivers;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.NeighboringCellInfo;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;

import androidx.annotation.RequiresApi;

import com.google.android.gms.maps.model.LatLng;

import timber.log.Timber;

import org.odk.collect.NetworkMapManager;
import org.odk.collect.model.ConcurrentLinkedHashMap;
import org.odk.collect.model.GsmOperator;
import org.odk.collect.model.GsmOperatorException;
import org.odk.collect.model.Network;
import org.odk.collect.model.NetworkType;
import org.odk.collect.util.CellNetworkLegend;
import org.odk.collect.util.FilterMatcher;
import org.odk.collect.util.PreferenceKeys;
import org.odk.collect.db.DatabaseHelper;

//import net.wigle.wigleandroid.FilterMatcher;
//import net.wigle.wigleandroid.ListFragment;
//import net.wigle.wigleandroid.manager;
//import net.wigle.wigleandroid.R;
//import net.wigle.wigleandroid.db.DatabaseHelper;
//
//import net.wigle.wigleandroid.ui.NetworkListSorter;
//import net.wigle.wigleandroid.ui.SetNetworkListAdapter;
//import net.wigle.wigleandroid.ui.UINumberFormat;
//import net.wigle.wigleandroid.ui.WiGLEToast;
//import net.wigle.wigleandroid.util.CellNetworkLegend;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;


/**
 * Primary receiver logic for WiFi and Cell nets.
 * Monolithic - candidate for refactor.
 * TODO: split Cell into own class
 * @author bobzilla, arkasha
 */
public class WifiReceiver extends BroadcastReceiver {
    private NetworkMapManager manager;
    private final DatabaseHelper dbHelper;
    private final NumberFormat numberFormat1;

    private Handler wifiTimer;
    private long scanRequestTime = Long.MIN_VALUE;
    private long lastScanResponseTime = Long.MIN_VALUE;
    private long lastWifiUnjamTime = 0;
    private long lastSaveLocationTime = 0;
    private long lastHaveLocationTime = 0;
    private int pendingWifiCount = 0;
    private int pendingCellCount = 0;
    private final long constructionTime = System.currentTimeMillis();
    private long previousTalkTime = System.currentTimeMillis();
    private final Set<String> runNetworks = new HashSet<>();
    private final Set<String> runCells = new HashSet<>();
    private long prevNewNetCount;
    private long prevScanPeriod;
    private boolean scanInFlight = false;

    public static final int CELL_MIN_STRENGTH = -113;

    public WifiReceiver(final NetworkMapManager manager, final DatabaseHelper dbHelper) {
        this.setNetworkMapManager(manager);
        this.dbHelper = dbHelper;
        prevScanPeriod = manager.getLocationSetPeriod();
//        NetworkMapManager.networkState.runNetworks = runNetworks;
        // formats for speech
        numberFormat1 = NumberFormat.getNumberInstance( Locale.US );
        if ( numberFormat1 instanceof DecimalFormat ) {
            numberFormat1.setMaximumFractionDigits(1);
        }
    }

    public void setNetworkMapManager( final NetworkMapManager manager) {
        this.manager = manager;
        if (manager != null) {
            prevScanPeriod = manager.getLocationSetPeriod();
            Timber.i("WifiReceiver setting prevScanPeriod: " + prevScanPeriod);
        }
    }

    public int getRunNetworkCount() {
        return runNetworks.size();
    }

    public void updateLastScanResponseTime() {
        lastHaveLocationTime = System.currentTimeMillis();
    }

    /**
     * the massive core receive handler for WiFi scan callback
     * @param context context of the onreceive
     * @param intent the intent for the receive
     */
    @SuppressLint("MissingPermission")
    @SuppressWarnings("ConstantConditions")
    @Override
    public void onReceive( final Context context, final Intent intent ) {
        scanInFlight = false;
        final long now = System.currentTimeMillis();
        lastScanResponseTime = now;
        // final long start = now;
        final WifiManager wifiManager = (WifiManager) manager.getContext().getSystemService(Context.WIFI_SERVICE);
        List<ScanResult> results = null;
        try {
            results = wifiManager.getScanResults(); // return can be null!
        }
        catch (final SecurityException ex) {
            Timber.i(ex, "security exception getting scan results: " + ex);
        }
        catch (final Exception ex) {
            // ignore, happens on some vm's
            Timber.i(ex, "exception getting scan results: " + ex);
        }
        Timber.i("wifi receive, results: " + (results == null ? null : results.size()));

        long nonstopScanRequestTime = Long.MIN_VALUE;
        final SharedPreferences prefs = manager.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );
        final long period = getScanPeriod();
        if ( period == 0 ) {
            // treat as "continuous", so request scan in here
            doWifiScan();
            nonstopScanRequestTime = now;
        }

        final long setPeriod = manager.getLocationSetPeriod();
        if ( setPeriod != prevScanPeriod && manager.isScanning() ) {
            // update our location scanning speed
            Timber.i("setting location updates to: " + setPeriod);
            manager.setLocationUpdates(setPeriod, 0f);

            prevScanPeriod = setPeriod;
        }

        // have the gps listener to a self-check, in case it isn't getting updates anymore
        final GNSSListener gpsListener = manager.getGPSListener();
        Location location = null;
        if (gpsListener != null) {
            location = gpsListener.checkGetLocation(prefs);
            Timber.d( "wifi location: " + location);
        }

        // save the location every minute, for later runs, or viewing map during loss of location.
        if (now - lastSaveLocationTime > 30000L && location != null) {
            manager.getGPSListener().saveLocation();
            lastSaveLocationTime = now;
        }

        if (location != null) {
            lastHaveLocationTime = now;
        }
        // manager.info("now minus haveloctime: " + (now-lastHaveLocationTime)
        //    + " lastHaveLocationTime: " + lastHaveLocationTime);
        if (now - lastHaveLocationTime > 15000L) {
            // no location in a while, make sure we're subscribed to updates
            Timber.i("no location for a while, setting location update period: " + setPeriod);
            manager.setLocationUpdates(setPeriod, 0f);
            // don't do this until another period has passed
            lastHaveLocationTime = now;
        }

        final boolean showCurrent = prefs.getBoolean( PreferenceKeys.PREF_SHOW_CURRENT, true );

        final int preQueueSize = dbHelper.getQueueSize();
        final boolean fastMode = dbHelper.isFastMode();
        final ConcurrentLinkedHashMap<String,Network> networkCache = NetworkMapManager.getNetworkCache();
        boolean somethingAdded = false;
        int resultSize = 0;
        int newWifiForRun = 0;

        //TODO: should we memoize the ssidMatcher in the manager state as well?
        final Matcher ssidMatcher = FilterMatcher.getSsidFilterMatcher( prefs, PreferenceKeys.FILTER_PREF_PREFIX );
        final Matcher bssidMatcher = manager.getBssidFilterMatcher( PreferenceKeys.PREF_EXCLUDE_DISPLAY_ADDRS );
        final Matcher bssidDbMatcher = manager.getBssidFilterMatcher( PreferenceKeys.PREF_EXCLUDE_LOG_ADDRS );

        // can be null on shutdown
        if ( results != null ) {
            resultSize = results.size();
            for ( ScanResult result : results ) {
                Network network = networkCache.get( result.BSSID );
                if ( network == null ) {
                    network = new Network( result );
                    Timber.i(network.getDetail());
                    networkCache.put( network.getBssid(), network );
                }
                else {
                    // cache hit, just set the level
                    network.setLevel( result.level );
                }

                final boolean added = runNetworks.add( result.BSSID );
                if ( added ) {
                    newWifiForRun++;
                }
                somethingAdded |= added;

                if ( location != null  ) {
                    // if in fast mode, only add new-for-run stuff to the db queue
                    if ( fastMode && ! added ) {
                        Timber.i( "in fast mode, not adding seen-this-run: " + network.getBssid() );
                    } else {
                        // loop for stress-testing
                        // for ( int i = 0; i < 10; i++ ) {
                        boolean matches = false;
                        if (bssidDbMatcher != null) {
                            bssidDbMatcher.reset(network.getBssid());
                            matches = bssidDbMatcher.find();
                        }
                        if (!matches) {
                            dbHelper.addObservation(network, location, added);
                        }
                        // }
                    }
                } else {
//                    Timber.i("No Location");
                    // no location
                    boolean matches = false;
                    if (bssidDbMatcher != null) {
                        bssidDbMatcher.reset(network.getBssid());
                        matches = bssidDbMatcher.find();
                    }
//                    Timber.i("network id:" + network.getBssid());
//                    Timber.i("network found in DB:" + matches);
                    if (!matches) {
                        dbHelper.pendingObservation( network, added, false, false );
                    }
                }
            }
        }

        // check if there are more "New" nets
        final long newNetCount = dbHelper.getNewNetworkCount();
        final long newWifiCount = dbHelper.getNewWifiCount();
        final long newNetDiff = newWifiCount - prevNewNetCount;
        prevNewNetCount = newWifiCount;
//        Timber.i("Wifi Count:" + newNetCount);
//        Timber.i("new networks Count:" + newNetDiff);
//        if ( ! manager.isMuted() ) {
//            final boolean playRun = prefs.getBoolean( PreferenceKeys.PREF_FOUND_SOUND, true );
//            final boolean playNew = prefs.getBoolean( PreferenceKeys.PREF_FOUND_NEW_SOUND, true );
//            if ( newNetDiff > 0 && playNew ) {
//                manager.playNewNetSound();
//            }
//            else if ( somethingAdded && playRun ) {
//                manager.playRunNetSound();
//            }
//        }

//        if ( manager.getPhoneState().isPhoneActive() ) {
//            // a phone call is active, make sure we aren't speaking anything
//            manager.interruptSpeak();
//        }

        // TODO: this ties cell collection to WiFi collection - refactor cells onto their own timer
        // check cell tower info
        final int preCellForRun = runNetworks.size();
        int newCellForRun = 0;
        final Map<String,Network>cellNetworks = recordCellInfo(location);
        if ( cellNetworks != null ) {
            for (String key: cellNetworks.keySet()) {
                final Network cellNetwork = cellNetworks.get(key);
                if (cellNetwork != null) {
                    resultSize++;
                    if (runNetworks.size() > preCellForRun) {
                        newCellForRun++;
                    }
                }
            }
        }

        // check for "New" cell towers
        final long newCellCount = dbHelper.getNewCellCount();

        final long dbNets = dbHelper.getNetworkCount();
        final long dbLocs = dbHelper.getLocationCount();

        // update stat
//        manager.setNetCountUI();

        if ( scanRequestTime <= 0 ) {
            // wasn't set, set to now
            scanRequestTime = now;
        }

        // set the statics for the map
        NetworkMapManager.networkState.runNets = runNetworks.size();
        NetworkMapManager.networkState.runCells = runCells.size();
        NetworkMapManager.networkState.newNets = newNetCount;
        NetworkMapManager.networkState.newWifi = newWifiCount;
        NetworkMapManager.networkState.newCells = newCellCount;
        NetworkMapManager.networkState.currNets = resultSize;
        NetworkMapManager.networkState.currWifiScanDurMs = (now - scanRequestTime);
        NetworkMapManager.networkState.preQueueSize = preQueueSize;
        NetworkMapManager.networkState.dbNets = dbNets;
        NetworkMapManager.networkState.dbLocs = dbLocs;

        // do this if trail is empty, so as soon as we get first gps location it gets triggered
        // and will show up on map
        if ( newWifiForRun > 0 || newCellForRun > 0 || NetworkMapManager.networkState.networkCache.isEmpty() ) {
            if ( location == null ) {
                // save for later
                pendingWifiCount += newWifiForRun;
                pendingCellCount += newCellForRun;
                // manager.info("pendingCellCount: " + pendingCellCount);
            }
            else {
                // add any pendings
                // don't go crazy
                if ( pendingWifiCount > 25 ) {
                    pendingWifiCount = 25;
                }
                pendingWifiCount = 0;

                if ( pendingCellCount > 25 ) {
                    pendingCellCount = 25;
                }
                pendingCellCount = 0;
            }
        }

        // info( savedStats );

        // notify
//        if (listAdapter != null) {
//            listAdapter.notifyDataSetChanged();
//        }

//        manager.setScanStatusUI( resultSize, NetworkMapManager.networkState.currWifiScanDurMs);

//        manager.setDBQueue(preQueueSize);
        // we've shown it, reset it to the nonstop time above, or min_value if nonstop wasn't set.
        scanRequestTime = nonstopScanRequestTime;

//        if ( somethingAdded && ssidSpeak ) {
//            ssidSpeaker.speak();
//        }

//        final long speechPeriod = prefs.getLong( PreferenceKeys.PREF_SPEECH_PERIOD, manager.DEFAULT_SPEECH_PERIOD );
//        if ( speechPeriod != 0 && now - previousTalkTime > speechPeriod * 1000L ) {
//            doAnnouncement( preQueueSize, newWifiCount, newCellCount, now );
//        }
    }


    /**
     * trigger for cell collection and logging
     * @param location the current Location
     * @return a map of IDs::Network records
     */
    private Map<String,Network> recordCellInfo(final Location location) {
        TelephonyManager tele = (TelephonyManager) manager.getSystemService( Context.TELEPHONY_SERVICE );
        Map<String,Network> networks = new HashMap<>();
        if ( tele != null ) {
            try {
                //DEBUG: manager.info("SIM State: "+tele.getSimState() + "("+getNetworkTypeName()+")");
                @SuppressLint("MissingPermission") CellLocation currentCell = tele.getCellLocation();
                if (currentCell != null) {
                    Network currentNetwork = handleSingleCellLocation(currentCell, tele, location);
                    if (currentNetwork != null) {
                        networks.put(currentNetwork.getBssid(), currentNetwork);
//                        NetworkMapManager.networkState.currCells = 1;
                    }
                }

                // we can survey cells
                @SuppressLint("MissingPermission") List<CellInfo> infos = tele.getAllCellInfo();
                if (null != infos) {
                    for (final CellInfo cell : infos) {
                        Network network = handleSingleCellInfo(cell, tele, location);
                        if (null != network) {
                            if (networks.containsKey(network.getBssid())) {
                                //DEBUG: manager.info("matching network already in map: " + network.getBssid());
                                Network n = networks.get(network.getBssid());
                                //TODO merge to improve data instead of replace?
                                networks.put(network.getBssid(), network);
                            } else {
                                networks.put(network.getBssid(), network);
                            }
                        }
                    }
//                    NetworkMapManager.networkState.currCells = infos.size();
                }
                //ALIBI: haven't been able to find a circumstance where there's anything but garbage in these.
                //  should be an alternative to getAllCellInfo above for older phones, but oly dBm looks valid


                /*List<NeighboringCellInfo> list = tele.getNeighboringCellInfo();
                if (null != list) {
                    for (final NeighboringCellInfo cell : list) {
                        //networks.put(
                        handleSingleNeighboringCellInfo(cell, tele, location);
                        //);
                    }
                }*/
            } catch (SecurityException sex) {
                Timber.w(sex, "unable to scan cells due to permission issue: ");
            } catch (NullPointerException ex) {
                Timber.w(ex,"NPE on cell scan: ");
            }
        }
        return networks;
    }

    /**
     * Translate a CellInfo record to Network record / sorts by type and capabilities to correct update methods
     * (new implementation)
     */
    private Network handleSingleCellInfo(final CellInfo cellInfo, final TelephonyManager tele, final Location location) {
        if (cellInfo == null) {
            Timber.i("null cellInfo");
            // ignore
        } else {
            if (manager.DEBUG_CELL_DATA) {
                Timber.i("cell: " + cellInfo + " class: " + cellInfo.getClass().getCanonicalName());
            }
            GsmOperator g;
            try {
                switch (cellInfo.getClass().getSimpleName()) {
                    case "CellInfoCdma":
                        return handleSingleCdmaInfo(((CellInfoCdma) (cellInfo)), tele, location);
                    case "CellInfoGsm":
                        g = new GsmOperator(((CellInfoGsm) (cellInfo)).getCellIdentity());
                        CellSignalStrengthGsm cellStrengthG = ((CellInfoGsm) (cellInfo)).getCellSignalStrength();
                        return addOrUpdateCell(g.getOperatorKeyString(), g.getOperatorString(), g.getXfcn(), "GSM",
                                cellStrengthG.getDbm(), NetworkType.typeForCode("G"), location);
                    case "CellInfoLte":
                        g = new GsmOperator(((CellInfoLte) (cellInfo)).getCellIdentity());
                        CellSignalStrengthLte cellStrengthL = ((CellInfoLte) (cellInfo)).getCellSignalStrength();
                        return addOrUpdateCell(g.getOperatorKeyString(), g.getOperatorString(), g.getXfcn(), "LTE",
                                cellStrengthL.getDbm(), NetworkType.typeForCode("L"), location);
                    case "CellInfoWcdma": {
                        g = new GsmOperator(((CellInfoWcdma) (cellInfo)).getCellIdentity());
                        CellSignalStrengthWcdma cellStrengthW = ((CellInfoWcdma) (cellInfo)).getCellSignalStrength();
                        return addOrUpdateCell(g.getOperatorKeyString(), g.getOperatorString(), g.getXfcn(), "WCDMA",
                                cellStrengthW.getDbm(), NetworkType.typeForCode("D"), location);
                    }
                    case "CellInfoNr":
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            g = new GsmOperator((CellIdentityNr) ((CellInfoNr) (cellInfo)).getCellIdentity());
                            CellSignalStrength cellStrengthW = ((CellInfoNr) (cellInfo)).getCellSignalStrength();
                            return addOrUpdateCell(g.getOperatorKeyString(), g.getOperatorString(), g.getXfcn(), "NR",
                                    cellStrengthW.getDbm(), NetworkType.typeForCode("D"), location);
                        }
                        break;
                    default:
                        Timber.w("Unknown cell case: " + cellInfo.getClass().getSimpleName());
                        break;
                }
            } catch (GsmOperatorException gsex) {
                //manager.info("skipping invalid cell data: "+gsex);
            }
        }
        return null;
    }

    /**
     * no test environment to implement this, but the handleCellInfo methods should work to complete it.
     * NeighboringCellInfos never appear in practical testing
     */
    @Deprecated
    private Network handleSingleNeighboringCellInfo(final NeighboringCellInfo cellInfo, final TelephonyManager tele, final Location location) {
        //noinspection StatementWithEmptyBody
        if (null == cellInfo) {
            // ignore
        } else {
            if (manager.DEBUG_CELL_DATA) {
                Timber.i("NeighboringCellInfo:" +
                        "\n\tCID: " + cellInfo.getCid() +
                        "\n\tLAC: " + cellInfo.getLac() +
                        "\n\tType: " + cellInfo.getNetworkType() +
                        "\n\tPsc: " + cellInfo.getPsc() +
                        "\n\tRSSI: " + cellInfo.getRssi());
            }
            switch (cellInfo.getNetworkType()) {
                case TelephonyManager.NETWORK_TYPE_GPRS:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_EDGE:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_UMTS:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_HSPA:
                    //TODO!!!
                    break;
                default:
                    //TODO!!!
                    break;
            }
        }
        return null; //TODO:
    }

    /**
     * Translate and categorize a CellLocation record for update and logging
     * (old/compat implementation)
     */
    private Network handleSingleCellLocation(final CellLocation cellLocation,
                                             final TelephonyManager tele, final Location location) {
        String bssid = null;
        NetworkType type = null;
        Network network = null;
        String ssid = null;

        //noinspection StatementWithEmptyBody
        if ( cellLocation == null ) {
            // ignore
        } else if ( cellLocation.getClass().getSimpleName().equals("CdmaCellLocation") ) {
            try {
                final int systemId = ((CdmaCellLocation) cellLocation).getSystemId();
                final int networkId = ((CdmaCellLocation) cellLocation).getNetworkId();
                final int baseStationId = ((CdmaCellLocation) cellLocation).getBaseStationId();
                if ( systemId > 0 && networkId >= 0 && baseStationId >= 0 ) {
                    bssid = systemId + "_" + networkId + "_" + baseStationId;
                    type = NetworkType.CDMA;
                }
                //TODO: not sure if there's anything else we can do here
                ssid = tele.getNetworkOperatorName();
            } catch ( Exception ex ) {
                Timber.e(ex,"CDMA reflection exception: " + ex);
            }
        } else if ( cellLocation instanceof GsmCellLocation ) {
            GsmCellLocation gsmCellLocation = (GsmCellLocation) cellLocation;
            final String operatorCode = tele.getNetworkOperator();
            if ( gsmCellLocation.getLac() >= 0 && gsmCellLocation.getCid() >= 0) {
                bssid = tele.getNetworkOperator() + "_" + gsmCellLocation.getLac() + "_" + gsmCellLocation.getCid();
                try {
                    //TODO: 1/2: when cell gets its own listener, make this async (Strict)
                    ssid = GsmOperator.getOperatorName(tele.getNetworkOperator());
                } catch (SQLException sex) {
                    Timber.e(sex, "failed to get op for "+tele.getNetworkOperator());
                }
                //DEBUG: manager.info("GSM Operator name: "+ ssid + " vs TM: "+ tele.getNetworkOperatorName());
                type = NetworkType.GSM;
            }
            if (operatorCode == null || operatorCode.isEmpty()) {
                return null;
            }
        } else {
            Timber.w("Unhandled CellLocation type: "+cellLocation.getClass().getSimpleName());
        }

        if ( bssid != null ) {
            final String networkType = CellNetworkLegend.getNetworkTypeName(tele);
            final String capabilities = networkType + ";" + tele.getNetworkCountryIso();

            int strength = 0;
            PhoneState phoneState = manager.getPhoneState();
            if (phoneState != null) {
                strength = phoneState.getStrength();
            }

            if ( NetworkType.GSM.equals(type) ) {
                // never seems to work well in practice
                strength = gsmDBmMagicDecoderRing( strength );
            }

            if (manager.DEBUG_CELL_DATA) {
                Timber.i("bssid: " + bssid);
                Timber.i("strength: " + strength);
                Timber.i("ssid: " + ssid);
                Timber.i("capabilities: " + capabilities);
                Timber.i("networkType: " + networkType);
                Timber.i("location: " + location);
            }

            final ConcurrentLinkedHashMap<String,Network> networkCache = manager.getNetworkCache();

            final boolean newForRun = runNetworks.add( bssid );
            runCells.add( bssid );

            network = networkCache.get( bssid );
            if ( network == null ) {
                network = new Network( bssid, ssid, 0, capabilities, strength, type );
                networkCache.put( network.getBssid(), network );
            } else {
                network.setLevel(strength);
            }

            if ( location != null && (newForRun || network.getLatLng() == null) ) {
                // set the LatLng for mapping
                final LatLng LatLng = new LatLng( location.getLatitude(), location.getLongitude() );
                network.setLatLng( LatLng );
            }

            if ( location != null ) {
                dbHelper.addObservation(network, location, newForRun);
            }
        }
        return network;
    }

    /**
     * This was named RSSI - but I think it's more accurately dBm. Also worth noting that ALL the
     * SignalStrength changes we've received in PhoneState for GSM networks have been resulting in
     * "99" -> -113 in every measurable case on all hardware in testing.
     */
    @Deprecated
    private int gsmDBmMagicDecoderRing( int strength ) {
        int retval;
        if ( strength == 99 ) {
            // unknown
            retval = CELL_MIN_STRENGTH;
        }
        else {
            //  0        -113 dBm or less
            //  1        -111 dBm
            //  2...30   -109... -53 dBm
            //  31        -51 dBm or greater
            //  99 not known or not detectable
            retval = strength * 2 + CELL_MIN_STRENGTH;
        }
        //DEBUG: manager.info("strength: " + strength + " dBm: " + retval);
        return retval;
    }

    /**
     * Voice announcement method for scan
     */
//    private void doAnnouncement( int preQueueSize, long newWifiCount, long newCellCount, long now ) {
//        final SharedPreferences prefs = manager.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );
//        StringBuilder builder = new StringBuilder();
//
//        if ( manager.getGPSListener().getCurrentLocation() == null && prefs.getBoolean( PreferenceKeys.PREF_SPEECH_GPS, true ) ) {
//            builder.append(manager.getString(R.string.tts_no_gps_fix)).append(", ");
//        }
//
//        // run, new, queue, miles, time, battery
//        if ( prefs.getBoolean( PreferenceKeys.PREF_SPEAK_RUN, true ) ) {
//            builder.append(manager.getString(R.string.run)).append(" ")
//                    .append(runNetworks.size()).append( ", " );
//        }
//        if ( prefs.getBoolean( PreferenceKeys.PREF_SPEAK_NEW_WIFI, true ) ) {
//            builder.append(manager.getString(R.string.tts_new_wifi)).append(" ")
//                    .append(newWifiCount).append( ", " );
//        }
//        if ( prefs.getBoolean( PreferenceKeys.PREF_SPEAK_NEW_CELL, true ) ) {
//            builder.append(manager.getString(R.string.tts_new_cell)).append(" ")
//                    .append(newCellCount).append( ", " );
//        }
//        if ( preQueueSize > 0 && prefs.getBoolean( PreferenceKeys.PREF_SPEAK_QUEUE, true ) ) {
//            builder.append(manager.getString(R.string.tts_queue)).append(" ")
//                    .append(preQueueSize).append( ", " );
//        }
//        if ( prefs.getBoolean( PreferenceKeys.PREF_SPEAK_MILES, true ) ) {
//            final float dist = prefs.getFloat( PreferenceKeys.PREF_DISTANCE_RUN, 0f );
//            final String distString = UINumberFormat.metersToString(prefs, numberFormat1, manager, dist, false );
//            builder.append(manager.getString(R.string.tts_from)).append(" ")
//                    .append(distString).append( ", " );
//        }
//        if ( prefs.getBoolean( PreferenceKeys.PREF_SPEAK_TIME, true ) ) {
//            String time = timeFormat.format( new Date() );
//            // time is hard to say.
//            time = time.replace(" 00", " " + manager.getString(R.string.tts_o_clock));
//            time = time.replace(" 0", " " + manager.getString(R.string.tts_o) +  " ");
//            builder.append( time ).append( ", " );
//        }
//        final int batteryLevel = manager.getBatteryLevelReceiver().getBatteryLevel();
//        if ( batteryLevel >= 0 && prefs.getBoolean( PreferenceKeys.PREF_SPEAK_BATTERY, true ) ) {
//            builder.append(manager.getString(R.string.tts_battery)).append(" ").append(batteryLevel).append(" ").append(manager.getString(R.string.tts_percent)).append(", ");
//        }
//
//        final String speak = builder.toString();
//        Timber.i( "speak: " + speak );
//        if (! "".equals(speak)) {
//            manager.speak( builder.toString() );
//        }
//        previousTalkTime = now;
//    }

    public void setupWifiTimer( final boolean turnedWifiOn ) {
        Timber.i( "create wifi timer" );
        if ( wifiTimer == null ) {
            wifiTimer = new Handler();
            final Runnable mUpdateTimeTask = new Runnable() {
                @Override
                public void run() {
                    // make sure the app isn't trying to finish
                    if ( ! manager.isFinishing() && !manager.isSerializing() ) {
                        // info( "timer start scan" );
                        doWifiScan();
                        if ( scanRequestTime <= 0 ) {
                            scanRequestTime = System.currentTimeMillis();
                        }
                        long period = getScanPeriod();
                        // check if set to "continuous"
                        if ( period == 0L ) {
                            // set to default here, as a scan will also be requested on the scan result listener
                            period = manager.SCAN_DEFAULT;
                        }
                        // info("wifitimer: " + period );
                        wifiTimer.postDelayed( this, period );
                    }
                    else {
                        Timber.i( "finishing timer" );
                    }
                }
            };
            wifiTimer.removeCallbacks( mUpdateTimeTask );
            wifiTimer.postDelayed( mUpdateTimeTask, 100 );

            if ( turnedWifiOn ) {
                Timber.i( "not immediately running wifi scan, since it was just turned on"
                        + " it will block for a few seconds and fail anyway");
            }
            else {
                Timber.i( "start first wifi scan");
                // starts scan, sends event when done
                final boolean scanOK = doWifiScan();
                if ( scanRequestTime <= 0 ) {
                    scanRequestTime = System.currentTimeMillis();
                }
                Timber.i( "startup finished. wifi scanOK: " + scanOK );
            }
        }
    }

    /**
     * get the scan period based on preferences and current speed
     */
    public long getScanPeriod() {
        final SharedPreferences prefs = manager.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );

        String scanPref = PreferenceKeys.PREF_SCAN_PERIOD;
        long defaultRate = manager.SCAN_DEFAULT;
        // if over 5 mph
        Location location = null;
        final GNSSListener gpsListener = manager.getGPSListener();
        if (gpsListener != null) {
            location = gpsListener.getCurrentLocation();
        }
        if ( location != null && location.getSpeed() >= 2.2352f ) {
            scanPref = PreferenceKeys.PREF_SCAN_PERIOD_FAST;
            defaultRate = manager.SCAN_FAST_DEFAULT;
        }
        else if ( location == null || location.getSpeed() < 0.1f ) {
            scanPref = PreferenceKeys.PREF_SCAN_PERIOD_STILL;
            defaultRate = manager.SCAN_STILL_DEFAULT;
        }
        return prefs.getLong( scanPref, defaultRate );
    }

    /**
     * Schedule the next WiFi scan
     */
    public void scheduleScan() {
        wifiTimer.post(this::doWifiScan);
    }

    /**
     * only call this from a Handler
     * @return true if startScan success
     */
    private boolean doWifiScan() {
        Timber.i("do wifi scan. lastScanTime: " + lastScanResponseTime);
        final WifiManager wifiManager = (WifiManager) manager.getContext().getSystemService(Context.WIFI_SERVICE);
        boolean success = false;

        if (manager.isScanning()) {
            if ( ! scanInFlight ) {
                try {
                    success = wifiManager.startScan();
                }
                catch (Exception ex) {
                    Timber.w(ex, "exception starting scan: " + ex);
                }
                if ( success ) {
                    scanInFlight = true;
                }
            }

            final long now = System.currentTimeMillis();
            if ( lastScanResponseTime < 0 ) {
                // use now, since we made a request
                lastScanResponseTime = now;
            } else {
                final long sinceLastScan = now - lastScanResponseTime;
//                Timber.i("startScan returned " + success + ". last response seconds ago: " + sinceLastScan/1000d);
                final SharedPreferences prefs = manager.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );
                final long resetWifiPeriod = prefs.getLong(
                        PreferenceKeys.PREF_RESET_WIFI_PERIOD, manager.DEFAULT_RESET_WIFI_PERIOD );

                if ( resetWifiPeriod > 0 && sinceLastScan > resetWifiPeriod ) {
                    Timber.w("Time since last scan: " + sinceLastScan + " milliseconds");
                    if ( now - lastWifiUnjamTime > resetWifiPeriod ) {
//                        final boolean disableToast = prefs.getBoolean(PreferenceKeys.PREF_DISABLE_TOAST, false);
//                        if (!disableToast) {
//                            Handler handler = new Handler(Looper.getMainLooper());
//                            handler.post(() -> WiGLEToast.showOverActivity(manager, R.string.error_general, manager.getString(R.string.wifi_jammed)));
//                        }
                        scanInFlight = false;
                        try {
                            if (wifiManager != null) {
                                wifiManager.setWifiEnabled(false);
                                wifiManager.setWifiEnabled(true);
                            }
                        } catch (SecurityException ex) {
                            Timber.i(ex, "exception resetting wifi: " + ex);
                        }
                        lastWifiUnjamTime = now;
                    }
                }
            }
        }
        else {
//            // scanning is off. since we're the only timer, update the UI
//            manager.setScanStatusUI(manager.getString(R.string.list_scanning_off));
            // keep the scan times from getting huge
            scanRequestTime = System.currentTimeMillis();
            // reset this
            lastScanResponseTime = Long.MIN_VALUE;
        }

        // battery kill
        if ( ! manager.isTransferring() ) {
            final SharedPreferences prefs = manager.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );
            long batteryKill = prefs.getLong(
                    PreferenceKeys.PREF_BATTERY_KILL_PERCENT, manager.DEFAULT_BATTERY_KILL_PERCENT);

            if ( manager.getBatteryLevelReceiver() != null ) {
                final int batteryLevel = manager.getBatteryLevelReceiver().getBatteryLevel();
                final int batteryStatus = manager.getBatteryLevelReceiver().getBatteryStatus();
                // manager.info("batteryStatus: " + batteryStatus);
                // give some time since starting up to change this configuration
                if ( batteryKill > 0 && batteryLevel > 0 && batteryLevel <= batteryKill
                        && batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING
                        && (System.currentTimeMillis() - constructionTime) > 30000L) {
                    if (null != manager) {
//                        final String text = manager.getString(R.string.battery_at) + " " + batteryLevel + " "
//                            + manager.getString(R.string.battery_postfix);
//
//                        Handler handler = new Handler(Looper.getMainLooper());
//                        handler.post(() -> WiGLEToast.showOverActivity(manager, R.string.error_general, text));
                        Timber.w("low battery, shutting down");
//                        manager.speak(text);
//                        manager.finishSoon(4000L, false);
                    }
                }
            }
        }

        return success;
    }

    /**
     * CDMA entrypoint to update and logging
     */
    private Network handleSingleCdmaInfo(final CellInfoCdma cellInfo, final TelephonyManager tele, final Location location) {
        CellIdentityCdma cellIdentC = cellInfo.getCellIdentity();
        CellSignalStrengthCdma cellStrengthC = cellInfo.getCellSignalStrength();

        final int bssIdInt = cellIdentC.getBasestationId();
        final int netIdInt = cellIdentC.getNetworkId();
        final int systemIdInt = cellIdentC.getSystemId();

        if ((Integer.MAX_VALUE == bssIdInt) || (Integer.MAX_VALUE == netIdInt) || (Integer.MAX_VALUE == systemIdInt)) {
            Timber.i("Discarding CDMA cell with invalid ID");
            return null;
        }

        final String networkKey = systemIdInt + "_" + netIdInt + "_" + bssIdInt;
        final int dBmLevel = cellStrengthC.getDbm();
        if (manager.DEBUG_CELL_DATA) {

            String res = "CDMA Cell:" +
                    "\n\tBSSID:" + bssIdInt +
                    "\n\tNet ID:" + netIdInt +
                    "\n\tSystem ID:" + systemIdInt +
                    "\n\tNetwork Key: " + networkKey;

            res += "\n\tLat: " + new Double(cellIdentC.getLatitude()) / 4.0d / 60.0d / 60.0d;
            res += "\n\tLon: " + new Double(cellIdentC.getLongitude()) / 4.0d / 60.0d / 60.0d;
            res += "\n\tSignal: " + cellStrengthC.getCdmaLevel();

            int rssi = cellStrengthC.getEvdoDbm() != 0 ? cellStrengthC.getEvdoDbm() : cellStrengthC.getCdmaDbm();
            res += "\n\tRSSI: " + rssi;

            final int asuLevel = cellStrengthC.getAsuLevel();

            res += "\n\tSSdBm: " + dBmLevel;
            res += "\n\tSSasu: " + asuLevel;
            res += "\n\tEVDOdBm: " + cellStrengthC.getEvdoDbm();
            res += "\n\tCDMAdBm: " + cellStrengthC.getCdmaDbm();
            Timber.i(res);
        }
        //TODO: don't see any way to get CDMA channel from current CellInfoCDMA/CellIdentityCdma
        //  references http://niviuk.free.fr/cdma_band.php
        return addOrUpdateCell(networkKey,
                /*TODO: can we improve on this?*/ tele.getNetworkOperator(),
                0, "CDMA", dBmLevel, NetworkType.typeForCode("C"), location);

    }

    /**
     * Cell update and logging
     */
    private Network addOrUpdateCell(final String bssid, final String operator,
                                    final int frequency, final String networkTypeName,
                                    final int strength, final NetworkType type,
                                    final Location location) {

        final String capabilities = networkTypeName + ";" + operator;

        final ConcurrentLinkedHashMap<String,Network> networkCache = manager.getNetworkCache();
        final boolean newForRun = runNetworks.add( bssid );
        runCells.add( bssid );

        Network network = networkCache.get( bssid );

        try {
            //TODO: 2/2: when cell gets its own listener, make this async (Strict)
            final String operatorName = GsmOperator.getOperatorName(operator);

            if ( network == null ) {
                network = new Network( bssid, operatorName, frequency, capabilities, (Integer.MAX_VALUE == strength) ? CELL_MIN_STRENGTH : strength, type );
                networkCache.put( network.getBssid(), network );
            } else {
                network.setLevel( (Integer.MAX_VALUE == strength) ? CELL_MIN_STRENGTH : strength);
                network.setFrequency(frequency);
            }

            if ( location != null && (newForRun || network.getLatLng() == null) ) {
                // set the LatLng for mapping
                final LatLng LatLng = new LatLng( location.getLatitude(), location.getLongitude() );
                network.setLatLng( LatLng );
            }

            if ( location != null ) {
                dbHelper.addObservation(network, location, newForRun);
            }
        } catch (SQLException sex) {
            Timber.e(sex, "Error in add/update:");
        }
        //ALIBI: allows us to run in conjunction with current-carrier detection
        return network;
    }

}
