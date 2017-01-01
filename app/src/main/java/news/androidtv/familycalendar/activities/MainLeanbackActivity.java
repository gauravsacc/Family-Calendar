package news.androidtv.familycalendar.activities;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import com.felkertech.settingsmanager.SettingsManager;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import news.androidtv.familycalendar.R;
import news.androidtv.familycalendar.adapters.AgendaViewAdapter;
import news.androidtv.familycalendar.shims.Consumer;
import news.androidtv.familycalendar.tasks.ListCalendarEventsRequestTask;
import news.androidtv.familycalendar.tasks.ListCalendarListRequestTask;
import news.androidtv.familycalendar.utils.CalendarUtils;
import news.androidtv.familycalendar.utils.EventComparator;
import news.androidtv.familycalendar.utils.SettingsConstants;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

import static news.androidtv.familycalendar.activities.QuickStartActivity.REQUEST_ACCOUNT_PICKER;
import static news.androidtv.familycalendar.activities.QuickStartActivity.REQUEST_AUTHORIZATION;
import static news.androidtv.familycalendar.activities.QuickStartActivity.REQUEST_GOOGLE_PLAY_SERVICES;
import static news.androidtv.familycalendar.activities.QuickStartActivity.REQUEST_PERMISSION_GET_ACCOUNTS;

/**
 * Created by Nick on 12/31/2016.
 *
 * This is the main entry point for a user. If the user has not authenticated, they will be moved
 * to the {@link QuickStartActivity}.
 */
public class MainLeanbackActivity extends Activity {
    private static final String TAG = MainLeanbackActivity.class.getSimpleName();

    private GoogleAccountCredential mCredential;
    private List<Event> mEventsList;
    private SettingsManager mSettingsManager;
    private int focusedEvent = 0;
    private int focusedCalendar = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mEventsList = new ArrayList<>();
        mSettingsManager = new SettingsManager(this);
        mCredential = CalendarUtils.getCredential(this);
        prepare();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Start getting calendar");
    }

    public void resyncEvents() {
        new ListCalendarListRequestTask(mCredential).setPostConsumer(new Consumer<List<CalendarListEntry>>() {
            @Override
            public void consume(List<CalendarListEntry> item) {
                // Get events from each and add
                for (final CalendarListEntry entry : item) {
                    Log.d(TAG, "Pull events for " + entry);
                    if (entry.isSelected()) {
                        new ListCalendarEventsRequestTask(mCredential, entry.getId()).setPostConsumer(new Consumer<List<Event>>() {
                            @Override
                            public void consume(List<Event> item) {
                                Log.d(TAG, "Adding events for " + entry.getSummary());
                                mEventsList.addAll(item);
                                redrawEvents();
                            }
                        })
                        .execute();
                    }
                }
            }
        })
        .execute();
    }

    /**
     * Displays events in a list.
     */
    public void redrawEvents() {
        // Sort all chronologically
        Log.d(TAG, "Draw " + mEventsList.size() + " items");
        Collections.sort(mEventsList, new EventComparator());
        // Now put into layout. This layout may depend on user settings.
        AgendaViewAdapter adapter = new AgendaViewAdapter(this, mEventsList);
        RecyclerView rv = (RecyclerView) findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        RecyclerView rv = (RecyclerView) findViewById(R.id.recycler);
        rv.findViewHolderForAdapterPosition(focusedEvent).itemView.setBackgroundColor(
                getResources().getColor(R.color.colorAccent));
        Log.d(TAG, "Key press " + keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (findViewById(R.id.calendars).hasFocus()) {
                    focusedCalendar++;
                    if (focusedCalendar >= 0) {
                        focusedCalendar = 0;
                    }
                } else {
                    focusedEvent++;
                    if (focusedEvent >= mEventsList.size()) {
                        focusedEvent = mEventsList.size() - 1;
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (findViewById(R.id.calendars).hasFocus()) {
                    focusedCalendar--;
                    if (focusedEvent < 0) {
                        focusedCalendar = 0;
                    }
                } else {
                    focusedEvent--;
                    if (focusedEvent < 0) {
                        focusedEvent = 0;
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                ((AgendaViewAdapter) rv.getAdapter()).displayPopup(focusedEvent);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // Open nav drawer
                findViewById(R.id.calendars).requestFocus();
                findViewById(R.id.calendars).setMinimumWidth(200);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                findViewById(R.id.recycler).requestFocus();
                findViewById(R.id.calendars).setMinimumWidth(60);
                break;
        }
        rv.findViewHolderForAdapterPosition(focusedEvent).itemView.setBackgroundColor(
                getResources().getColor(R.color.colorAccentDark));
        return super.onKeyDown(keyCode, event);
    }

    // Code copied from QuickStartActivity
    /**
     * Attempt to call the API, after verifying that all the preconditions are
     * satisfied. The preconditions are: Google Play Services installed, an
     * account was selected and the device currently has online access. If any
     * of the preconditions are not satisfied, the app will prompt the user as
     * appropriate.
     */
    void getResultsFromApi() {
        if (! isGooglePlayServicesAvailable()) {
            acquireGooglePlayServices();
        } else if (mCredential.getSelectedAccountName() == null) {
            chooseAccount();
        } else if (! isDeviceOnline()) {
            Toast.makeText(this, "No network connection available.", Toast.LENGTH_SHORT).show();
        } else {
            resyncEvents();
        }
    }

    boolean prepare() {
        if (! isGooglePlayServicesAvailable()) {
            acquireGooglePlayServices();
        } else if (mCredential.getSelectedAccountName() == null) {
            chooseAccount();
        } else if (! isDeviceOnline()) {
            Toast.makeText(this, "No network connection available.", Toast.LENGTH_SHORT).show();
        } else {
            return true;
        }
        return false;
    }

    /**
     * Attempts to set the account used with the API credentials. If an account
     * name was previously saved it will use that one; otherwise an account
     * picker dialog will be shown to the user. Note that the setting the
     * account to use with the credentials object requires the app to have the
     * GET_ACCOUNTS permission, which is requested here if it is not already
     * present. The AfterPermissionGranted annotation indicates that this
     * function will be rerun automatically whenever the GET_ACCOUNTS permission
     * is granted.
     */
    @AfterPermissionGranted(REQUEST_PERMISSION_GET_ACCOUNTS)
    void chooseAccount() {
        if (EasyPermissions.hasPermissions(this, Manifest.permission.GET_ACCOUNTS)) {
            String accountName = mSettingsManager.getString(SettingsConstants.PREF_ACCOUNT_NAME);
            if (accountName != null && !accountName.isEmpty()) {
                mCredential.setSelectedAccountName(accountName);
                getResultsFromApi();
            } else {
                // Start a dialog from which the user can choose an account
                startActivityForResult(
                        mCredential.newChooseAccountIntent(),
                        REQUEST_ACCOUNT_PICKER);
            }
        } else {
            // Request the GET_ACCOUNTS permission via a user dialog
            EasyPermissions.requestPermissions(
                    this,
                    "This app needs to access your Google account (via Contacts).",
                    REQUEST_PERMISSION_GET_ACCOUNTS,
                    Manifest.permission.GET_ACCOUNTS);
        }
    }

    /**
     * Called when an activity launched here (specifically, AccountPicker
     * and authorization) exits, giving you the requestCode you started it with,
     * the resultCode it returned, and any additional data from it.
     * @param requestCode code indicating which activity result is incoming.
     * @param resultCode code indicating the result of the incoming
     *     activity result.
     * @param data Intent (containing result data) returned by incoming
     *     activity result.
     */
    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch(requestCode) {
            case REQUEST_GOOGLE_PLAY_SERVICES:
                if (resultCode != RESULT_OK) {
                    Toast.makeText(this, "This app requires Google Play Services. Please install " +
                            "Google Play Services on your device and relaunch this app.", Toast.LENGTH_SHORT).show();
                } else {
                    getResultsFromApi();
                }
                break;
            case REQUEST_ACCOUNT_PICKER:
                if (resultCode == RESULT_OK && data != null &&
                        data.getExtras() != null) {
                    String accountName =
                            data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                    if (accountName != null) {
                        mSettingsManager.setString(SettingsConstants.PREF_ACCOUNT_NAME, accountName);
                        mCredential.setSelectedAccountName(accountName);
                        getResultsFromApi();
                    }
                }
                break;
            case REQUEST_AUTHORIZATION:
                if (resultCode == RESULT_OK) {
                    getResultsFromApi();
                }
                break;
        }
    }

    /**
     * Respond to requests for permissions at runtime for API 23 and above.
     * @param requestCode The request code passed in
     *     requestPermissions(android.app.Activity, String, int, String[])
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *     which is either PERMISSION_GRANTED or PERMISSION_DENIED. Never null.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(
                requestCode, permissions, grantResults, this);
    }

    /**
     * Checks whether the device currently has a network connection.
     * @return true if the device has a network connection, false otherwise.
     */
    private boolean isDeviceOnline() {
        ConnectivityManager connMgr =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return (networkInfo != null && networkInfo.isConnected());
    }

    /**
     * Check that Google Play services APK is installed and up to date.
     * @return true if Google Play Services is available and up to
     *     date on this device; false otherwise.
     */
    private boolean isGooglePlayServicesAvailable() {
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(this);
        return connectionStatusCode == ConnectionResult.SUCCESS;
    }

    /**
     * Attempt to resolve a missing, out-of-date, invalid or disabled Google
     * Play Services installation via a user dialog, if possible.
     */
    private void acquireGooglePlayServices() {
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(this);
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode);
        }
    }


    /**
     * Display an error dialog showing that Google Play Services is missing
     * or out of date.
     * @param connectionStatusCode code describing the presence (or lack of)
     *     Google Play Services on this device.
     */
    void showGooglePlayServicesAvailabilityErrorDialog(
            final int connectionStatusCode) {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        Dialog dialog = apiAvailability.getErrorDialog(
                MainLeanbackActivity.this,
                connectionStatusCode,
                REQUEST_GOOGLE_PLAY_SERVICES);
        dialog.show();
    }
}