package org.odk.collect.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;

import org.odk.collect.NetworkMapManager;
import org.odk.collect.db.DBException;
import org.odk.collect.db.DatabaseHelper;
import org.odk.collect.model.Network;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import timber.log.Timber;

/**
 * file space and name routines
 */
public class FileUtility {

    //directory locations - centrally managed here, but must be in sync with fileprovider defs
    private  final static String APP_DIR = "wiglewifi";
    private final static String APP_SUB_DIR = "/"+APP_DIR+"/";
    private static final String GPX_DIR = APP_SUB_DIR+"gpx/";
    private final static String KML_DIR = "app_kml";
    private final static String KML_DIR_BASE = "kml";
    private static final String M8B_DIR = APP_SUB_DIR+"m8b/";
    private final static String SQLITE_BACKUPS_DIR = "sqlite";

    public final static String CSV_EXT = ".csv";
    public static final String ERROR_STACK_FILE_PREFIX = "errorstack";
    public static final String GPX_EXT = ".gpx";
    public static final String GZ_EXT = ".gz";
    public final static String CSV_GZ_EXT = CSV_EXT+GZ_EXT;
    public final static String KML_EXT = ".kml";
    public static final String M8B_FILE_PREFIX = "export";
    public static final String M8B_EXT = ".m8b";
    public static final String SQL_EXT = ".sqlite";

    public static final String WIWI_PREFIX = "ODKWifi_";

    private static final String COMMA = ",";
    private static final String NEWLINE = "\n";
    public final static String CSV_COLUMN_HEADERS = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type";
    //ALIBI: can't actually read the size of compressed assets via the asset manager - has to be hardcoded
    //  this can be updated by checking the size of wiglewifiwardriving/src/main/assets/mmcmnc.sqlite on build
    public final static long EST_MXC_DB_SIZE = 331776;

    // Start warning if there isn't this much space left on the primary storage location for networks
    public final static long WARNING_THRESHOLD_BYTES = 131072;

    //based on the smart answer in https://stackoverflow.com/questions/7115016/how-to-find-the-amount-of-free-storage-disk-space-left-on-android
    public static long getFreeBytes(File path) {
        try {
            StatFs stats = new StatFs(path.getAbsolutePath());
            return stats.getAvailableBlocksLong() * stats.getBlockSizeLong();

        } catch (Exception ex) {
            // if we can't determine free space, be optimistic. Possibly because of missing permission?
            Timber.e(ex,"Unable to determine free space: ");
            return Long.MAX_VALUE;
        }
    }

    /**
     * check internal storage for near-fullness
     * @return true if we're in the danger zone
     */
    public static boolean checkInternalStorageDangerZone() {
        return getFreeInternalBytes() > WARNING_THRESHOLD_BYTES;
    }

    /**
     * check external storage for near-fullness
     * @return true if we're in the danger zone
     */
    public static boolean checkExternalStorageDangerZone() {
        return getFreeExternalBytes() > WARNING_THRESHOLD_BYTES;
    }

    /**
     * get the free bytes on external storage
     * @return the number of bytes
     */
    public static long getFreeExternalBytes() {
        return FileUtility.getFreeBytes(Environment.getExternalStorageDirectory());
    }

    /**
     * get the free bytes on internal storage
     * @return the number of bytes
     */
    public static long getFreeInternalBytes() {
        return FileUtility.getFreeBytes(Environment.getDataDirectory());
    }

    /**
     * Core check to determine whether this device has "external" storage the app can use
     * @return true if we can find it and we have permission
     */
    public static boolean hasSD() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            // past android 10 external doesn't detect properly, but also isn't available
            return false;
        }
        File sdCard = new File(safeFilePath(Environment.getExternalStorageDirectory()) + "/");
        Timber.i("exists: " + sdCard.exists() + " dir: " + sdCard.isDirectory()
                + " read: " + sdCard.canRead() + " write: " + sdCard.canWrite()
                + " path: " + sdCard.getAbsolutePath());

        return sdCard.exists() && sdCard.isDirectory() && sdCard.canRead() && sdCard.canWrite();
    }

    /**
     * determine the FS location on which the "external" storage is mounted
     * @return the string file path
     */
    public static String getSDPath() {
        return safeFilePath(Environment.getExternalStorageDirectory()) + APP_SUB_DIR;
    }

    /**
     * Create an output file sensitive to the SD availability of the install - currently used for network temp files and KmlWriter output
     * @param context Context of the application
     * @param filename the filename to store
     * @param internalCacheArea whether to locate this in the cache directory if internal storage
     * @return tje FileOutputStream of the new file
     * @throws IOException if unable to create the file/directory.
     */
    public static FileOutputStream createFile(final Context context, final String filename,
                                              final boolean internalCacheArea) throws IOException {
        final String filepath = getSDPath();
        final File path = new File(filepath);

        final boolean hasSD = hasSD();
        if (internalCacheArea) {
            File file = new File(context.getCacheDir(), filename);
            Timber.i("creating file: " + file.getCanonicalPath());
            return new FileOutputStream(file);
        } else if (hasSD) {
            //noinspection ResultOfMethodCallIgnored
            path.mkdirs();
            final String openString = filepath + filename;
            Timber.i("openString: " + openString);
            final File file = new File(openString);
            if (!file.exists()) {
                if (!file.createNewFile()) {
                    throw new IOException("Could not create file: " + openString);
                }
            }
            return new FileOutputStream(file);
        }

        //TODO: dedupe w/ KmlDownloader.writeSharefile()
        if (filename.endsWith(KML_EXT)) return createFileInSubdir(context, filename, KML_DIR);
        if (filename.endsWith(SQL_EXT)) return createFileInSubdir(context, filename, SQLITE_BACKUPS_DIR);
        Timber.i("saving as: "+filename);

        return context.openFileOutput(filename, Context.MODE_PRIVATE);
    }

    private static FileOutputStream createFileInSubdir(final Context context, final String filename,
                                                       final String dir) throws IOException {
        File path = new File(context.getFilesDir(), dir);
        if (!path.exists()) {
            //noinspection ResultOfMethodCallIgnored
            path.mkdir();
        }
        if (path.exists() && path.isDirectory()) {
            //DEBUG: MainActivity.info("... file output directory found");
            File kmlFile = new File(path, filename);
            return new FileOutputStream(kmlFile);
        }
        return context.openFileOutput(filename, Context.MODE_PRIVATE);
    }

    /**
     * return the uploads dir if we're using external storage
     * @return external file location if we're using external/otherwise null
     * //TODO: do we write uploads to context.getApplicationContext().getFilesDir() if !hasSD?
     */
    public static String getUploadFilePath(final Context context) throws IOException {
        if ( hasSD() ) {
            return getSDPath();
        }
        return context.getApplicationContext().getFilesDir().getCanonicalPath();
    }

    /**
     * return the m8b dir if we're using external storage
     * @return external file location if we're using external/otherwise null
     * //TODO: useful to return the true path if !hasSD?
     */
    public static String getM8bPath() {
        if ( hasSD() ) {
            return safeFilePath(Environment.getExternalStorageDirectory()) + M8B_DIR;
        }
        return null;
    }

    /**
     * return the GPX dir if we're using external storage
     * @return external file location if we're using external/otherwise null
     * //TODO: useful to return the true path if !hasSD?
     */
    public static String getGpxPath() {
        if ( hasSD() ) {
            return safeFilePath(Environment.getExternalStorageDirectory()) + GPX_DIR;
        }
        return null;
    }

    /**
     * just get the KML location for internal purposes; should be compatible with the results of
     * getKmlDownloadFile
     * @param context the context of the application
     * @return the string path suitable for intent construction
     */
    public static String getKmlPath(final Context context) {
        if (hasSD()) {
            //ALIBI: placing these right in the appdir external in storage for now.
            return FileUtility.getSDPath();
        }
        File f = new File(context.getFilesDir(), KML_DIR);
        return f.getAbsolutePath();
    }

    /**
     * just get the DB backup location for internal purposes
     * @param context the context of the application
     * @return the string path suitable for intent construction
     */
    public static String getBackupPath(final Context context) {
        if (hasSD()) {
            //ALIBI: placing these right in the appdir external in storage for now.
            return FileUtility.getSDPath();
        }
        File f = new File(context.getFilesDir(), SQLITE_BACKUPS_DIR);
        return f.getAbsolutePath();
    }

    /**
     * return the error stack dir
     * @param context application context to locate output
     * @return the File instance for the path
     */
    public static File getErrorStackPath(final Context context) {
        if (hasSD()) {
            return new File(getSDPath());
        }
        return context.getApplicationContext().getFilesDir();
    }

    public static File getKmlDownloadFile(final Context context, final String fileName, final String localFilePath) {
        if (hasSD()) {
            return new File(localFilePath);
        } else {
            File dir = new File(context.getFilesDir(), KML_DIR);
            File file = new File(dir, fileName + KML_EXT);
            if (!file.exists()) {
                Timber.e("file does not exist: " + file.getAbsolutePath());
                return null;
            } else {
                //DEBUG: MainActivity.info(file.getAbsolutePath());
                return file;
            }
        }
    }

    public static File getCsvGzFile(final Context context, final String fileName) throws NullPointerException {
        File file;
        if (hasSD()) {
            file = new File(getSDPath(), fileName);
        } else {
            file = new File(context.getFilesDir(), fileName);
        }
        if (!file.exists()) {
            Timber.e("file does not exist: " + file.getAbsolutePath());
            return null;
        } else {
            //DEBUG: MainActivity.info(file.getAbsolutePath());
            return file;
        }
    }

    /**
     * Get the latest stack file
     * @param context context for the request
     * @return the path string for the latest stack file
     */
    public static String getLatestStackfilePath(final Context context) {
        try {
            File fileDir = getErrorStackPath(context);
            if (!fileDir.canRead() || !fileDir.isDirectory()) {
                Timber.e("file is not readable or not a directory. fileDir: " + fileDir);
            } else {
                String[] files = fileDir.list();
                if (files == null) {
                    Timber.e("no files in dir: " + fileDir);
                } else {
                    String latestFilename = null;
                    for (String filename : files) {
                        if (filename.startsWith(ERROR_STACK_FILE_PREFIX)) {
                            if (latestFilename == null || filename.compareTo(latestFilename) > 0) {
                                latestFilename = filename;
                            }
                        }
                    }
                    Timber.i("latest filename: " + latestFilename);

                    return safeFilePath(fileDir) + "/" + latestFilename;
                }
            }
        } catch (Exception ex) {
            Timber.e( "error finding stack file: " + ex, ex );
        }
        return null;
    }

    /**
     *  safely get the canonical path, as this call throws exceptions on some devices
     * @param file the file for which to retrieve the cannonical path
     * @return the String path
     */
    private static String safeFilePath(final File file) {
        String retval = null;
        try {
            retval = file.getCanonicalPath();
        } catch (Exception ex) {
            Timber.e(ex,"Failed to get filepath");
        }

        if (retval == null) {
            retval = file.getAbsolutePath();
        }
        return retval;
    }

    /**
     * file inspection debugging method - probably should get moved into a utility class eventually
     * @param directory the directory to enumerate
     */
    public static void printDirContents(final File directory) {
        Timber.i("Listing for: "+directory.toString());
        File[] files = directory.listFiles();
        if (files != null) {
            Timber.i("\t# files: " + files.length);
            for (File file : files) {
                Timber.i("\t\t" + file.getName() + "\t" + file.getAbsoluteFile());
            }
        } else {
            Timber.e("Null file listing for "+directory.toString());
        }
    }

    public static List<File> getCsvUploadsAndDownloads(final Context context) throws IOException {
        List<File> rawFiles = new ArrayList<>();

        final String location = FileUtility.getUploadFilePath(context);
        if (null != location) {
            final File directory = new File(location);
            if (directory.exists()) {
                File[] files = directory.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        return name.endsWith(CSV_GZ_EXT);
                    }
                });
                if (null != files) {
                    for (File file : files) {
                        if (file.getName().endsWith(CSV_GZ_EXT)) {
                            rawFiles.add(file);
                            //} else {
                            //DEBUG: MainActivity.info("skipping: " + files[i].getName());
                        }
                    }
                }
            }
        }
        return rawFiles;
    }


    /**
     * (directly lifted from FileUploadTask)
     */
    public static long writeFile(final Context context, final DatabaseHelper dbHelper,
                                 final OutputStream fos, final Bundle bundle) throws IOException,
            PackageManager.NameNotFoundException, InterruptedException, DBException {

        final SharedPreferences prefs = context.getSharedPreferences( PreferenceKeys.SHARED_PREFS, 0);
        long maxId = prefs.getLong( PreferenceKeys.PREF_DB_MARKER, 0L );
        // max id at startup
        maxId = prefs.getLong( PreferenceKeys.PREF_MAX_DB, 0L );

        Timber.i( "Writing file starting with observation id: " + maxId);
        final Cursor cursor = dbHelper.locationIterator( maxId );

        //noinspection
        try {
            return writeFileWithCursor( context, dbHelper, fos, cursor, prefs );
        } finally {
            fos.close();
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * (lifted directly from FileUploaderTask)
     */
    private static long writeFileWithCursor(final Context context, final DatabaseHelper dbHelper,
                                            final OutputStream fos, final Cursor cursor,
                                            final SharedPreferences prefs ) throws IOException,
            PackageManager.NameNotFoundException, InterruptedException {
        long maxId = prefs.getLong( PreferenceKeys.PREF_DB_MARKER, 0L );

        final long start = System.currentTimeMillis();
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        final int total = cursor.getCount();
        long fileWriteMillis = 0;
        long netMillis = 0;

        final PackageManager pm = context.getPackageManager();
        final PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);

        // name, version, header
        final String header = "ODKWifi-1.4"
                + ",appRelease=" + pi.versionName
                + ",model=" + android.os.Build.MODEL
                + ",release=" + android.os.Build.VERSION.RELEASE
                + ",device=" + android.os.Build.DEVICE
                + ",display=" + android.os.Build.DISPLAY
                + ",board=" + android.os.Build.BOARD
                + ",brand=" + android.os.Build.BRAND
                + NEWLINE
                + CSV_COLUMN_HEADERS
                + NEWLINE;
        FileAccess.writeFos( fos, header );

        if ( total > 0 ) {
            CharBuffer charBuffer = CharBuffer.allocate( 1024 );
            ByteBuffer byteBuffer = ByteBuffer.allocate( 1024 ); // this ensures hasArray() is true
            final CharsetEncoder encoder = Charset.forName( NetworkMapManager.ENCODING ).newEncoder();
            // don't stop when a goofy character is found
            encoder.onUnmappableCharacter( CodingErrorAction.REPLACE );
            final NumberFormat numberFormat = NumberFormat.getNumberInstance( Locale.US );
            // no commas in the comma-separated file
            numberFormat.setGroupingUsed( false );
            if ( numberFormat instanceof DecimalFormat) {
                final DecimalFormat dc = (DecimalFormat) numberFormat;
                dc.setMaximumFractionDigits( 16 );
            }
            final StringBuffer stringBuffer = new StringBuffer();
            final FieldPosition fp = new FieldPosition(NumberFormat.INTEGER_FIELD);
            final Date date = new Date();
            // loop!
            for ( cursor.moveToFirst(); ! cursor.isAfterLast(); cursor.moveToNext() ) {
                // _id,bssid,level,lat,lon,time
                final long id = cursor.getLong(0);
                if ( id > maxId ) {
                    maxId = id;
                }
                final String bssid = cursor.getString(1);
                final long netStart = System.currentTimeMillis();
                final Network network = dbHelper.getNetwork( bssid );
                netMillis += System.currentTimeMillis() - netStart;
                if ( network == null ) {
                    // weird condition, skipping
                    Timber.e("network not in database: " + bssid );
                    continue;
                }

                String ssid = network.getSsid();
                if (ssid.contains(COMMA)) {
                    // comma isn't a legal ssid character, but just in case
                    ssid = ssid.replaceAll( COMMA, "_" );
                }
                // ListActivity.debug("writing network: " + ssid );
                Timber.i("writing network: " + ssid );

                // reset the buffers
                charBuffer.clear();
                byteBuffer.clear();
                // fill in the line
                try {
                    charBuffer.append( network.getBssid() );
                    charBuffer.append( COMMA );
                    // ssid can be unicode
                    charBuffer.append( ssid );
                    charBuffer.append( COMMA );
                    charBuffer.append( network.getCapabilities() );
                    charBuffer.append( COMMA );
                    date.setTime( cursor.getLong(7) );
                    FileAccess.singleCopyDateFormat( dateFormat, stringBuffer, charBuffer, fp, date );
                    charBuffer.append( COMMA );
                    Integer channel = network.getChannel();
                    if ( channel == null ) {
                        channel = network.getFrequency();
                    }
                    FileAccess.singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, channel );
                    charBuffer.append( COMMA );
                    FileAccess.singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getInt(2) );
                    charBuffer.append( COMMA );
                    FileAccess.singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getDouble(3) );
                    charBuffer.append( COMMA );
                    FileAccess.singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getDouble(4) );
                    charBuffer.append( COMMA );
                    FileAccess.singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getDouble(5) );
                    charBuffer.append( COMMA );
                    FileAccess.singleCopyNumberFormat( numberFormat, stringBuffer, charBuffer, fp, cursor.getDouble(6) );
                    charBuffer.append( COMMA );
                    charBuffer.append( network.getType().name() );
                    charBuffer.append( NEWLINE );
                }
                catch ( BufferOverflowException ex ) {
                    Timber.i(ex, "buffer overflow: " + ex);
                    // double the buffer
                    charBuffer = CharBuffer.allocate( charBuffer.capacity() * 2 );
                    byteBuffer = ByteBuffer.allocate( byteBuffer.capacity() * 2 );
                    // try again
                    cursor.moveToPrevious();
                    continue;
                }

                // tell the encoder to stop here and to start at the beginning
                charBuffer.flip();

                // do the encoding
                encoder.reset();
                encoder.encode( charBuffer, byteBuffer, true );
                try {
                    encoder.flush( byteBuffer );
                }
                catch ( IllegalStateException ex ) {
                    Timber.e(ex,"exception flushing: " + ex, ex);
                    continue;
                }
                // byteBuffer = encoder.encode( charBuffer );  (old way)

                // figure out where in the byteBuffer to stop
                final int end = byteBuffer.position();
                final int offset = byteBuffer.arrayOffset();
                //if ( end == 0 ) {
                // if doing the encode without giving a long-term byteBuffer (old way), the output
                // byteBuffer position is zero, and the limit and capacity are how long to write for.
                //  end = byteBuffer.limit();
                //}

                // MainActivity.info("buffer: arrayOffset: " + byteBuffer.arrayOffset() + " limit: "
                // + byteBuffer.limit()
                //     + " capacity: " + byteBuffer.capacity() + " pos: " + byteBuffer.position() +
                // " end: " + end
                //     + " result: " + result );
                final long writeStart = System.currentTimeMillis();
                fos.write(byteBuffer.array(), offset, end+offset );
                fileWriteMillis += System.currentTimeMillis() - writeStart;

            }
        }

        Timber.i("wrote file in: " + (System.currentTimeMillis() - start) +
                "ms. fileWriteMillis: " + fileWriteMillis + " netmillis: " + netMillis );

        return maxId;
    }
}
