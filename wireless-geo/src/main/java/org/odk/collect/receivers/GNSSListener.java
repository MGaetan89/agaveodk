package org.odk.collect.receivers;

import static android.content.Context.LOCATION_SERVICE;
import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.GpsStatus.Listener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.odk.collect.permissions.ContextCompatPermissionChecker;
import org.odk.collect.util.KalmanLatLong;
import org.odk.collect.permissions.PermissionsChecker;
import org.odk.collect.util.PreferenceKeys;
import org.odk.collect.NetworkMapManager;
import org.odk.collect.db.DatabaseHelper;
import timber.log.Timber;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.model.LatLng;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

@RequiresApi(api = Build.VERSION_CODES.CUPCAKE)
public class GNSSListener implements LocationListener {
    public static final long GPS_TIMEOUT_DEFAULT = 5000L;
    public static final long NET_LOC_TIMEOUT_DEFAULT = 10000L;
    public static final float LERP_MIN_THRESHOLD_METERS = 20.0f;
    public static final float LERP_MAX_THRESHOLD_METERS = 200f;

    public static final float MIN_ROUTE_LOCATION_DIFF_METERS = 3.8f;
    public static final long MIN_ROUTE_LOCATION_DIFF_TIME = 3 * 1000;
    public static final float MIN_ROUTE_LOCATION_PRECISION_METERS = 24.99f;

    //Minimum difference between updates to change total distance.
    public static final float MACH_1_3_METERS_SEC = 445.9f; // compensate for use on vehicles up to the HB-88
    // ALIBI: excludes the snail-stumbling community until they work out weight/power supply problems
    public static final float SLOW_METERS_SEC = 0.025f;     // snails actually vary between 0.013m/s and 0.0028m/s
    // ALIBI: maybe this is a happy medium for kalman filtering?
    public static final float GOLDILOCKS_METERS_SEC = 3.0f;

    private final AtomicLong currentDistPointCount = new AtomicLong(0L);

    private NetworkMapManager networkMapManager;
    private final DatabaseHelper dbHelper;
    private Location currentLocation;
    private Location prevLocation;
    private Location networkLocation;
    private GpsStatus gpsStatus;
    private GnssStatus gnssStatus;
    // set these times to avoid NPE in locationOK() seen by <DooMMasteR>
    private Long lastLocationTime = 0L;
    private Long lastNetworkLocationTime = 0L;
    private Long satCountLowTime = 0L;
    private float previousSpeed = 0f;
    private LocationListener mapLocationListener;
    private int prevStatus = 0;
    private final KalmanLatLong kalmanLatLong;
    private PermissionsChecker permissionsChecker;

    public GNSSListener(final NetworkMapManager networkMapManager, final DatabaseHelper dbHelper) {
        this.networkMapManager = networkMapManager;
        this.dbHelper = dbHelper;
        this.permissionsChecker = new ContextCompatPermissionChecker(networkMapManager.getContext());
        final SharedPreferences prefs = networkMapManager.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );
        this.kalmanLatLong = prefs.getBoolean(PreferenceKeys.PREF_GPS_KALMAN_FILTER,true) ?
                new KalmanLatLong(GOLDILOCKS_METERS_SEC): null;
    }

    public void setMapListener( LocationListener mapLocationListener ) {
        this.mapLocationListener = mapLocationListener;
    }

    public void setPermissionsChecker( PermissionsChecker permissionsChecker ) {
        this.permissionsChecker = permissionsChecker;
    }

    public void handleScanStop() {
        gnssStatus = null;
        gpsStatus = null;
        currentLocation = null;
    }

    @Override
    public void onLocationChanged( final Location newLocation ) {
        Timber.d("GNSS GPS onLocationChanged: " + newLocation);
        updateLocationData( newLocation );

        if ( mapLocationListener != null ) {
            mapLocationListener.onLocationChanged( newLocation );
        }
    }

    @Override
    public void onLocationChanged(final List<Location> locations) {
        Timber.d("GNSS GPS onLocationChanged<batched>: " + locations);
        Location l = locations.get(locations.size()-1);
        onLocationChanged(l);
    }

    @Override
    public void onProviderDisabled( final String provider ) {
        Timber.d("provider disabled: " + provider);

        if ( mapLocationListener != null ) {
            mapLocationListener.onProviderDisabled( provider );
        }
    }

    @Override
    public void onProviderEnabled( final String provider ) {
        Timber.d("provider enabled: " + provider);
        if (null != kalmanLatLong) {
            kalmanLatLong.reset();
        }
        if ( mapLocationListener != null ) {
            mapLocationListener.onProviderEnabled( provider );
        }
    }

    @Override
    public void onStatusChanged( final String provider, final int status, final Bundle extras ) {
        final boolean isgps = "gps".equals(provider);
        if (!isgps || status != prevStatus) {
            Timber.d("provider status changed: " + provider + " status: " + status);
            if (isgps) prevStatus = status;
        }

        if ( mapLocationListener != null ) {
            mapLocationListener.onStatusChanged( provider, status, extras );
        }
    }

    public void onGnssStatusChanged(GnssStatus gnssStatus) {
        this.gnssStatus = gnssStatus;
        Timber.d("GNSS Status:" + gnssStatus.toString());
    }

    /** newLocation can be null */
    @SuppressLint("MissingPermission")
    private synchronized void updateLocationData(final Location L ) {
        Location newLocation = L;
        Timber.i("validating location data: " + newLocation);
        /*
          ALIBI: the location manager call's a non-starter if permission hasn't been granted.
         */
        if ( Build.VERSION.SDK_INT >= 23 &&
                !permissionsChecker.isPermissionGranted(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )) {
            Timber.e("Missing location permissions");
            return;
        }
        final SharedPreferences prefs = networkMapManager.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );

        final LocationManager locationManager = (LocationManager)
                networkMapManager.getSystemService(LOCATION_SERVICE);

        final int satCount = getSatCount();

        final long gpsTimeout = prefs.getLong(PreferenceKeys.PREF_GPS_TIMEOUT, GPS_TIMEOUT_DEFAULT);
        final long netLocTimeout = prefs.getLong(PreferenceKeys.PREF_NET_LOC_TIMEOUT, NET_LOC_TIMEOUT_DEFAULT);

        boolean newOK = newLocation != null;
        if (null != newLocation && null != kalmanLatLong &&  kalmanLatLong.getAccuracy() < 0) {
            kalmanLatLong.setState(newLocation.getLatitude(), newLocation.getLongitude(), newLocation.getAccuracy(), newLocation.getTime());
        } else if (null != newLocation) {
            if (null != kalmanLatLong) {
                kalmanLatLong.process(newLocation.getLatitude(), newLocation.getLongitude(), newLocation.getAccuracy(), newLocation.getTime());
                //DEBUG: NetworkMapManager.error("KALMAN TEST: [lat: " + newLocation.getLatitude() + " v. (k):" +  kalmanLatLong.getLat() + " lon: " + newLocation.getLongitude() + " v. (k):" + kalmanLatLong.getLng() + "], acc(k): " + kalmanLatLong.getAccuracy() + " location prov: " + newLocation.getProvider());
                //Testing: replace with smoothed
                newLocation.setLatitude(kalmanLatLong.getLat());
                newLocation.setLongitude(kalmanLatLong.getLng());
            }
        }
        final boolean locOK = locationOK(currentLocation, satCount, gpsTimeout, netLocTimeout );
        final long now = System.currentTimeMillis();
        if ( newOK ) {
            if ( NETWORK_PROVIDER.equals( newLocation.getProvider() )
            || FUSED_PROVIDER.equals( newLocation.getProvider() ) ) {
                // save for later, in case we lose gps
                networkLocation = newLocation;
                lastNetworkLocationTime = now;
            }
            else {
                lastLocationTime = now;
                // make sure there's enough sats on this new gps location
                newOK = locationOK( newLocation, satCount, gpsTimeout, netLocTimeout );
            }
        }

        final boolean logRoutes = prefs.getBoolean(PreferenceKeys.PREF_LOG_ROUTES, true);
        final boolean showRoute = prefs.getBoolean(PreferenceKeys.PREF_VISUALIZE_ROUTE, true);

        final boolean netLocOK = locationOK( networkLocation, satCount, gpsTimeout, netLocTimeout );

        boolean wasProviderChange = false;
        if ( ! locOK ) {
            if ( newOK ) {
                wasProviderChange = true;
                //noinspection RedundantIfStatement
                if ( currentLocation != null && ! currentLocation.getProvider().equals( newLocation.getProvider() ) ) {
                    wasProviderChange = false;
                }

                currentLocation = newLocation;
            }
            else if ( netLocOK ) {
                currentLocation = networkLocation;
                wasProviderChange = true;
            }
            else if ( currentLocation != null ) {
                // transition to null
                Timber.d( "nulling location: " + currentLocation);
                currentLocation = null;
                wasProviderChange = true;
                // make sure we're registered for updates
                networkMapManager.setLocationUpdates();
            }
        }
        else if ( newOK && GPS_PROVIDER.equals( newLocation.getProvider() ) ) {
            if ( NETWORK_PROVIDER.equals( currentLocation.getProvider() ) ) {
                // this is an upgrade from network to gps
                wasProviderChange = true;
            }
            currentLocation = newLocation;
            if ( wasProviderChange ) {
                // save it in prefs
                saveLocation();
            }
        }
        else if ( newOK && NETWORK_PROVIDER.equals( newLocation.getProvider() ) ) {
            if ( NETWORK_PROVIDER.equals( currentLocation.getProvider() ) ) {
                // just a new network provided location over an old one
                currentLocation = newLocation;
            }
        }
        Timber.i("current location data: " + currentLocation);
        if (currentLocation != null && currentLocation.getTime() != 0L &&
                currentLocation.getAccuracy()  < MIN_ROUTE_LOCATION_PRECISION_METERS &&
                currentLocation.getAccuracy()  > 0.0d) {
            if (prevLocation != null && prevLocation.getTime() < currentLocation.getTime()) {
                float dist = prevLocation.distanceTo(currentLocation);
                if ((dist > MIN_ROUTE_LOCATION_DIFF_METERS &&
                        (currentLocation.getTime() - prevLocation.getTime() > MIN_ROUTE_LOCATION_DIFF_TIME)
                )) {
                    if (realisticMovement(dist, (float) (currentLocation.getTime() - prevLocation.getTime()) * 0.001f,
                            prevLocation.getAccuracy(), currentLocation.getAccuracy())) {
                        final Editor edit = prefs.edit();
                        edit.putFloat(PreferenceKeys.PREF_DISTANCE_RUN,
                                dist + prefs.getFloat(PreferenceKeys.PREF_DISTANCE_RUN, 0f));
                        edit.putFloat(PreferenceKeys.PREF_DISTANCE_TOTAL,
                                dist + prefs.getFloat(PreferenceKeys.PREF_DISTANCE_TOTAL, 0f));
                        edit.apply();
                        //DEBUG: long distPoints = currentDistPointCount.incrementAndGet();
                        //DEBUG: Timber.i("dist points: "+distPoints+" vs. route points: "+ dbHelper.getCurrentRoutePointCount());
                    }
                    if (dist > LERP_MIN_THRESHOLD_METERS && dbHelper != null) {
                        if (dist > LERP_MAX_THRESHOLD_METERS) {
                            Timber.w("Diff is too large, not lerping. " + dist + " meters");
                            dbHelper.clearPendingObservations();
                        } else if (!currentLocation.equals(prevLocation)) {
                            Timber.d("lerping for " + dist + " meters");
                            dbHelper.recoverLocations(currentLocation);
                        }
                    }
                    // set for next time; only update if this was a distance calc-event.
                    prevLocation = currentLocation;
                }
            } else if (prevLocation == null ||  prevLocation.getTime() < currentLocation.getTime()) {
                // initialize previous location
                prevLocation = currentLocation;
            } else if (prevLocation != null){
                Timber.w("Location timestamp ("+ currentLocation.getTime()+") <= previous location timestamp ("+ prevLocation.getTime()+")");
                //ALIBI: we're ignoring this rather than trying to slot it in only because we'd need an in-memory or DB route otherwise.
            }
        }

        // do lerp if need be
        if ( currentLocation == null ) {
            if ( prevLocation != null ) {
                if (null != dbHelper) {
                    dbHelper.lastLocation(prevLocation);
                    Timber.d("set last location for lerping");
                }
            }
        }

//        // for maps. so lame!
//        NetworkMapManager.networkState.location = currentLocation;
//        boolean scanScheduled = false;
//        if ( currentLocation != null ) {
//            final float currentSpeed = currentLocation.getSpeed();
//            if ( (previousSpeed == 0f && currentSpeed > 0f)
//                    || (previousSpeed < 5f && currentSpeed >= 5f)) {
//                // moving faster now than before, schedule a scan because the timing config pry changed
//                Timber.d("Going faster, scheduling scan");
//                networkMapManager.scheduleScan();
//                scanScheduled = true;
//            }
//            previousSpeed = currentSpeed;
//        }
//        else {
//            previousSpeed = 0f;
//        }

        if ( wasProviderChange ) {
            Timber.d( "wasProviderChange: satCount: " + satCount
                    + " newOK: " + newOK + " locOK: " + locOK + " netLocOK: " + netLocOK
                    + (newOK ? " newProvider: " + newLocation.getProvider() : "")
                    + (locOK ? " locProvider: " + currentLocation.getProvider() : "")
                    + " newLocation: " + newLocation );

            // get the ball rolling
            Timber.d("Location provider changed, scheduling scan");
            networkMapManager.scheduleScan();
        }

        if (logRoutes && null != currentLocation) {
            final long routeId = prefs.getLong(PreferenceKeys.PREF_ROUTE_DB_RUN, 0L);
            try {
                if (null != dbHelper) {
                    dbHelper.logRouteLocation(currentLocation, NetworkMapManager.networkState.currNets,
                            NetworkMapManager.networkState.currCells, NetworkMapManager.networkState.currBt, routeId);
                }
            } catch (Exception ex) {
                Timber.e(ex, "failed to log route update: ");
            }
        } else if (showRoute && null != currentLocation) {
            try {
                if (null != dbHelper) {
                    dbHelper.logRouteLocation(currentLocation, NetworkMapManager.networkState.currNets,
                            NetworkMapManager.networkState.currCells, NetworkMapManager.networkState.currBt, 0L);
                }
            } catch (Exception ex) {
                Timber.e(ex, "filed to log default route update for viz: ");
            }
        }

    }

    public void checkLocationOK(final long gpsTimeout, final long netLocsTimeout) {
        if ( ! locationOK(currentLocation, getSatCount(), gpsTimeout, netLocsTimeout) ) {
            // do a self-check
            //DEBUG: NetworkMapManager.info("checkLocationOK was false");
            updateLocationData(null);
        }
    }

    private boolean locationOK( final Location location, final int satCount, final long gpsTimeout,
                                final long networkLocationTimeout ) {
        boolean retval = false;
        final long now = System.currentTimeMillis();

        //noinspection StatementWithEmptyBody
        if ( location == null ) {
            // bad!
            return false;
        }
        else if ( GPS_PROVIDER.equals( location.getProvider() ) ) {
            if ( satCount > 0 && satCount < 3 ) {
                if ( satCountLowTime == null ) {
                    satCountLowTime = now;
                }
            }
            else {
                // plenty of sats
                satCountLowTime = null;
            }
            boolean gpsLost = satCountLowTime != null && (now - satCountLowTime) > gpsTimeout;
            gpsLost |= now - lastLocationTime > gpsTimeout;
            gpsLost |= horribleGps(location);
            if (gpsLost) Timber.i("gps gpsLost");
            retval = ! gpsLost;
        }
        else if ( NETWORK_PROVIDER.equals( location.getProvider() )||
                FUSED_PROVIDER.equals( location.getProvider() ) ) {
            boolean gpsLost = now - lastNetworkLocationTime > networkLocationTimeout;
            gpsLost |= horribleGps(location);
            if (gpsLost) Timber.i("network gpsLost");
            retval = ! gpsLost;
        }

        return retval;
    }

    private boolean horribleGps(final Location location) {
        // try to protect against some horrible gps's out there
        // check if accuracy is under 10 miles
        boolean horrible = location.hasAccuracy() && location.getAccuracy() > 16000;
        horrible |= location.getLatitude() < -90 || location.getLatitude() > 90;
        horrible |= location.getLongitude() < -180 || location.getLongitude() > 180;
        return horrible;
    }

    public int getSatCount() {
        int satCount = 0;
        if (gnssStatus != null && Build.VERSION.SDK_INT >= 24) {
            for ( int i = 0; i < gnssStatus.getSatelliteCount(); i++ ) {
                if ( gnssStatus.usedInFix(i) ) satCount++;
            }
        }
        else if ( gpsStatus != null ) {
            for ( GpsSatellite sat : gpsStatus.getSatellites() ) {
                if ( sat.usedInFix() ) satCount++;
            }
        }
        return satCount;
    }

    public Map<String, Integer> getConstellations() {
        final Map<String, Integer> cons = new TreeMap<>();
        if (gnssStatus != null && Build.VERSION.SDK_INT >= 24) {
            for ( int i = 0; i < gnssStatus.getSatelliteCount(); i++ ) {
                if ( gnssStatus.usedInFix(i) ) {
                    final String key = constellationToString(gnssStatus.getConstellationType(i));
                    int old = cons.getOrDefault(key, 0);
                    cons.put(key, old + 1);
                }
            }
        }
        return Collections.unmodifiableMap(cons);
    }

    /**
     * Provide string names for different GNSS constellations. Not i18n.
     * @param constellationType the GnssStatus type
     * @return the string matching the integer from the GnssStatus ints
     */
    private String constellationToString(final int constellationType) {
        String con = "?";
        switch(constellationType) {
            case GnssStatus.CONSTELLATION_GPS:
                con = "GPS";
                break;
            case GnssStatus.CONSTELLATION_SBAS:
                con = "SBAS";
                break;
            case GnssStatus.CONSTELLATION_GLONASS:
                con = "Glonass";
                break;
            case GnssStatus.CONSTELLATION_QZSS:
                con = "QZSS";
                break;
            case GnssStatus.CONSTELLATION_BEIDOU:
                con = "Beidou";
                break;
            case GnssStatus.CONSTELLATION_GALILEO:
                con = "Galileo";
                break;
        }
        if (Build.VERSION.SDK_INT > 28 && constellationType == GnssStatus.CONSTELLATION_IRNSS) {
            con = "IRNSS";
        }
        return con;
    }

    public void saveLocation() {
        // save our location for use on later runs
        if ( this.currentLocation != null ) {
            final SharedPreferences prefs = networkMapManager.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0 );
            final Editor edit = prefs.edit();
            // there is no putDouble
            edit.putFloat( PreferenceKeys.PREF_PREV_LAT, (float) currentLocation.getLatitude() );
            edit.putFloat( PreferenceKeys.PREF_PREV_LON, (float) currentLocation.getLongitude() );
            edit.apply();
        }
    }

    public void setPrevLocation(Location location) {
        prevLocation = location;
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }

    /**
     * utility method which takes prefs and checks location freshness vs. configured limits
     * @param prefs SharedPreferences instance containing PREF_GPS_TIMEOUT and PREF_NET_LOC_TIMEOUT values to check
     * @return the location is valid
     */
    public Location checkGetLocation(final SharedPreferences prefs) {
        final long gpsTimeout = prefs.getLong(PreferenceKeys.PREF_GPS_TIMEOUT, GNSSListener.GPS_TIMEOUT_DEFAULT);
        final long netLocTimeout = prefs.getLong(PreferenceKeys.PREF_NET_LOC_TIMEOUT, GNSSListener.NET_LOC_TIMEOUT_DEFAULT);
        checkLocationOK(gpsTimeout, netLocTimeout);
        return getCurrentLocation();
    }

    /**
     * classify speed as realistic or unrealistic for distance calcs. mostly a stop-gap,
     * Disabled after practical testing problems.
     * @param distanceMeters meters travelled
     * @param timeDiffSecs time since previous measurement
     * @return true if the movement is realistically possible, false if it's obvious bunk
     */
    public static boolean realisticMovement(float distanceMeters, float timeDiffSecs, float lastAccuracyMeters, float currentAccuracyMeters) {
        if (distanceMeters == 0f) {
            //ALIBI: small movements are likely to be noise.
            return false;
        }

        /*final float metersSecondJump = distanceMeters/timeDiffSecs;
        //Without smoothing, this results in massive loss of distance.
        if (metersSecondJump > MACH_1_3_METERS_SEC || metersSecondJump < SLOW_METERS_SEC || Float.isNaN(metersSecondJump)) {
            //DEBUG: NetworkMapManager.info("DQ: "+metersSecondJump+"m/s");
            return false;
        }
        //Great in theory. Real-world testing of GPS accuracy makes this appear impractical
        if (currentAccuracyMeters > 10 && currentAccuracyMeters > distanceMeters) {
            //DEBUG: NetworkMapManager.info("ACC DQ: "+currentAccuracyMeters+"m ac, dist: "+distanceMeters);
            return false;
        }
        //ALIBI: Jump on fix, disabled pending further successful testing.
        if ((currentAccuracyMeters-lastAccuracyMeters) > distanceMeters) {
            //DEBUG: NetworkMapManager.info("JUMP DQ: "+(currentAccuracyMeters-lastAccuracyMeters)+"m, "+distanceMeters+"m");
            return false;
        }*/
        return true;
    }
}
