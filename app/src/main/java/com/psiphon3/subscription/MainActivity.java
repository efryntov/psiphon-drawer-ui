package com.psiphon3.subscription;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.ViewModelProviders;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.navigation.NavigationView;
import com.psiphon3.EmbeddedValues;
import com.psiphon3.TunnelManager;
import com.psiphon3.billing.PlayStoreBillingViewModel;
import com.psiphon3.subscription.ui.settings.SettingsFragment;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    public static final String STATUS_ENTRY_AVAILABLE = "STATUS_ENTRY_AVAILABLE";
    private AppBarConfiguration mAppBarConfiguration;
    private PlayStoreBillingViewModel billingViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        TextView versionLabel = toolbar.findViewById(R.id.toolbar_version_label);
        versionLabel.setText(String.format(Locale.US, "v.%s", EmbeddedValues.CLIENT_VERSION));
        setSupportActionBar(toolbar);
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        NavigationView navigationView = findViewById(R.id.nav_view);
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_home, R.id.nav_premium, R.id.nav_statistics,
                R.id.nav_settings, R.id.nav_share, R.id.nav_send)
                .setDrawerLayout(drawer)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);

        billingViewModel = ViewModelProviders.of(this).get(PlayStoreBillingViewModel.class);
        billingViewModel.startObservingIabUpdates();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
//        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration)
                || super.onSupportNavigateUp();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        billingViewModel.queryCurrentSubscriptionStatus();
        billingViewModel.queryAllSkuDetails();
        handleCurrentIntent();
    }

    private void handleCurrentIntent() {
        Intent intent = getIntent();
        if (intent == null || intent.getAction() == null || intent.getAction() == "ACTION_VIEW") {
            return;
        }

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        Bundle data = intent.getExtras();
        if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_HANDSHAKE)) {
            navController.navigate(R.id.nav_home, data);
        } else if (0 == intent.getAction().compareTo(SettingsFragment.LANGUAGE_SETTINGS_RESET_ACTION)) {
            navController.navigate(R.id.nav_settings, data);
        }
        // We only want to respond to the HANDSHAKE_SUCCESS action once,
        // so we need to clear it (by setting it to a non-special intent).
        setIntent(new Intent(
                "ACTION_VIEW",
                null,
                this,
                this.getClass()));
    }
}
