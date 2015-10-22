package in.udacity.learning.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncRequest;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Time;
import android.util.Log;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Vector;

import in.udacity.learning.constant.AppConstant;
import in.udacity.learning.dbhelper.WeatherContract;
import in.udacity.learning.model.LocationAttribute;
import in.udacity.learning.model.WeatherAttribute;
import in.udacity.learning.shunshine.app.BuildConfig;
import in.udacity.learning.shunshine.app.R;
import in.udacity.learning.utility.Utility;
import in.udacity.learning.web_services.JSONParser;
import in.udacity.learning.web_services.WebServiceURL;

public class SunshineSyncAdapter extends AbstractThreadedSyncAdapter {
    public final String TAG = SunshineSyncAdapter.class.getSimpleName();

    // Interval at which to sync with the weather, in seconds.
    // 60 seconds (1 minute) * 180 = 3 hours
    public static final int SYNC_INTERVAL = 60*180;
    public static final int SYNC_FLEXTIME = SYNC_INTERVAL / 3;

    public SunshineSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        Log.d(TAG, "Starting sync");
        String locationQuery = Utility.getPreferredLocation(getContext());

        // These two need to be declared outside the try/catch
        // so that they can be closed in the finally block.
        HttpURLConnection urlConnection = null;
        BufferedReader reader = null;

        // Will contain the raw JSON response as a string.
        String forecastJsonStr = null;

        String format = "json";
        String units = "metric";
        int numDays = 14;

        try {
            // Construct the URL for the OpenWeatherMap query
            // Possible parameters are avaiable at OWM's forecast API page, at
            // http://openweathermap.org/API#forecast
            final String FORECAST_BASE_URL = WebServiceURL.baseURLWeatherForcast;

            Uri builtUri = Uri.parse(FORECAST_BASE_URL).buildUpon()
                    .appendQueryParameter(WebServiceURL.QUERY, locationQuery)
                    .appendQueryParameter(WebServiceURL.MODE, format)
                    .appendQueryParameter(WebServiceURL.UNIT, units)
                    .appendQueryParameter(WebServiceURL.DAYS, Integer.toString(numDays))
                    .appendQueryParameter(WebServiceURL.KEYS, BuildConfig.OPEN_WEATHER_MAP_API_KEY)
                    .build();

            URL url = new URL(builtUri.toString());

            // Create the request to OpenWeatherMap, and open the connection
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.connect();

            // Read the input stream into a String
            InputStream inputStream = urlConnection.getInputStream();
            StringBuffer buffer = new StringBuffer();
            if (inputStream == null) {
                // Nothing to do.
                return;
            }
            reader = new BufferedReader(new InputStreamReader(inputStream));

            String line;
            while ((line = reader.readLine()) != null) {
                // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                // But it does make debugging a *lot* easier if you print out the completed
                // buffer for debugging.
                buffer.append(line + "\n");
            }

            if (buffer.length() == 0) {
                // Stream was empty.  No point in parsing.
                return;
            }
            forecastJsonStr = buffer.toString();
            getWeatherDataFromJson(forecastJsonStr, locationQuery);
        } catch (IOException e) {
            Log.e(TAG, "Error ", e);
            // If the code didn't successfully get the weather data, there's no point in attempting
            // to parse it.
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException e) {
                    Log.e(TAG, "Error closing stream", e);
                }
            }
        }
        return;

    }


    /**
     * Take the String representing the complete forecast in JSON Format and
     * pull out the data we need to construct the Strings needed for the wireframes.
     * <p/>
     * Fortunately parsing is easy:  constructor takes the JSON string and converts it
     * into an Object hierarchy for us.
     */
    private void getWeatherDataFromJson(String forecastJsonStr, String locationSetting) {

        try {
            LocationAttribute la = JSONParser.parseLocationForcast(forecastJsonStr);

            long locationId = addLocation(locationSetting, la.getCityName(), Double.parseDouble(la.getLati())
                    , Double.parseDouble(la.getLongi()));

            List<WeatherAttribute> lsWeather = JSONParser.parseWeatherForcast(forecastJsonStr, locationId + "");

            // Insert the new weather information into the database
            Vector<ContentValues> cVVector = new Vector<ContentValues>(lsWeather.size());

            // OWM returns daily forecasts based upon the local time of the city that is being
            // asked for, which means that we need to know the GMT offset to translate this data
            // properly.

            // Since this data is also sent in-order and the first day is always the
            // current day, we're going to take advantage of that to get a nice
            // normalized UTC date for all of our weather.

            Time dayTime = new Time();
            dayTime.setToNow();

            // we start at the day returned by local time. Otherwise this is a mess.
            int julianStartDay = Time.getJulianDay(System.currentTimeMillis(), dayTime.gmtoff);

            // now we work exclusively in UTC
            dayTime = new Time();

            for (int i = 0; i < lsWeather.size(); i++) {

                // Cheating to convert this to UTC time, which is what we want anyhow
                long dateTime = dayTime.setJulianDay(julianStartDay + i);

                // These are the values that will be collected.

                WeatherAttribute wa = lsWeather.get(i);
                ContentValues weatherValues = new ContentValues();

                weatherValues.put(WeatherContract.WeatherEntry.LOCATION_ID, locationId);
                weatherValues.put(WeatherContract.WeatherEntry.DATE, dateTime);
                weatherValues.put(WeatherContract.WeatherEntry.HUMIDITY, wa.getHumidity());
                weatherValues.put(WeatherContract.WeatherEntry.PRESSURE, wa.getPressure());
                weatherValues.put(WeatherContract.WeatherEntry.WIND_SPEED, wa.getWindSpeed());
                weatherValues.put(WeatherContract.WeatherEntry.DEGREES, wa.getDegree());
                weatherValues.put(WeatherContract.WeatherEntry.MAX, wa.getMax());
                weatherValues.put(WeatherContract.WeatherEntry.MIN, wa.getMin());
                weatherValues.put(WeatherContract.WeatherEntry.SHORT_DESC, wa.getDescription());
                weatherValues.put(WeatherContract.WeatherEntry.WEATHER_ID, wa.getWeather_id());

                cVVector.add(weatherValues);
            }

            int inserted = 0;
            // add to database
            if (cVVector.size() > 0) {
                ContentValues[] cv = new ContentValues[cVVector.size()];
                cVVector.toArray(cv);
                inserted = getContext().getContentResolver().bulkInsert(WeatherContract.WeatherEntry.CONTENT_URI, cv);
            }

            if (AppConstant.DEBUG)
                Log.d(TAG, TAG + " Complete. " + inserted + " Inserted");

        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
            e.printStackTrace();
        }
    }

    /**
     * Helper method to handle insertion of a new location in the weather database.
     *
     * @param locationSetting The location string used to request updates from the server.
     * @param cityName        A human-readable city name, e.g "Mountain View"
     * @param lat             the latitude of the city
     * @param lon             the longitude of the city
     * @return the row ID of the added location.
     */
    public long addLocation(String locationSetting, String cityName, double lat, double lon) {
        // First, check if the location with this city name exists in the db
        // If it exists, return the current ID
        // Otherwise, insert it using the content resolver and the base URI

        long locationId = -1;
        Cursor locationCursor = getContext().getContentResolver().query(WeatherContract.LocationEntry.CONTENT_URI,
                new String[]{WeatherContract.LocationEntry._ID},
                WeatherContract.LocationEntry.LOCATION_SETTING + "= ? ",
                new String[]{locationSetting}, null);

        if (locationCursor.moveToFirst()) {
            int locationIdIndex = locationCursor.getColumnIndex(WeatherContract.LocationEntry._ID);
            locationId = locationCursor.getLong(locationIdIndex);
        } else {

            ContentValues cv = new ContentValues();
            cv.put(WeatherContract.LocationEntry.CITY_NAME, cityName);
            cv.put(WeatherContract.LocationEntry.CORD_LAT, lat);
            cv.put(WeatherContract.LocationEntry.CORD_LONG, lon);
            cv.put(WeatherContract.LocationEntry.LOCATION_SETTING, locationSetting);

            Uri uri = getContext().getContentResolver().insert(WeatherContract.LocationEntry.CONTENT_URI, cv);
            locationId = ContentUris.parseId(uri);
        }

        if (locationCursor != null)
            locationCursor.close();

        return locationId;
    }

    /**
     * Helper method to have the sync adapter sync immediately
     *
     * @param context The context used to access the account service
     */
    public static void syncImmediately(Context context) {
        Log.d("LOG", "syncImmediately Called.");
        Bundle bundle = new Bundle();
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSync(getSyncAccount(context), context.getString(R.string.content_authority), bundle);
    }

    /**
     * Helper method to schedule the sync adapter periodic execution
     */
    public static void configurePeriodicSync(Context context, int syncInterval, int flexTime) {
        Account account = getSyncAccount(context);
        String authority = context.getString(R.string.content_authority);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // we can enable inexact timers in our periodic sync
            SyncRequest request = new SyncRequest.Builder().
                    syncPeriodic(syncInterval, flexTime).
                    setSyncAdapter(account, authority).
                    setExtras(new Bundle()).build();
            ContentResolver.requestSync(request);
        } else {
            ContentResolver.addPeriodicSync(account,
                    authority, new Bundle(), syncInterval);
        }
    }


    /**
     * Helper method to get the fake account to be used with SyncAdapter, or make a new one
     * if the fake account doesn't exist yet.  If we make a new account, we call the
     * onAccountCreated method so we can initialize things.
     *
     * @param context The context used to access the account service
     * @return a fake account.
     */
    public static Account getSyncAccount(Context context) {
        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);

        // Create the account type and default account
        Account newAccount = new Account(
                context.getString(R.string.app_name), context.getString(R.string.sync_account_type));

        // If the password doesn't exist, the account doesn't exist
        if ( null == accountManager.getPassword(newAccount) ) {

        /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
            if (!accountManager.addAccountExplicitly(newAccount, "", null)) {
                return null;
            }
            /*
             * If you don't set android:syncable="true" in
             * in your <provider> element in the manifest,
             * then call ContentResolver.setIsSyncable(account, AUTHORITY, 1)
             * here.
             */

            onAccountCreated(newAccount, context);
        }
        return newAccount;
    }

    private static void onAccountCreated(Account newAccount, Context context) {
        /*
         * Since we've created an account
         */
        SunshineSyncAdapter.configurePeriodicSync(context, SYNC_INTERVAL, SYNC_FLEXTIME);

        /*
         * Without calling setSyncAutomatically, our periodic sync will not be enabled.
         */
        ContentResolver.setSyncAutomatically(newAccount, context.getString(R.string.content_authority), true);

        /*
         * Finally, let's do a sync to get things started
         */
        syncImmediately(context);
    }

    public static void initializeSyncAdapter(Context context) {
        getSyncAccount(context);
    }
}